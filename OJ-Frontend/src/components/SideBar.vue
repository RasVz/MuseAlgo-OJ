<template>
  <el-aside width="72px" class="icon-sidebar">
    <!-- <div class="side-logo-mini">EB</div> -->

    <div
      class="sidebar-brand"
      role="button"
      tabindex="0"
      aria-label="回到首页"
      @click="goHome"
      @keydown.enter.prevent="goHome"
      @keydown.space.prevent="goHome"
    >
      <div class="brand-text">MA</div>
    </div>

    <div 
      v-for="item in menuItems"
      :key="item.path"
      class="menu-item" 
      :class="{ active: isActive(item.path) }"
      role="button"
      tabindex="0"
      :aria-label="item.label"
      :aria-current="isActive(item.path) ? 'page' : undefined"
      @click="nav(item.path)"
      @keydown.enter.prevent="nav(item.path)"
      @keydown.space.prevent="nav(item.path)"
    >
      <el-icon :size="22"><component :is="item.icon" /></el-icon>
      <span class="menu-label">{{ item.label }}</span>
    </div>
  </el-aside>
</template>

<script setup>
import { useRouter, useRoute } from 'vue-router'
import {
  DataBoard, EditPen, Grid, List, Collection, Trophy, Timer, Notebook
} from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()

// 定义菜单配置（图标为显式导入组件，支持 tree-shaking，不再依赖全局注册）
const menuItems = [
  { label: '课程', path: '/my/class', icon: DataBoard },
  { label: '作业', path: '/my/homework', icon: EditPen },
  { label: '题库', path: '/problem', icon: Grid },
  { label: '题单', path: '/problem/list', icon: List },
  { label: '训练', path: '/training', icon: Collection },
  { label: '比赛', path: '/contest', icon: Trophy },
  { label: '记录', path: '/submission', icon: Timer },
  { label: '动态', path: '/post', icon: Notebook }
]

const goHome = () => {
  router.push({ name: 'Home' })
}

const nav = (path) => {
  router.push(path)
}

// 最长前缀匹配：嵌套路由高亮对应菜单项，同时避免 /problem 和 /problem/list 同时高亮
const isActive = (path) => {
  const best = menuItems.reduce((best, item) => {
    if (route.path === item.path || route.path.startsWith(item.path + '/')) {
      if (!best || item.path.length > best.path.length) return item
    }
    return best
  }, null)
  return best?.path === path
}
</script>

<style scoped>
.icon-sidebar {
  background: var(--bg-surface);
  border-right: 1px solid var(--border-light);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 0; 
  height: 100vh;
}

.sidebar-brand {
  width: 100%; 
  height: 84px; 
  background: var(--bg-sidebar-brand); 
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  margin-bottom: 15px; 
  transition: background 0.3s;
}

.sidebar-brand:hover {
  background: var(--bg-sidebar-brand-hover); 
}

.brand-text {
  color: var(--text-inverse);
  font-family: 'Gill Sans', 'Gill Sans MT', Calibri, 'Trebuchet MS', sans-serif;
  font-size: 24px;
  font-weight: 800; 
  font-style: italic; 
  letter-spacing: 1px;
}

.menu-item {
  width: 60px;
  height: 60px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  border-radius: 12px;
  color: var(--text-secondary);
  margin-bottom: 12px;
  cursor: pointer;
  transition: all 0.2s;
  gap: 3px;
}

.menu-item:hover {
  background-color: var(--accent-primary-light);
  color: var(--accent-primary);
}

.menu-item.active {
  background-color: var(--accent-primary-light);
  color: var(--accent-primary);
  font-weight: 500;
}

.menu-label {
  font-size: 12px;
  line-height: 1;
  transform: scale(0.95);
}
</style>
