/**
 * 题面生成工作台的纯函数层：枚举、校验、归一化、问题分组、保存载荷。
 *
 * 单独抽出来的原因：这些判断同时被面板、画布、审阅栏与单元测试使用，
 * 放在组件里会出现「三个地方各自判断能不能保存」的漂移。
 * 本文件不依赖 Vue 与 Element Plus，可直接被 node:test 引入。
 *
 * 注意：这里刻意不 re-export `@/utils/difficulty`。该别名只有 Vite 能解析，
 * 一旦引入，node:test 直接 import 本文件就会失败；组件各自从 `@/utils/difficulty` 取即可。
 */

export const KNOWLEDGE_POINT_OPTIONS = Object.freeze([
  '模拟', '枚举', '贪心', '二分查找', '前缀和', '差分', '动态规划', '排序',
  '字符串', '数学', '递归', '栈与队列', '图论入门', '搜索'
])

export const GRADE_OPTIONS = Object.freeze(['四年级', '五年级', '六年级', '初一'])

export const SCENARIO_TYPE_OPTIONS = Object.freeze(['生活故事', '校园故事', '太空冒险', '游戏关卡', '纯数学包装'])

/**
 * theme 是机器值，不是展示文案：服务端只认 ip / neutral / custom。
 * 早期版本把中文直接当值传，服务端会判为非法枚举，这里显式分开 value 与 label。
 */
export const THEME_OPTIONS = Object.freeze([
  { value: 'ip', label: '睿思星球世界观' },
  { value: 'neutral', label: '中性命名' },
  { value: 'custom', label: '自定义设定' }
])

export const SAMPLE_COUNT_OPTIONS = Object.freeze([2, 3, 4, 5])

export const DEFAULT_BRIEF = Object.freeze({
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
})

export const QUICK_STARTS = Object.freeze([
  {
    id: 'greedy-easy',
    label: '贪心入门',
    description: '五年级 · 校园故事 · n ≤ 10^5',
    brief: { knowledgePoint: '贪心', difficultyScore: 1200, grade: '五年级', scenarioType: '校园故事', scale: 'n ≤ 10^5', sampleCount: 3, theme: 'ip' }
  },
  {
    id: 'simulation',
    label: '模拟 · 边界多',
    description: '四年级 · 生活故事 · 5 组样例',
    brief: { knowledgePoint: '模拟', difficultyScore: 1000, grade: '四年级', scenarioType: '生活故事', scale: 'n ≤ 1000', sampleCount: 5, theme: 'ip' }
  },
  {
    id: 'dp',
    label: '动态规划进阶',
    description: '六年级 · 太空冒险 · n ≤ 10^6',
    brief: { knowledgePoint: '动态规划', difficultyScore: 1800, grade: '六年级', scenarioType: '太空冒险', scale: 'n ≤ 10^6', sampleCount: 3, theme: 'ip' }
  }
])

/**
 * 可落库字段。顺序即界面顺序，也是 `POST /{id}/save` 里 fields 的提交顺序。
 * `required` 表示题目表必填：服务端始终写入，前端即使取消勾选也不会真的不传。
 */
export const FIELD_DEFS = Object.freeze([
  { key: 'title', label: '题名', kind: 'text', maxLength: 128, required: true },
  { key: 'content', label: '题目描述', kind: 'markdown', required: true },
  { key: 'inputDesc', label: '输入格式', kind: 'markdown' },
  { key: 'outputDesc', label: '输出格式', kind: 'markdown' },
  { key: 'hint', label: '提示', kind: 'markdown' },
  { key: 'tags', label: '标签', kind: 'list' },
  { key: 'samples', label: '样例', kind: 'samples' }
])

/** 问题定位用的字段顺序：与画布上的区块顺序一致，点问题能滚到对应位置。 */
export const FIELD_ORDER = Object.freeze([
  'title', 'content', 'inputDesc', 'outputDesc', 'hint', 'tags', 'samples', 'difficultyScore', 'timeLimit', 'memoryLimit'
])

const FIELD_LABELS = Object.freeze({
  title: '题名',
  content: '题目描述',
  inputDesc: '输入格式',
  outputDesc: '输出格式',
  hint: '提示',
  tags: '标签',
  samples: '样例',
  difficultyScore: '难度分',
  timeLimit: '时间限制',
  memoryLimit: '内存限制'
})

const SEVERITY_ORDER = Object.freeze(['ERROR', 'WARN', 'INFO'])

const STATUS_META = Object.freeze({
  PENDING: { label: '排队中', type: 'info', terminal: false },
  RUNNING: { label: '生成中', type: 'primary', terminal: false },
  READY: { label: '待审阅', type: 'success', terminal: true },
  FAILED: { label: '失败', type: 'danger', terminal: true },
  CANCELED: { label: '已取消', type: 'info', terminal: true }
})

