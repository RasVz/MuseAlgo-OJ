import request from '@/utils/request'

// 注册接口
export const registerApi = (data) => {
  return request({
    url: '/auth/register',
    method: 'post',
    data
  })
}

// 登录接口
export const loginApi = (data) => {
  return request({
    url: '/auth/login',
    method: 'post',
    data
  })
}

// 注销登录（服务端拉黑 token；尽力而为：失败由调用方降级为本地退出）
export const logoutApi = (refreshToken) => {
  return request({
    url: '/auth/logout',
    method: 'post',
    data: refreshToken ? { refreshToken } : {}
  })
}