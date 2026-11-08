/**
 * 题库列表「列设置」的显隐状态读写。
 *
 * 抽成纯函数是为了能在 node --test 里直接验证持久化与容错行为：
 * 组件内联实现无法脱离浏览器环境测试，而这段逻辑的失败表现是
 * 「刷新后列设置丢失」或「localStorage 里是坏 JSON 导致整页报错」，
 * 都属于用户可感知的问题，值得单独覆盖。
 */

/** 列设置支持的列（顺序即下拉里的展示顺序） */
export const ADMIN_PROBLEM_COLUMN_OPTIONS = Object.freeze([
  { key: 'passRatio', label: '提交 / 通过' },
  { key: 'source', label: '来源' },
  { key: 'tags', label: '标签' }
])

/** 默认三列全部隐藏（需求：默认不展示） */
export const DEFAULT_ADMIN_PROBLEM_COLUMNS = Object.freeze({
  passRatio: false,
  source: false,
  tags: false
})

/** localStorage 键名 */
export const ADMIN_PROBLEM_COLUMNS_KEY = 'musealgo_admin_problem_columns'

/**
 * 从 storage 读取列显隐；缺省、坏 JSON、非对象一律回落到默认值（全隐藏）。
 * @param {{ getItem: (key: string) => string|null }} storage
 */
export function loadColumnVisibility(storage) {
  const result = { ...DEFAULT_ADMIN_PROBLEM_COLUMNS }
  if (!storage || typeof storage.getItem !== 'function') {
    return result
  }
  let saved
  try {
    saved = JSON.parse(storage.getItem(ADMIN_PROBLEM_COLUMNS_KEY) || '{}')
  } catch {
    return result
  }
  if (!saved || typeof saved !== 'object' || Array.isArray(saved)) {
    return result
  }
  Object.keys(result).forEach((key) => {
    result[key] = saved[key] === true
  })
  return result
}

/**
 * 持久化列显隐；storage 不可用（隐私模式、配额满）时静默失败，不影响页面使用。
 * @param {{ setItem: (key: string, value: string) => void }} storage
 * @param {Record<string, boolean>} visibility
 */
export function saveColumnVisibility(storage, visibility) {
  if (!storage || typeof storage.setItem !== 'function') {
    return false
  }
  const payload = {}
  Object.keys(DEFAULT_ADMIN_PROBLEM_COLUMNS).forEach((key) => {
    payload[key] = visibility?.[key] === true
  })
  try {
    storage.setItem(ADMIN_PROBLEM_COLUMNS_KEY, JSON.stringify(payload))
    return true
  } catch {
    return false
  }
}
