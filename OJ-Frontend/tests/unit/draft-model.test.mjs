import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  FIELD_DEFS,
  buildSavePayload,
  canCancel,
  countIssues,
  defaultSelection,
  formatElapsed,
  groupIssues,
  hasContent,
  isTerminal,
  normalizeBrief,
  normalizeSamples,
  normalizeTags,
  reviewSummary,
  statusMeta,
  toStatementMarkdown,
  validateBrief
} from '../../src/views/teacher/tools/draft/draftModel.js'

const baseBrief = {
  knowledgePoint: '贪心',
  difficultyScore: 1200,
  grade: '五年级',
  scenarioType: '校园故事',
  scale: 'n ≤ 10^5',
  sampleCount: 3,
  theme: 'ip',
  customTheme: '',
  forbidden: '',
  reference: '',
  extra: ''
}

test('validateBrief 拒绝缺知识点与越界分值', () => {
  assert.deepEqual(validateBrief(baseBrief), [])
  assert.deepEqual(validateBrief({ ...baseBrief, knowledgePoint: '  ' }), ['请填写知识点'])
  assert.deepEqual(validateBrief({ ...baseBrief, difficultyScore: 700 }), ['难度分值需在 800~3000 之间'])
  assert.deepEqual(validateBrief({ ...baseBrief, difficultyScore: 3100 }), ['难度分值需在 800~3000 之间'])
})

test('validateBrief 拒绝非法 theme 与未填的自定义设定', () => {
  assert.deepEqual(validateBrief({ ...baseBrief, theme: '睿思星球' }), ['世界观选项不合法'])
  assert.deepEqual(validateBrief({ ...baseBrief, theme: 'custom', customTheme: ' ' }), ['选择自定义世界观时请填写设定'])
  assert.deepEqual(validateBrief({ ...baseBrief, theme: 'custom', customTheme: '星际邮局' }), [])
})

test('validateBrief 只在样例组数越界时报告', () => {
  assert.deepEqual(validateBrief({ ...baseBrief, sampleCount: 1 }), ['样例组数需在 2~5 之间'])
  assert.deepEqual(validateBrief({ ...baseBrief, sampleCount: 6 }), ['样例组数需在 2~5 之间'])
  assert.deepEqual(validateBrief({ ...baseBrief, sampleCount: 5 }), [])
})

test('normalizeBrief 吸附到 100 的整数倍并夹在 800~3000', () => {
  assert.equal(normalizeBrief({ ...baseBrief, difficultyScore: 1350 }).difficultyScore, 1400)
  assert.equal(normalizeBrief({ ...baseBrief, difficultyScore: 760 }).difficultyScore, 800)
  assert.equal(normalizeBrief({ ...baseBrief, difficultyScore: 3420 }).difficultyScore, 3000)
  assert.equal(normalizeBrief({ difficultyScore: 1200 }).knowledgePoint, '贪心')
})

test('normalizeBrief 补齐缺省值并 trim 文本字段', () => {
  const result = normalizeBrief({ knowledgePoint: ' 模拟 ', forbidden: ' 位运算 ', extra: '  a  ' })
  assert.equal(result.knowledgePoint, '模拟')
  assert.equal(result.forbidden, '位运算')
  assert.equal(result.extra, 'a')
  assert.equal(result.theme, 'ip')
  assert.equal(result.sampleCount, 3)
})

test('normalizeTags 去掉 # 与引号、去重、截 20 字、最多 6 个', () => {
  assert.deepEqual(normalizeTags(['#贪心', '贪心', '"模拟"', '[枚举]', '   ', 'a'.repeat(30)]), [
    '贪心',
    '模拟',
    '枚举',
    'a'.repeat(20)
  ])
  assert.deepEqual(normalizeTags(['#贪心', '贪心', '', ' 排序 ']), ['贪心', '排序'])
  const many = Array.from({ length: 10 }, (_, i) => `t${i}`)
  assert.equal(normalizeTags(many).length, 6)
  assert.deepEqual(normalizeTags(null), [])
  assert.deepEqual(normalizeTags(['x'], 2), ['x'])
})

test('normalizeSamples 丢弃双空组、保留单侧空、按原值去重、上限 8', () => {
  const samples = normalizeSamples([
    { input: '1 2', output: '3' },
    { input: '', output: '' },
    { input: '1 2', output: '3' },
    { input: '5', output: '' },
    { input: ' 1 2 ', output: '3' }
  ])
  // 去重按 (input, output) 原值：仅行尾空格不同的两组不合并，避免悄悄改掉有语义的空白
  assert.deepEqual(samples, [
    { input: '1 2', output: '3', explanation: '' },
    { input: '5', output: '', explanation: '' },
    { input: ' 1 2 ', output: '3', explanation: '' }
  ])
  assert.deepEqual(normalizeSamples([null, 'x', {}]), [])
  const nine = Array.from({ length: 9 }, (_, i) => ({ input: String(i), output: String(i) }))
  assert.equal(normalizeSamples(nine).length, 8)
})

test('normalizeSamples 保留原始空白与顺序', () => {
  const result = normalizeSamples([
    { input: '  4 5  ', output: '9', explanation: ' 解释 ' },
    { input: '1\n2', output: '3' }
  ])
  assert.equal(result[0].input, '  4 5  ')
  assert.equal(result[0].explanation, ' 解释 ')
  assert.equal(result[1].input, '1\n2')
})

