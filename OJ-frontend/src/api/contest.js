import request from '@/utils/request'

export function getContestList(params) {
  return request({
    url: '/contest/list',
    method: 'get',
    params
  })
}

export function getContestDetail(id) {
  return request({
    url: `/contest/${id}`,
    method: 'get'
  })
}

// 获取比赛题目
export function getContestProblemList(contestId) {
  return request({
    url: `/contest/${contestId}/problem/list`,
    method: 'get'
  })
}

// 创建比赛
export function createContest(data) {
  return request({
    url: '/contest/add',
    method: 'post',
    data
  })
}

// 更新比赛
export function updateContest(data) {
  return request({
    url: '/contest/update',
    method: 'post',
    data
  })
}

// 更新比赛状态（常见接口）
export function updateContestStatus(contestId, status) {
  return request({
    url: '/contest/update/status',
    method: 'post',
    data: { contestId, status }
  })
}

export async function exportContestRank(contestId) {
  const res = await request({
    url: `/contest/${contestId}/rank/export`,
    method: 'get',
    responseType: 'blob'
  })
  const url = URL.createObjectURL(res)
  const a = document.createElement('a')
  a.href = url
  a.download = `contest-${contestId}-rank.csv`
  a.click()
  URL.revokeObjectURL(url)
}

export function startVp(contestId) {
  return request({ url: `/contest/${contestId}/vp/start`, method: 'post' })
}

export function finishVp(contestId) {
  return request({ url: `/contest/${contestId}/vp/finish`, method: 'post' })
}

export function getVp(contestId) {
  return request({ url: `/contest/${contestId}/vp`, method: 'get' })
}

export function getCorrection(contestId) {
  return request({ url: `/contest/${contestId}/correction`, method: 'get' })
}

// 更新比赛状态（兼容兜底）
export async function updateContestStatusSmart(contestId, status) {
  const candidates = [
    {
      url: '/contest/update/status',
      method: 'post',
      data: { contestId, status }
    },
    {
      url: '/contest/status/update',
      method: 'post',
      data: { contestId, status }
    },
    {
      url: '/contest/update',
      method: 'post',
      data: { id: contestId, contestId, status, contestStatus: status }
    }
  ]

  let lastError
  for (const config of candidates) {
    try {
      return await request(config)
    } catch (e) {
      lastError = e
    }
  }
  throw lastError
}

// 删除比赛
export function deleteContest(id) {
  return request({
    url: `/contest/delete/${id}`,
    method: 'post'
  })
}

// 报名比赛接口
export function registerContestApi(contestId) {
  return request({
    url: `/contest/register/${contestId}`,
    method: 'post'
  })
}

/**
 * 获取比赛提交记录（优先接口）
 * 期望后端支持 params:
 * - currentPage / pageSize
 * - mineOnly (true/false)
 * - problemId
 * - language
 * - status
 */
export function getContestSubmissionList(contestId, params = {}) {
  return request({
    url: `/contest/${contestId}/submission/list`,
    method: 'get',
    params
  })
}

/**
 * 兼容兜底接口1
 */
export function getContestSubmissionListFallbackA(contestId, params = {}) {
  return request({
    url: `/contest/submission/list/${contestId}`,
    method: 'get',
    params
  })
}

/**
 * 兼容兜底接口2（一些项目会复用全站提交接口并带 contestId）
 */
export function getContestSubmissionListFallbackB(params = {}) {
  return request({
    url: '/submission/list',
    method: 'get',
    params
  })
}

/**
 * 重建比赛实时排行榜
 */
export function rebuildContestRank(contestId) {
  return request({
    url: `/contest/${contestId}/rank/rebuild`,
    method: 'post'
  })
}

// 获取即将开始的比赛数量（顶部导航栏徽标用）
export function getUpcomingContestCount() {
  return request({
    url: '/contest/upcoming-count',
    method: 'get'
  })
}

// 响应处理工具 — re-export 自公共模块
export { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
