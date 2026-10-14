import request from '@/utils/request'

// 触发查重（同步执行，返回本次新增报告数）
export function runPlagiarismCheck(data) {
  return request({
    url: '/teacher/plagiarism/run',
    method: 'post',
    data // { classId, problemId }
  })
}

// 触发比赛查重（M2，同步执行比赛内全部题目跨班比对，返回新增报告数）
export function runContestPlagiarismCheck(contestId) {
  return request({
    url: `/teacher/plagiarism/contest/${contestId}/run`,
    method: 'post'
  })
}

// 查重报告分页（相似度降序）
export function getPlagiarismReports(params) {
  return request({
    url: '/teacher/plagiarism/reports',
    method: 'get',
    params // classId, contestId, problemId, status, page, size
  })
}

// 取提交代码（报告展开对比用）
export function getPlagiarismCodes(submissionIds) {
  return request({
    url: '/teacher/plagiarism/codes',
    method: 'get',
    params: { submissionIds: submissionIds.join(',') }
  })
}

// 更新报告处置状态（PENDING / REVIEWED / IGNORED，处置可逆）
export function updatePlagiarismStatus(id, status) {
  return request({
    url: `/teacher/plagiarism/reports/${id}/status`,
    method: 'post',
    data: { status }
  })
}
