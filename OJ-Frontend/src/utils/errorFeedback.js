import { ElMessage } from 'element-plus'

/** A shared request failure has already produced either a toast or an authentication prompt. */
export function showApiError(error, fallback) {
  if (error?.notified || error?.authNotified) return
  ElMessage.error(error?.message || fallback || '操作失败，请稍后重试')
}
