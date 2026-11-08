/**
 * 报告站内提醒投递队列（仅管理员）。
 *
 * 这里只处理 notice 域的投递事实：报告是否生成成功仍以报告中心为准；
 * 列表不返回 HTML、学生姓名或通知正文。
 */
import request from '@/utils/request'

/** 分页查询报告通知意图，可按状态 / 事件类型 / 任务过滤。 */
export function listReportNotificationIntents(params) {
  return request({
    url: '/admin/report-notification-intents',
    method: 'get',
    params
  })
}

/** 对当前终态修订的 FAILED 意图执行唯一一次重投；原因必填 10-200 字。 */
export function redriveReportNotificationIntent(intentId, reason) {
  return request({
    url: `/admin/report-notification-intents/${intentId}/redrive`,
    method: 'post',
    data: { reason },
    // 对话框内就地展示拒绝原因，避免与全局 toast 重复弹出。
    silent: true
  })
}
