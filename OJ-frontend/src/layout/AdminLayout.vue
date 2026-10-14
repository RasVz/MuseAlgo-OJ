<template>
  <div class="admin-layout">
    <aside class="admin-side">
      <div class="admin-brand" @click="router.push('/admin')" title="回到管理中心首页">
        <span class="brand-dot"></span>
        睿思学院管理中心
      </div>
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
      <div class="admin-back" @click="router.push('/')">
        <el-icon><Back /></el-icon> 返回主站
      </div>
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
import {
  FolderOpened, List, Collection, Trophy, DataBoard, School, Bell, Flag,
  UserFilled, MagicStick, Document, Odometer, Back, HomeFilled, Grid
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.role >= 3)

const contentMenu = [
  { label: '题库管理', path: '/admin/problem', icon: FolderOpened },
  { label: '官方题单', path: '/admin/problem-lists', icon: List },
  { label: '比赛管理', path: '/admin/contest', icon: Trophy },
  { label: '课件资源', path: '/admin/courseware', icon: DataBoard },
  { label: '班级管理', path: '/admin/class', icon: School },
  { label: '公告管理', path: '/admin/announcements', icon: Bell },
  { label: '站内工具', path: '/admin/tools', icon: Grid }
]

const adminOnlyMenu = [
  { label: '训练试卷', path: '/admin/exam-paper', icon: Collection },
  { label: '社区治理', path: '/admin/community', icon: Flag },
  { label: '用户管理', path: '/admin/users', icon: UserFilled },
  { label: '全部班级', path: '/admin/classes', icon: School },
  { label: 'AI 管理', path: '/admin/ai', icon: MagicStick },
  { label: '审计日志', path: '/admin/audit-logs', icon: Document },
  { label: '运维仪表盘', path: '/admin/dashboard', icon: Odometer }
]

const menuGroups = computed(() => [
  { title: '内容管理', items: contentMenu },
  ...(isAdmin.value
    ? [
        { title: '用户与权限', items: adminOnlyMenu.filter((i) => ['/admin/users', '/admin/classes'].includes(i.path)) },
        { title: 'AI 与运维', items: adminOnlyMenu.filter((i) => !['/admin/users', '/admin/classes'].includes(i.path)) }
      ]
    : [])
])

// 分组默认全部展开（管理员三个分组 / 教师仅内容管理）
const defaultOpeneds = computed(() => menuGroups.value.map((g) => g.title))

// 菜单高亮：最长前缀匹配，保证详情页（如 /admin/class/:id）时对应菜单项保持选中
const allMenuPaths = computed(() => [
  '/admin',
  ...contentMenu.map((i) => i.path),
  ...adminOnlyMenu.map((i) => i.path)
])
const activeMenu = computed(() => {
  let best = '/admin'
  // 子页面归属映射：查重报告已并入站内工具，侧边栏高亮「站内工具」
  const matched = route.path === '/admin/plagiarism' ? '/admin/tools' : route.path
  for (const p of allMenuPaths.value) {
    if (matched === p || matched.startsWith(p + '/')) {
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
</style>
