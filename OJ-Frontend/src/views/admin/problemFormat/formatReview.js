/**
 * 题面优化工作台的纯函数层：字段元信息、差异统计、样例对照、问题归并、应用载荷。
 *
 * 与组件分开的原因：这些判断（哪个字段变了、哪些问题阻断、默认勾选什么）
 * 决定了「教师看到什么、点应用会写什么」，是最需要单测覆盖的部分。
 * 组件只负责把它们渲染出来。
 */

/** 字段顺序即界面顺序，与服务端 apply 的 fields 白名单一一对应 */
export const FORMAT_FIELDS = Object.freeze([
  { key: 'title', label: '题名', kind: 'text' },
  { key: 'content', label: '题目正文', kind: 'text' },
  { key: 'inputDesc', label: '输入描述', kind: 'text' },
  { key: 'outputDesc', label: '输出描述', kind: 'text' },
  { key: 'hint', label: '提示', kind: 'text' },
  { key: 'timeLimit', label: '时间限制', kind: 'number', unit: 'ms' },
  { key: 'memoryLimit', label: '内存限制', kind: 'number', unit: 'MB' },
  { key: 'samples', label: '样例', kind: 'samples' }
])

export const FORMAT_FIELD_KEYS = Object.freeze(FORMAT_FIELDS.map((field) => field.key))

export const CHANGE_KIND_LABEL = Object.freeze({
  same: '未变化',
  modified: '有修改',
  added: '新增内容',
  removed: '被清空'
})

function text(value) {
  return typeof value === 'string' ? value : value == null ? '' : String(value)
}

function normalizeSamples(samples) {
  if (!Array.isArray(samples)) return []
  return samples.map((sample) => ({
    input: text(sample?.input),
    output: text(sample?.output)
  }))
}

/** 服务端原文快照 → 界面统一结构 */
export function normalizeSource(source) {
  const value = source && typeof source === 'object' ? source : {}
  return {
    title: text(value.title),
    content: text(value.content),
    inputDesc: text(value.inputDesc),
    outputDesc: text(value.outputDesc),
    hint: text(value.hint),
    timeLimit: Number(value.timeLimitMs ?? value.timeLimit ?? 1000),
    memoryLimit: Number(value.memoryLimitMb ?? value.memoryLimit ?? 512),
    samples: normalizeSamples(value.samples)
  }
}

/** 服务端整理结果 → 界面统一结构（毫秒/MB 字段名与 apply 请求不同） */
export function normalizeFormatted(formatted) {
  const value = formatted && typeof formatted === 'object' ? formatted : {}
  return {
    title: text(value.title),
    content: text(value.content),
    inputDesc: text(value.inputDesc),
    outputDesc: text(value.outputDesc),
    hint: text(value.hint),
    timeLimit: Number(value.timeLimitMs ?? value.timeLimit ?? 1000),
    memoryLimit: Number(value.memoryLimitMb ?? value.memoryLimit ?? 512),
    samples: normalizeSamples(value.samples)
  }
}

/** 界面结构 → apply 请求体 */
export function toFormattedPayload(values) {
  const value = values || {}
  return {
    title: text(value.title),
    content: text(value.content),
    inputDesc: text(value.inputDesc),
    outputDesc: text(value.outputDesc),
    hint: text(value.hint),
    timeLimitMs: Number(value.timeLimit) || 1000,
    memoryLimitMb: Number(value.memoryLimit) || 512,
    samples: normalizeSamples(value.samples)
  }
}

function splitLines(value) {
  if (!value) return []
  return text(value).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
}

function countChangedLines(before, after) {
  const left = splitLines(before)
  const right = splitLines(after)
  const ops = diffLines(left, right)
  if (ops.truncated) {
    return { addedLines: right.length, removedLines: left.length, truncated: true }
  }
  return {
    addedLines: ops.rows.filter((row) => row.type === 'add').length,
    removedLines: ops.rows.filter((row) => row.type === 'del').length,
    truncated: false
  }
}

/**
 * 行级差异（LCS）。返回统一的单列行序列，便于「源码」视图着色。
 * @param {string[]} leftLines
 * @param {string[]} rightLines
 * @param {{maxLines?: number}} options 超过上限直接放弃逐行比对，避免大文本卡住主线程
 */
