/**
 * response — 响应处理公共工具函数
 * 从 api/contest.js 和 api/contestRank.js 中提取，
 * 避免跨文件重复定义。
 */

/**
 * 从响应对象中提取错误消息
 */
export function getResultMessage(resp, fallback = '操作失败') {
  if (!resp || typeof resp !== 'object') return fallback
  return resp.message || resp.msg || fallback
}

/**
 * 判断响应是否成功
 * 兼容 code === 200 和 code === 0 两种常见约定
 */
export function isResultSuccess(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp) {
    return Number(resp.code) === 200 || Number(resp.code) === 0
  }
  return true
}

/**
 * 从响应中安全解包 data
 */
export function unwrapResultData(resp) {
  if (resp && typeof resp === 'object' && 'data' in resp) {
    return resp.data
  }
  return resp
}
