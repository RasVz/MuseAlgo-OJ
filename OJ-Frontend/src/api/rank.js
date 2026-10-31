import request from '@/utils/request'

// 获取全站排行榜
export function getRankList(limit) {
  return request({
    url: '/rank/list',
    method: 'get',
    params: {
      limit: limit
    }
  })
}