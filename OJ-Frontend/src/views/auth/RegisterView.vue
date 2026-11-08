<template>
  <div class="register-page">
    <BackButton />
    <div class="bg-blob blob-blue"></div>
    <div class="bg-blob blob-green"></div>

    <el-card class="register-card">
      <div class="register-header">
        <h2 class="register-title">创建账号</h2>
        <p class="register-subtitle">加入睿思学院，开启你的算法之旅</p>
      </div>

      <el-form 
        :model="form" 
        :rules="rules" 
        ref="registerFormRef"
        label-position="top" 
        size="large"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="4-16位字母或数字" prefix-icon="User" />
        </el-form-item>

        <el-form-item label="电子邮箱" prop="email">
          <el-input v-model="form.email" placeholder="you@example.com" prefix-icon="Message" />
        </el-form-item>

        <el-form-item label="设置密码" prop="password">
          <el-input 
            v-model="form.password" 
            type="password" 
            placeholder="至少6位，需包含字母和数字" 
            prefix-icon="Lock"
            show-password 
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input 
            v-model="form.confirmPassword" 
            type="password" 
            placeholder="请再次输入密码" 
            prefix-icon="CircleCheck"
            show-password 
          />
        </el-form-item>

        <el-button 
          type="primary" 
          class="register-btn" 
          :loading="loading" 
          @click="handleRegister"
        >
          立即注册
        </el-button>

        <div class="login-link">
          已经有账号了？ 
          <el-link type="primary" :underline="false" @click="$router.push({ name: 'Login' })">
            返回登录
          </el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { registerApi } from '@/api/auth' // 确保你已按之前步骤创建了 api/user.js
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'

const router = useRouter()
const registerFormRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

/**
 * 严谨的前端校验规则
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 16, message: '用户名长度需在 4-16 位', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]+$/, message: '仅支持字母、数字及下划线', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入合法的邮箱格式', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少为 6 位', trigger: 'blur' },
    // 增加复杂度校验：必须包含字母和数字
    { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '密码必须包含字母和数字', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/**
 * 提交注册
 */
const handleRegister = async () => {
  if (!registerFormRef.value) return
  
  // 1. 前端表单预校验
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      // 2. 调用后端接口 [对应 UserController.register]
      // 传递后端期待的字段：username, password, email, nickname
      await registerApi({
        username: form.username,
        password: form.password,
        email: form.email,
        nickname: form.username // 初始昵称默认设置为用户名
      })
      
      // 3. 处理后端返回的 Result
      // 注意：我们在 axios 拦截器里已经统一处理了 res.code !== 200 的报错
      ElMessage.success('注册成功，欢迎加入睿思学院！')
      router.push({ name: 'Login' })
      
    } catch (error) {
      // 错误由拦截器弹出，此处仅做日志
      console.error('注册异常:', error)
    } finally {
      loading.value = false
    }
  })
}
</script>

<style scoped>
.register-page {
  height: 100vh;
  width: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--bg-app);
  position: relative;
  overflow: hidden;
}

/* 背景装饰球 (复用登录页风格) */
.bg-blob {
  position: absolute;
  width: 500px;
  height: 500px;
  filter: blur(100px);
  z-index: 0;
  opacity: 0.4;
  pointer-events: none;
}
.blob-blue { background: var(--accent-primary); top: -100px; right: -100px; }
.blob-green { background: var(--accent-primary-light); bottom: -100px; left: -100px; }

.register-card {
  width: 450px;
  border-radius: 20px;
  border: none;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.05);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(5px);
  z-index: 1;
  padding: 10px 20px;
}

.register-header {
  text-align: center;
  margin-bottom: 20px;
}

.register-title {
  font-size: 26px;
  margin: 0;
  background: linear-gradient(90deg, var(--text-regular), var(--text-secondary));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  font-weight: bold;
}

.register-subtitle {
  color: var(--text-secondary);
  margin-top: 8px;
  font-size: 14px;
}

.register-btn {
  width: 100%;
  margin-top: 10px;
  height: 48px;
  font-size: 16px;
  border: none !important;
  background: linear-gradient(90deg, var(--accent-primary), var(--text-regular)) !important;
  border-radius: 10px;
}

.login-link {
  text-align: center;
  margin-top: 20px;
  font-size: 14px;
  color: var(--text-regular);
}
</style>
