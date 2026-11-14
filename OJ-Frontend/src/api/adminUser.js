import request from '@/utils/request'

export function getAdminUsers(params, config = {}) {
  return request({ url: '/admin/users/list', method: 'get', params, ...config })
}

export function getAdminUserDetail(id) {
  return request({ url: `/admin/users/${id}`, method: 'get' })
}

export function updateUserRole(id, role) {
  return request({ url: `/admin/users/${id}/role`, method: 'post', data: { role } })
}

export function setUserBanned(id, { banned, reason, expireTime } = {}) {
  return request({ url: `/admin/users/${id}/ban`, method: 'post', data: { banned, reason, expireTime } })
}

export function batchUpdateUserRole(ids, role) {
  return request({ url: '/admin/users/batch/role', method: 'post', data: { ids, role } })
}

export function batchSetUserBanned(ids, { banned, reason, expireTime } = {}) {
  return request({ url: '/admin/users/batch/ban', method: 'post', data: { ids, banned, reason, expireTime } })
}

// 管理员代建账号：{username, password, nickname, role, email?, school?, realName?}
export function createAdminUser(data) {
  return request({ url: '/admin/users/create', method: 'post', data })
}

// 管理员重置密码：{newPassword}
export function resetUserPassword(id, newPassword) {
  return request({ url: `/admin/users/${id}/reset-password`, method: 'post', data: { newPassword } })
}

export function exportAdminUsers(params) {
  return request({ url: '/admin/users/export', method: 'get', params, responseType: 'blob', timeout: 60000 })
}
