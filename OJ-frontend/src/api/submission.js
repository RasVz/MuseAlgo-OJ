/**
 * 提交记录查询接口。
 * 列表统一走服务端分页与筛选，前端只负责传参；
 * 详情按 id 查询，返回判题结果与代码正文。
 */
import request from '@/utils/request'

export function getSubmissionList(params) {
    return request({
        url: '/submission/list',
        method: 'get',
        params: params
    })
}

export function getSubmissionDetail(id) {
  return request({
    url: '/submission/detail',
    method: 'get',
    params: { id }
  })
}