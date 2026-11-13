import request from '@/utils/request'

// 成套试卷整卷答题页：只拉作答期允许看到的字段。
export function getAnswerPaper(paperId, attemptId) {
  return request({
    url: `/exam-paper/${paperId}/attempt/${attemptId}/answer/paper`,
    method: 'get'
  })
}

export function getAnswerProblem(paperId, attemptId, problemId) {
  return request({
    url: `/exam-paper/${paperId}/attempt/${attemptId}/answer/problem/${problemId}`,
    method: 'get'
  })
}
