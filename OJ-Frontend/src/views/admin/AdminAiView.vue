<template>
  <div class="admin-ai admin-scroll-page">
    <div class="global-bar">
      <div class="bar-title">
        <h1 class="page-title">AI 管理</h1>
        <p class="page-desc">用量、配额与教学诊断；接口、模型和密钥在「AI 服务」维护</p>
      </div>
      <div class="bar-tools">
        <span class="mode-badge" :class="modeClass"><i class="mode-dot"></i>{{ runtimeModeLabel }}</span>
        <el-radio-group :model-value="days" @change="setDays">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
        <el-button @click="openAiServices">AI 服务配置</el-button>
        <el-button @click="setTab('config')">提示词模板</el-button>
      </div>
    </div>

    <el-alert v-if="globalError" :title="globalError" type="error" :closable="false" class="block">
      <el-button size="small" @click="loadGlobal">重新加载</el-button>
    </el-alert>

    <div v-loading="loading" class="kpi-grid">
      <div v-for="card in cards" :key="card.key" class="kpi-card">
        <div class="kpi-label">{{ card.label }}</div>
        <div class="kpi-value" :class="{ 'is-mode': card.mode }">{{ card.value }}</div>
        <div class="kpi-foot">
          <svg v-if="card.spark" viewBox="0 0 64 18" width="64" height="18" aria-hidden="true">
            <polyline :points="card.spark" fill="none" stroke="var(--accent-primary)" stroke-width="1.5" />
          </svg>
          <span class="kpi-note">{{ card.note }}</span>
        </div>
      </div>
    </div>

    <div class="tab-bar" role="tablist" aria-label="AI 管理分区">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        role="tab"
        class="tab-btn"
        :class="{ active: activeTab === tab.key }"
        :aria-selected="activeTab === tab.key"
        @click="setTab(tab.key)"
      >{{ tab.label }}</button>
    </div>

    <div class="tab-content" role="tabpanel">
      <AiOverviewPanel
        v-if="activeTab === 'overview'"
        :overview="overview"
        :trend="trend"
        :ranking="ranking"
        :days="days"
        @navigate="navigate"
      />
      <AiLogsPanel v-else-if="activeTab === 'logs'" />
      <AiDiagnosticsPanel v-else-if="activeTab === 'diagnostics'" :days="days" />
      <AiConfigPanel v-else :config="config" @saved="loadGlobal" />
    </div>
  </div>
</template>

<script setup>
// AI 管理后台（仅管理员）：方案 A 运维仪表盘。
// 常驻全局条 + KPI + 四个 Tab；Tab 与时间范围写入 URL query，刷新与返回可复现。
// 全局数据（概览/趋势/排行/配置）在此加载，各 Tab 面板按需挂载并自行取数。
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiOverviewPanel from './components/AiOverviewPanel.vue'
import AiLogsPanel from './components/AiLogsPanel.vue'
import AiDiagnosticsPanel from './components/AiDiagnosticsPanel.vue'
import AiConfigPanel from './components/AiConfigPanel.vue'
import {
  getAiOverview, getAiTrend, getAiRanking, getAiConfig
} from '@/api/adminAi'
import { unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const route = useRoute()
const router = useRouter()

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'logs', label: '日志与配额' },
  { key: 'diagnostics', label: '诊断试跑' },
  { key: 'config', label: '配置与模板' }
]

const activeTab = computed(() =>
  tabs.some((t) => t.key === route.query.tab) ? route.query.tab : 'overview'
)
const days = computed(() => (Number(route.query.days) === 30 ? 30 : 7))

const overview = ref({})
const trend = ref([])
const ranking = ref([])
const config = ref({})
const loading = ref(false)
const globalError = ref('')

const runtimeModeLabel = computed(() => ({
  LEGACY: '旧环境',
  MANAGED: '后台服务',
  OFF: '已停用',
  UNKNOWN: '未知'
}[config.value.runtimeMode] || '读取中'))

const modeClass = computed(() => ({
  LEGACY: 'mode-legacy',
  MANAGED: 'mode-managed',
  OFF: 'mode-off',
  UNKNOWN: 'mode-unknown'
}[config.value.runtimeMode] || 'mode-unknown'))

// 迷你趋势线只用真实 trend 数据；不足两个点时画一条水平基线，避免空图形。
const sparkPoints = (values) => {
  const list = Array.isArray(values) ? values.map((v) => Number(v) || 0) : []
  if (list.length < 2) return '0,9 64,9'
  const min = Math.min(...list)
  const span = Math.max(...list) - min || 1
  const step = 64 / (list.length - 1)
  return list.map((v, i) => `${(i * step).toFixed(1)},${(16 - ((v - min) / span) * 14).toFixed(1)}`).join(' ')
}

