import request from '@/utils/request'

// 获取用户个人资料
export function getUserInfo(userId) {
  return request({
    url: `/user/info/${userId}`,
    method: 'get'
  })
}

// 获取用户活跃图数据
export function getUserHeatmap(userId) {
  return request({
    url: `/user/activity/heatmap/${userId}`,
    method: 'get'
  })
}

// 切换关注状态 (关注/取关)
export function toggleFollow(data) {
  return request({
    url: '/user-relation/toggle',
    method: 'post',
    data
  })
}

// 获取某人的关注列表
export function getFollowingList(userId, params) {
  return request({
    url: `/user-relation/following/${userId}`,
    method: 'get',
    params // page, size
  })
}

// 获取某人的粉丝列表
export function getFollowerList(userId, params) {
  return request({
    url: `/user-relation/followers/${userId}`,
    method: 'get',
    params
  })
}

export function updateUserProfile(data) {
  return request({
    url: '/user/profile',
    method: 'put',
    data
  })
}

export function changeUserPassword(data) {
  return request({
    url: '/user/password',
    method: 'put',
    data
  })
}

export function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)

  return request({
    url: '/file/upload',
    method: 'post',
    data: formData,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export function getUserPreferences() {
  return request({
    url: '/user/preferences',
    method: 'get'
  })
}

export function updateUserPreferences(data) {
  return request({
    url: '/user/preferences',
    method: 'put',
    data
  })
}

export function getAcceptedRankList(limit = 10) {
  return request({
    url: '/user/rank/accepted',
    method: 'get',
    params: { limit }
  })
}

export function getRatingRankList(limit = 10) {
  return request({
    url: '/user/rank/rating',
    method: 'get',
    params: { limit }
  })
}

// 获取用户 Rating 历史数据
export function getUserRatingHistory(userId) {
  return request({
    url: `/user/rating/history/${userId}`,
    method: 'get'
  })
}

// 获取用户六维雷达图数据
export function getUserRadar(userId) {
  return request({
    url: `/user/radar/${userId}`,
    method: 'get'
  })
}

// 获取用户最近 AC 记录
export function getRecentAC(userId) {
  return request({
    url: `/user/recent-ac/${userId}`,
    method: 'get'
  })
}

// 获取智能推荐题单
export function getRecommend(userId) {
  return request({
    url: `/user/recommend/${userId}`,
    method: 'get'
  })
}


// 获取错题本；today 为今日优先复习，all 为全部待复习题
export function getReviewBook(scope = 'today') {
  return request({
    url: '/user/review-book',
    method: 'get',
    params: { scope }
  })
}

export function markReviewMastered(problemId, mastered) {
  return request({
    url: `/user/review-book/${problemId}/mastered`,
    method: 'post',
    params: { mastered }
  })
}

export function getWeaknessAnalysis(params) {
  return request({
    url: '/profile/weakness',
    method: 'get',
    params
  })
}
