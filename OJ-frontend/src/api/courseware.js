import request from '@/utils/request'

// 获取课件详情（包含 Slidev URL 和 关联题单ID）
// 对应后端: /courseware/{id}
export function getCoursewareDetail(id) {
  return request({
    url: `/courseware/${id}`,
    method: 'get'
  })
}

// 如果你之前还有“上传课件”或“列表”的需求，也可以把它们补在这里
// 例如：
export function getCoursewareList(params) {
  return request({
    url: '/courseware/list',
    method: 'get',
    params
  })
}

// 3. 创建课件
// data: { title, url, problemListId, description, privacy }
export function createCourseware(data) {
  return request({
    url: '/courseware/add',
    method: 'post',
    data
  })
}

// 4. 更新课件
// data: { id, title, url, ... }
export function updateCourseware(data) {
  return request({
    url: '/courseware/update',
    method: 'post',
    data
  })
}

// 5. 删除课件
export function deleteCourseware(id) {
  return request({
    url: `/courseware/delete/${id}`,
    method: 'post'
  })
}

/**
 * 老师上传 MD 课件并生成 Slidev 网页
 * @param {FormData} data 包含文件的表单数据
 */
export function uploadMdCourseware(data) {
  return request({
    url: '/courseware/upload-md',
    method: 'post',
    // 极其重要：告诉 Axios 这是文件上传，不要用默认的 JSON 格式
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: 60000,
    data
  })
}