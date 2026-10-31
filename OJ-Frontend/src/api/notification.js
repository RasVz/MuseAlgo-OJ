/**
 * 站内通知接口：通知列表与未读数。
 * scope 用于区分提醒类型（全部 / 互动 / 业务提醒），默认取全部。
 */
import request from '@/utils/request'

export const getNotifications = (scope = 'all') => request({ url: '/notification/list', method: 'get', params: { scope } })
export const getUnreadNotificationCount = () => request({ url: '/notification/unread-count', method: 'get' })
export const markNotificationRead = (id) => request({ url: `/notification/${id}/read`, method: 'post' })
export const markAllNotificationsRead = () => request({ url: '/notification/read-all', method: 'post' })
