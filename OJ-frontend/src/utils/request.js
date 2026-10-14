/**
 * axios 实例与全局拦截器。
 * <p>
 * 集中处理三件事，让业务代码不必各自重复：
 * 1. 注入 Authorization 头；
 * 2. 把后端统一响应体（{@code {code, message, data}}）解包成可直接使用的数据；
 * 3. Token 过期时自动用 refreshToken 换新并重放原请求。
 * </p>
 * <p>
 * 交互提示（弹窗、toast）不直接依赖 element-plus，而是通过 {@link authBus}
 * 发事件、由 {@code useAuthInterceptor} 统一接管——这样 utils 层可独立测试，
 * 也避免在非组件环境（路由守卫）中直接调用 UI 组件。
 * </p>
 */
import axios from 'axios'
import { useUserStore } from '@/stores/userStore'
import { authBus } from '@/utils/eventBus'
import { trackApiFail } from '@/utils/metrics'

// baseURL 走 vite dev proxy / Nginx 反代，前端不写死后端域名
const service = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// === Token 刷新队列 ===
// 并发场景下多个请求同时 401，若各自去刷新会互相覆盖 refreshToken。
// 这里用「单飞」策略：第一个 401 发起刷新，其余请求挂起进 requests 队列，
// 刷新成功后统一带上新 token 重放；刷新失败则整队reject，避免请求悬挂。
let isRefreshing = false
let requests = []

function rejectQueuedRequests(error) {
  requests.forEach(({ reject }) => reject(error))
  requests = []
}

// === 请求拦截器 ===
service.interceptors.request.use(
  config => {
    const token = useUserStore().getAccessToken()
    if (token) {
      config.headers['Authorization'] = 'Bearer ' + token
    }
    return config
  },
  error => Promise.reject(error)
)

// === 响应拦截器 ===
service.interceptors.response.use(
  response => {
    const res = response.data
    // 业务层 401 → 登录态失效，通知 UI 层处理
    if (res.code === 401) {
      authBus.emit('auth:fail')
      return Promise.reject(new Error(res.message || 'Unauthorized'))
    }
    // 业务层 403 → 已登录但无权限，保持登录态，仅提示原因
    if (res.code === 403) {
      authBus.emit('request:error', res.message || '无权访问该内容')
      const err = new Error(res.message || 'Forbidden')
      // 附带原始业务响应，方便调用方识别是 403 并做对应 UI 处理
      err.response = { data: res }
      return Promise.reject(err)
    }
    // 业务层其他错误码（400/500 等）→ 统一全局提示，避免页面静默失败。
    // 页面如需自行处理提示，可在请求配置中传 silent: true 跳过全局 toast。
    if (res.code !== 200) {
      if (!response.config?.silent) {
        authBus.emit('request:error', res.message || '请求失败')
      }
      const err = new Error(res.message || 'Request failed')
      // 附带原始业务响应，方便调用方识别业务码并做对应 UI 处理
      err.response = { data: res }
      return Promise.reject(err)
    }
    return res
  },
  async error => {
    const originalRequest = error.config

    if (!error.response) {
      trackApiFail(originalRequest?.url) // 体验埋点：网络异常
      authBus.emit('request:error', '网络连接异常')
      return Promise.reject(error)
    }

    const status = error.response.status

    // === 1. 处理 401 Token 过期 (尝试刷新) ===
    if (status === 401) {
      // 刷新接口自身 401 说明 refreshToken 也失效了，不能再重试，直接判登录失效
      if (originalRequest.url.includes('/auth/refresh')) {
        authBus.emit('auth:fail')
        return Promise.reject(error)
      }

      // 已有刷新在进行中：本次请求挂起，等刷新完成后由队列统一重放
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          requests.push({
            resolve: (newToken) => {
              originalRequest.headers['Authorization'] = 'Bearer ' + newToken
              resolve(service(originalRequest))
            },
            reject
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      const refreshToken = useUserStore().getRefreshToken()

      if (!refreshToken) {
        isRefreshing = false
        rejectQueuedRequests(error)
        authBus.emit('auth:fail')
        return Promise.reject(error)
      }

      try {
        const { data } = await axios.post('/api/auth/refresh', { refreshToken })
        const newAccessToken = data.accessToken

        if (newAccessToken) {
          // 通过 store 统一写入，保持响应式同步
          useUserStore().setAccessToken(newAccessToken)

          requests.forEach(({ resolve }) => resolve(newAccessToken))
          requests = []
          originalRequest.headers['Authorization'] = 'Bearer ' + newAccessToken
          return service(originalRequest)
        } else {
          throw new Error('Refresh failed')
        }
      } catch (refreshError) {
        authBus.emit('auth:fail')
        rejectQueuedRequests(refreshError)
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    // === 2. 处理 403 无权限：登录态仍然有效，只提示，不清登录 ===
    if (status === 403) {
      authBus.emit('request:error', '无权访问该资源')
      return Promise.reject(error)
    }

    // === 3. 其他错误 (500, 404) ===
    trackApiFail(originalRequest?.url) // 体验埋点：接口失败
    authBus.emit('request:error', error.message || '系统繁忙')
    return Promise.reject(error)
  }
)

export default service
