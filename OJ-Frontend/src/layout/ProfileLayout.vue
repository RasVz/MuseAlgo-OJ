<template>
  <div class="profile-page">
    <div class="user-banner">
      <img
        v-if="cover && !coverFailed"
        :src="cover"
        alt="个人主页封面"
        :style="{ objectPosition: `50% ${displayUser.coverPosition ?? 50}%` }"
        @error="coverFailed = true"
      />
      <div class="banner-shade"></div>
      <span class="banner-caption"
        >{{ displayUser.nickname || displayUser.username || '个人主页'
        }}<small>记录学习，也表达自己</small></span
      >
      <ProfileCoverEditor
        v-if="isSelf && !loading && !error"
        :user="displayUser"
        @updated="applyPatch"
      />
    </div>
    <div v-if="loading" class="profile-loading"><el-skeleton :rows="6" animated /></div>
    <div v-else-if="error" class="profile-error" role="status">
      <el-empty :description="error" /><el-button v-if="!hidden" @click="loadUser"
        >重新加载</el-button
      ><el-button v-else type="primary" @click="$router.push('/login')">登录后查看</el-button>
    </div>
    <div v-else class="profile-main">
      <aside class="profile-sidebar"><UserInfoCard :user="displayUser" /></aside>
      <main class="profile-content"><RouterView :key="$route.fullPath" /></main>
    </div>
  </div>
</template>
<script setup>
import { ref, watch, computed, provide } from 'vue'
import { useRoute } from 'vue-router'
import { getUserInfo } from '@/api/user'
import { useUserStore } from '@/stores/userStore'
import { safeProfileImageUrl } from '@/utils/profileMarkdown'
import UserInfoCard from '@/views/user/components/UserInfoCard.vue'
import ProfileCoverEditor from '@/views/user/components/ProfileCoverEditor.vue'
const route = useRoute(),
  store = useUserStore()
const displayUser = ref({}),
  loading = ref(true),
  error = ref(''),
  hidden = ref(false),
  coverFailed = ref(false)
const cover = computed(() => safeProfileImageUrl(displayUser.value.coverUrl))
const isSelf = computed(
  () => !!store.userId && String(store.userId) === String(displayUser.value.id),
)
let generation = 0
function applyPatch(patch) {
  displayUser.value = { ...displayUser.value, ...patch }
  coverFailed.value = false
}
async function loadUser() {
  const current = ++generation
  loading.value = true
  error.value = ''
  hidden.value = false
  displayUser.value = {}
  coverFailed.value = false
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
provide('profileContext', { user: displayUser, isSelf, applyPatch })
watch(() => route.params.id, loadUser, { immediate: true })
</script>
<style scoped>
.profile-page {
  font-family: var(--font-family);
  min-width: 0;
  margin: -20px;
  padding-bottom: 40px;
}
.user-banner {
  height: 230px;
  position: relative;
  overflow: hidden;
  background: linear-gradient(115deg, #193c77 0%, #5476bd 65%, #a3b3d3 100%);
}
.user-banner > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.banner-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(0deg, rgba(10, 26, 56, 0.35), transparent 80%);
  pointer-events: none;
}
.banner-caption {
  position: absolute;
  left: max(32px, calc((100% - 1192px) / 2 + 292px));
  bottom: 30px;
  color: white;
  font-size: 26px;
  font-weight: 600;
  text-shadow: 0 1px 12px rgba(0, 0, 0, 0.2);
}
.banner-caption small {
  display: block;
  font-size: 12px;
  font-weight: 400;
  margin-top: 6px;
  opacity: 0.85;
  letter-spacing: 1px;
}
.profile-main {
  display: grid;
  grid-template-columns: 252px minmax(0, 1fr);
  gap: 24px;
  max-width: 1240px;
  padding: 0 28px;
  margin: 24px auto 0;
  box-sizing: border-box;
  align-items: start;
}
.profile-sidebar {
  min-width: 0;
  margin-top: -60px;
  position: relative;
}
.profile-content {
  min-width: 0;
}
.profile-loading,
.profile-error {
  max-width: 1100px;
  padding: 40px 24px;
  margin: auto;
}
.profile-error {
  text-align: center;
}
@media (min-width: 1600px) {
  .profile-main {
    max-width: 1380px;
  }
}
@media (max-width: 900px) {
  .profile-main {
    grid-template-columns: 212px minmax(0, 1fr);
    gap: 18px;
    padding: 0 20px;
  }
  .banner-caption {
    left: 256px;
  }
}
@media (max-width: 680px) {
  .user-banner {
    height: 180px;
  }
  .banner-caption {
    display: none;
  }
  .profile-main {
    grid-template-columns: minmax(0, 1fr);
    margin-top: 0;
    padding: 0 14px;
    gap: 16px;
  }
  .profile-sidebar {
    margin-top: -36px;
  }
  .profile-page {
    font-family: var(--font-family);
    padding-bottom: 24px;
  }
}
:global(.profile-edit-dialog) {
  max-width: calc(100vw - 32px);
  border-radius: 16px;
}
</style>
