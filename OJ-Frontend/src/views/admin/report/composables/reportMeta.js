// 学习周报状态与文案映射：报告域、交付域、通知域各自独立，不合并展示。

export const MAX_ATTEMPTS = 3

export const STATUS_META = {
  PENDING: { label: '排队中', type: 'info' },
  GENERATING: { label: '生成中', type: 'warning' },
  GENERATED: { label: '已生成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

export const DELIVERY_STATUS_META = {
  PENDING: { label: '待交付', type: 'info' },
  DELIVERED: { label: '已送达', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  DRY_RUN: { label: 'dry-run', type: 'warning' }
}

export const NOTIFICATION_STATUS_META = {
  PENDING: { label: '等待投递', type: 'info' },
  DISPATCHING: { label: '投递中', type: 'warning' },
  DELIVERED: { label: '已写入站内通知', type: 'success' },
  SKIPPED_PREFERENCE: { label: '偏好关闭，已跳过', type: 'info' },
  SKIPPED_PERMISSION: { label: '权限失效，已跳过', type: 'warning' },
  SKIPPED_SUPERSEDED: { label: '已被新版本取代', type: 'info' },
  FAILED: { label: '提醒投递失败', type: 'danger' }
}

export const EVENT_KIND_LABEL = {
  REPORT_READY: '报告完成提醒',
  REPORT_FAILED: '报告失败提醒'
}

export const CHANNEL_LABEL = {
  IN_APP: '站内', EMAIL: '邮件', SMS: '短信', WECHAT: '微信'
}

export const EXTERNAL_CHANNELS = [
  { value: 'EMAIL', label: '邮件 EMAIL' },
  { value: 'SMS', label: '短信 SMS' },
  { value: 'WECHAT', label: '微信 WECHAT' }
]

export function metaOf(map, status) {
  return map[status] || { label: status || '未知', type: 'info' }
}

export function isInProgress(status) {
  return status === 'PENDING' || status === 'GENERATING'
}

export function failureCode(job) {
  return job?.generationFailure?.code || job?.lastErrorCode || ''
}

export function failureMessage(job) {
  return job?.generationFailure?.safeMessage || job?.errorMessage || '报告生成失败'
}

// 重试入口只认服务端预算与错误策略，前端 attempts 不参与判定。
export function canRetry(job) {
  return job?.status === 'FAILED' && (job?.canRetry === true || job?.generationFailure?.canRetry === true)
}

export function errorMessage(error, fallback) {
  return error?.response?.data?.message || error?.response?.data?.msg || error?.message || fallback
}

export function isForbidden(error) {
  return Number(error?.response?.status) === 403 || Number(error?.response?.data?.code) === 403
}
