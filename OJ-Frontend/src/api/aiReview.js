import request from '@/utils/request'

export function createAiReview(payload) {
  return request({ url: '/ai/reviews', method: 'post', data: payload })
}

export function getAiReviewList(params) {
  return request({ url: '/ai/reviews', method: 'get', params })
}

export function getAiReviewDetail(id) {
  return request({ url: `/ai/reviews/${id}`, method: 'get' })
}

export function getAiReviewStatus(id) {
  return request({ url: `/ai/reviews/${id}/status`, method: 'get' })
}

export function cancelAiReview(id) {
  return request({ url: `/ai/reviews/${id}/cancel`, method: 'post' })
}
