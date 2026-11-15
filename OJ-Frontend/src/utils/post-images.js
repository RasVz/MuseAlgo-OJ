/**
 * post.images 在接口里是逗号分隔字符串，写作台与卡片内部用数组。
 * 统一解析，避免各页面各自 split 时漏掉空白或空项。
 */
export function parsePostImages(value) {
  if (Array.isArray(value)) return value.map((item) => String(item).trim()).filter(Boolean)
  if (typeof value !== 'string') return []
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}