test('hasContent：数值恒真，标签与样例按长度', () => {
  assert.equal(hasContent('difficultyScore', {}), true)
  assert.equal(hasContent('tags', { tags: [] }), false)
  assert.equal(hasContent('tags', { tags: ['贪心'] }), true)
  assert.equal(hasContent('samples', { samples: [] }), false)
  assert.equal(hasContent('hint', { hint: '  ' }), false)
  assert.equal(hasContent('hint', { hint: '看数据范围' }), true)
})

test('defaultSelection 始终勾上必填字段', () => {
  const selection = defaultSelection({ title: '', content: '', tags: [], samples: [] })
  assert.equal(selection.title, true)
  assert.equal(selection.content, true)
  assert.equal(selection.tags, false)
  assert.equal(selection.samples, false)
})

test('groupIssues 按字段固定顺序与严重度排序', () => {
  const grouped = groupIssues([
    { field: 'samples', severity: 'WARN', code: 'SAMPLE_DUPLICATED', message: '重复' },
    { field: 'content', severity: 'INFO', code: 'X', message: '说明' },
    { field: 'content', severity: 'ERROR', code: 'Y', message: '阻断' },
    { field: 'unknown', severity: 'WARN', code: 'Z', message: '兜底到 content' }
  ])
  assert.deepEqual(grouped.map((g) => g.field), ['content', 'samples'])
  assert.deepEqual(grouped[0].issues.map((i) => i.severity), ['ERROR', 'WARN', 'INFO'])
  assert.equal(grouped[1].label, '样例')
})

test('countIssues 统计总数并把未知严重度归为 INFO', () => {
  const counts = countIssues([
    { severity: 'ERROR' }, { severity: 'WARN' }, { severity: 'WARN' }, { severity: '???' }
  ])
  assert.deepEqual(counts, { ERROR: 1, WARN: 2, INFO: 1, total: 4 })
})

test('canCancel 只在排队阶段为真，且优先采用服务端 cancellable', () => {
  assert.equal(canCancel({ status: 'PENDING', phase: 'QUEUED' }), true)
  assert.equal(canCancel({ status: 'PENDING', phase: 'PROMPTING' }), false)
  assert.equal(canCancel({ status: 'RUNNING', phase: 'GENERATING' }), false)
  assert.equal(canCancel({ status: 'RUNNING', phase: 'QUEUED', cancellable: true }), true)
  assert.equal(canCancel({ status: 'PENDING', phase: 'QUEUED', cancellable: false }), false)
  assert.equal(canCancel(null), false)
})

test('isTerminal 与 statusMeta 覆盖终态', () => {
  assert.equal(isTerminal('READY'), true)
  assert.equal(isTerminal('FAILED'), true)
  assert.equal(isTerminal('CANCELED'), true)
  assert.equal(isTerminal('RUNNING'), false)
  assert.equal(statusMeta('RUNNING', 'PARSING').phaseLabel, '正在校验生成结果')
  assert.equal(statusMeta('UNKNOWN').label, 'UNKNOWN')
})

test('buildSavePayload 按 FIELD_DEFS 顺序过滤并补齐默认值', () => {
  const payload = buildSavePayload({ fields: ['samples', 'title'], timeLimit: 0, memoryLimit: 0 })
  assert.deepEqual(payload, {
    fields: ['title', 'samples'],
    timeLimit: 1000,
    memoryLimit: 512,
    allowIssues: false,
    force: false
  })
  assert.deepEqual(
    buildSavePayload({ fields: ['title', 'content', 'tags', 'samples'], timeLimit: 2000, memoryLimit: 256, allowIssues: true }).fields,
    FIELD_DEFS.map((f) => f.key).filter((k) => ['title', 'content', 'tags', 'samples'].includes(k))
  )
})

test('toStatementMarkdown 用代码块包裹样例并保留空输出', () => {
  const text = toStatementMarkdown({
    title: '求和',
    content: '读入两个数',
    inputDesc: '两个整数',
    samples: [{ input: '1 2', output: '3', explanation: '相加' }, { input: '5', output: '' }]
  })
  assert.match(text, /^# 求和/)
  assert.match(text, /### 输入格式\n两个整数/)
  assert.match(text, /### 样例 1\n输入：\n```\n1 2\n```\n输出：\n```\n3\n```/)
  assert.match(text, /解释：相加/)
  assert.match(text, /### 样例 2\n输入：\n```\n5\n```\n输出：\n```\n\n```/)
})

test('reviewSummary 四种语气', () => {
  assert.equal(reviewSummary(null, []).tone, 'empty')
  assert.equal(reviewSummary({}, [{ severity: 'ERROR' }]).tone, 'error')
  assert.equal(reviewSummary({}, [{ severity: 'WARN' }]).tone, 'warn')
  assert.equal(reviewSummary({}, []).tone, 'ok')
})

test('formatElapsed 秒与分秒格式', () => {
  assert.equal(formatElapsed(0), '0s')
  assert.equal(formatElapsed(1200), '1s')
  assert.equal(formatElapsed(65000), '1m05s')
  assert.equal(formatElapsed(null), '0s')
})
