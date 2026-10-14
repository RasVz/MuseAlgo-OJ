import request from '@/utils/request'

// 获取班级题单列表 (支持分页)
export function getClassProblemLists(classId, params) {
  return request({
    url: `/class/${classId}/problem-list`,
    method: 'get',
    params
  })
}

// 为班级添加新题单
export function addClassProblemList(classId, data) {
  return request({
    url: `/class/${classId}/problem-list`,
    method: 'post',
    data
  })
}

// 更新班级题单信息
export function updateClassProblemList(classId, id, data) {
  return request({
    url: `/class/${classId}/problem-list/${id}`,
    method: 'put',
    data
  })
}

// 删除班级题单
export function deleteClassProblemList(classId, id) {
  return request({
    url: `/class/${classId}/problem-list/${id}`,
    method: 'delete'
  })
}