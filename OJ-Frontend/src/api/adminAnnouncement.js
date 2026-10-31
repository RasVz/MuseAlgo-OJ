/**
 * 全站公告管理接口（仅管理员）。
 * 公告对所有登录用户可见，删除不可恢复，前端因此在删除前做二次确认。
 */
import request from '@/utils/request'

export function getAnnouncementList(params) {
  return request({ url: '/admin/announcements/list', method: 'get', params })
}

export function saveAnnouncement(data) {
  return request({ url: '/admin/announcements/save', method: 'post', data })
}

export function deleteAnnouncement(id) {
  return request({ url: `/admin/announcements/delete/${id}`, method: 'post' })
}
