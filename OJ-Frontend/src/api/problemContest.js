import request from '@/utils/request'

// 添加单题到竞赛
export function addProblemContest(data) {
  return request({
    url: '/api/problem-contest/add',
    method: 'post',
    data
  })
}

// 批量添加题目到竞赛
export function batchAddProblemContest(contestId, problemIds) {
  return request({
    url: '/api/problem-contest/batch-add',
    method: 'post',
    params: { contestId },
    data: problemIds
  })
}

// 从竞赛移除题目（软删除）
export function removeProblemContest(id) {
  return request({
    url: `/api/problem-contest/remove/${id}`,
    method: 'post'
  })
}

// 切换题目可见性
export function toggleProblemContestVisible(id, visible) {
  return request({
    url: `/api/problem-contest/toggle-visible/${id}`,
    method: 'post',
    params: { visible }
  })
}

// 用户端题目列表（仅 visible=1）
export function getContestProblemList(contestId) {
  return request({
    url: `/api/problem-contest/contest/${contestId}`,
    method: 'get'
  })
}

// 管理端题目列表（包含隐藏）
export function getContestProblemListAdmin(contestId) {
  return request({
    url: `/api/problem-contest/contest/${contestId}/admin`,
    method: 'get'
  })
}

// 更新题目配置（顺序、分值、可见性等）
export function updateProblemContest(data) {
  return request({
    url: '/api/problem-contest/update',
    method: 'post',
    data
  })
}
