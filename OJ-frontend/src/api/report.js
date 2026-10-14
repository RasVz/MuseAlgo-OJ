import request from '@/utils/request'

/**
 * 为单个学生生成周报（含 PDF 尝试，Chromium 不可达时降级返回 HTML）
 */
export function generateReport(studentId, { classId, periodStart, periodEnd } = {}) {
  return request({
    url: `/profile/report/${studentId}`,
    method: 'post',
    params: { classId, periodStart, periodEnd }
  })
}

/**
 * 批量生成周报 HTML（不走 PDF，始终返回 HTML，前端直接预览）
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
 * 获取周报 HTML 预览地址（直接打开或 iframe 加载）
 */
export function getReportPreviewUrl(studentId, { classId, periodStart, periodEnd } = {}) {
  const params = new URLSearchParams()
  params.set('classId', classId)
  if (periodStart) params.set('periodStart', periodStart)
  if (periodEnd) params.set('periodEnd', periodEnd)
  return `/api/profile/report/${studentId}/preview?${params.toString()}`
}
