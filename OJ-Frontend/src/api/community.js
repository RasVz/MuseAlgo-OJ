/**
 * 社区治理接口（仅管理员）：举报受理、帖子干预与标签治理。
 * 举报列表默认只取待处理（status='PENDING'）——治理台关注的是「还有什么没处理完」，
 * 需要看历史记录时由调用方显式传 status。
 */
import request from '@/utils/request'

export const getModerationReports = (status = 'PENDING') => request({ url: '/community/moderation/reports', method: 'get', params: { status } })
export const getModerationReportDetail = (id) => request({ url: `/community/moderation/reports/${id}`, method: 'get' })
export const resolveModerationReport = (id, data) => request({ url: `/community/moderation/reports/${id}`, method: 'post', data })
export const moderatePost = (id, data) => request({ url: `/community/moderation/post/${id}`, method: 'post', data })
export const getCommunityTags = () => request({ url: '/community/moderation/tags', method: 'get' })
export const createCommunityTag = (data) => request({ url: '/community/moderation/tags', method: 'post', data })
export const setCommunityTagEnabled = (id, enabled) => request({ url: `/community/moderation/tags/${id}`, method: 'post', params: { enabled } })
