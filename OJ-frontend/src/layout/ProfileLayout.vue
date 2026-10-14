<template>
  <div class="profile-fusion-container">
    <div class="user-banner">
      <div class="banner-mask"></div>
    </div>

    <div class="profile-main">
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

const router = useRoute()
const userLevel = ref(6)
const displayUser = ref({})

// 初始化数据：获取用户信息 (这个信息是公共的，左右两边可能都要用)
const initData = async () => {
  const targetId = router.params.id
  if (targetId) {
    try {
      const res = await getUserInfo(targetId)
      if (res.code === 200) {
        displayUser.value = res.data
      }
    } catch (error) {
      console.error(error)
    } 
  } 
}

watch(
  () => router.params.id,
  () => { initData() },
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
</style>