<template>
  <div class="settings-container">
    <el-card shadow="never" class="settings-card">
      <el-tabs tab-position="left" class="settings-tabs" v-model="activeTab">

        <el-tab-pane name="profile">
          <template #label>
            <span class="custom-tab-label">
              <el-icon><User /></el-icon> 基本资料
            </span>
          </template>
          <ProfileTab
            :form="form"
            :loading="loading"
            :avatar-uploading="avatarUploading"
            @save="handleProfileSave"
            @random-avatar="handleRandomAvatar"
            @avatar-change="handleAvatarChange"
          />
        </el-tab-pane>

        <el-tab-pane name="security">
          <template #label>
            <span class="custom-tab-label">
              <el-icon><Lock /></el-icon> 账号安全
            </span>
          </template>
          <SecurityTab
            :pwd-form="pwdForm"
            :pwd-rules="pwdRules"
            :form="form"
            :email-saving="emailSaving"
            @change-password="handleChangePassword"
            @save-email="handleSaveEmail"
          />
        </el-tab-pane>

        <el-tab-pane name="preference">
          <template #label>
            <span class="custom-tab-label">
              <el-icon><Operation /></el-icon> 偏好设置
            </span>
          </template>
          <PreferenceTab
            :pref="pref"
            :pref-loading="prefLoading"
            @save="handlePrefSave"
            @reset="resetPref"
          />
        </el-tab-pane>

      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
// 用户设置页容器：三个 Tab（基本资料 / 账号安全 / 偏好设置）共享同一份状态，
// 因此所有状态与动作都在 useUserSettings 里，本页只做 Tab 编排与事件转发。
import { onMounted } from 'vue'
import { User, Lock, Operation } from '@element-plus/icons-vue'
import ProfileTab from './components/settings/ProfileTab.vue'
import SecurityTab from './components/settings/SecurityTab.vue'
import PreferenceTab from './components/settings/PreferenceTab.vue'
import { useUserSettings } from './composables/useUserSettings'

const {
  activeTab, loading, prefLoading, emailSaving, avatarUploading,
  form, pwdForm, pwdRules, pref,
  initProfile, handleAvatarChange, handleRandomAvatar,
  saveProfile, changePassword, saveEmail, savePref, resetPref
} = useUserSettings()

onMounted(initProfile)

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
.settings-container {
  max-width: 1000px;
  margin: 0 auto;
  /* 确保在 BasicLayout 中垂直居中或者有顶部间距 */
  padding-top: 20px;
}

.settings-card {
  min-height: 600px;
  border-radius: 12px;
  border: none;
}

/* 调整 Tabs 样式 */
.settings-tabs :deep(.el-tabs__item) {
  height: 50px;
  line-height: 50px;
  font-size: 15px;
  text-align: left;
  padding-left: 20px !important;
}
.settings-tabs :deep(.el-tabs__item.is-active) {
  background-color: #ecf5ff;
  border-right: 3px solid #409eff;
  font-weight: 600;
}
.custom-tab-label {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
