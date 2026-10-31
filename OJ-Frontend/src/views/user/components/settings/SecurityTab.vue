<template>
  <div class="tab-content">
    <h3 class="tab-title">修改密码</h3>
    <el-form :model="localPwdForm" :rules="pwdRules" ref="pwdFormRef" label-position="top" class="setting-form">
      <el-form-item label="当前密码" prop="oldPassword">
        <el-input v-model="localPwdForm.oldPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="localPwdForm.newPassword" type="password" show-password />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input v-model="localPwdForm.confirmPassword" type="password" show-password />
      </el-form-item>
      <el-form-item>
        <el-button type="danger" plain @click="handleChangePassword">确认修改</el-button>
      </el-form-item>
    </el-form>

    <el-divider />

    <h3 class="tab-title">邮箱绑定</h3>
    <el-form label-position="top" class="setting-form">
      <el-form-item label="电子邮箱">
        <el-input v-model="localEmail" placeholder="请输入邮箱" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" plain :loading="emailSaving" @click="handleSaveEmail">保存邮箱</el-button>
        <span class="pref-tip">当前为预览功能：仅保存邮箱，验证邮件功能开发中</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'

/**
 * 账号安全 Tab（展示组件）。
 * 密码表单校验在本组件内完成（ElForm 实例只在模板里存在），
 * 校验通过后上抛 change-password（携带本地表单载荷）由父组件合并后调接口。
 */
const props = defineProps({
  pwdForm: { type: Object, required: true },
  pwdRules: { type: Object, required: true },
  form: { type: Object, required: true },
  emailSaving: { type: Boolean, default: false }
})

const emit = defineEmits(['change-password', 'save-email'])

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

const handleSaveEmail = () => {
  emit('save-email', localEmail.value)
}
</script>

<style scoped>
.tab-content {
  padding: 0 40px 20px;
}
.tab-title {
  margin-top: 0;
  margin-bottom: 25px;
  font-size: 20px;
  color: var(--text-primary);
}
.setting-form {
  max-width: 600px;
}
.pref-tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
