<template>
  <div class="relation-page">
    <header class="relation-header">
      <div class="header-copy">
        <span class="page-eyebrow">CONNECTIONS</span>
        <h1>关注与粉丝</h1>
        <p class="header-desc">
          正在查看 <strong class="highlight">{{ targetUserName }}</strong> 的社交关系
        </p>
      </div>
      <el-button plain icon="ArrowLeft" @click="goBack">返回主页</el-button>
    </header>

    <section class="relation-panel">
      <div class="panel-toolbar">
        <WorkbenchSegmented
          v-model="activeTab"
          :options="tabOptions"
          size="sm"
          aria-label="关注列表与粉丝列表切换"
          @change="handleTabChange"
        />
        <span class="panel-total">共 {{ total }} 人</span>
      </div>

      <div v-loading="loading" class="user-list">
        <template v-if="userList.length > 0">
          <article v-for="user in userList" :key="user.id" class="user-row">
            <div
              class="user-main"
              role="link"
              tabindex="0"
              @click="goToProfile(user.id)"
              @keydown.enter="goToProfile(user.id)"
            >
              <el-avatar :size="52" :src="user.avatar" class="user-avatar">
                {{ initialOf(user) }}
              </el-avatar>
              <div class="user-copy">
                <div class="name-row">
                  <span class="nickname">{{ displayName(user) }}</span>
                  <el-tag v-if="isCurrentUser(user)" size="small" type="info" effect="plain">我</el-tag>
                  <el-tag v-else-if="!isMe && user.isFollowed" size="small" type="success" effect="plain">已关注</el-tag>
                </div>
                <span v-if="user.username" class="username">@{{ user.username }}</span>
                <p class="signature">{{ signatureOf(user) }}</p>
              </div>
            </div>

            <div class="user-action">
              <el-button
                v-if="canFollow(user)"
                size="small"
                :type="user.isFollowed ? 'info' : 'primary'"
                :plain="user.isFollowed"
                @click.stop="handleToggleFollow(user)"
                @mouseenter="hoverId = user.id"
                @mouseleave="hoverId = null"
              >
                <span v-if="user.isFollowed">{{ hoverId === user.id ? '取消关注' : '已关注' }}</span>
                <span v-else>+ 关注</span>
              </el-button>
              <span v-else class="row-arrow" aria-hidden="true">›</span>
            </div>
          </article>
        </template>
        <el-empty v-else-if="!loading" :description="emptyText" :image-size="72" />
      </div>

      <footer v-if="total > pageSize" class="pagination-box">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="handlePageChange"
        />
      </footer>
    </section>
  </div>
</template>

<script setup>
// 关注/粉丝关系页：展示某个用户的关注列表与粉丝列表。
// 只有查看自己的列表时才显示关注按钮——在别人的列表里只展示关系与主页入口。
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFollowingList, getFollowerList, toggleFollow, getUserInfo } from '@/api/user'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import WorkbenchSegmented from '@/components/WorkbenchSegmented.vue'

const route = useRoute()
const router = useRouter()

const initialType = route.query.type === 'followers' ? 'followers' : 'following'
const targetUserId = ref(route.params.id)
const targetUser = ref({})
const targetUserName = ref('用户')
const activeTab = ref(initialType)

const loading = ref(false)
const userList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 20
const hoverId = ref(null) // 用于处理按钮悬停文字变化

// 获取当前登录人ID
const currentUserId = computed(() => {
  return useUserStore().userId
})

// 只有看自己的列表时才显示关注操作按钮。
// ID 比较统一转 String：route.params.id 是字符串，store 里的 userId 可能是数字。
const isMe = computed(() => {
  return currentUserId.value && targetUserId.value && String(currentUserId.value) === String(targetUserId.value)
})

const formatCount = (value) => {
  const count = Number(value)
  if (!Number.isFinite(count) || count < 0) return ''
  if (count >= 1000) return `${(count / 1000).toFixed(1)}k`
  return String(count)
}

const tabOptions = computed(() => {
  const following = formatCount(targetUser.value.followingCount)
  const followers = formatCount(targetUser.value.followerCount)
  return [
    { value: 'following', label: following ? `关注 ${following}` : '关注' },
    { value: 'followers', label: followers ? `粉丝 ${followers}` : '粉丝' }
  ]
})

const emptyText = computed(() => {
  const who = isMe.value ? '你' : targetUserName.value
  return activeTab.value === 'following' ? `${who}还没有关注任何人` : `${who}还没有粉丝`
})

const isCurrentUser = (user) => String(user.id) === String(currentUserId.value)

const canFollow = (user) => isMe.value && !isCurrentUser(user)

const displayName = (user) => user.nickname || user.username || '未命名用户'

const initialOf = (user) => (user.nickname || user.username || 'U').charAt(0).toUpperCase()

// 关系列表接口用 signature 承载个性签名；motto 是旧字段名，保留兜底。
const signatureOf = (user) => user.signature || user.motto || '这个人很懒，什么都没写'

