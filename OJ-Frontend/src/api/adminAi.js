/**
 * AI 能力管理后台接口（仅管理员）。
 * 覆盖调用概览与趋势、调用日志、token 配额排行、运行时配置与提示词模板。
 *
 * 配置项是逐key 保存而非整体提交（见 updateAiConfig）：
 * 后端按 key 增量覆盖，前端保存时串行逐项写入，任一项失败即中断，
 * 避免出现半新半旧的配置组合。
 */
import request from '@/utils/request'

export function getAiOverview(days) {
  return request({ url: '/admin/ai/overview', method: 'get', params: { days } })
}

export function getAiTrend(days) {
  return request({ url: '/admin/ai/trend', method: 'get', params: { days } })
}

export function getAiLogs(params) {
  return request({ url: '/admin/ai/logs', method: 'get', params })
}

export function getAiRanking(days) {
  return request({ url: '/admin/ai/quota-ranking', method: 'get', params: { days } })
}

export function getAiConfig() {
  return request({ url: '/admin/ai/config', method: 'get' })
}

export function updateAiConfig(key, value) {
  return request({ url: '/admin/ai/config', method: 'post', data: { key, value } })
}

export function getAiQuota(userId) {
  return request({ url: `/admin/ai/quota/${userId}`, method: 'get' })
}

export function resetAiQuota(userId) {
  return request({ url: `/admin/ai/quota/${userId}/reset`, method: 'post' })
}

export function setAiQuota(userId, used) {
  return request({ url: `/admin/ai/quota/${userId}/set`, method: 'post', data: { used } })
}

export function getPromptTemplates(key) {
  return request({ url: '/admin/ai/prompts/list', method: 'get', params: key ? { key } : {} })
}

export function createPromptTemplate(key, content) {
  return request({ url: '/admin/ai/prompts/create', method: 'post', data: { key, content } })
}

export function activatePromptTemplate(id) {
  return request({ url: `/admin/ai/prompts/${id}/activate`, method: 'post' })
}
