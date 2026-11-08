/** Shared request/authentication error handling; success payloads retain the Result contract. */
import axios from 'axios'
import { useUserStore } from '@/stores/userStore'
import { authBus } from '@/utils/eventBus'
import { trackApiFail } from '@/utils/metrics'
import { ApiError, decodeResponse, responseError } from '@/utils/apiError'

const service = axios.create({ baseURL: '/api', timeout: 10000 })
let refreshPromise = null
let refreshingFor = null
let lastRefresh = null

service.interceptors.request.use(config => {
  const token = useUserStore().getAccessToken()
  if (token) config.headers['Authorization'] = 'Bearer ' + token
  return config
}, error => Promise.reject(error))

function notify(error, config) {
  if (error.notified || config?.silent || config?.authFailureHandled) return
  error.notified = true
  authBus.emit('request:error', error.message)
}

function expireSession(error) {
  if (!error.authNotified) {
    error.authNotified = true
    authBus.emit('auth:fail')
  }
}

function refreshAccessToken() {
  const store = useUserStore()
  const refreshToken = store.getRefreshToken()
  if (!refreshPromise || refreshingFor !== refreshToken) {
    const previousToken = store.getAccessToken()
    if (!refreshToken) {
      const error = new ApiError('请重新登录', { code: 401, errorCode: 'AUTH_REFRESH_INVALID' })
      expireSession(error)
      return Promise.reject(error)
    }
    const pending = axios.post('/api/auth/refresh', { refreshToken }).then(response => {
      const body = response.data
      if (body?.code !== 200) throw responseError(response)
      const token = body.data
      if (typeof token !== 'string' || !token) {
        throw new ApiError('登录刷新响应格式错误', { errorCode: 'INVALID_RESPONSE', response })
      }
      if (store.getRefreshToken() !== refreshToken || store.getAccessToken() !== previousToken) throw sessionChanged()
      store.setAccessToken(token)
      lastRefresh = { previousToken, token, refreshToken }
      return token
    }).catch(async cause => {
      const error = cause instanceof ApiError ? cause : cause.response
        ? responseError({ ...cause.response, data: await decodeResponse(cause.response) })
        : new ApiError('网络连接异常', { errorCode: 'NETWORK_ERROR' })
      if ((error.code === 401 || error.status === 401) && store.getRefreshToken() === refreshToken) expireSession(error)
      throw error
    }).finally(() => {
      if (refreshPromise === pending) { refreshPromise = null; refreshingFor = null }
    })
    refreshPromise = pending
    refreshingFor = refreshToken
  }
  return refreshPromise
}

function sessionChanged() {
  return new ApiError('登录状态已变化，请重新操作', { errorCode: 'AUTH_SESSION_CHANGED' })
}

async function handleFailure(error, config = {}) {
  const unauthorized = error.code === 401 || error.status === 401
  if (unauthorized && config.authFailureHandled) throw error
  const legacyHttp401 = !error.response?.data?.errorCode && error.status === 401
  const mayRefresh = error.errorCode === 'AUTH_TOKEN_EXPIRED' || legacyHttp401
  if (unauthorized && mayRefresh && !config._retry && !config.url?.includes('/auth/')) {
    config._retry = true
    const sentAuthorization = config.headers?.get?.('Authorization') || config.headers?.Authorization || config.headers?.authorization
    const currentToken = useUserStore().getAccessToken()
    let token
    try {
      if ((sentAuthorization || null) !== (currentToken ? 'Bearer ' + currentToken : null)) {
        // Reuse only a rotation performed for this session; never replay across an account switch.
        if (lastRefresh?.previousToken && sentAuthorization === 'Bearer ' + lastRefresh.previousToken
            && currentToken === lastRefresh.token && useUserStore().getRefreshToken() === lastRefresh.refreshToken) token = currentToken
        else throw sessionChanged()
      } else token = await refreshAccessToken()
    } catch (refreshError) {
      if (refreshError.errorCode !== 'AUTH_SESSION_CHANGED' && refreshError.code !== 401 && refreshError.status !== 401) notify(refreshError, config)
      throw refreshError
    }
    config.headers = { ...config.headers, Authorization: 'Bearer ' + token }
    return service(config)
  }
  if (unauthorized) expireSession(error)
  else notify(error, config)
  if (error.status >= 500 || error.code >= 500 || !error.response) trackApiFail(config.url)
  throw error
}

service.interceptors.response.use(async response => {
  let data
  try {
    data = await decodeResponse(response)
  } catch (error) {
    return handleFailure(error, response.config)
  }
  if (response.config?.responseType === 'blob' && data === response.data) return data
  if (data?.code === 200) return data
  return handleFailure(responseError({ ...response, data }), response.config)
}, async cause => {
  if (axios.isCancel(cause) || cause.code === 'ERR_CANCELED') throw cause
  if (!cause.response) {
    return handleFailure(new ApiError(cause.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络连接异常', {
      errorCode: cause.code === 'ECONNABORTED' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR', config: cause.config
    }), cause.config)
  }
  try {
    const data = await decodeResponse({ ...cause.response, config: cause.config })
    return handleFailure(responseError({ ...cause.response, data, config: cause.config }), cause.config)
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === 'INVALID_RESPONSE') return handleFailure(error, cause.config)
    throw error
  }
})

export default service
