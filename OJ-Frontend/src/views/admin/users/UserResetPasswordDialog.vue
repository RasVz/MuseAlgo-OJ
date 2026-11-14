<template>
  <el-dialog
    :model-value="modelValue"
    :title="`重置密码：${targetName || ''}`"
    width="420px"
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="6–32 位" maxlength="32" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          show-password
          placeholder="再次输入新密码"
          maxlength="32"
        />
      </el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" title="重置后该用户的所有旧登录凭据将失效，需使用新密码重新登录。" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认重置</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'

const props = defineProps({
  modelValue: Boolean,
  saving: Boolean,
  targetName: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const formRef = ref(null)
const form = reactive({ newPassword: '', confirmPassword: '' })
const rules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需为 6–32 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    form.newPassword = ''
    form.confirmPassword = ''
    formRef.value?.clearValidate()
  },
)

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  emit('confirm', form.newPassword)
}
</script>
