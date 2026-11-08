<template>
  <div
    class="back-btn"
    role="button"
    tabindex="0"
    :aria-label="text"
    @click="handleBack"
    @keydown.enter.prevent="handleBack"
    @keydown.space.prevent="handleBack"
  >
    <el-icon><ArrowLeft /></el-icon>
    <span>{{ text }}</span>
  </div>
</template>

<script setup>
// 返回按钮：优先回到浏览器上一页，没有历史记录时跳到 defaultPath。
// 用 replace 而非 push——新标签页直接打开的场景下，返回不应该在历史里留一条无意义的记录。
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue' // 确保引入图标

const props = defineProps({
  // 按钮显示的文字
  text: {
    type: String,
    default: '返回'
  },
  // 兜底跳转路径：如果用户直接打开此页（无历史记录），点返回时去哪里
  defaultPath: {
    type: String,
    default: '/'
  }
})

const router = useRouter()

const handleBack = () => {
  // 核心逻辑：判断浏览器是否有历史记录
  if (window.history.state && window.history.state.back) {
    router.back() // 有上一页，直接返回
  } else {
    router.replace(props.defaultPath) // 没有上一页（如新标签页打开），去兜底路径
  }
}
</script>

<style scoped>
.back-btn {
  /* 你的原始样式保持不变 */
  position: absolute;
  top: 30px;
  left: 50px;
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 8px 15px;
  background: var(--bg-surface-soft);
  backdrop-filter: blur(10px);
  border-radius: 20px;
  cursor: pointer;
  z-index: 10;
  transition: all 0.3s ease;
  color: var(--text-regular);
  border: 1px solid rgba(255, 255, 255, 0.3);
  user-select: none;
}

.back-btn:hover {
  background: rgba(255, 255, 255, 0.9);
  color: var(--accent-primary);
  transform: translateX(-5px);
  box-shadow: var(--shadow-md);
}
</style>