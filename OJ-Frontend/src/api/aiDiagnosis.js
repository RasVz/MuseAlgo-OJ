import request from '@/utils/request'

export function getAiDiagnosis(submissionId) {
  return request({ url: `/ai/diagnoses/${submissionId}`, method: 'get' })
}

/** 诊断生成使用 SSE；fetch 才能携带 Bearer token 并支持中止。 */
export function generateAiDiagnosis(submissionId, token, signal) {
  return fetch(`/api/ai/diagnoses/${submissionId}/generate`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  })
}
