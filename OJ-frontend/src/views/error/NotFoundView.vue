<template>
  <div class="not-found">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <p class="nf-text">页面走丢了，它可能已被移动或删除。</p>
      <div class="nf-actions">
        <el-button type="primary" @click="goHome">回到首页</el-button>
        <el-button plain @click="goBack">返回上一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 404 页面：路由未匹配到任何组件时由路由表兜底跳转至此。
 *
 * 交互上刻意提供两个出口而不是一个：
 * - 回到首页：给误入的用户一条确定的出路；
 * - 返回上一页：给「链接过期」的用户保留原路径的上下文。
 *
 * 返回上一页必须先判断 history.length：
 * 用户可能是直接粘贴 URL 或从外部站点跳进来的，此时历史栈里没有站内记录，
 * 直接 router.back() 会把用户带出本站（回到外站甚至空白页），
 * 这是 404 页最糟糕的结果，所以在无历史时退化为回首页。
 */
import { useRouter } from 'vue-router'

const router = useRouter()

// 走具名路由而非硬编码路径：路由改名或加前缀时不必回头改这里
const goHome = () => router.push({ name: 'Home' })

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push({ name: 'Home' })
  }
}
</script>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-app, #f6f8fa);
}
.nf-card {
  text-align: center;
  padding: 48px 64px;
  background: var(--bg-surface, #fff);
  border-radius: var(--radius-lg, 16px);
  border: 1px solid var(--border-light, #e8eaed);
  box-shadow: var(--shadow-md, 0 4px 12px rgba(31, 35, 40, 0.08));
}
.nf-code {
  font-size: 72px;
  font-weight: 800;
  color: var(--accent-primary, #2563eb);
  line-height: 1;
  margin-bottom: 16px;
}
.nf-text {
  color: var(--text-regular, #656d76);
  font-size: 15px;
  margin: 0 0 28px;
}
.nf-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
</style>