const cards = computed(() => {
  const o = overview.value
  const t = trend.value
  return [
    {
      key: 'calls', label: '调用次数', value: Number(o.calls ?? 0).toLocaleString(),
      note: `成功 ${o.success ?? 0} · 失败 ${o.fail ?? 0}`,
      spark: sparkPoints(t.map((x) => x.calls))
    },
    {
      key: 'tokens', label: 'token 总量', value: Number(o.tokens ?? 0).toLocaleString(),
      note: `主动停止 ${o.cancelled ?? 0} 次`,
      spark: sparkPoints(t.map((x) => x.tokens))
    },
    {
      key: 'fail', label: '失败率', value: `${o.failRate ?? 0}%`,
      note: `配额拒绝 ${o.quotaBlocked ?? 0} · 比赛拦截 ${o.contestBlocked ?? 0}`,
      spark: ''
    },
    {
      key: 'mode', label: '运行模式', value: runtimeModeLabel.value, mode: true,
      note: config.value.model || '模型信息待读取',
      spark: ''
    }
  ]
})

const setTab = (key, extra = {}) => {
  router.push({
    query: { ...route.query, tab: key, status: extra.status || undefined }
  })
}

const setDays = (value) => {
  router.replace({ query: { ...route.query, days: value } })
}

const navigate = ({ tab, status }) => setTab(tab, { status })

const openAiServices = () => router.push('/admin/ai-services')

// 用版本号丢弃过期响应：切换时间范围或保存配置后可能同时存在多次全局请求。
let generation = 0
const loadGlobal = async () => {
  const version = ++generation
  loading.value = true
  globalError.value = ''
  try {
    const [o, t, r, c] = await Promise.all([
      getAiOverview(days.value),
      getAiTrend(days.value),
      getAiRanking(days.value),
      getAiConfig()
    ])
    if (version !== generation) return
    overview.value = unwrapResultData(o) || {}
    trend.value = unwrapResultData(t) || []
    ranking.value = unwrapResultData(r) || []
    config.value = unwrapResultData(c) || {}
  } catch (error) {
    if (version !== generation) return
    globalError.value = 'AI 概览数据加载失败，可重新加载。'
    showApiError(error, 'AI 概览数据加载失败')
  } finally {
    if (version === generation) loading.value = false
  }
}

watch(() => route.query.days, loadGlobal, { immediate: true })
</script>

<style scoped>
.admin-ai { padding: 16px 20px 28px; }
.global-bar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.page-title { margin: 0; color: var(--text-primary); font-size: 18px; font-weight: 600; }
.page-desc { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; }
.bar-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mode-badge { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--border-base); border-radius: 999px; font-size: 12px; color: var(--text-regular); background: var(--bg-surface); }
.mode-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-secondary); }
.mode-managed .mode-dot { background: var(--accent-success); }
.mode-legacy .mode-dot { background: var(--accent-warning); }
.mode-off .mode-dot { background: var(--accent-danger); }
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.kpi-card { padding: 12px 14px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.kpi-label { color: var(--text-secondary); font-size: 13px; }
.kpi-value { margin-top: 6px; color: var(--text-primary); font-size: 24px; font-weight: 700; line-height: 1.25; }
.kpi-value.is-mode { font-size: 19px; }
.kpi-foot { display: flex; align-items: center; gap: 8px; min-height: 18px; margin-top: 6px; }
.kpi-note { color: var(--text-secondary); font-size: 12px; }
.tab-bar { display: flex; gap: 2px; border-bottom: 1px solid var(--border-base); margin-bottom: 16px; overflow-x: auto; }
.tab-btn { flex: none; appearance: none; border: none; border-bottom: 2px solid transparent; background: none; padding: 9px 16px; font: inherit; font-size: 13px; color: var(--text-regular); cursor: pointer; }
.tab-btn:hover { color: var(--accent-primary); }
.tab-btn.active { color: var(--accent-primary); border-bottom-color: var(--accent-primary); font-weight: 600; }
.tab-btn:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: -2px; }
.block { margin-bottom: 16px; }
@media (max-width: 1100px) { .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .kpi-grid { grid-template-columns: minmax(0, 1fr); } .admin-ai { padding: 12px; } }
</style>
