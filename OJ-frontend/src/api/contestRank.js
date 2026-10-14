import request from '@/utils/request'

export function getContestRankList(contestId, params = {}) {
  return request({
    url: `/contest/${contestId}/rank/list`,
    method: 'get',
    params
  })
}

export function getContestFinalRankList(contestId, params = {}) {
  return request({
    url: `/contest/${contestId}/rank/final`,
    method: 'get',
    params
  })
}

// 响应处理工具 — re-export 自公共模块
export { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
