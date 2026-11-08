<template>
  <el-card shadow="hover" class="home-card problem-card">
    <template #header>
      <div class="card-header">
        <span class="header-title"><el-icon><Aim /></el-icon> 每日推荐</span>
        <el-button link @click="refresh"><el-icon><Refresh /></el-icon> 换一批</el-button>
      </div>
    </template>
    <div class="problem-grid">
      <div v-for="problem in list" :key="problem.id" class="p-item" @click="$router.push({ name: 'ProblemDetail', params: { id: problem.id } })">
        <div class="p-top">
          <span class="p-id">#{{ problem.id }}</span>
          <el-tag size="small" :type="problem.type" effect="plain">{{ problem.level }}</el-tag>
        </div>
        <div class="p-title">{{ problem.title }}</div>
        <div class="p-tags">
          <span v-for="tag in problem.tags" :key="tag" class="p-tag">{{ tag }}</span>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { ref } from 'vue'
import { Aim, Refresh } from '@element-plus/icons-vue'

// Mock Data
const list = ref([
  { id: 1001, title: 'A+B Problem', level: '入门', type: 'info', tags: ['模拟'] },
  { id: 1024, title: '矩阵快速幂', level: '普及/提高-', type: 'success', tags: ['数学', '矩阵'] },
  { id: 2048, title: '最长公共子序列', level: '普及-', type: 'warning', tags: ['DP'] },
])

const refresh = () => {
    // 模拟刷新逻辑
    list.value = list.value.reverse()
}
</script>

<style scoped>
.home-card { border-radius: 12px; border: none; height: 100%; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-title { font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 6px; }

.problem-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; }
.p-item { background: var(--bg-surface-hover); border-radius: 8px; padding: 15px; cursor: pointer; transition: transform 0.2s; border: 1px solid transparent; }
.p-item:hover { transform: translateY(-3px); border-color: var(--border-light); background: var(--bg-surface); box-shadow: 0 4px 12px rgba(0,0,0,0.05); }

.p-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.p-id { font-family: monospace; color: var(--text-secondary); font-size: 12px; }
.p-title { font-weight: bold; font-size: 15px; color: var(--text-primary); margin-bottom: 8px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.p-tags { font-size: 12px; color: var(--text-secondary); display: flex; gap: 5px; }
.p-tag { background: var(--bg-surface); padding: 0 4px; border-radius: 2px; }
</style>