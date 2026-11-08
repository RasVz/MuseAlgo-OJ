/**
 * 本人练习分析接口。
 *
 * 后端契约：GET /profile/practice/me?range=all|30d，返回练习总览、维度掌握度、
 * 标签分布、难度分桶与最近通过/尝试题目。字段名由后端任务冻结，前端只做展示，
 * 不在本地重新统计；range=all 为全部时间，30d 为服务端定义的最近 30 天窗口。
 */
import request from '@/utils/request'

export function getMyPractice(range = 'all') {
  return request({
    url: '/profile/practice/me',
    method: 'get',
    params: { range }
  })
}
