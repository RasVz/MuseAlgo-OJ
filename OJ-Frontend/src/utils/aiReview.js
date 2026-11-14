export const AI_REVIEW_ACTIVE_STATES = new Set(['QUEUED', 'RUNNING', 'CANCELLING'])

export const isAiReviewActive = status => AI_REVIEW_ACTIVE_STATES.has(String(status || '').toUpperCase())

export const aiReviewStatus = status => ({
  QUEUED: { label: '排队中', type: 'info' },
  RUNNING: { label: '生成中', type: 'warning' },
  CANCELLING: { label: '停止中', type: 'warning' },
  SUCCEEDED: { label: '已完成', type: 'success' },
  FAILED: { label: '生成失败', type: 'danger' },
  CANCELLED: { label: '已停止', type: 'info' }
}[String(status || '').toUpperCase()] || { label: '状态未知', type: 'info' })

export const aiReviewRelation = type => ({
  INITIAL: '首次复盘',
  REANALYZE: '重新分析',
  FOLLOW_UP: '追问',
  RETRY: '失败重试'
}[String(type || '').toUpperCase()] || '代码复盘')

export const aiReviewRequestKey = () => {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `review-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
