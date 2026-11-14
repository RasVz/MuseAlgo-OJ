<template>
  <el-drawer
    :model-value="modelValue"
    title="创建账号"
    size="480px"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="登录名，3–32 位字母/数字/下划线" maxlength="32" />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="展示名称" maxlength="32" />
      </el-form-item>
      <el-form-item label="初始密码" prop="password">
        <el-input v-model="form.password" type="password" show-password placeholder="6–32 位" maxlength="32" />
      </el-form-item>
      <el-form-item label="角色" prop="role">
        <el-radio-group v-model="form.role">
          <el-radio :value="1">学生</el-radio>
          <el-radio :value="2">教师</el-radio>
          <el-radio :value="3">管理员</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="选填" maxlength="64" />
      </el-form-item>
      <el-form-item label="学校" prop="school">
        <el-input v-model="form.school" placeholder="选填" maxlength="64" />
      </el-form-item>
      <el-form-item label="真实姓名" prop="realName">
        <el-input v-model="form.realName" placeholder="选填" maxlength="32" />
      </el-form-item>
    </el-form>
    <el-alert
      type="info"
      :closable="false"
      title="账号创建后用户名不可修改；密码请通过安全渠道交给使用者。"
      class="form-hint"
    />
    <template #footer>
      <div class="drawer-footer">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">创建</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createAdminUser } from '@/api/adminUser'
import { getResultMessage, isResultSuccess } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'created'])

const formRef = ref(null)
const creating = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  password: '',
  role: 1,
  email: '',
  school: '',
  realName: '',
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{3,32}$/, message: '3–32 位字母、数字或下划线', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 32, message: '昵称不能超过 32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需为 6–32 位', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, { username: '', nickname: '', password: '', role: 1, email: '', school: '', realName: '' })
    formRef.value?.clearValidate()
  },
)

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  creating.value = true
  try {
    const res = await createAdminUser({ ...form })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '创建失败'))
      return
    }
    ElMessage.success(`账号「${form.username}」创建成功`)
    emit('update:modelValue', false)
    emit('created')
  } catch (cause) {
    showApiError(cause, '创建失败')
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.form-hint {
  margin-top: 4px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
