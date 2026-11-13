import test from 'node:test'
import assert from 'node:assert/strict'

import {
  buildApplyPayload,
  buildSummary,
  compareSamples,
  countSeverity,
  defaultSelection,
  diffLines,
  fieldChange,
  groupIssuesByField,
  normalizeFormatted,
  normalizeSource,
  toFormattedPayload
} from '../../src/views/admin/problemFormat/formatReview.js'

function source(overrides = {}) {
  return {
    title: '旧题名',
    content: '第一行\n第二行',
    inputDesc: '',
    outputDesc: '',
    hint: '',
    timeLimitMs: 1000,
    memoryLimitMb: 512,
    samples: [{ input: '1', output: '1' }],
    ...overrides
  }
}

function formatted(overrides = {}) {
  return {
    title: '新题名',
    content: '第一行\n第二行',
    inputDesc: '输入格式',
    outputDesc: '输出格式',
    hint: '',
    timeLimitMs: 2000,
    memoryLimitMb: 512,
    samples: [{ input: '1', output: '1' }],
    ...overrides
  }
}

test('服务端字段名映射到界面字段名', () => {
  const normalized = normalizeFormatted(formatted())
  assert.equal(normalized.timeLimit, 2000)
  assert.equal(normalized.memoryLimit, 512)
  assert.equal(normalized.title, '新题名')
})

test('原文缺失时给出安全默认值', () => {
  const normalized = normalizeSource(null)
  assert.equal(normalized.title, '')
  assert.deepEqual(normalized.samples, [])
  assert.equal(normalized.timeLimit, 1000)
  assert.equal(normalized.memoryLimit, 512)
})

test('变更摘要区分新增、修改与未变化', () => {
  const summary = buildSummary(normalizeSource(source()), normalizeFormatted(formatted()))
  assert.equal(summary.fields.title.kind, 'modified')
  assert.equal(summary.fields.content.kind, 'same')
  assert.equal(summary.fields.inputDesc.kind, 'added')
  assert.equal(summary.fields.timeLimit.kind, 'modified')
  assert.deepEqual(summary.changedKeys.sort(), ['inputDesc', 'outputDesc', 'timeLimit', 'title'])
})

test('结果被清空时标记为 removed', () => {
  const change = fieldChange({ hint: '有提示' }, { hint: '' }, 'hint')
  assert.equal(change.kind, 'removed')
})

test('单行差异给出增删计数', () => {
  const change = fieldChange({ content: 'a\nb\nc' }, { content: 'a\nc\nd' }, 'content')
  assert.equal(change.removedLines, 1)
  assert.equal(change.addedLines, 1)
  assert.equal(change.truncated, false)
})

test('diffLines 保留相同行并标出增删', () => {
  const result = diffLines(['a', 'b'], ['a', 'c'])
  assert.equal(result.truncated, false)
  assert.deepEqual(result.rows.map((row) => row.type), ['same', 'del', 'add'])
  assert.deepEqual(result.rows.map((row) => row.text), ['a', 'b', 'c'])
})

test('超长文本放弃逐行比对而不是卡住', () => {
  const longLines = new Array(500).fill('x')
  const result = diffLines(longLines, longLines)
  assert.equal(result.truncated, true)
  assert.deepEqual(result.rows, [])
})

test('样例对照区分一致、改写、新增与缺失', () => {
  const rows = compareSamples(
    [{ input: '1', output: '1' }, { input: '2', output: '2' }, { input: '3', output: '3' }],
    [{ input: '1', output: '1' }, { input: '2', output: '9' }]
  )
  assert.deepEqual(rows.map((row) => row.status), ['same', 'changed', 'removed'])
})

test('样例顺序变化但内容一致仍判为一致', () => {
  const rows = compareSamples(
    [{ input: '1', output: '1' }, { input: '2', output: '2' }],
    [{ input: '2', output: '2' }, { input: '1', output: '1' }]
  )
  assert.deepEqual(rows.map((row) => row.status), ['same', 'same'])
})

test('默认勾选排除有阻断问题的字段', () => {
  const summary = buildSummary(normalizeSource(source()), normalizeFormatted(formatted()))
  const issues = [
    { code: 'TITLE_EMPTY', field: 'title', severity: 'ERROR', message: '题名为空' },
    { code: 'SAMPLE_CONTENT_CHANGED', field: 'samples', severity: 'WARN', message: '样例被改写' }
  ]
  const { selection, skipped } = defaultSelection(summary, issues)
  assert.equal(selection.title, false)
  assert.deepEqual(skipped, ['title'])
  assert.equal(selection.inputDesc, true)
  assert.equal(selection.samples, false, '样例未变化时不应被勾选')
})

test('原文未声明时限时默认不采纳模型填的平台默认值', () => {
  const summary = buildSummary(normalizeSource(source()), normalizeFormatted(formatted()))
  const { selection, limitsSkipped } = defaultSelection(summary, [], {
    sourceText: '给定一个整数数组，求和为目标值的两个下标。'
  })
  assert.equal(selection.timeLimit, false, '原文没写限制，不应把 1000ms 改成 2000ms')
  assert.deepEqual(limitsSkipped, ['timeLimit'])
})

test('原文声明了时限时默认采纳 AI 换算出的值', () => {
  const summary = buildSummary(normalizeSource(source()), normalizeFormatted(formatted()))
  const { selection } = defaultSelection(summary, [], { sourceText: 'Time Limit: 2 seconds / Memory Limit: 512 MB' })
  assert.equal(selection.timeLimit, true)
})

test('只声明时限时，内存不被平台默认值覆盖', () => {
  const summary = buildSummary(
    normalizeSource(source({ memoryLimitMb: 256 })),
    normalizeFormatted(formatted({ memoryLimitMb: 512 }))
  )
  const { selection, limitsSkipped } = defaultSelection(summary, [], {
    sourceText: 'Time Limit: 2 seconds'
  })
  assert.equal(selection.timeLimit, true)
  assert.equal(selection.memoryLimit, false)
  assert.deepEqual(limitsSkipped, ['memoryLimit'])
})

test('问题按字段归并，未知字段落到正文', () => {
  const grouped = groupIssuesByField([
    { code: 'A', field: 'title', severity: 'ERROR' },
    { code: 'B', field: 'unknown', severity: 'WARN' }
  ])
  assert.equal(grouped.title.length, 1)
  assert.equal(grouped.content.length, 1)
})

test('严重级别统计', () => {
  const counts = countSeverity([
    { severity: 'ERROR' }, { severity: 'WARN' }, { severity: 'WARN' }, { severity: 'INFO' }
  ])
  assert.deepEqual(counts, { error: 1, warn: 2, info: 1, total: 4 })
})

test('应用载荷保留字段顺序并回写服务端字段名', () => {
  const payload = buildApplyPayload({
    problemId: 7,
    baseHash: 'abc',
    selection: { samples: true, title: true, content: false },
    values: normalizeFormatted(formatted({ timeLimitMs: 3000 }))
  })
  assert.deepEqual(payload.fields, ['title', 'samples'])
  assert.equal(payload.formatted.timeLimitMs, 3000)
  assert.equal(payload.formatted.memoryLimitMb, 512)
  assert.equal(payload.baseHash, 'abc')
  assert.equal(payload.problemId, 7)
})

test('载荷始终包含未被选中的字段，服务端才能按真实内容复检', () => {
  const payload = toFormattedPayload({ title: 't', content: 'c', timeLimit: 0, memoryLimit: 0 })
  assert.equal(payload.timeLimitMs, 1000)
  assert.equal(payload.memoryLimitMb, 512)
  assert.equal(payload.content, 'c')
})
