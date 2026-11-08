/**
 * 比赛等级配置接口（仅管理员，T10-1）。
 *
 * 等级决定正式赛的参赛 Rating 范围（下限含、上限不含，null 表示该侧不限），
 * 比赛创建/更新时把等级快照到比赛行，之后修改等级配置不会追溯历史比赛。
 */
import request from '@/utils/request'

export function getContestLevelList() {
  return request({ url: '/admin/contest-levels', method: 'get' })
}

export function createContestLevel(data) {
  return request({ url: '/admin/contest-levels', method: 'post', data })
}

export function updateContestLevel(id, data) {
  return request({ url: `/admin/contest-levels/${id}`, method: 'put', data })
}
