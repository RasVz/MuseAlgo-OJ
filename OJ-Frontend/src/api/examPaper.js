import request from '@/utils/request'

// 训练试卷（训练 tab）
export function getExamPaperList(params) {
  return request({
    url: '/exam-paper/list',
    method: 'get',
    params
  })
}

export function getExamPaperDetail(id) {
  return request({
    url: `/exam-paper/${id}`,
    method: 'get'
  })
}

export function saveExamPaper(data) {
  return request({
    url: '/exam-paper/save',
    method: 'post',
    data
  })
}

export function deleteExamPaper(id) {
  return request({
    url: `/exam-paper/delete/${id}`,
    method: 'post'
  })
}

export function submitExamPaperAnswers(id, data) {
  return request({
    url: `/exam-paper/${id}/submit-answers`,
    method: 'post',
    data
  })
}

export function startExamAttempt(id) {
  return request({
    url: `/exam-paper/${id}/attempt/start`,
    method: 'post'
  })
}

export function getExamAttempt(id) {
  return request({
    url: `/exam-paper/${id}/attempt`,
    method: 'get'
  })
}

export function finishExamAttempt(id) {
  return request({
    url: `/exam-paper/${id}/attempt/finish`,
    method: 'post'
  })
}

export function copyExamPaper(id) {
  return request({
    url: `/exam-paper/${id}/copy`,
    method: 'post'
  })
}

export function getExamPaperStats(id) {
  return request({
    url: `/exam-paper/${id}/stats`,
    method: 'get'
  })
}

export function getExamPaperAttempts(id, params) {
  return request({
    url: `/exam-paper/${id}/attempts`,
    method: 'get',
    params
  })
}

export async function exportExamPaperAttempts(id) {
  const res = await request({
    url: `/exam-paper/${id}/attempts/export`,
    method: 'get',
    responseType: 'blob'
  })
  const url = URL.createObjectURL(res)
  const a = document.createElement('a')
  a.href = url
  a.download = `exam-paper-${id}-attempts.csv`
  a.click()
  URL.revokeObjectURL(url)
}
