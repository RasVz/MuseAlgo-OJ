<template>
  <el-config-provider :locale="zhCn">
    <router-view v-slot="{ Component }">
      <transition name="app-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </el-config-provider>
</template>

<script setup>
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAuthInterceptor } from '@/composables/useAuthInterceptor'

// 初始化鉴权拦截器（监听 request.js 发出的 auth:fail / request:error 事件）
useAuthInterceptor()
// 暗色模式由 index.html 首帧内联脚本应用，避免刷新闪白（FOUC）
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  overflow-x: hidden;
}
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 80vh;
}

/* 顶层路由切换淡入淡出（登录 ↔ 主站等跨布局切换） */
.app-fade-enter-active,
.app-fade-leave-active {
  transition: opacity 0.15s ease;
}
.app-fade-enter-from,
.app-fade-leave-to {
  opacity: 0;
}
</style>
