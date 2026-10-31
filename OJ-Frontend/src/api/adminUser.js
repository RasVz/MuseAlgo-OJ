import request from '@/utils/request'

export function getAdminUsers(params) {
  return request({ url: '/admin/users/list', method: 'get', params })
}

export function updateUserRole(id, role) {
  return request({ url: `/admin/users/${id}/role`, method: 'post', data: { role } })
}

export function setUserBanned(id, banned) {
  return request({ url: `/admin/users/${id}/ban`, method: 'post', data: { banned } })
}

// 管理员代建账号：{username, password, nickname, role, email?, school?, realName?}
export function createAdminUser(data) {
  return request({ url: '/admin/users/create', method: 'post', data })
}

// 管理员重置密码：{newPassword}
export function resetUserPassword(id, newPassword) {
  return request({ url: `/admin/users/${id}/reset-password`, method: 'post', data: { newPassword } })
}
