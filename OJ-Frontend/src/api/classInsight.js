import request from '@/utils/request'

// 班级学情聚合统计（比赛管理右侧面板）：GET /class/{classId}/insight?days=7|30
export function getClassInsight(classId, days = 30) {
  return request({
    url: `/class/${classId}/insight`,
    method: 'get',
    params: { days }
  })
}

// 班级学情 AI 解读（基于聚合数据，耗时较长须覆盖全局 10s 超时）
export function generateClassInsightAi(classId, days = 30) {
  return request({
    url: `/class/${classId}/insight/ai`,
    method: 'post',
    params: { days },
    timeout: 300000
  })
}
