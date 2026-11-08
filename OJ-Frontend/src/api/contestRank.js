import request from '@/utils/request'

// 正式榜（实时）：只含正式选手，由 GET 查询当前权威提交快照。
// SSE 仅提示重新查询，不携带榜单行。
export function getContestRankList(contestId, params = {}) {
  return request({
    url: `/contest/${contestId}/rank/list`,
    method: 'get',
    params
  })
}

// 正式榜（最终）：比赛结束后由 MySQL 固化快照返回，同样只含正式选手
export function getContestFinalRankList(contestId, params = {}) {
  return request({
    url: `/contest/${contestId}/rank/final`,
    method: 'get',
    params
  })
}

// 全局榜：正式 OFFICIAL + 超限打星号 STARRED + 虚拟赛 VP，三类合并、名次统一连续编号。
// 每行额外带 participantType；正式榜接口不返回该字段的差异只体现在过滤范围上。
export function getContestGlobalRankList(contestId) {
  return request({
    url: `/contest/${contestId}/rank/global/list`,
    method: 'get'
  })
}

// Rank SSE frames only invalidate this board; its matching GET remains authoritative.
// The access token is attached by the fetch-based SSE client in Authorization.
export function getContestRankStreamUrl(contestId, boardType = 'official') {
  const streamPath = boardType === 'global' ? 'ranking/global/stream' : 'ranking/stream'
  return `/api/contest/${contestId}/${streamPath}`
}

/**
 * 全局榜 CSV 导出（仅管理员）。
 *
 * 后端直接返回 text/csv 字节流（含 UTF-8 BOM），不是统一响应体，
 * 因此 responseType 用 blob；响应拦截器对 blob 原样放行，这里只负责触发下载。
 */
export async function exportContestGlobalRank(contestId) {
  const blob = await request({
    url: `/contest/${contestId}/rank/global/export`,
    method: 'get',
    responseType: 'blob'
  })

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `contest-${contestId}-global-rank.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

// 响应处理工具 — re-export 自公共模块
export { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
