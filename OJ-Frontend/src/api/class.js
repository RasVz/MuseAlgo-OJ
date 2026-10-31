import request from '@/utils/request'

// 获取我的班级列表
export function getMyClasses(params) {
  return request({
    url: '/class/my',
    method: 'get',
    params
  })
}

// 加入班级
export function joinClass(data) {
  return request({
    url: '/class/join',
    method: 'post',
    data
  })
}

// 获取班级详情
export function getClassDetail(id) {
  return request({
    url: `/class/${id}`,
    method: 'get'
  })
}

// 获取班级课程目录
export function getClassDirectory(classId) {
  return request({
    url: '/class-courseware/list',
    method: 'get',
    params: { classId, isVisible: 1 }
  })
}

// 获取班级作业（题单）
export function getClassList(params) {
  return request({
    url: '/problem-list/list',
    method: 'get',
    params: params // 包含 page, size, classId
  })
}

export function getClassMembers(params) {
  return request({
    url: '/class/members',
    method: 'get',
    params // classId, page, size, keyword
  })
}

// 修改成员备注
export function updateMemberRemark(data) {
  return request({
    url: '/class/member/remark',
    method: 'put',
    data // { classId, userId, remark }
  })
}

// 移出成员
export function removeMember(data) {
  return request({
    url: '/class/member/remove',
    method: 'delete',
    data // { classId, userId }
  })
}

export function getClassHomeworkStatusOverview(classId, homeworkId) {
  return request({
    url: `/class/${classId}/homework/${homeworkId}/status/overview`,
    method: 'get'
  })
}

export function getClassHomeworkStatusSubmissions(classId, homeworkId, params) {
  return request({
    url: `/class/${classId}/homework/${homeworkId}/status/submissions`,
    method: 'get',
    params
  })
}

export function getClassContestList(classId, params) {
  return request({
    url: `/class/${classId}/contests`, // 根据你后端实际的Controller路径调整
    method: 'get',
    params: params
  })
}

// 教师：按 teacherId 获取班级列表（管理端）
export function getTeacherClassList(teacherId) {
  return request({
    url: '/class/list',
    method: 'get',
    params: { teacherId }
  })
}

// 创建班级（教师）
export function createClass(data) {
  return request({
    url: '/class/create',
    method: 'post',
    data // { name, school, description, teacherId }
  })
}

// 生成班级邀请码
export function getClassInviteCode(classId) {
  return request({
    url: '/class/inviteCode',
    method: 'get',
    params: { classId }
  })
}

// 通过邀请码加入班级
export function joinClassByCode(data) {
  return request({
    url: '/class/joinByCode',
    method: 'post',
    data // { code, userId }
  })
}