const PHASE_LABELS = Object.freeze({
  QUEUED: '已排队，等待执行',
  PROMPTING: '正在构建命题要求',
  GENERATING: '模型生成中',
  PARSING: '正在校验生成结果',
  DONE: '已完成'
})

export function fieldLabel(field) {
  return FIELD_LABELS[field] || field || ''
}

/** 简报校验：与服务端 `DraftBrief.validate()` 同一组规则，先在前端拦一次只是省一次请求。 */
export function validateBrief(brief) {
  const errors = []
  const value = brief || {}
  if (!value.knowledgePoint || !String(value.knowledgePoint).trim()) {
    errors.push('请填写知识点')
  }
  const score = Number(value.difficultyScore)
  if (!Number.isFinite(score) || score < 800 || score > 3000) {
    errors.push('难度分值需在 800~3000 之间')
  }
  if (value.grade && !GRADE_OPTIONS.includes(value.grade)) {
    errors.push('年级不在可选范围内')
  }
  if (value.scenarioType && !SCENARIO_TYPE_OPTIONS.includes(value.scenarioType)) {
    errors.push('场景类型不在可选范围内')
  }
  const sampleCount = Number(value.sampleCount)
  if (value.sampleCount != null && (!Number.isFinite(sampleCount) || sampleCount < 2 || sampleCount > 5)) {
    errors.push('样例组数需在 2~5 之间')
  }
  if (value.theme && !THEME_OPTIONS.some(option => option.value === value.theme)) {
    errors.push('世界观选项不合法')
  }
  if (value.theme === 'custom' && !String(value.customTheme || '').trim()) {
    errors.push('选择自定义世界观时请填写设定')
  }
  return errors
}

/** 提交前把字符串两端空白去掉，并补齐缺省值；服务端仍会再校验一次。 */
export function normalizeBrief(brief) {
  const merged = { ...DEFAULT_BRIEF, ...(brief || {}) }
  const score = Math.max(800, Math.min(3000, Math.round(Number(merged.difficultyScore) / 100) * 100 || 1200))
  return {
    knowledgePoint: String(merged.knowledgePoint || '').trim(),
    difficultyScore: score,
    grade: merged.grade || '',
    scenarioType: merged.scenarioType || '',
    scale: String(merged.scale || '').trim(),
    sampleCount: Math.max(2, Math.min(5, Number(merged.sampleCount) || 3)),
    theme: merged.theme || 'ip',
    customTheme: String(merged.customTheme || '').trim(),
    forbidden: String(merged.forbidden || '').trim(),
    reference: String(merged.reference || '').trim(),
    extra: String(merged.extra || '').trim()
  }
}

