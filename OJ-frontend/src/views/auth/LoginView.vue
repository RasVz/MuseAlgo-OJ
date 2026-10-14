<template>
  <div class="login-page">
    <back-button />
    <div class="bg-blob blob-blue"></div>
    <div class="bg-blob blob-green"></div>

    <el-card class="login-card">
      <div class="login-header">
        <h2 class="login-title">欢迎回到睿思学院</h2>
        <p class="login-subtitle">请登录您的睿思学院账号</p>
      </div>

      <el-form :model="loginForm" label-position="top" size="large" @keyup.enter="handleLogin">
        <el-form-item label="用户名/邮箱">
          <el-input 
            v-model="loginForm.username" 
            placeholder="请输入账号"
            prefix-icon="User"
          />
        </el-form-item>
        
        <el-form-item label="密码">
          <el-input 
            v-model="loginForm.password" 
            type="password" 
            placeholder="请输入密码" 
            prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <div class="form-options">
          <el-checkbox v-model="loginForm.rememberMe">记住我</el-checkbox>
          <el-link type="primary" underline="never" v-show="isVisible">忘记密码？</el-link>
        </div>

        <el-button type="primary" :loading="loading" class="login-btn" @click="handleLogin">
          立即登录
        </el-button>
        
        <div class="register-link">
          还没有账号？ 
          <el-link type="success" underline="never" @click="$router.push({ name: 'Register' })">
            立即注册
          </el-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { loginApi } from '@/api/auth'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import BackButton from '@/components/BackButton.vue'
import { useUserStore } from '@/stores/userStore'

const isVisible = false
const loading = ref(false)

// 使用 reactive 整合数据，方便传递给后端
const loginForm = reactive({
  username: '',
  password: '',
  rememberMe: false // 增加字段
})

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const handleLogin = async () => {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }

  loading.value = true

  try {
    // 直接把整个 loginForm 对象发给后端，包含 username, password, rememberMe
    const res = await loginApi(loginForm)
    
    if (res.code === 200) {
      const { accessToken, refreshToken, user } = res.data
      userStore.setAuth({ accessToken, refreshToken, user })
      
      ElMessage.success('欢迎回来，' + (user.nickname || user.username))
      
      let redirectPath = route.query.redirect || '/'
      if (redirectPath === '/login') {
        redirectPath = '/'
      } 

      router.push(redirectPath)
      
    } else {
      ElMessage.error(res.message || '登录失败')
    }
  } catch (error) {
    console.error('登录失败', error)
    ElMessage.error(error.message || '登录请求异常')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  width: 100%; 
  display: flex;
  justify-content: center;
  align-items: center;
  background: var(--bg-app);
  position: relative;
  overflow: hidden; 
}

.bg-blob {
  position: absolute;
  width: 500px;
  height: 500px;
  filter: blur(100px);
  z-index: 0;
  opacity: 0.4;
  pointer-events: none; 
}

.blob-blue {
  background: var(--accent-primary);
  top: -100px;
  right: -100px;
}
.blob-green {
  background: var(--accent-primary-light);
  bottom: -100px;
  left: -100px;
}

.login-card {
  width: 420px;
  border-radius: 20px;
  border: none;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.05);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(5px);
  z-index: 1;
  padding: 20px;
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-title {
  font-size: 28px;
  margin: 0;
  background: linear-gradient(90deg, var(--text-regular), var(--text-secondary));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  font-weight: bold;
}

.login-subtitle {
  color: var(--text-secondary);
  margin-top: 10px;
}

.login-btn {
  width: 100%;
  margin-top: 20px;
  height: 48px;
  font-size: 16px;
  border: none !important;
  background: linear-gradient(90deg, var(--accent-primary), var(--accent-primary-dark)) !important;
  border-radius: 10px;
  transition: opacity 0.3s;
}

.login-btn:hover {
  opacity: 0.9;
}

.form-options {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.register-link {
  text-align: center;
  margin-top: 25px;
  font-size: 14px;
  color: var(--text-regular);
}
</style>