// 初始化：获取目标用户信息（名称与关注/粉丝计数）
const initTargetUser = async () => {
  const targetId = targetUserId.value
  try {
    const res = await getUserInfo(targetId)
    if (targetId !== targetUserId.value) return
    if (res.code === 200 && res.data) {
      targetUser.value = res.data
      targetUserName.value = res.data.nickname || res.data.username || '用户'
    }
  } catch { /* 忽略 */ }
}

let loadGeneration = 0
const fetchData = async () => {
  const generation = ++loadGeneration
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize }
    let res
    if (activeTab.value === 'following') {
      res = await getFollowingList(targetUserId.value, params)
    } else {
      res = await getFollowerList(targetUserId.value, params)
    }

    if (generation !== loadGeneration) return
    if (res.code === 200) {
      userList.value = res.data.records
      total.value = res.data.total
    }
  } catch {
    // Shared request handling presents the concrete failure.
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

// 切 Tab 用 replace 写回 query：刷新与前进后退能回到同一栏，
// 但不该在历史里堆一堆记录。
const handleTabChange = (name) => {
  currentPage.value = 1
  total.value = 0
  router.replace({ query: { ...route.query, type: name } })
  fetchData()
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

const handleToggleFollow = async (user) => {
  if (!currentUserId.value) {
    ElMessage.warning('请先登录')
    return
  }
  try {
    const res = await toggleFollow({ followedId: user.id })

    if (res.code === 200) {
      // 只翻转本地状态，不重拉列表：
      // 在「我的关注」里取关后条目仍留在原位（只是变成未关注），
      // 这样误点后还能在原地找回这个人。
      user.isFollowed = !user.isFollowed

      // 粉丝/关注计数不在此处更新：列表接口不返回这两个总数，
      // 等下次刷新自然对齐，避免在本地猜一个可能错误的值。
      if (user.isFollowed) {
        ElMessage.success('关注成功')
      } else {
        ElMessage.info('已取消关注')
      }
    }
  } catch (e) {
    console.error(e)
  }
}

const goToProfile = (id) => {
  router.push({ name: 'UserProfile', params: { id } })
}

const goBack = () => {
  router.push({ name: 'UserProfile', params: { id: targetUserId.value } })
}

onMounted(() => {
  initTargetUser()
  fetchData()
})

// 监听路由参数变化
watch(() => route.params.id, (newId) => {
  if (newId) {
    targetUserId.value = newId
    targetUser.value = {}
    currentPage.value = 1
    initTargetUser()
    fetchData()
  }
})

// 浏览器前进/后退改到另一栏时，让分段控件与列表一起回到对应状态。
watch(() => route.query.type, (type) => {
  const next = type === 'followers' ? 'followers' : 'following'
  if (next === activeTab.value) return
  activeTab.value = next
  currentPage.value = 1
  total.value = 0
  fetchData()
})
</script>

<style scoped>
.relation-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
  padding-bottom: 8px;
  font-family: var(--font-family);
}

.relation-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 2px 2px 0;
}
.header-copy { min-width: 0; }
.page-eyebrow {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.8px;
  color: var(--el-color-primary);
}
.relation-header h1 {
  margin: 6px 0 8px;
  font-size: 26px;
  line-height: 1.3;
  font-weight: 650;
  color: var(--text-primary);
}
.header-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}
.highlight { color: var(--text-primary); font-weight: 600; }

.relation-panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-light);
}
.panel-total {
  font-size: 12px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.user-list { min-height: 300px; }

.user-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--border-light);
  transition: background-color .18s;
}
.user-row:last-child { border-bottom: none; }
.user-row:hover { background: var(--bg-surface-hover); }

.user-main {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.user-main:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: 4px;
  border-radius: var(--radius-sm);
}
.user-avatar {
  flex-shrink: 0;
  border: 1px solid var(--border-light);
  background: var(--bg-app);
}
.user-copy {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.nickname {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.username {
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.signature {
  margin: 2px 0 0;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  overflow: hidden;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
  overflow-wrap: anywhere;
}

.user-action {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  min-width: 104px;
}
.user-action .el-button { width: 96px; }
.row-arrow {
  padding-right: 26px;
  font-size: 20px;
  line-height: 1;
  color: var(--text-placeholder);
}

.pagination-box {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 14px;
  border-top: 1px solid var(--border-light);
}
.pagination-box :deep(.el-pagination.is-background .btn-prev),
.pagination-box :deep(.el-pagination.is-background .btn-next),
.pagination-box :deep(.el-pagination.is-background .el-pager li) {
  border-radius: var(--radius-control);
}

/* 鼠标悬停在“已关注”按钮上时，改变样式提示用户这是取消操作 */
.el-button--info.is-plain:hover {
  color: var(--accent-danger);
  background: var(--accent-danger-light);
  border-color: var(--el-color-danger-light-5);
}

@media (max-width: 680px) {
  .relation-header {
    flex-direction: column;
    gap: 12px;
  }
  .relation-header h1 { font-size: 22px; }
  .panel-toolbar {
    flex-wrap: wrap;
    padding: 12px 14px;
  }
  .user-row { padding: 14px; }
  .user-action { min-width: 84px; }
  .user-action .el-button { width: 84px; }
  .row-arrow { padding-right: 8px; }
}
</style>
