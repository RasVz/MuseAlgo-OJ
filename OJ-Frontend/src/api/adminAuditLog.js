/**
 * 操作审计日志查询（仅管理员，只读）。
 * 审计数据不可修改，因此本模块不提供任何写操作。
 */
import request from '@/utils/request'

export function getAuditLogs(params) {
  return request({ url: '/admin/audit-logs/list', method: 'get', params })
}
