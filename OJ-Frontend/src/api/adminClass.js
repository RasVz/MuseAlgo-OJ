import request from '@/utils/request'

// 管理员：查看所有教师创建的班级
export function getAdminClasses(params) {
  return request({
    url: '/admin/classes/list',
    method: 'get',
    params
  })
}