export function diffLines(leftLines, rightLines, { maxLines = 400 } = {}) {
  const left = Array.isArray(leftLines) ? leftLines : []
  const right = Array.isArray(rightLines) ? rightLines : []
  if (left.length > maxLines || right.length > maxLines) {
    return { truncated: true, rows: [] }
  }
  const rows = []
  let start = 0
  while (start < left.length && start < right.length && left[start] === right[start]) {
    rows.push({ type: 'same', text: left[start] })
    start += 1
  }
  let endLeft = left.length - 1
  let endRight = right.length - 1
  while (endLeft >= start && endRight >= start && left[endLeft] === right[endRight]) {
    endLeft -= 1
    endRight -= 1
  }
  const middleLeft = left.slice(start, endLeft + 1)
  const middleRight = right.slice(start, endRight + 1)
  rows.push(...lcsRows(middleLeft, middleRight))
  for (let index = endLeft + 1; index < left.length; index += 1) {
    rows.push({ type: 'same', text: left[index] })
  }
  return { truncated: false, rows }
}

function lcsRows(left, right) {
  const n = left.length
  const m = right.length
  if (n === 0) return right.map((line) => ({ type: 'add', text: line }))
  if (m === 0) return left.map((line) => ({ type: 'del', text: line }))

  // 表格只保留长度，回溯时按规则走，避免存整个方向矩阵
  const table = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i -= 1) {
    for (let j = m - 1; j >= 0; j -= 1) {
      table[i][j] = left[i] === right[j]
        ? table[i + 1][j + 1] + 1
        : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }
  const rows = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (left[i] === right[j]) {
      rows.push({ type: 'same', text: left[i] })
      i += 1
      j += 1
    } else if (table[i + 1][j] >= table[i][j + 1]) {
      rows.push({ type: 'del', text: left[i] })
      i += 1
    } else {
      rows.push({ type: 'add', text: right[j] })
      j += 1
    }
  }
  while (i < n) {
    rows.push({ type: 'del', text: left[i] })
    i += 1
  }
  while (j < m) {
    rows.push({ type: 'add', text: right[j] })
    j += 1
  }
  return rows
}

/**
 * 单字段变更摘要。
 * @returns {{kind: string, before: string|number, after: string|number, addedLines: number, removedLines: number, charDelta: number, truncated: boolean}}
 */
export function fieldChange(source, formatted, key) {
  const before = source?.[key]
  const after = formatted?.[key]
  if (key === 'samples') {
    return samplesChange(source?.samples, formatted?.samples)
  }
  if (key === 'timeLimit' || key === 'memoryLimit') {
    const kind = Number(before) === Number(after) ? 'same' : 'modified'
    return { kind, before: Number(before), after: Number(after), addedLines: 0, removedLines: 0, charDelta: 0, truncated: false }
  }
  const left = text(before)
  const right = text(after)
  let kind = 'modified'
  if (left === right) kind = 'same'
  else if (!left.trim() && right.trim()) kind = 'added'
  else if (left.trim() && !right.trim()) kind = 'removed'
  const { addedLines, removedLines, truncated } = kind === 'same'
    ? { addedLines: 0, removedLines: 0, truncated: false }
    : countChangedLines(left, right)
  return {
    kind,
    before: left,
    after: right,
    addedLines,
    removedLines,
    charDelta: right.length - left.length,
    truncated
  }
}

function samplesChange(sourceSamples, resultSamples) {
  const left = normalizeSamples(sourceSamples)
  const right = normalizeSamples(resultSamples)
  const identical = JSON.stringify(left) === JSON.stringify(right)
  return {
    kind: identical ? 'same' : (left.length === 0 ? 'added' : (right.length === 0 ? 'removed' : 'modified')),
    before: left.length,
    after: right.length,
    addedLines: Math.max(0, right.length - left.length),
    removedLines: Math.max(0, left.length - right.length),
    charDelta: 0,
    truncated: false
  }
}

