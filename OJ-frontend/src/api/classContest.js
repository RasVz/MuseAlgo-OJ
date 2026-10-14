import request from '@/utils/request'

// 获取班级比赛列表 (支持分页)
export function getClassContests(classId, params) {
  const normalizedParams = { ...(params || {}) }

  if (normalizedParams.currentPage != null && normalizedParams.page == null) {
    normalizedParams.page = normalizedParams.currentPage
  }
  if (normalizedParams.page != null && normalizedParams.currentPage == null) {
    normalizedParams.currentPage = normalizedParams.page
  }
  if (normalizedParams.pageSize != null && normalizedParams.limit == null) {
    normalizedParams.limit = normalizedParams.pageSize
  }
  if (normalizedParams.limit != null && normalizedParams.pageSize == null) {
    normalizedParams.pageSize = normalizedParams.limit
  }

  return request({
    url: `/class/${classId}/contest`,
    method: 'get',
    params: normalizedParams
  })
}

// 为班级添加新比赛
export function addClassContest(classId, data) {
  return request({
    url: `/class/${classId}/contest`,
    method: 'post',
    data
  })
}

// 更新班级比赛信息
export function updateClassContest(classId, id, data) {
  return request({
    url: `/class/${classId}/contest/${id}`,
    method: 'put',
    data
  })
}

// 删除班级比赛
export function deleteClassContest(classId, id) {
  return request({
    url: `/class/${classId}/contest/${id}`,
    method: 'delete'
  })
}
