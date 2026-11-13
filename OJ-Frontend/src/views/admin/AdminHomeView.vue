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
// 管理后台首页：与侧栏共用 adminMenu 配置，按角色过滤分组。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { getAdminMenuGroups } from '@/constants/adminMenu'

const router = useRouter()
const userStore = useUserStore()

const groups = computed(() => getAdminMenuGroups(userStore.role || 0))
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
