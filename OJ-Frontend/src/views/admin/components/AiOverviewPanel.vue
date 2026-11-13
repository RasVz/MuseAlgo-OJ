<template>
  <div class="overview-page">
    <div class="overview-grid top-row">
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">调用量与 token 趋势</span>
          <span class="panel-hint">近 {{ days }} 天 · 按日聚合</span>
        </div>
        <div ref="chartRef" class="trend-chart"></div>
        <div v-if="!trend.length" class="empty-hint">当前时间范围暂无调用记录</div>
      </div>

      <div class="panel">
        <div class="panel-head"><span class="panel-title">状态分布</span></div>
        <div v-for="row in statusRows" :key="row.label" class="status-row">
          <div class="status-line">
            <span>{{ row.label }}</span>
            <span>{{ row.value.toLocaleString() }} · {{ row.rate }}%</span>
          </div>
          <div class="track"><div class="fill" :style="{ width: row.rate + '%', background: row.color }"></div></div>
        </div>
        <p class="panel-foot">分母为该时间窗内的调用总次数 {{ (overview.calls ?? 0).toLocaleString() }} 次。</p>
      </div>
    </div>

    <div class="overview-grid bottom-row">
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">用户 token 消耗排行</span>
          <span class="panel-hint">近 {{ days }} 天 · 前 5 名</span>
        </div>
        <div v-if="!topRanking.length" class="empty-hint">暂无排行数据</div>
        <div v-for="row in topRanking" :key="row.userId ?? row.rank" class="rank-row">
          <span class="rank-no">{{ row.rank }}</span>
          <span class="rank-name">{{ row.username || `用户 ${row.userId ?? '—'}` }}</span>
          <span class="rank-tokens">{{ Number(row.tokens ?? 0).toLocaleString() }}</span>
          <div class="track slim" :title="`今日配额已用 ${row.usageRate ?? 0}%`">
            <div class="fill" :style="{ width: Math.min(Number(row.usageRate ?? 0), 100) + '%', background: Number(row.usageRate ?? 0) >= 100 ? 'var(--accent-danger)' : 'var(--accent-primary)' }"></div>
          </div>
          <span class="rank-rate">{{ row.usageRate ?? 0 }}%</span>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">异常事件流</span>
          <button type="button" class="link-btn" @click="emit('navigate', { tab: 'logs' })">查看全部日志</button>
        </div>
        <div v-if="!anomalies.length" class="empty-hint">{{ anomalyNote || '暂无异常记录' }}</div>
        <div v-for="(row, index) in anomalies" :key="`${row.createdAt}-${index}`" class="event-row">
          <span class="event-time">{{ shortTime(row.createdAt) }}</span>
          <span class="event-status" :style="{ color: statusColor(row.status) }">{{ statusLabel(row.status) }}</span>
          <span class="event-detail">用户 {{ row.userId }} · {{ row.detail || row.scene || '—' }}</span>
          <button type="button" class="link-btn" @click="emit('navigate', { tab: 'logs', status: row.status })">定位</button>
        </div>
        <p class="panel-foot">{{ anomalyNote }}；不含成功的记录。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
