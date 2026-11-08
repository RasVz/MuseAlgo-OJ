/**
 * 题目编辑页与 AI 格式化结果的字段合并规则。
 *
 * 背景：格式化是服务端直接落库的（预览确认后写入 title/content/inputDesc/
 * outputDesc/hint/timeLimit/memoryLimit/samples），落库成功后编辑页需要刷新这些字段。
 * 早期实现直接 `Object.assign(form, 详情)` 整体覆盖，会把用户在其他字段上
 * （难度、来源、标签、可见性）尚未保存的修改一起冲掉。
 *
 * 这里把「格式化会改哪些字段」和「只合并这些字段」固化成纯函数，
 * 便于在 node --test 中验证，也避免两个页面各写一份判断。
 */

/** AI 格式化会写入的字段（与服务端 applyFormattedFields 对应） */
export const FORMAT_OWNED_FIELDS = Object.freeze([
  'title',
  'content',
  'inputDesc',
  'outputDesc',
  'hint',
  'timeLimit',
  'memoryLimit',
  'samples'
])

/** 字段中文名，用于覆盖提示文案 */
export const FORMAT_FIELD_LABELS = Object.freeze({
  title: '题目名称',
  content: '题目内容',
  inputDesc: '输入描述',
  outputDesc: '输出描述',
  hint: '提示',
  timeLimit: '时间限制',
  memoryLimit: '空间限制',
  samples: '样例数据'
})

/** 样例归一：只保留 input/output 字符串，过滤非数组输入 */
export function normalizeSamples(samples) {
  if (!Array.isArray(samples)) {
    return []
  }
  return samples.map((sample) => ({
    input: sample?.input ?? '',
    output: sample?.output ?? ''
  }))
}

/** 从题目详情中取出格式化负责的字段（缺失时给安全默认值） */
export function pickFormattedFields(detail) {
  const source = detail && typeof detail === 'object' ? detail : {}
  return {
    title: source.title ?? '',
    content: source.content ?? '',
    inputDesc: source.inputDesc ?? '',
    outputDesc: source.outputDesc ?? '',
    hint: source.hint ?? '',
    timeLimit: Number(source.timeLimit ?? 1000),
    memoryLimit: Number(source.memoryLimit ?? 512),
    samples: normalizeSamples(source.samples)
  }
}

/**
 * 只把格式化负责的字段合并进表单对象（原地写入，保持 reactive 引用）。
 * 其他字段（难度、来源、标签、可见性等）保持用户当前编辑值不动。
 */
export function mergeFormattedFields(form, detail) {
  if (!form) {
    return pickFormattedFields(detail)
  }
  const next = pickFormattedFields(detail)
  Object.assign(form, next)
  return next
}

function sameValue(left, right) {
  if (Array.isArray(left) || Array.isArray(right)) {
    return JSON.stringify(left) === JSON.stringify(right)
  }
  return left === right
}

/**
 * 对比当前表单与基线快照，返回格式化会覆盖且有未保存修改的字段 key。
 * @param {object} form 当前表单
 * @param {object} baseline pickFormattedFields 生成的快照
 */
export function changedFormattedFields(form, baseline) {
  if (!form || !baseline) {
    return []
  }
  const current = pickFormattedFields(form)
  return FORMAT_OWNED_FIELDS.filter((field) => !sameValue(current[field], baseline[field]))
}

/** 把字段 key 列表转成提示文案用的中文串 */
export function describeFieldChanges(fields) {
  if (!Array.isArray(fields) || fields.length === 0) {
    return ''
  }
  return fields.map((field) => FORMAT_FIELD_LABELS[field] || field).join('、')
}
