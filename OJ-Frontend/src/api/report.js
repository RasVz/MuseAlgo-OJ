import request from '@/utils/request'

// =============================================================================
// 学习周报（教师侧）API。
//
// 生成入口统一为 enqueue-only：POST 只创建或复用持久任务并立即返回，报告由后台
// worker 生成。PENDING / GENERATING 是正常进行态；只有 GET 详情且 status=GENERATED
// 才返回 html。列表永远不返回 html；前端不得把受理响应当成同步生成结果。
//
// REPORT_READY 提醒、站内通知与已读是通知域的独立事实，用
// getReportNotificationStatus 单独查询；提醒投递失败不等于报告生成失败。
// =============================================================================

/**
 * 旧单人报告入口（兼容）：只受理/复用报告任务，不等待渲染。
 * 响应保留 snapshotId/pdfUrl/pdfGenerated/html 字段并追加 jobId/status/accepted；
 * 非 GENERATED 时 snapshotId/html 为 null。HTML 请从任务详情读取。
 */
export function generateReport(studentId, { classId, periodStart, periodEnd } = {}) {
  return request({
    url: `/profile/report/${studentId}`,
    method: 'post',
    params: { classId, periodStart, periodEnd }
  })
}

/**
 * 旧批量报告入口（兼容）：每个学生独立受理/复用任务，接口立即返回。
 * 每条结果含 jobId/status/accepted；PENDING/GENERATING 为 accepted=true、success=false、
 * html=null（排队不是失败）。只有 GENERATED 才 success=true。前端必须先看 accepted/status。
 */
export function generateBatchReports({ classId, studentIds, periodStart, periodEnd }) {
  return request({
    url: '/profile/report/batch',
    method: 'post',
    params: { periodStart, periodEnd },
    data: { classId, studentIds }
  })
}

/**
 * 旧只读预览地址：仅当该自然键任务已 GENERATED 才返回 HTML；
 * PENDING/GENERATING/FAILED 返回 409 且无 HTML，无任务返回 404。
 * 该 GET 不会创建任务、重试或同步渲染。
 */
export function getReportPreviewUrl(studentId, { classId, periodStart, periodEnd } = {}) {
  const params = new URLSearchParams()
  params.set('classId', classId)
  if (periodStart) params.set('periodStart', periodStart)
  if (periodEnd) params.set('periodEnd', periodEnd)
  return `/api/profile/report/${studentId}/preview?${params.toString()}`
}

/**
 * 受理或复用一份学习周报任务（幂等键：学生 + 班级 + 周期）。
 * 立即返回当前任务状态，可能是 PENDING / GENERATING / GENERATED / FAILED；
 * 不等待后台生成，也不返回 HTML。已存在任务（含历史预生成任务）不会重新渲染。
 */
export function requestReportJob({ studentId, classId, periodStart, periodEnd }) {
  return request({
    url: '/profile/report/jobs',
    method: 'post',
    params: { studentId, classId, periodStart, periodEnd }
  })
}

/**
 * 班级报告历史（分页）。列表行 status 可为进行态，但 html 始终为 null；
 * 前端不得为列表行拉取 HTML。
 * @param {{classId: number|string, page?: number, size?: number}} params
 * @param {{silent?: boolean}} [options] 轮询时传 silent，避免重复全局错误提示
 */
export function listReportJobs({ classId, page = 1, size = 10 }, { silent = false } = {}) {
  return request({
    url: '/profile/report/jobs',
    method: 'get',
    params: { classId, page, size },
    silent
  })
}

/**
 * 报告任务详情。仅 status=GENERATED 时返回 html 与真实 snapshotId；
 * PENDING/GENERATING/FAILED 的 html 为 null。
 * @param {number|string} jobId
 * @param {{silent?: boolean}} [options] 轮询时传 silent
 */
export function getReportJob(jobId, { silent = false, signal } = {}) {
  return request({
    url: `/profile/report/jobs/${jobId}`,
    method: 'get',
    silent,
    signal
  })
}

/**
 * 显式重试失败任务。只有服务端 ReportJobVO.canRetry=true 的 FAILED 任务才应展示入口；
 * PENDING/GENERATING 返回当前任务，GENERATED 返回当前成功结果；
 * 预算耗尽或错误类型不允许时返回 409（ATTEMPTS_EXHAUSTED / RETRY_NOT_ALLOWED）。
 */
export function retryReportJob(jobId) {
  return request({
    url: `/profile/report/jobs/${jobId}/retry`,
    method: 'post'
  })
}

/**
 * 报告通知状态（仅当前收件人）。与报告状态分开返回：
 * { jobId, centerAvailable, currentTerminalRevision, intents: [...] }。
 * intents 状态为 PENDING / DISPATCHING / DELIVERED / SKIPPED_PREFERENCE /
 * SKIPPED_PERMISSION / SKIPPED_SUPERSEDED / FAILED，含 attempts/maxAttempts 与
 * isRead/readAt。centerAvailable 由报告域按 job.status=GENERATED 汇总。
 */
export function getReportNotificationStatus(jobId) {
  return request({
    url: `/profile/report/jobs/${jobId}/notification-status`,
    method: 'get'
  })
}

/**
 * 外部通道 dry-run（仅管理员）：只记录交付演练，不会真实发送。
 * channel: EMAIL | SMS | WECHAT；recipientId: 平台用户 ID。
 */
export function dryRunReportDelivery(jobId, { channel, recipientId }) {
  return request({
    url: `/profile/report/jobs/${jobId}/deliveries/dry-run`,
    method: 'post',
    data: { channel, recipientId }
  })
}
