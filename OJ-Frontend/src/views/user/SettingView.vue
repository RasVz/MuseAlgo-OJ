<template>
  <div class="settings-page">
    <header class="settings-hero">
      <div class="settings-hero-shade"></div>
      <div class="settings-hero-inner">
        <div class="settings-hero-identity">
          <el-avatar :size="88" :src="heroAvatar" class="settings-hero-avatar" />
          <div class="settings-hero-text">
            <p class="settings-hero-name">{{ displayName }}</p>
            <p class="settings-hero-handle">@{{ form.username || '—' }}</p>
          </div>
        </div>
        <el-button class="settings-hero-link" @click="goToProfile">查看我的主页</el-button>
      </div>
    </header>

    <div class="settings-body">
      <h1 class="settings-page-title">用户设置</h1>
      <p class="settings-page-subtitle">管理个人资料、账号安全与编辑器偏好。</p>

      <el-tabs
        v-model="activeTab"
        :tab-position="isNarrow ? 'top' : 'left'"
        class="settings-tabs"
      >
        <el-tab-pane name="profile">
          <template #label>
            <span class="settings-tab-label"><el-icon><User /></el-icon> 基本资料</span>
          </template>
          <div class="settings-panel">
            <ProfileTab
              :form="form"
              :loading="loading"
              :avatar-uploading="avatarUploading"
              @save="handleProfileSave"
              @random-avatar="handleRandomAvatar"
              @avatar-change="handleAvatarChange"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane name="security">
          <template #label>
            <span class="settings-tab-label"><el-icon><Lock /></el-icon> 账号安全</span>
          </template>
          <div class="settings-panel">
            <SecurityTab
              :pwd-form="pwdForm"
              :pwd-rules="pwdRules"
              :form="form"
              :email-saving="emailSaving"
              @change-password="handleChangePassword"
              @save-email="handleSaveEmail"
            />
          </div>
        </el-tab-pane>

        <el-tab-pane name="preference">
          <template #label>
            <span class="settings-tab-label"><el-icon><Operation /></el-icon> 偏好设置</span>
          </template>
          <div class="settings-panel">
            <PreferenceTab
              :pref="pref"
              :pref-loading="prefLoading"
              @save="handlePrefSave"
              @reset="resetPref"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
// 用户设置页容器：封面身份条 + 左侧导航 + 行式分区。
// 三个 Tab 共享 useUserSettings 的状态与动作，本页只做编排、事件转发与深链。
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock, Operation } from '@element-plus/icons-vue'
import ProfileTab from './components/settings/ProfileTab.vue'
import SecurityTab from './components/settings/SecurityTab.vue'
import PreferenceTab from './components/settings/PreferenceTab.vue'
import { useUserSettings } from './composables/useUserSettings'
import { useUserStore } from '@/stores/userStore'
import { getDefaultAvatar } from '@/utils/dicebear'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const {
  activeTab, loading, prefLoading, emailSaving, avatarUploading,
  form, pwdForm, pwdRules, pref,
  initProfile, handleAvatarChange, handleRandomAvatar,
  saveProfile, changePassword, saveEmail, savePref, resetPref
} = useUserSettings()

// --- 深链：当前分区写入 query，刷新或分享链接可回到同一分区 ---
// 用 replace 而不是 push，避免连点导航把浏览器历史塞满。
const VALID_TABS = ['profile', 'security', 'preference']
const initialTab = VALID_TABS.includes(route.query.tab) ? route.query.tab : 'profile'
activeTab.value = initialTab

watch(activeTab, (tab) => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab } })
  }
})
watch(() => route.query.tab, (tab) => {
  if (VALID_TABS.includes(tab) && tab !== activeTab.value) {
    activeTab.value = tab
  }
})

// --- 窄屏：导航由左侧转顶部，表单标签由左对齐转顶部 ---
// Element Plus 的 tab-position 是属性而非样式，必须按视口切换；
// 两侧的 role="tab" 语义保持不变，既有浏览器断言无需改动。
const narrowMedia = window.matchMedia('(max-width: 680px)')
const isNarrow = ref(narrowMedia.matches)
const onNarrowChange = (event) => {
  isNarrow.value = event.matches
}
provide('settingsNarrow', isNarrow)

onMounted(() => {
  narrowMedia.addEventListener('change', onNarrowChange)
  initProfile()
})
onUnmounted(() => narrowMedia.removeEventListener('change', onNarrowChange))

const defaultAvatar = getDefaultAvatar()
const heroAvatar = computed(() => form.avatar || defaultAvatar)
const displayName = computed(() => form.nickname || form.username || '未命名用户')

function goToProfile() {
  const id = form.id || userStore.userId
  if (!id) return
  router.push({ name: 'UserProfile', params: { id } })
}

