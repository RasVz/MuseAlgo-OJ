<template>
  <div class="profile-fusion-container">
    <div class="user-banner">
      <div class="banner-mask"></div>
    </div>

    <div v-if="loading" class="profile-state"><el-skeleton :rows="6" animated /></div>
    <div v-else-if="error" class="profile-state" role="status">
      <el-empty :description="error" />
      <el-button v-if="!hidden" @click="loadUser">重新加载</el-button>
    </div>
    <div v-else class="profile-main">
      <el-row :gutter="25">
        <el-col :span="7" class="left-column">
          <UserInfoCard :user="displayUser" :level="userLevel" />
        </el-col>

        <el-col :span="17" class="right-column">
          <RouterView :key="$route.fullPath" />
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getUserInfo } from '@/api/user'
import UserInfoCard from '@/views/user/components/UserInfoCard.vue'

const route = useRoute()
const userLevel = ref(6)
const displayUser = ref({})
const loading = ref(true)
const hidden = ref(false)
const error = ref('')
let generation = 0

async function loadUser() {
  const current = ++generation
  loading.value = true
  error.value = ''
  hidden.value = false
  displayUser.value = {}
  try {
    const result = await getUserInfo(route.params.id, { silent: true })
    if (current !== generation) return
    if (result.code !== 200 || !result.data)
      throw new Error(result.message || '个人主页暂时无法加载')
    displayUser.value = result.data
  } catch (cause) {
    if (current !== generation) return
    hidden.value = Number(cause.code || cause.response?.data?.code) === 403
    error.value = hidden.value
      ? '该用户未公开主页，登录后可查看'
      : cause.message || '个人主页加载失败，请稍后重试'
  } finally {
    if (current === generation) loading.value = false
  }
}

watch(
  () => route.params.id,
  loadUser,
  { immediate: true }
)
</script>

<style scoped>
.profile-fusion-container {
  margin: -20px; 
  height: calc(100% + 40px);
  overflow-y: auto;
  overflow-x: hidden;
  background-color: var(--bg-app);
  position: relative; 
}

.user-banner {
  height: 240px; 
  background: linear-gradient(135deg, #1e40af 0%, #3b82f6 40%, #6366f1 100%);
  background-size: cover;
  background-position: center;
  position: relative;
  z-index: 1;
}
.banner-mask {
  width: 100%; height: 100%;
  background: linear-gradient(to bottom, rgba(0,0,0,0) 60%, rgba(0,0,0,0.6) 100%);
}

.profile-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px 40px;
  position: relative;
  top: -60px; /* 整体上浮 */
  z-index: 10;
}

.right-column {
  padding-top: 85px; /* 避开左侧上浮的影响 */
}
.profile-state {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 20px;
}
</style>
