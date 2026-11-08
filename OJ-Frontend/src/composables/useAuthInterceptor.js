/**
 * useAuthInterceptor — 处理 request.js 发出的鉴权/错误事件
 * 在 App.vue 中调用，把 UI 交互（ElMessageBox、ElMessage）集中在此处，
 * 让 utils/request.js 不再直接依赖 element-plus。
 */
import { onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { authBus } from '@/utils/eventBus'

export function useAuthInterceptor() {
  const router = useRouter()
  let unsubAuthFail = null
  let unsubNetError = null
  let isReloginShow = false

  function handleAuthFail() {
    if (isReloginShow) return
    isReloginShow = true

    useUserStore().clearAuth()

    ElMessageBox.confirm(
      '您的登录状态已失效，无法继续操作。是否立即前往登录？',
      '登录提示',
      {
        confirmButtonText: '去登录',
        cancelButtonText: '留在本页',
        type: 'warning',
        distinguishCancelAndClose: true,
        closeOnClickModal: false
      }
    )
      .then(() => {
        router.push({
          path: '/login',
          query: { redirect: router.currentRoute.value.fullPath }
        })
      })
      .catch(() => {})
      .finally(() => {
        isReloginShow = false
      })
  }

  function handleNetError(message) {
    ElMessage.error(message || '网络连接异常')
  }

  onMounted(() => {
    unsubAuthFail = authBus.on('auth:fail', handleAuthFail)
    unsubNetError = authBus.on('request:error', handleNetError)
  })

  onUnmounted(() => {
    unsubAuthFail?.()
    unsubNetError?.()
  })
}
