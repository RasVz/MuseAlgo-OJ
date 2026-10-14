/**
 * 社区动态（帖子）接口：列表、详情、发布、编辑、评论、点赞、收藏。
 * 列表接口统一走服务端分页，前端只负责传筛选与排序参数。
 */
import request from '@/utils/request'

// 获取帖子列表
// 对应后端: /post/list
export function getPostList(params) {
  return request({
    url: '/post/list',
    method: 'get',
    params
  })
}

// 获取文章编辑器可选择的受控标签
export function getPostTagOptions() {
  return request({
    url: '/post/tag-options',
    method: 'get'
  })
}

export function getRelatedArticles(problemId, params) {
  return request({
    url: `/post/problem/${problemId}/articles`,
    method: 'get',
    params
  })
}

// 获取热榜列表（动态热门推荐）
// 对应后端: /api/post/hot
export function getHotPosts(params) {
  return request({
    url: '/api/post/hot',
    method: 'get',
    params
  })
}

// 获取帖子详情
// 对应后端: /post/{id}
export function getPostDetail(id) {
  return request({
    url: `/post/${id}`,
    method: 'get'
  })
}

// 帖子点赞/取消点赞
export function togglePostLike(id, active) {
  return request({
    url: `/post/${id}/like`,
    method: 'post',
    params: { active }
  })
}

// 帖子点踩/取消点踩
export function togglePostDislike(id, active) {
  return request({
    url: `/post/${id}/dislike`,
    method: 'post',
    params: { active }
  })
}

// === 新增：获取我的帖子 ===
export function getMyPosts(params) {
  return request({
    url: '/post/my',
    method: 'get',
    params
  })
}

// === 新增：获取评论列表 ===
export function getPostComments(id) {
  return request({
    url: '/comment/list',
    method: 'get',
    params: { entityId: id, entityType: 1 }
  })
}

// === 发布评论 ===
export function addComment(data) {
  return request({
    url: '/comment/add',
    method: 'post',
    data: data
  })
}

// === 评论点赞/取消点赞 ===
export function toggleCommentLike(commentId, active) {
  return request({
    url: '/comment/like',
    method: 'post',
    params: { commentId, active }
  })
}

// 发布新文章
export function createPost(data) {
  return request({
    url: '/post/add',
    method: 'post',
    data: data
  })
}

// === 新增：更新文章 ===
export function updatePost(data) {
  return request({
    url: '/post/update',
    method: 'post',
    data: data
  })
}

// === 新增：删除文章 ===
export function deletePost(id) {
  return request({
    url: `/post/delete/${id}`,
    method: 'post'
  })
}

export function reportPost(data) {
  return request({ url: '/community/report', method: 'post', data })
}

// 获取公告列表
// 对应后端: /post/announcements
export function getAnnouncements() {
  return request({
    url: '/post/announcements',
    method: 'get'
  })
}
