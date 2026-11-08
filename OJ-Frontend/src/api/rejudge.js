import request from '@/utils/request'

// T8-4 管理端重判 API 封装。后端统一返回 {code,message,data}，页面负责解包/判错。
export function rejudgeSubmission(submissionId, reason) {
  return request({
    url: `/admin/rejudge/submissions/${submissionId}`,
    method: 'post',
    data: { reason }
  })
}

export function previewRejudgeBatch(payload) {
  return request({ url: '/admin/rejudge/batch/preview', method: 'post', data: payload })
}

export function executeRejudgeBatch(payload) {
  return request({ url: '/admin/rejudge/batch', method: 'post', data: payload })
}

export function getRejudgeOperations(params) {
  return request({ url: '/admin/rejudge/operations', method: 'get', params })
}

export function getRejudgeOperation(id) {
  return request({ url: `/admin/rejudge/operations/${id}`, method: 'get' })
}

export function previewSettlementReplay(contestId) {
  return request({ url: `/admin/settlement-replay/${contestId}/preview`, method: 'get' })
}

export function executeSettlementReplay(contestId, payload) {
  return request({ url: `/admin/settlement-replay/${contestId}`, method: 'post', data: payload })
}

export function getSettlementReplayHistory() {
  return request({ url: '/admin/settlement-replay/history', method: 'get' })
}
export function getSettlementReplayBackup(id) {
  return request({ url: `/admin/settlement-replay/history/${id}`, method: 'get' })
}
export function previewSettlementRestore(id) {
  return request({ url: `/admin/settlement-replay/history/${id}/restore-preview`, method: 'get' })
}
export function restoreSettlementReplay(id, payload) {
  return request({ url: `/admin/settlement-replay/history/${id}/restore`, method: 'post', data: payload })
}