// Tab 子组件本地编辑后通过事件回传载荷，先合并回 composable 状态再落库。
// 合并这一步不能省：saveProfile 等动作是从共享状态读的，
// 直接把 payload 透传会让其他依赖 form 的逻辑（如头像流程）看到旧值。
function handleProfileSave(payload) {
  Object.assign(form, payload)
  saveProfile()
}

function handleChangePassword(payload) {
  Object.assign(pwdForm, payload)
  changePassword()
}

function handleSaveEmail(email) {
  form.email = email
  saveEmail()
}

function handlePrefSave(payload) {
  Object.assign(pref, payload)
  savePref()
}
</script>

<style scoped>
.settings-page {
  margin: -20px;
  padding-bottom: 40px;
  min-width: 0;
  font-family: var(--font-family);
}
.settings-hero {
  height: 230px;
  position: relative;
  overflow: hidden;
  background: linear-gradient(115deg, #193c77 0%, #5476bd 65%, #a3b3d3 100%);
}
.settings-hero-shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(0deg, rgba(10, 26, 56, 0.38), transparent 80%);
  pointer-events: none;
}
.settings-hero-inner {
  position: relative;
  height: 100%;
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 28px 24px;
  box-sizing: border-box;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}
.settings-hero-identity {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  min-width: 0;
}
.settings-hero-avatar {
  flex: none;
  border: 4px solid var(--bg-surface);
  background: var(--bg-app);
}
.settings-hero-text {
  min-width: 0;
  padding-bottom: 6px;
}
.settings-hero-name {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 1px 12px rgba(0, 0, 0, 0.25);
  overflow-wrap: anywhere;
}
.settings-hero-handle {
  margin: 4px 0 0;
  font-size: 13px;
  color: #ffffff;
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.35);
  overflow-wrap: anywhere;
}
.settings-hero-link {
  flex: none;
  min-height: 44px;
}
.settings-body {
  max-width: 1240px;
  margin: 0 auto;
  padding: 24px 28px 0;
  box-sizing: border-box;
}
.settings-page-title {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-page-subtitle {
  margin: 0 0 20px;
  font-size: 14px;
  color: var(--text-regular);
}
.settings-tabs {
  align-items: flex-start;
}
.settings-tabs :deep(.el-tabs__header.is-left) {
  flex: 0 0 232px;
  width: 232px;
  margin: 0 24px 0 0;
  padding-right: 12px;
  border-right: 1px solid var(--border-light);
}
.settings-tabs :deep(.el-tabs__nav-wrap.is-left) {
  margin-right: 0;
}
.settings-tabs :deep(.el-tabs__item) {
  height: 48px;
  line-height: 48px;
  font-size: 15px;
  text-align: left;
  padding: 0 16px !important;
  border-radius: var(--radius-sm);
}
.settings-tabs :deep(.el-tabs__item.is-active) {
  background: color-mix(in srgb, var(--accent-primary) 12%, transparent);
  color: var(--text-primary);
  font-weight: 600;
}
.settings-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--accent-primary);
}
.settings-tabs :deep(.el-tabs__content) {
  flex: 1 1 auto;
  min-width: 0;
}
.settings-tab-label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.settings-panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  padding: 20px 24px 24px;
  box-shadow: var(--shadow-sm);
}
:global(.settings-panel .el-button) {
  min-height: 44px;
}
:global(.settings-panel .el-input__wrapper) {
  min-height: 40px;
}
@media (max-width: 900px) {
  .settings-tabs :deep(.el-tabs__header.is-left) {
    flex-basis: 196px;
    width: 196px;
    margin-right: 16px;
  }
  .settings-body {
    padding: 20px 20px 0;
  }
  .settings-hero-inner {
    padding: 0 20px 20px;
  }
}
@media (max-width: 680px) {
  .settings-hero {
    height: 180px;
  }
  .settings-hero-inner {
    padding: 0 14px 16px;
    flex-wrap: wrap;
  }
  .settings-hero-avatar {
    width: 64px !important;
    height: 64px !important;
  }
  .settings-hero-name {
    font-size: 18px;
  }
  .settings-hero-link {
    margin-left: auto;
  }
  .settings-body {
    padding: 16px 14px 0;
  }
  .settings-tabs {
    display: block;
  }
  .settings-tabs :deep(.el-tabs__header.is-top) {
    width: 100%;
    box-sizing: border-box;
    margin: 0 0 12px;
    padding-right: 0;
    border-right: none;
  }
  .settings-tabs :deep(.el-tabs__nav.is-top) {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    width: 100%;
  }
  .settings-tabs :deep(.el-tabs__active-bar) {
    display: none;
  }
  .settings-tabs :deep(.el-tabs__item) {
    padding: 0 6px !important;
    font-size: 13px;
    justify-content: center;
  }
  .settings-panel {
    padding: 16px 16px 20px;
  }
}
</style>
