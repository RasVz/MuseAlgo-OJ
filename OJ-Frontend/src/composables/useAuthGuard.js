/**
 * useAuthGuard — 路由鉴权守卫逻辑（从 router/index.js 中提取）
 * 封装登录确认框 UI，让路由文件只依赖此 composable 的返回方法。
 */
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/userStore'

export function useAuthGuard() {
  /**
   * 检查用户是否已登录；未登录时弹出确认框引导跳转
   * @returns {boolean} 已登录返回 true，否则 false
   */
  function requireLogin(router, redirectPath) {
    const userStore = useUserStore()
    const token = userStore.getAccessToken()

    if (token) return true

    ElMessageBox.confirm(
      '该功能需要登录后才能使用，是否立即前往登录？',
      '提示',
      {
        confirmButtonText: '去登录',
        cancelButtonText: '暂不',
        type: 'warning',
      }
    )
      .then(() => {
        router.push({
          path: '/login',
          query: { redirect: redirectPath }
        })
      })
      .catch(() => {})

    return false
  }

  return { requireLogin }
}
