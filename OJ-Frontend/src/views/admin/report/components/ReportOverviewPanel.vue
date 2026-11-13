<template>
  <div class="overview-panel" v-loading="loading">
    <div class="kpi-grid">
      <div v-for="card in cards" :key="card.label" class="kpi-card">
        <div class="kpi-label">{{ card.label }}</div>
        <div class="kpi-value" :style="{ color: card.color }">{{ card.value }}</div>
        <div class="kpi-hint">{{ card.hint }}</div>
      </div>
    </div>

    <div class="panel-grid">
      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-head">
            <span>最近报告</span>
            <el-button link type="primary" @click="emit('switch-tab', 'history')">查看全部</el-button>
          </div>
        </template>
        <div v-if="recent.length" class="recent-list">
          <div v-for="row in recent" :key="row.id" class="recent-row">
            <el-tag :type="metaOf(STATUS_META, row.status).type" size="small">
              {{ metaOf(STATUS_META, row.status).label }}
            </el-tag>
            <span class="recent-name">{{ row.studentName || `学生 #${row.studentId}` }}</span>
            <span class="recent-period">{{ row.periodStart }} ~ {{ row.periodEnd }}</span>
            <el-button
              v-if="row.status === 'GENERATED'"
              link
              type="primary"
              @click="emit('open-report', row.id)"
            >
              打开报告
            </el-button>
            <span v-else class="recent-placeholder">—</span>
          </div>
        </div>
        <el-empty v-else description="当前班级暂无报告" :image-size="72" />
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-head"><span>任务状态分布</span></div>
        </template>
        <div v-if="items.length" class="distribution">
          <div v-for="part in distribution" :key="part.label" class="dist-row">
            <span class="dist-label">{{ part.label }}</span>
            <div class="dist-track">
              <div class="dist-fill" :style="{ width: part.percent + '%', background: part.color }"></div>
            </div>
            <span class="dist-count">{{ part.count }}</span>
          </div>
        </div>
        <el-empty v-else description="暂无可统计的任务" :image-size="72" />
        <div class="panel-note">
          统计范围为当前加载页的 {{ items.length }} 条{{ className ? `（${className}）` : '' }}；跨页总数见「历史报告」。
        </div>
      </el-card>
    </div>

    <el-alert v-if="failedCount" type="warning" :closable="false" show-icon class="failed-alert">
      <template #title>有 {{ failedCount }} 份报告生成失败</template>
      <template #default>
        <div class="failed-line">
          <span>失败原因与重试入口在「历史报告」中按行处理。</span>
          <el-button link type="primary" @click="emit('switch-tab', 'history')">前往处理</el-button>
        </div>
      </template>
    </el-alert>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { STATUS_META, metaOf } from '../composables/reportMeta'

const props = defineProps({
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  className: { type: String, default: '' }
})
const emit = defineEmits(['switch-tab', 'open-report'])

const countOf = (status) => props.items.filter((row) => row.status === status).length
const generatedCount = computed(() => countOf('GENERATED'))
const inProgressCount = computed(() => countOf('PENDING') + countOf('GENERATING'))
const failedCount = computed(() => countOf('FAILED'))

const cards = computed(() => [
  { label: '本页报告', value: props.items.length, hint: '当前加载页条数', color: 'var(--text-primary)' },
  { label: '已生成', value: generatedCount.value, hint: '可打开阅读页', color: 'var(--accent-success)' },
  { label: '进行中', value: inProgressCount.value, hint: '排队 / 后台生成', color: 'var(--accent-warning)' },
  { label: '失败', value: failedCount.value, hint: '按服务端 canRetry 处理', color: 'var(--accent-danger)' }
])

const recent = computed(() => props.items.slice(0, 6))

const distribution = computed(() => {
  const definitions = [
    { label: '已生成', count: generatedCount.value, color: '#16a34a' },
    { label: '排队中', count: countOf('PENDING'), color: '#0ea5e9' },
    { label: '生成中', count: countOf('GENERATING'), color: '#d97706' },
    { label: '失败', count: failedCount.value, color: '#dc2626' }
  ]
  const max = Math.max(1, ...definitions.map((item) => item.count))
  return definitions.map((item) => ({ ...item, percent: Math.round((item.count / max) * 100) }))
})
</script>

<style scoped>
.overview-panel { padding-top: 4px; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card { background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); padding: 16px 18px; }
.kpi-label { font-size: 13px; color: var(--text-regular); }
.kpi-value { font-size: 28px; font-weight: 700; line-height: 1.3; margin-top: 4px; }
.kpi-hint { font-size: 12px; color: var(--text-secondary); margin-top: 2px; }
.panel-grid { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 16px; }
.panel-card { border: 1px solid var(--border-light); }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; color: var(--text-primary); }
.recent-list { display: flex; flex-direction: column; }
.recent-row { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border-light); font-size: 13px; }
.recent-row:last-child { border-bottom: none; }
.recent-name { color: var(--text-primary); min-width: 90px; }
.recent-period { color: var(--text-secondary); font-size: 12px; flex: 1; }
.recent-placeholder { color: var(--text-secondary); min-width: 62px; text-align: center; }
.distribution { display: flex; flex-direction: column; gap: 12px; }
.dist-row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.dist-label { width: 56px; color: var(--text-regular); }
.dist-track { flex: 1; height: 8px; background: var(--bg-surface-hover); border-radius: 999px; overflow: hidden; }
.dist-fill { height: 100%; border-radius: 999px; }
.dist-count { width: 28px; text-align: right; color: var(--text-primary); }
.panel-note { margin-top: 14px; font-size: 12px; color: var(--text-secondary); line-height: 1.6; }
.failed-alert { margin-top: 16px; }
.failed-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
@media (max-width: 1100px) {
  .panel-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>
