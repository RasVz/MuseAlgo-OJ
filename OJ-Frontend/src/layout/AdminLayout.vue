<template>
  <div class="admin-layout" @keydown.esc="closeMobileMenu">
    <button v-if="isMobile && mobileMenuOpen" class="mobile-backdrop" aria-label="关闭管理导航" @click="closeMobileMenu"></button>
    <aside ref="sidePanel" class="admin-side" :class="{ 'mobile-open': mobileMenuOpen }" :inert="isMobile && !mobileMenuOpen || undefined" aria-label="管理导航">
      <button class="mobile-close" @click="closeMobileMenu">关闭导航</button>
      <button type="button" class="admin-brand" @click="router.push('/admin')" title="回到管理中心首页">
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
      <button type="button" class="admin-back" @click="router.push('/')">
        <el-icon><Back /></el-icon> 返回主站
      </button>
    </aside>
    <div class="admin-body" :inert="isMobile && mobileMenuOpen || undefined">
      <header class="admin-mobile-toolbar">
        <button ref="menuToggle" type="button" :aria-expanded="mobileMenuOpen" @click="openMobileMenu">管理导航</button>
        <span>{{ route.meta.title || '管理中心' }}</span>
      </header>
      <main class="admin-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { getAdminMenuGroups } from '@/constants/adminMenu'
import { Back, HomeFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const mobileMedia = window.matchMedia('(max-width: 720px)')
const isMobile = ref(mobileMedia.matches)
const mobileMenuOpen = ref(false)
const sidePanel = ref(null)
const menuToggle = ref(null)
const openMobileMenu = async () => {
  mobileMenuOpen.value = true
  await nextTick()
  sidePanel.value?.querySelector('button')?.focus()
}
const closeMobileMenu = () => {
  mobileMenuOpen.value = false
  if (isMobile.value) nextTick(() => menuToggle.value?.focus())
}
const onMediaChange = (event) => {
  isMobile.value = event.matches
  mobileMenuOpen.value = false
}
mobileMedia.addEventListener('change', onMediaChange)
onBeforeUnmount(() => mobileMedia.removeEventListener('change', onMediaChange))
watch(() => route.fullPath, () => { if (mobileMenuOpen.value) closeMobileMenu() })

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
  padding: 14px 16px;
  border-top: 1px solid var(--admin-side-hover);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.admin-back:hover { color: var(--admin-side-active-text); }
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
.admin-mobile-toolbar, .mobile-close, .mobile-backdrop { display: none; }
@media (max-width: 720px) {
  .admin-side { position: fixed; left: 0; top: 0; width: min(280px, 85vw); height: 100dvh; z-index: 2101; transform: translateX(-100%); }
  .admin-side.mobile-open { transform: translateX(0); }
  .admin-body { height: 100dvh; }
  .admin-mobile-toolbar { display: flex; flex-shrink: 0; align-items: center; gap: 16px; min-height: 56px; padding: 0 12px; background: var(--bg-surface); border-bottom: 1px solid var(--border-light); color: var(--text-primary); }
  .admin-mobile-toolbar button, .mobile-close { min-height: 40px; padding: 8px 12px; border: 1px solid var(--border-light); border-radius: 6px; background: var(--bg-surface); color: var(--text-primary); cursor: pointer; font: inherit; }
  .mobile-close { display: block; margin: 12px 12px 0; }
  .mobile-backdrop { display: block; position: fixed; inset: 0; border: 0; padding: 0; background: rgba(0,0,0,.45); z-index: 2100; }
}
</style>
