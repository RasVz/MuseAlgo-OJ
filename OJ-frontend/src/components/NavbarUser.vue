<template>
  <div class="user-control">

    <div v-if="!userStore.isLoggedIn" class="guest-actions">
      <el-button text
        @click="$router.push({ name: 'Login' })">登录
      </el-button>
      <el-button type="primary" round
        @click="$router.push({ name: 'Register' })">注册
      </el-button>
    </div>

    <!-- 班级管理页：创建新班级按钮（紧贴用户头像左侧） -->
    <el-button
      v-if="userStore.isLoggedIn && userStore.isTeacher && isAdminClassPage"
      type="primary"
      round
      size="small"
      style="margin-right: 10px"
      @click="handleCreateClass"
    >
      <el-icon style="margin-right: 4px"><Plus /></el-icon>
      创建新班级
    </el-button>

    <el-badge v-if="userStore.isLoggedIn" :value="notificationBadge" :hidden="notificationBadge === 0" style="margin-right: 12px;">
      <el-button circle @click="$router.push({ name: 'NotificationCenter' })" title="通知中心">
        <el-icon><Bell /></el-icon>
      </el-button>
    </el-badge>

    <el-dropdown
      v-if="userStore.isLoggedIn"
      trigger="click"
      placement="bottom-end"
      @command="handleUserCommand"
    >
      <div class="user-dropdown-link">
        <el-avatar :size="32" :src="userStore.avatar || defaultAvatar" />
        <span class="username">{{ userStore.nickname }}</span>
        <el-icon class="el-icon--right"><CaretBottom /></el-icon>
      </div>

      <template #dropdown>
        <el-dropdown-menu class="user-dropdown">
          <el-dropdown-item command="UserProfile" icon="User">个人中心</el-dropdown-item>
          <el-dropdown-item command="UserSetting" icon="Setting">用户设置</el-dropdown-item>
          <el-dropdown-item command="MyProblemList" icon="List">我的题单</el-dropdown-item>
          <el-dropdown-item command="MyPost" icon="Notebook">我的动态</el-dropdown-item>
          <el-dropdown-item command="MyFavorite" icon="Star">收藏夹</el-dropdown-item>
          <el-dropdown-item command="ReviewBook" icon="Reading">错题本</el-dropdown-item>
          <el-dropdown-item command="NotificationCenter" icon="Bell">通知中心</el-dropdown-item>

          <el-dropdown-item v-if="userStore.isTeacher" command="AdminHome" divided icon="Odometer">管理中心</el-dropdown-item>

          <el-dropdown-item divided command="logout" icon="SwitchButton">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Bell, Plus, CaretBottom } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/userStore'
import { onMounted, ref, computed } from 'vue'
import { getUpcomingContestCount } from '@/api/contest'
import { getUnreadNotificationCount } from '@/api/notification'
import { logoutApi } from '@/api/auth'
import { authBus } from '@/utils/eventBus'
import { getDefaultAvatar } from '@/utils/dicebear'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const upcomingContests = ref(0)
const unreadNotifications = ref(0)
const notificationBadge = computed(() => Math.min(99, unreadNotifications.value + upcomingContests.value))

const isAdminClassPage = computed(() => route.name === 'AdminClass')

const defaultAvatar = getDefaultAvatar()

const handleCreateClass = () => {
  authBus.emit('class:open-create-dialog')
}

onMounted(async () => {
  if (!userStore.isLoggedIn) return
  try {
    const res = await getUpcomingContestCount()
    if (res.code === 200) upcomingContests.value = res.data || 0
  } catch { /* ignore */ }
  try {
    const res = await getUnreadNotificationCount()
    if (res.code === 200) unreadNotifications.value = res.data || 0
  } catch { /* ignore */ }
})

const handleUserCommand = (cmd) => {
  switch (cmd) {
    case 'UserProfile':
      router.push({ name: 'UserProfile', params: { id: userStore.userId } })
      break
    case 'logout':
      ElMessageBox.confirm(
        '确定要退出当前账号吗？',
        '提示',
        {
          confirmButtonText: '确定退出',
          cancelButtonText: '取消',
          type: 'warning'
        }
      ).then(async () => {
        // 服务端注销（拉黑 access + refresh）：尽力而为，失败也照常本地退出
        try {
          await logoutApi(userStore.getRefreshToken())
        } catch { /* 忽略：服务端注销失败不影响本地退出 */ }
        userStore.clearAuth()
        ElMessage.success('已安全退出')
        router.push('/')

      }).catch(() => {
        // 用户点了取消，什么都不做
      })
      break
    case 'AdminHome':
      // 管理中心相当于独立站点：新开标签页
      window.open(router.resolve({ name: 'AdminHome' }).href, '_blank')
      break
    default:
      router.push({ name: cmd })
  }
}
</script>

<style scoped>
.user-control { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.user-dropdown-link { display: flex; align-items: center; gap: 8px; cursor: pointer; outline: none; }
.username { font-weight: 500; color: var(--text-regular); max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:global(.user-dropdown) { min-width: 160px; }
</style>