// 概览面板：趋势图、真实状态分布、用户排行与异常事件流。
// 图表自行处理容器尺寸与 html.dark 主题切换，卸载时释放全部观察者与实例。
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import echarts from '@/utils/echarts'
import { getAiLogs } from '@/api/adminAi'
import { unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({
  overview: { type: Object, default: () => ({}) },
  trend: { type: Array, default: () => [] },
  ranking: { type: Array, default: () => [] },
  days: { type: Number, default: 7 }
})
const emit = defineEmits(['navigate'])

const chartRef = ref(null)
let chart = null
let resizeObserver = null
let themeObserver = null

const chartTheme = () => {
  const style = getComputedStyle(document.documentElement)
  const read = (name, fallback) => (style.getPropertyValue(name) || '').trim() || fallback
  return {
    text: read('--text-regular', '#656d76'),
    line: read('--border-light', '#e8eaed'),
    primary: read('--accent-primary', '#2563eb'),
    warning: read('--accent-warning', '#d97706')
  }
}

const renderChart = () => {
  nextTick(() => {
    if (!chartRef.value) return
    if (!chart) chart = echarts.init(chartRef.value)
    const theme = chartTheme()
    const axisLabel = { color: theme.text, fontSize: 11 }
    chart.setOption({
      animation: false,
      tooltip: { trigger: 'axis' },
      legend: { data: ['调用次数', 'token'], top: 0, textStyle: { color: theme.text } },
      grid: { left: 56, right: 62, top: 34, bottom: 30 },
      xAxis: {
        type: 'category',
        data: props.trend.map((t) => shortDate(t.day)),
        axisLabel,
        axisLine: { lineStyle: { color: theme.line } }
      },
      yAxis: [
        {
          type: 'value', name: '次数', nameTextStyle: { color: theme.text },
          axisLabel, splitLine: { lineStyle: { color: theme.line } }
        },
        {
          type: 'value', name: 'token', nameTextStyle: { color: theme.text },
          axisLabel, splitLine: { show: false }
        }
      ],
      series: [
        {
          name: '调用次数', type: 'line', smooth: true, symbolSize: 5,
          lineStyle: { color: theme.primary }, itemStyle: { color: theme.primary },
          data: props.trend.map((t) => Number(t.calls) || 0)
        },
        {
          name: 'token', type: 'line', smooth: true, symbolSize: 5, yAxisIndex: 1,
          lineStyle: { color: theme.warning }, itemStyle: { color: theme.warning },
          data: props.trend.map((t) => Number(t.tokens) || 0)
        }
      ]
    }, true)
    chart.resize()
  })
}

const shortDate = (value) => String(value ?? '').slice(5) || '—'
const shortTime = (value) => String(value ?? '').slice(11, 16) || '—'

const statusRows = computed(() => {
  const o = props.overview
  const total = Number(o.calls) || 0
  const rows = [
    { label: '成功', value: Number(o.success) || 0, color: 'var(--accent-success)' },
    { label: '失败', value: Number(o.fail) || 0, color: 'var(--accent-danger)' },
    { label: '用户停止', value: Number(o.cancelled) || 0, color: 'var(--text-secondary)' },
    { label: '配额拒绝', value: Number(o.quotaBlocked) || 0, color: 'var(--accent-warning)' },
    { label: '比赛拦截', value: Number(o.contestBlocked) || 0, color: 'var(--accent-info)' }
  ]
  return rows.map((row) => ({
    ...row,
    rate: total ? Math.round((row.value * 1000) / total) / 10 : 0
  }))
})

const topRanking = computed(() => props.ranking.slice(0, 5))

const statusLabel = (status) => ({
  SUCCESS: '成功', FAIL: '失败', CANCELLED: '用户停止',
  QUOTA_REJECTED: '配额拒绝', CONTEST_BLOCKED: '比赛拦截'
}[status] || status || '未知')

const statusColor = (status) => ({
  SUCCESS: 'var(--accent-success)', FAIL: 'var(--accent-danger)',
  CANCELLED: 'var(--text-secondary)', QUOTA_REJECTED: 'var(--accent-warning)',
  CONTEST_BLOCKED: 'var(--accent-warning)'
}[status] || 'var(--text-regular)')

// 后端日志只支持单一 status 过滤，这里读取最近一页并在前端筛出非成功记录，
// 标注为「最近 N 条中的异常」，避免把样本说成全量异常统计。
const anomalies = ref([])
const anomalyNote = ref('')
let anomalyGeneration = 0
const loadAnomalies = async () => {
  const version = ++anomalyGeneration
  try {
    const res = await getAiLogs({ page: 1, size: 20 })
    if (version !== anomalyGeneration) return
    const page = unwrapResultData(res) || {}
    const records = Array.isArray(page.records) ? page.records : Array.isArray(page) ? page : []
    anomalies.value = records.filter((row) => row.status && row.status !== 'SUCCESS').slice(0, 5)
    anomalyNote.value = `最近 ${records.length} 条调用`
  } catch (error) {
    if (version === anomalyGeneration) showApiError(error, '异常记录加载失败')
  }
}

watch(() => props.trend, renderChart)
watch(() => props.days, loadAnomalies)

onMounted(() => {
  renderChart()
  loadAnomalies()
  if (chartRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => chart && chart.resize())
    resizeObserver.observe(chartRef.value)
  }
  if (typeof MutationObserver !== 'undefined') {
    themeObserver = new MutationObserver(() => renderChart())
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  }
})

onUnmounted(() => {
  if (resizeObserver) resizeObserver.disconnect()
  if (themeObserver) themeObserver.disconnect()
  if (chart) chart.dispose()
  chart = null
})
</script>

<style scoped>
.overview-page { display: flex; flex-direction: column; gap: 12px; }
.overview-grid { display: grid; gap: 12px; min-width: 0; }
.top-row { grid-template-columns: minmax(0, 1.9fr) minmax(0, 1fr); }
.bottom-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.panel { min-width: 0; padding: 12px 14px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.panel-title { color: var(--text-primary); font-size: 14px; font-weight: 600; }
.panel-hint, .panel-foot { color: var(--text-secondary); font-size: 12px; }
.panel-foot { margin: 10px 0 0; }
.trend-chart { height: 260px; width: 100%; }
.empty-hint { padding: 18px 0; text-align: center; color: var(--text-secondary); font-size: 13px; }
.status-row { margin-bottom: 8px; }
.status-line { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-regular); }
.track { height: 6px; margin-top: 4px; background: var(--bg-surface-hover); border-radius: 3px; overflow: hidden; }
.track.slim { height: 5px; margin: 0; }
.fill { height: 100%; border-radius: 3px; transition: width .2s ease; }
.rank-row { display: grid; grid-template-columns: 24px minmax(0, 1fr) 80px 90px 46px; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--border-light); font-size: 12px; }
.rank-row:last-child { border-bottom: none; }
.rank-no { color: var(--text-secondary); }
.rank-name { overflow: hidden; color: var(--text-primary); text-overflow: ellipsis; white-space: nowrap; }
.rank-tokens, .rank-rate { text-align: right; color: var(--text-regular); }
.event-row { display: grid; grid-template-columns: 42px 72px minmax(0, 1fr) 36px; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--border-light); font-size: 12px; }
.event-row:last-of-type { border-bottom: none; }
.event-time { color: var(--text-secondary); }
.event-detail { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-regular); }
.link-btn { border: none; background: none; padding: 0; color: var(--accent-primary); font: inherit; font-size: 12px; cursor: pointer; }
.link-btn:hover { text-decoration: underline; }
.link-btn:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 2px; }
@media (max-width: 1000px) { .top-row, .bottom-row { grid-template-columns: minmax(0, 1fr); } }
</style>
