<template>
  <div class="overview-grid">
    <el-card shadow="never" class="stat-card" v-for="item in items" :key="item.label">
      <div class="stat-content">
        <div class="stat-icon" :style="{ background: item.bg, color: item.color }">
          <el-icon><component :is="item.icon" /></el-icon>
        </div>
        <div class="stat-text">
          <div class="value">{{ item.value }}</div>
          <div class="label">{{ item.label }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 练习分析页顶部四格指标：提交总数 / 通过题目 / AC 率 / 活跃天数。
 *
 * 纯展示组件，数据由父页面从 GET /profile/practice/me 的 overview 传入；
 * AC 率的「保留 1 位小数 + %」与缺失值兜底在这里统一处理，页面不再各自格式化。
 * 配色一律取站点 CSS 变量，跟随明暗主题自动切换。
 */
import { computed } from 'vue'
import { Trophy, CircleCheck, DataLine, Timer } from '@element-plus/icons-vue'

const props = defineProps({
  stats: { type: Object, default: () => ({}) }
})

const toNumber = (value) => {
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

const formatAcRate = (value) => {
  const num = Number(value)
  if (value === null || value === undefined || !Number.isFinite(num)) return '—'
  return num.toFixed(1) + '%'
}

const items = computed(() => [
  { label: '提交总数', value: toNumber(props.stats?.totalSubmissions), icon: DataLine, color: 'var(--el-color-primary)', bg: 'var(--el-color-primary-light-9)' },
  { label: '通过题目', value: toNumber(props.stats?.solvedProblemCount), icon: CircleCheck, color: 'var(--accent-success)', bg: 'var(--accent-success-light)' },
  { label: 'AC 率', value: formatAcRate(props.stats?.acRate), icon: Trophy, color: 'var(--accent-warning)', bg: 'var(--accent-warning-light)' },
  { label: '活跃天数', value: toNumber(props.stats?.activeDays), icon: Timer, color: 'var(--accent-danger)', bg: 'var(--accent-danger-light)' }
])
</script>

<style scoped>
.overview-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 20px; }
.stat-card { border: none; border-radius: 12px; }
.stat-content { display: flex; align-items: center; gap: 15px; }
.stat-icon { width: 48px; height: 48px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 24px; }
.value { font-size: 24px; font-weight: bold; color: var(--text-primary); line-height: 1.2; }
.label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
</style>
