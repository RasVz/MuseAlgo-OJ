<template>
  <div class="admin-home admin-scroll-page">
    <div class="page-header">
      <div class="page-title">管理中心</div>
      <div class="page-desc">按分组直达各管理模块</div>
    </div>

    <div v-for="group in groups" :key="group.title" class="group">
      <div class="group-title">{{ group.title }}</div>
      <div class="card-grid">
        <div
          v-for="item in group.items"
          :key="item.path"
          class="nav-card"
          @click="router.push(item.path)"
        >
          <el-icon :size="26" class="card-icon"><component :is="item.icon" /></el-icon>
          <div class="card-label">{{ item.label }}</div>
          <div class="card-desc">{{ item.desc }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 管理后台首页：按角色分组的入口导航，本身不含业务逻辑。
// role >= 3 即管理员；教师（role 2）只看到内容管理分组，学生无入口（不进后台）。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import {
  FolderOpened, List, Collection, Trophy, DataBoard, School, Bell, Flag,
  UserFilled, MagicStick, Document, Odometer, Grid
} from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
// 角色降级或退出登录后导航需要立即重算，否则会把管理员专属入口留在页面上
const isAdmin = computed(() => userStore.role >= 3)

const groups = computed(() => [
  {
    title: '内容管理',
    items: [
      { label: '题库管理', desc: '题目 CRUD / FPS 导入', path: '/admin/problem', icon: FolderOpened },
      { label: '官方题单', desc: '维护题单广场', path: '/admin/problem-lists', icon: List },
      { label: '比赛管理', desc: '创建 / 成绩导出', path: '/admin/contest', icon: Trophy },
      { label: '课件资源', desc: 'Slidev 课件', path: '/admin/courseware', icon: DataBoard },
      { label: '班级管理', desc: '班级 / 作业完成情况', path: '/admin/class', icon: School },
      { label: '公告管理', desc: '发布 / 置顶公告', path: '/admin/announcements', icon: Bell },
      { label: '站内工具', desc: '教师 AI 助手等全部工具', path: '/admin/tools', icon: Grid }
    ]
  },
  ...(isAdmin.value
    ? [
        {
          title: '训练与内容',
          items: [
            { label: '训练试卷', desc: '套卷出题 / 成绩统计', path: '/admin/exam-paper', icon: Collection },
            { label: '社区治理', desc: '帖子 / 评论治理', path: '/admin/community', icon: Flag }
          ]
        },
        {
          title: '用户与权限',
          items: [
            { label: '用户管理', desc: '角色 / 禁用', path: '/admin/users', icon: UserFilled },
            { label: '全部班级', desc: '查看所有教师创建的班级', path: '/admin/classes', icon: School }
          ]
        },
        {
          title: 'AI 与运维',
          items: [
            { label: 'AI 管理', desc: '用量 / 配置 / 模板 / 配额', path: '/admin/ai', icon: MagicStick },
            { label: '审计日志', desc: '操作审计查询', path: '/admin/audit-logs', icon: Document },
            { label: '运维仪表盘', desc: '统计与趋势', path: '/admin/dashboard', icon: Odometer }
          ]
        }
      ]
    : [])
])
</script>

<style scoped>
.admin-home { padding: 20px; }
.page-header { margin-bottom: 18px; }
.page-title { font-size: 20px; font-weight: 700; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.group { margin-bottom: 22px; }
.group-title { font-size: 13px; font-weight: 600; color: var(--text-regular); margin-bottom: 10px; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.nav-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 18px 16px;
  cursor: pointer;
  transition: all .15s;
}
.nav-card:hover { border-color: #6366f1; box-shadow: 0 6px 16px rgba(99,102,241,.12); transform: translateY(-2px); }
.card-icon { color: #6366f1; }
.card-label { font-size: 15px; font-weight: 600; margin-top: 10px; }
.card-desc { color: var(--text-secondary); font-size: 12px; margin-top: 4px; }
</style>