/** 标签归一化：去 #、去引号、trim、截 20 字、去重、最多 6 个。与后端解析器一致。 */
export function normalizeTags(tags, limit = 6) {
  const seen = new Set()
  const result = []
  for (const raw of Array.isArray(tags) ? tags : []) {
    const tag = String(raw == null ? '' : raw).replace(/[[\]"'#]/g, '').trim().slice(0, 20)
    if (!tag || seen.has(tag)) continue
    seen.add(tag)
    result.push(tag)
    if (result.length >= limit) break
  }
  return result
}

/**
 * 样例归一化：丢弃「输入输出同时为空」的组、按 (input, output) 去重、最多 8 组。
 * 只丢完全空的组：某些题的输出确实可以是空串，不能用「输出为空」单独判废。
 */
export function normalizeSamples(samples, limit = 8) {
  const seen = new Set()
  const result = []
  for (const sample of Array.isArray(samples) ? samples : []) {
    if (!sample || typeof sample !== 'object') continue
    const input = sample.input == null ? '' : String(sample.input)
    const output = sample.output == null ? '' : String(sample.output)
    if (!input.trim() && !output.trim()) continue
    // 去重键必须用原值：行尾空格在样例里可能是语义的一部分（例如字符串题），trim 只用于判空
    const key = `${input}\u0000${output}`
    if (seen.has(key)) continue
    seen.add(key)
    result.push({
      input,
      output,
      explanation: sample.explanation == null ? '' : String(sample.explanation)
    })
    if (result.length >= limit) break
  }
  return result
}

/** 该字段是否有可采纳的内容：数值字段恒为 true，其余按非空判断。 */
export function hasContent(field, content) {
  if (!content) return false
  if (field === 'difficultyScore' || field === 'timeLimit' || field === 'memoryLimit') return true
  if (field === 'tags') return Array.isArray(content.tags) && content.tags.length > 0
  if (field === 'samples') return Array.isArray(content.samples) && content.samples.length > 0
  return String(content[field] == null ? '' : content[field]).trim().length > 0
}

/** 默认勾选：有内容的字段全选；必填字段即使为空也勾上，让用户在保存前看到缺口。 */
export function defaultSelection(content) {
  const selection = {}
  for (const field of FIELD_DEFS) {
    selection[field.key] = field.required || hasContent(field.key, content)
  }
  return selection
}

export function selectableFields(content) {
  const result = {}
  for (const field of FIELD_DEFS) {
    result[field.key] = field.required || hasContent(field.key, content)
  }
  return result
}

export function countIssues(issues) {
  const counts = { ERROR: 0, WARN: 0, INFO: 0, total: 0 }
  for (const issue of Array.isArray(issues) ? issues : []) {
    const severity = SEVERITY_ORDER.includes(issue?.severity) ? issue.severity : 'INFO'
    counts[severity] += 1
    counts.total += 1
  }
  return counts
}

/** 按严重度再按字段顺序分组：教师关心「哪些必须改」，不是「按接口返回顺序」。 */
export function groupIssues(issues) {
  const buckets = new Map()
  for (const issue of Array.isArray(issues) ? issues : []) {
    const field = FIELD_ORDER.includes(issue?.field) ? issue.field : 'content'
    if (!buckets.has(field)) buckets.set(field, [])
    buckets.get(field).push(issue)
  }
  return FIELD_ORDER.filter(field => buckets.has(field)).map(field => ({
    field,
    label: fieldLabel(field),
    issues: buckets.get(field).sort(
      (a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity)
    )
  }))
}

export function statusMeta(status, phase) {
  const meta = STATUS_META[status] || { label: status || '未知', type: 'info', terminal: false }
  return {
    ...meta,
    phaseLabel: PHASE_LABELS[phase] || ''
  }
}

/** 取消窗口只有「已排队未发出」：进入 RUNNING 后服务端无法中断模型调用。 */
export function canCancel(draft) {
  if (!draft) return false
  if (typeof draft.cancellable === 'boolean') return draft.cancellable
  return draft.status === 'PENDING' && (!draft.phase || draft.phase === 'QUEUED')
}

export function isTerminal(status) {
  return Boolean(STATUS_META[status]?.terminal)
}

export function formatElapsed(ms) {
  const seconds = Math.max(0, Math.round(Number(ms) / 1000) || 0)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m${String(seconds % 60).padStart(2, '0')}s`
}

/**
 * 保存载荷：fields 只取用户勾选且有内容的项；title/content 由服务端强制补齐，这里不重复塞。
 * 时限与内存单独取界面上的输入，避免教师改了输入框却没保存。
 */
export function buildSavePayload({ fields, timeLimit, memoryLimit, allowIssues = false, force = false }) {
  // 空数组与 undefined 都表示「全选」：服务端把空 fields 解释为全量，前端不能在语义上分叉
  const selected = Array.isArray(fields) && fields.length
    ? FIELD_DEFS.map(field => field.key).filter(key => fields.includes(key))
    : FIELD_DEFS.map(field => field.key)
  return {
    fields: selected,
    timeLimit: Number(timeLimit) || 1000,
    memoryLimit: Number(memoryLimit) || 512,
    allowIssues: Boolean(allowIssues),
    force: Boolean(force)
  }
}

/** 导出为可粘贴的 Markdown：样例必须用代码块包裹，否则多行输入会被折行破坏。 */
export function toStatementMarkdown(content) {
  if (!content) return ''
  const parts = [`# ${content.title || ''}`, '', content.content || '']
  if (content.inputDesc) parts.push('', '### 输入格式', content.inputDesc)
  if (content.outputDesc) parts.push('', '### 输出格式', content.outputDesc)
  if (content.hint) parts.push('', '### 提示', content.hint)
  const samples = Array.isArray(content.samples) ? content.samples : []
  samples.forEach((sample, index) => {
    parts.push('', `### 样例 ${index + 1}`, '输入：', '```', sample.input || '', '```', '输出：', '```', sample.output || '', '```')
    if (sample.explanation) parts.push(`解释：${sample.explanation}`)
  })
  return parts.join('\n')
}

/** 审阅栏的一句话结论：把「有多少问题、能不能直接存」讲清楚，不用教师自己数。 */
export function reviewSummary(content, issues) {
  const counts = countIssues(issues)
  if (!content) return { tone: 'empty', text: '还没有可审阅的内容' }
  if (counts.ERROR > 0) {
    return { tone: 'error', text: `有 ${counts.ERROR} 项必须处理的问题，修好或确认后才能保存` }
  }
  if (counts.WARN > 0) {
    return { tone: 'warn', text: `有 ${counts.WARN} 项建议核对，确认无误即可保存` }
  }
  return { tone: 'ok', text: '未发现阻断问题，可以直接保存为私有题目' }
}
