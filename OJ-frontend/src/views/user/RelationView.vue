<template>
  <div class="relation-container">
    <div class="relation-header">
      <el-button link icon="ArrowLeft" @click="$router.push({ name: 'UserProfile', params: { id: targetUserId } })">
        返回主页
      </el-button>
      <div class="user-hint">
        正在查看 <span class="highlight">{{ targetUserName }}</span> 的列表
      </div>
    </div>

    <el-card shadow="never" class="list-card">
      <el-tabs v-model="activeTab" class="relation-tabs" @tab-change="handleTabChange">
        <el-tab-pane label="关注列表" name="following" />
        <el-tab-pane label="粉丝列表" name="followers" />
      </el-tabs>

      <div v-loading="loading" class="user-list">
        <div v-if="userList.length > 0">
          <div v-for="user in userList" :key="user.id" class="user-item">
            <div class="user-info" @click="goToProfile(user.id)">
              <el-avatar :size="50" :src="user.avatar">{{ (user.username || 'U').charAt(0) }}</el-avatar>
              <div class="text-info">
                <div class="name-row">
                  <span class="nickname">{{ user.nickname || user.username }}</span>
                  <el-tag v-if="String(user.id) === String(currentUserId)" size="small" type="info">我</el-tag>
                </div>
                <div class="motto">{{ user.motto || '这个人很懒，什么都没写' }}</div>
              </div>
            </div>

            <div class="action-btn" v-if="isMe && String(user.id) !== String(currentUserId)">
              <el-button 
                :type="user.isFollowed ? 'info' : 'primary'" 
                :plain="user.isFollowed"
                round
                @click="handleToggleFollow(user)"
                @mouseenter="hoverId = user.id"
                @mouseleave="hoverId = null"
              >
                <span v-if="user.isFollowed">{{ hoverId === user.id ? '取消关注' : '已关注' }}</span>
                <span v-else>+ 关注</span>
              </el-button>
            </div>
          </div>
        </div>
        <el-empty v-else :description="activeTab === 'following' ? 'Ta还没有关注任何人' : 'Ta还没有粉丝'" />
      </div>

      <div class="pagination-box" v-if="total > 0">
        <el-pagination 
          background 
          layout="prev, pager, next" 
          :total="total" 
          :page-size="pageSize"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 关注/粉丝关系页：展示某个用户的关注列表与粉丝列表。
// 只有查看自己的列表时才显示关注按钮——在别人的关注列表里点关注是允许的，
// 但在「Ta 的粉丝」里逐个关注意义不大，所以按钮由 isMe 控制。
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFollowingList, getFollowerList, toggleFollow, getUserInfo } from '@/api/user'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'

const route = useRoute()
const router = useRouter()

const targetUserId = ref(route.params.id)
const targetUserName = ref('用户') 
const activeTab = ref(route.query.type || 'following') 

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

// 初始化：获取目标用户信息
const initTargetUser = async () => {
  try {
    const res = await getUserInfo(targetUserId.value)
    if (res.code === 200) {
      targetUserName.value = res.data.nickname || res.data.username
    }
  } catch { /* 忽略 */ }
}

const fetchData = async () => {
  loading.value = true
  try {
    const params = { page: currentPage.value, size: pageSize }
    let res
    if (activeTab.value === 'following') {
      res = await getFollowingList(targetUserId.value, params)
    } else {
      res = await getFollowerList(targetUserId.value, params)
    }

    if (res.code === 200) {
      userList.value = res.data.records
      total.value = res.data.total
    }
  } finally {
    loading.value = false
  }
}

// 切 Tab 用 replace 写回 query：刷新与前进后退能回到同一栏，
// 但不该在历史里堆一堆记录。
const handleTabChange = (name) => {
  activeTab.value = name
  currentPage.value = 1
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

onMounted(() => {
  initTargetUser()
  fetchData()
})

// 监听路由参数变化
watch(() => route.params.id, (newId) => {
  if (newId) {
    targetUserId.value = newId
    initTargetUser()
    fetchData()
  }
})
</script>

<style scoped>
.relation-container { max-width: 900px; margin: 20px auto; padding: 0 10px; }
.relation-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 15px; }
.user-hint { font-size: 14px; color: var(--text-regular); }
.highlight { color: var(--accent-primary); font-weight: 600; }

.list-card { min-height: 500px; }
.relation-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background-color: var(--el-fill-color-light); }

.user-item { display: flex; justify-content: space-between; align-items: center; padding: 15px 0; border-bottom: 1px solid var(--el-fill-color-light); }
.user-item:last-child { border-bottom: none; }

.user-info { display: flex; align-items: center; gap: 15px; cursor: pointer; flex: 1; }
.text-info { display: flex; flex-direction: column; gap: 4px; }
.name-row { display: flex; align-items: center; gap: 8px; }
.nickname { font-weight: 600; font-size: 15px; color: var(--text-primary); }
.motto { font-size: 12px; color: var(--text-secondary); max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.action-btn .el-button { width: 90px; } /* 固定按钮宽度防止文字变化导致抖动 */
.pagination-box { margin-top: 20px; display: flex; justify-content: center; }

/* 鼠标悬停在“已关注”按钮上时，改变样式提示用户这是取消操作 */
.el-button--info.is-plain:hover {
  color: var(--accent-danger);
  background: var(--accent-danger-light);
  border-color: var(--el-color-danger-light-5);
}
</style>