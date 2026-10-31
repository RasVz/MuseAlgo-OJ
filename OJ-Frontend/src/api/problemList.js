import request from '@/utils/request'

// 1. 获取题单列表 (广场/我的题单)
export function getProblemLists(params) {
  return request({
    url: '/problem-list/list',
    method: 'get',
    params
  })
}

// 1.1 题单统计（总数/公开/私密/累计收藏，管理中心统计条用）
export function getProblemListStats(params) {
  return request({
    url: '/problem-list/stats',
    method: 'get',
    params
  })
}

// 1.2 题单手动排序（全量有序 id 列表，管理中心拖拽/置顶用）
// data: { type, ids: [有序 id] }
export function updateProblemListSort(data) {
  return request({
    url: '/problem-list/update-sort',
    method: 'post',
    data
  })
}

// 2. 创建题单
export function createProblemList(data) {
  return request({
    url: '/problem-list/add',
    method: 'post',
    data
  })
}

// 3. 更新题单 (基本信息)
export function updateProblemList(data) {
  return request({
    url: '/problem-list/update',
    method: 'post',
    data
  })
}

// 4. 删除题单
export function deleteProblemList(id) {
  return request({
    url: `/problem-list/delete/${id}`,
    method: 'post'
  })
}

// 5. 获取题单详情(不含题目列表)
export function getProblemListDetail(id) {
  return request({
    url: `/problem-list/${id}`,
    method: 'get'
  })
}

// 6. 向题单添加题目 (对应 ProblemListItemController)
// data: { problemListId, problemDisplayId }
export function addProblemToList(data) {
  return request({
    url: '/problem-list-item/add',
    method: 'post',
    data
  })
}

// 7. 从题单移除题目 (对应 ProblemListItemController)
// data: { problemListId, problemId }
export function removeProblemFromList(data) {
  return request({
    url: '/problem-list-item/remove',
    method: 'post',
    data
  })
}

// 8. 获取题单内的题目列表
// params: { listId }
export function getListProblems(params) {
  return request({
    url: '/problem-list-item/list',
    method: 'get',
    params
  })
}

// 8.1 查询当前用户题单中包含指定题目的题单 id 集合（题目详情页「加入题单」标记已加入用）
export function getContainedProblemLists(problemId) {
  return request({
    url: '/problem-list-item/contained',
    method: 'get',
    params: { problemId }
  })
}

// 其他辅助接口
export function getMyHomeworks(params) {
  return request({
    url: '/problem-list/my-homeworks',
    method: 'get',
    params
  })
}

export function getTodoHomeworkCount(userId) {
  return request({
    url: '/problem-list/todo-count',
    method: 'get',
    params: { userId }
  })
}

// 修改题目顺序
export function updateProblemOrder(data) {
  return request({
    url: '/problem-list/update-order',
    method: 'post',
    data
  })
}