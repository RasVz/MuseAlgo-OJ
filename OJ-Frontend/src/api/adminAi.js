/**
 * AI 能力管理后台接口（仅管理员）。
 * 覆盖调用概览与趋势、调用日志、token 配额排行、运行时配置、
 * 提示词模板以及教学诊断与试跑。
 *
 * 配置项是逐 key 保存而非整体提交（见 updateAiConfig）：
 * 后端按 key 增量覆盖，前端保存时串行逐项写入，任一项失败即中断，
 * 各键独立提交；中途失败时页面回读实际配置并明确提示部分生效。
 * key 必须与后端 AiAdminController.CONFIG_KEYS 白名单一致（点分隔）。
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

// 教学诊断与试跑。诊断面板原先直接调用 request，此处统一收口，
// 使所有 AI 管理端点在同一处可查、可复用。
export function getAiDiagnostics(days) {
  return request({ url: '/admin/ai/diagnostics', method: 'get', params: { days } })
}

export function getAiDiagnosticsSettings() {
  return request({ url: '/admin/ai/diagnostics/settings', method: 'get' })
}

export function updateAiDiagnosticsSettings(settings) {
  return request({ url: '/admin/ai/diagnostics/settings', method: 'put', data: settings })
}

export function getAiDiagnosticsReference(problemId) {
  return request({ url: '/admin/ai/diagnostics/reference', method: 'get', params: { problemId } })
}

export function updateAiDiagnosticsReference(problemId, postId) {
  return request({ url: '/admin/ai/diagnostics/reference', method: 'put', data: { problemId, postId } })
}
