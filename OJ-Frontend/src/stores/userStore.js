/**
 * userStore — 全局用户状态 (Pinia)
 * <p>
 * 所有 localStorage 读写集中在此处，Token 为响应式 ref。
 * localStorage 是刷新页面后唯一的登录态来源，因此每次读都同时兜底读一次
 * localStorage——避免同一浏览器多标签页登录时，本标签页内存态落后导致误判未登录。
 * </p>
 * <p>
 * 角色语义：role &gt;= 2 视为教师（教师 2 / 管理员 3），与后端 {@code SecurityUtils}
 * 的判定口径保持一致。
 * </p>
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  // --- 响应式 Token ---
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const userInfo = ref(loadUser())

  // 存量数据可能因手工改localStorage 而非法 JSON，解析失败按未登录处理而非崩溃
  function loadUser() {
    try {
      const raw = localStorage.getItem('user') || '{}'
      return JSON.parse(raw)
    } catch {
      return {}
    }
  }

  // --- 计算属性 ---
  const isLoggedIn = computed(() => !!accessToken.value)
  const userId = computed(() => userInfo.value?.id)
  const nickname = computed(() => userInfo.value?.nickname || '用户')
  const avatar = computed(() => userInfo.value?.avatar || '')
  const role = computed(() => userInfo.value?.role || 0)
  const isTeacher = computed(() => role.value >= 2)

  // --- 写操作（统一管理 localStorage + ref）---
  // 读操作双兜底：先读响应式值，为空再读 localStorage（应对多标签页登录）
  function getAccessToken() {
    return accessToken.value || localStorage.getItem('accessToken') || ''
  }

  function getRefreshToken() {
    return refreshToken.value || localStorage.getItem('refreshToken') || ''
  }

  function setAccessToken(token) {
    accessToken.value = token
    localStorage.setItem('accessToken', token)
  }

  function setAuth({ accessToken: at, refreshToken: rt, user }) {
    accessToken.value = at
    refreshToken.value = rt
    userInfo.value = user
    localStorage.setItem('accessToken', at)
    localStorage.setItem('refreshToken', rt)
    localStorage.setItem('user', JSON.stringify(user))
  }

  function clearAuth() {
    accessToken.value = ''
    refreshToken.value = ''
    userInfo.value = {}
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('user')
  }

  function refreshUser() {
    userInfo.value = loadUser()
  }

  return {
    accessToken,
    refreshToken,
    userInfo,
    isLoggedIn,
    userId,
    nickname,
    avatar,
    role,
    isTeacher,
    getAccessToken,
    getRefreshToken,
    setAccessToken,
    setAuth,
    clearAuth,
    refreshUser
  }
})
