<template>
  <div class="admin-layout">
    <aside class="admin-side" aria-label="管理导航">
      <button type="button" class="admin-brand" @click="router.push('/admin')" title="回到管理中心首页" aria-label="回到管理中心首页">
        <span class="brand-dot"></span>
        睿思学院管理中心
      </button>
      <nav class="admin-nav">
        <el-menu
          :default-active="activeMenu"
          :default-openeds="defaultOpeneds"
          router
          class="admin-el-menu"
          background-color="transparent"
          text-color="var(--admin-side-text)"
          active-text-color="var(--admin-side-active-text)"
        >
          <el-menu-item index="/admin">
            <el-icon><HomeFilled /></el-icon>
            <span>管理中心首页</span>
          </el-menu-item>

          <el-sub-menu v-for="group in menuGroups" :key="group.title" :index="group.title">
            <template #title>
              <span class="group-title">{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </el-sub-menu>
        </el-menu>
      </nav>
      <button type="button" class="admin-back" @click="router.push('/')" title="返回主站" aria-label="返回主站">
        <el-icon><Back /></el-icon> 返回主站
      </button>
    </aside>
    <div class="admin-body">
      <main class="admin-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { getAdminMenuGroups } from '@/constants/adminMenu'
import { Back, HomeFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menuGroups = computed(() => getAdminMenuGroups(userStore.role || 0))

const defaultOpeneds = computed(() => menuGroups.value.map((g) => g.title))

// 菜单高亮：最长前缀匹配，保证详情页（如 /admin/class/:id）时对应菜单项保持选中
const allMenuPaths = computed(() => [
  '/admin',
  ...menuGroups.value.flatMap((g) => g.items.map((i) => i.path))
])
const activeMenu = computed(() => {
  let best = '/admin'
  for (const p of allMenuPaths.value) {
    if (route.path === p || route.path.startsWith(p + '/')) {
      if (p.length > best.length) best = p
    }
  }
  return best
})
</script>

<style scoped>
/* 侧栏刻意保持深色（GitHub 风格），浅色/暗色共用；页面背景跟随主题 tokens */
.admin-layout {
  --admin-side-bg: #1f2937;
  --admin-side-text: #d1d5db;
  --admin-side-active-text: #ffffff;
  --admin-side-muted: #9ca3af;
  --admin-side-hover: rgba(255, 255, 255, 0.08);
  --admin-accent: #6366f1;
  display: flex; min-height: 100vh; background: var(--bg-app);
}
.dark .admin-layout {
  --admin-side-bg: #161b22;
  --admin-side-text: #cbd5e1;
  --admin-side-muted: #8b949e;
  --admin-side-hover: rgba(255, 255, 255, 0.06);
}
.admin-side {
  width: 220px;
  flex-shrink: 0;
  background: var(--admin-side-bg);
  color: var(--admin-side-text);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}
.admin-body {
  flex: 1;
  min-width: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
}
.admin-brand {
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 15px;
  color: var(--admin-side-active-text);
  padding: 18px 16px;
  cursor: pointer;
  user-select: none;
}
.brand-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--admin-accent); }
.admin-nav { flex: 1; padding: 4px 8px; }
.admin-el-menu { border-right: none; }
.admin-el-menu :deep(.el-menu-item),
.admin-el-menu :deep(.el-sub-menu__title) {
  height: 42px;
  line-height: 42px;
  border-radius: 8px;
  margin: 2px 0;
}
.admin-el-menu :deep(.el-menu-item:hover),
.admin-el-menu :deep(.el-sub-menu__title:hover) {
  background: var(--admin-side-hover);
}
.admin-el-menu :deep(.el-menu-item.is-active) {
  background: var(--admin-accent);
}
.admin-el-menu :deep(.el-sub-menu .el-menu) {
  background: transparent;
}
.group-title { font-size: 12px; color: var(--admin-side-muted); }
.admin-back {
  border: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  text-align: left;
  width: 100%;
  padding: 14px 16px;
  border-top: 1px solid var(--admin-side-hover);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.admin-back:hover { color: var(--admin-side-active-text); }
.admin-brand:focus-visible, .admin-back:focus-visible { outline: 2px solid var(--admin-side-active-text); outline-offset: -4px; }
.admin-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
/* 让每个管理页根元素填满可视区（去掉原先按“带顶部导航栏”计算的留白） */
.admin-main :deep(> *) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
/* 非表格型页面：保持自然高度、允许内部纵向滚动，避免内容被压缩 */
.admin-main :deep(> .admin-scroll-page) {
  flex: 1;
  min-height: 0;
  display: block;
  overflow-y: auto;
  box-sizing: border-box;
}
</style>