/** 全部字段的变更摘要，附带「改了哪些」的快捷信息 */
export function buildSummary(source, formatted) {
  const fields = {}
  const changedKeys = []
  for (const field of FORMAT_FIELDS) {
    const change = fieldChange(source, formatted, field.key)
    fields[field.key] = change
    if (change.kind !== 'same') changedKeys.push(field.key)
  }
  return { fields, changedKeys }
}

/** 按字段归并质量问题；field 为空的问题归到 content（服务端兜底） */
export function groupIssuesByField(issues) {
  const grouped = {}
  for (const key of FORMAT_FIELD_KEYS) grouped[key] = []
  const list = Array.isArray(issues) ? issues : []
  for (const issue of list) {
    const key = FORMAT_FIELD_KEYS.includes(issue?.field) ? issue.field : 'content'
    grouped[key].push(issue)
  }
  return grouped
}

export function countSeverity(issues) {
  const list = Array.isArray(issues) ? issues : []
  let error = 0
  let warn = 0
  let info = 0
  for (const issue of list) {
    if (issue?.severity === 'ERROR') error += 1
    else if (issue?.severity === 'WARN') warn += 1
    else info += 1
  }
  return { error, warn, info, total: list.length }
}

export function fieldsWithBlockingIssue(issues) {
  const list = Array.isArray(issues) ? issues : []
  return list.filter((issue) => issue?.severity === 'ERROR').map((issue) => issue.field)
}

/**
 * 默认勾选策略：变化过的字段才勾；有阻断问题的字段不勾，
 * 让「应用」在默认状态下就能通过服务端质量闸门，用户看清红点后可主动放开。
 */
export function defaultSelection(summary, issues) {
  const blocking = new Set(fieldsWithBlockingIssue(issues))
  const selection = {}
  const skipped = []
  for (const field of FORMAT_FIELDS) {
    const changed = summary?.fields?.[field.key]?.kind !== 'same'
    const blocked = blocking.has(field.key)
    selection[field.key] = changed && !blocked
    if (blocked && changed) skipped.push(field.key)
  }
  return { selection, skipped }
}

/** 样例逐组对照：same / changed / added / removed */
export function compareSamples(sourceSamples, resultSamples) {
  const left = normalizeSamples(sourceSamples)
  const right = normalizeSamples(resultSamples)
  const used = new Set()
  const rows = right.map((sample, index) => {
    const exact = left.findIndex((item, leftIndex) => !used.has(leftIndex)
      && item.input === sample.input && item.output === sample.output)
    if (exact >= 0) {
      used.add(exact)
      return { index, status: 'same', input: sample.input, output: sample.output, before: left[exact] }
    }
    const hasCounterpart = index < left.length && !used.has(index)
    if (hasCounterpart) used.add(index)
    return {
      index,
      status: hasCounterpart ? 'changed' : 'added',
      input: sample.input,
      output: sample.output,
      before: hasCounterpart ? left[index] : null
    }
  })
  const removed = left
    .map((sample, index) => ({ sample, index }))
    .filter(({ index }) => !used.has(index))
    .map(({ sample, index }) => ({ index, status: 'removed', input: sample.input, output: sample.output, before: sample }))
  return [...rows, ...removed]
}

/**
 * 组装 apply 请求。formatted 始终整份回传：服务端要按「实际内容」重跑质量检查，
 * 只传选中字段会让未选字段变成空值，反而触发误报。
 */
export function buildApplyPayload({ problemId, baseHash, selection, values }) {
  const fields = FORMAT_FIELD_KEYS.filter((key) => selection?.[key])
  return {
    problemId,
    baseHash,
    fields,
    formatted: toFormattedPayload(values)
  }
}

export function formatChangeBadge(change) {
  if (!change || change.kind === 'same') return ''
  if (change.kind === 'added') return '新增'
  if (change.kind === 'removed') return '清空'
  return '已改'
}

/** 变更行摘要，供字段头展示；数字字段返回 null 交给调用方格式化 */
export function describeLineChange(change) {
  if (!change || change.kind === 'same') return ''
  if (change.truncated) return '内容较长，已省略逐行差异'
  const parts = []
  if (change.removedLines) parts.push(`-${change.removedLines}`)
  if (change.addedLines) parts.push(`+${change.addedLines}`)
  return parts.length ? `${parts.join(' / ')} 行` : ''
}
