/**
 * 管理后台概览统计接口。
 * 仅管理员可访问，用于仪表盘的总量卡片与趋势图。
 */
import request from '@/utils/request'

// 获取管理后台统计概览（用户/题目/提交总数）
// 对应后端: /admin/status
export function getAdminStatus() {
  return request({
    url: '/admin/status',
    method: 'get'
  })
}

// 获取近 N 日趋势数据
// 对应后端: /admin/trends
export function getAdminTrends(days = 7) {
  return request({
    url: '/admin/trends',
    method: 'get',
    params: { days }
  })
}
