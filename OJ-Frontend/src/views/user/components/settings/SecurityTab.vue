<template>
  <div class="settings-tab">
    <h2 class="settings-tab-title">账号安全</h2>
    <p class="settings-tab-note">密码修改成功后会退出登录，需要用新密码重新登录。</p>

    <SettingsSection
      title="修改密码"
      description="建议定期更换密码；修改成功后当前会话立即失效。"
      tone="danger"
    >
      <el-form
        ref="pwdFormRef"
        :model="localPwdForm"
        :rules="pwdRules"
        :label-position="labelPosition"
        :label-width="labelWidth"
        class="settings-form"
      >
        <SettingsRow title="当前密码" prop="oldPassword">
          <el-input
            v-model="localPwdForm.oldPassword"
            type="password"
            show-password
            class="settings-input"
          />
        </SettingsRow>
        <SettingsRow title="新密码" description="至少 6 位" prop="newPassword">
          <template #default="{ describedBy }">
            <el-input
              v-model="localPwdForm.newPassword"
              type="password"
              show-password
              :aria-describedby="describedBy"
              class="settings-input"
            />
          </template>
        </SettingsRow>
        <SettingsRow title="确认新密码" description="需与新密码一致" prop="confirmPassword">
          <template #default="{ describedBy }">
            <el-input
              v-model="localPwdForm.confirmPassword"
              type="password"
              show-password
              :aria-describedby="describedBy"
              class="settings-input"
            />
          </template>
        </SettingsRow>
      </el-form>
      <div class="section-actions">
        <el-button type="danger" plain @click="handleChangePassword">确认修改</el-button>
        <el-button text @click="clearPasswordForm">清空</el-button>
      </div>
    </SettingsSection>

    <SettingsSection
      title="邮箱绑定"
      description="当前为预览功能：仅保存邮箱，验证邮件功能开发中。"
    >
      <el-form :label-position="labelPosition" :label-width="labelWidth" class="settings-form">
        <SettingsRow title="电子邮箱" description="用于接收账号相关通知">
          <template #default="{ describedBy }">
            <el-input
              v-model="localEmail"
              :aria-describedby="describedBy"
              placeholder="请输入邮箱"
              class="settings-input"
            />
          </template>
        </SettingsRow>
      </el-form>
      <div class="section-actions">
        <el-button type="primary" plain :loading="emailSaving" @click="handleSaveEmail">
          保存邮箱
        </el-button>
      </div>
    </SettingsSection>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import SettingsRow from './SettingsRow.vue'
import SettingsSection from './SettingsSection.vue'

/**
 * 账号安全 Tab（展示组件）。
 * 密码表单校验在本组件内完成（ElForm 实例只在模板里存在），
 * 校验通过后上抛 change-password（携带本地表单载荷）由父组件合并后调接口。
 * 改密与绑定邮箱是两个独立动作，各自就地保存。
 */
const props = defineProps({
  pwdForm: { type: Object, required: true },
  pwdRules: { type: Object, required: true },
  form: { type: Object, required: true },
  emailSaving: { type: Boolean, default: false }
})

const emit = defineEmits(['change-password', 'save-email'])

const narrow = inject('settingsNarrow', ref(false))
const labelPosition = computed(() => (narrow.value ? 'top' : 'left'))
const labelWidth = computed(() => (narrow.value ? 'auto' : '220px'))

const pwdFormRef = ref(null)

// 本地拷贝编辑，不直接改 props：
// 改密成功后父组件会清空登录态，若直接绑 props.pwdForm，
// 用户改到一半输错时清空会连累正在输入的旧密码。
const localPwdForm = ref({ ...props.pwdForm })
watch(() => props.pwdForm, (v) => {
  localPwdForm.value = { ...v }
}, { deep: true })

const localEmail = ref(props.form.email)
watch(() => props.form.email, (v) => {
  localEmail.value = v
})

// validate() 会 reject：校验不通过时直接中止，不发请求。
// 确认密码与新密码的一致性由 pwdRules 的自定义 validator 在这一步拦住。
const handleChangePassword = async () => {
  if (!pwdFormRef.value) return
  try {
    await pwdFormRef.value.validate()
  } catch {
    return
  }
  emit('change-password', { ...localPwdForm.value })
}

function clearPasswordForm() {
  localPwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  pwdFormRef.value?.clearValidate()
}

const handleSaveEmail = () => {
  emit('save-email', localEmail.value)
}
</script>

<style scoped>
.settings-tab-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.settings-tab-note {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-regular);
}
.settings-input {
  width: min(100%, 420px);
}
.section-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}
</style>
