import request from '@/utils/request'

// 1. 获取班级课件目录
export function getClassDirectory(classId) {
  return request({
    url: '/class-courseware/list',
    method: 'get',
    params: { 
      classId, 
      isVisible: 0 
    }
  })
}

// 2. 添加课件到班级 (根据url查找或创建课件并关联)
// data: { classId, title, url, problemListId, description }
export function addCoursewareToClass(data) {
  return request({
    url: '/class-courseware/add',
    method: 'post',
    data
  })
}

// 3. 更新关联信息 (可见性)
// data: { id, isVisible }  这里的 id 是关联记录的 id (relationId)
export function updateClassCourseware(data) {
  return request({
    url: '/class-courseware/update',
    method: 'post',
    data
  })
}

// 4. 批量更新排序
// sortList: [{ id: relationId, sort: sortOrder }, ...]
export function updateSortBatch(sortList) {
  return request({
    url: '/class-courseware/update-sort-batch',
    method: 'post',
    data: sortList
  })
}

// 5. 从班级移除课件
// id: 关联记录的 id (relationId)
export function removeCoursewareFromClass(id) {
  return request({
    url: `/class-courseware/remove/${id}`,
    method: 'post'
  })
}