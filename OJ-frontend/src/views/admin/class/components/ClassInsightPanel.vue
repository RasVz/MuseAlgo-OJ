<template>
  <div class="class-insight-panel">
    <div class="insight-header">
      <div class="insight-title">班级学情解读</div>
      <el-radio-group v-model="days" size="small" @change="load">
        <el-radio-button :value="7">近一周</el-radio-button>
        <el-radio-button :value="30">近一月</el-radio-button>
      </el-radio-group>
    </div>
    <div class="insight-sub">基于班级近 {{ days }} 天真实提交数据自动统计</div>

    <div v-loading="loading" class="overview-grid">
      <div class="ov-card">
        <div class="ov-num">{{ fmtNum(overview.totalSubmissions) }}</div>
        <div class="ov-label">总提交</div>
      </div>
      <div class="ov-card">
        <div class="ov-num">{{ fmtNum(overview.acRate) }}%</div>
        <div class="ov-label">AC 率</div>
      </div>
      <div class="ov-card">
        <div class="ov-num">{{ fmtNum(overview.activeStudentCount) }}<span class="ov-sub">/{{ fmtNum(memberCount) }}</span></div>
        <div class="ov-label">活跃学生</div>
      </div>
      <div class="ov-card">
        <div class="ov-num">{{ fmtNum(overview.avgDailySubmissions) }}</div>
        <div class="ov-label">日均提交</div>
      </div>
    </div>

    <div class="chart-card">
      <div class="chart-title">每日提交趋势</div>
      <div v-if="hasData" ref="trendRef" class="chart-box chart-box-wide"></div>
      <el-empty v-else-if="!loading" description="近 {{ days }} 天暂无提交数据" :image-size="56" />
    </div>

    <div class="charts-grid">
      <div class="chart-card">
        <div class="chart-title">题目提交与通过</div>
        <div class="chart-sub">按提交量取前 8 题</div>
        <div v-if="hasData" ref="problemRef" class="chart-box"></div>
        <el-empty v-else-if="!loading" description="暂无题目数据" :image-size="56" />
      </div>

      <div class="chart-card">
        <div class="chart-title">学生活跃分层</div>
        <div class="chart-sub">活跃 ≥5 次 / 一般 1~4 次 / 沉寂 0 次</div>
        <div v-if="hasData" ref="tierRef" class="chart-box"></div>
        <el-empty v-else-if="!loading" description="暂无分层数据" :image-size="56" />
      </div>
    </div>

    <div class="ai-card">
      <div class="ai-head">
        <span class="ai-title">AI 学情解读</span>
        <el-button size="small" type="primary" :loading="aiLoading" :disabled="!hasData" @click="runAi">
          ✨ 生成解读
        </el-button>
      </div>
      <div v-if="aiOutput" class="ai-output markdown-body custom-md" v-html="renderMarkdown(aiOutput)" />
      <div v-else class="ai-placeholder">点击「生成解读」，AI 将基于以上统计数据输出班级整体水平、薄弱环节与教学建议</div>
    </div>
  </div>
</template>

<script setup>
// 班级学情洞察面板：教师查看本班在指定时间窗内的提交趋势、题目与活跃度分布，
// 并可一键让 AI 生成文字解读。
//
// 面板内含三个 ECharts 实例，随班级 / 天数变化重绘，随窗口 resize 重排。
// 所有监听与实例都在 onBeforeUnmount 里成对释放——这是本组件最容易出泄漏的地方。
import { ref, computed, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import echarts from '@/utils/echarts'
import { createMarkdown } from '@/utils/markdown'
import { getClassInsight, generateClassInsightAi } from '@/api/classInsight'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'

const props = defineProps({
  classId: {
    type: [Number, String],
    required: true
  }
})

const md = createMarkdown({ html: false, math: true, breaks: true })
const renderMarkdown = (text) => (text ? md.render(text) : '')

const days = ref(7)
const loading = ref(false)
const aiLoading = ref(false)
const stats = ref(null)
const aiOutput = ref('')

const trendRef = ref(null)
const problemRef = ref(null)
const tierRef = ref(null)
let trendChart = null
let problemChart = null
let tierChart = null

const overview = computed(() => stats.value?.overview ?? {})
const memberCount = computed(() => stats.value?.memberCount ?? 0)
const hasData = computed(() => {
  const total = Number(overview.value.totalSubmissions ?? 0)
  return total > 0
})

const fmtNum = (v) => {
  const num = Number(v)
  return Number.isFinite(num) ? num : '-'
}

const toNumber = (v, d = 0) => {
  const num = Number(v)
  return Number.isFinite(num) ? num : d
}

const shortDate = (d) => {
  const parts = String(d).split('-')
  return parts.length === 3 ? `${parts[1]}-${parts[2]}` : String(d)
}

// ---------------- charts ----------------
// ECharts 实例只 init 一次并复用：重复 init 同一容器会产生两个实例互相覆盖，
// 后 setOption 的那个渲染，前一个白占内存。
const ensureCharts = () => {
  if (trendRef.value && !trendChart) trendChart = echarts.init(trendRef.value)
  if (problemRef.value && !problemChart) problemChart = echarts.init(problemRef.value)
  if (tierRef.value && !tierChart) tierChart = echarts.init(tierRef.value)
}

const renderTrend = () => {
  if (!trendChart || !stats.value) return
  const daily = stats.value.daily ?? []
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['提交数', 'AC 数'], top: 0, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11 } },
    grid: { top: 28, left: 38, right: 14, bottom: 26 },
    xAxis: {
      type: 'category',
      data: daily.map(d => shortDate(d.submitDate)),
      axisLabel: { fontSize: 10, interval: Math.ceil(daily.length / 8) }
    },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { fontSize: 10 } },
    series: [
      { name: '提交数', type: 'bar', barMaxWidth: 18, data: daily.map(d => toNumber(d.submitCount)), itemStyle: { color: '#409EFF', borderRadius: [3, 3, 0, 0] } },
      { name: 'AC 数', type: 'line', smooth: true, symbolSize: 5, data: daily.map(d => toNumber(d.acCount)), itemStyle: { color: '#67C23A' }, lineStyle: { width: 2 } }
    ]
  })
}

const renderProblems = () => {
  if (!problemChart || !stats.value) return
  const rows = (stats.value.problems ?? []).slice(0, 8)
  const names = rows.map(p => {
    const t = String(p.problemTitle ?? '未知')
    return t.length > 8 ? t.slice(0, 8) + '…' : t
  })
  problemChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const idx = params?.[0]?.dataIndex
        const p = rows[idx]
        if (!p) return ''
        return `${p.problemTitle}<br/>提交 ${p.submitCount} 次 / AC ${p.acCount} 次<br/>通过率 ${p.passRate}%`
      }
    },
    legend: { data: ['提交数', '通过率(%)'], top: 0, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11 } },
    grid: { top: 28, left: 38, right: 38, bottom: 40 },
    xAxis: { type: 'category', data: names, axisLabel: { fontSize: 10, interval: 0, rotate: 30 } },
    yAxis: [
      { type: 'value', name: '提交', minInterval: 1, axisLabel: { fontSize: 10 } },
      { type: 'value', name: '%', max: 100, axisLabel: { fontSize: 10 }, splitLine: { show: false } }
    ],
    series: [
      { name: '提交数', type: 'bar', barMaxWidth: 22, data: rows.map(p => toNumber(p.submitCount)), itemStyle: { color: '#409EFF', borderRadius: [3, 3, 0, 0] } },
      { name: '通过率(%)', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 5, data: rows.map(p => toNumber(p.passRate)), itemStyle: { color: '#E6A23C' }, lineStyle: { width: 2 } }
    ]
  })
}

const TIER_COLORS = { active: '#67C23A', ordinary: '#409EFF', silent: '#C0C4CC' }

const renderTiers = () => {
  if (!tierChart || !stats.value) return
  const tiers = stats.value.studentTiers ?? {}
  const data = [
    { name: `活跃 ${tiers.active ?? 0} 人`, value: toNumber(tiers.active) },
    { name: `一般 ${tiers.ordinary ?? 0} 人`, value: toNumber(tiers.ordinary) },
    { name: `沉寂 ${tiers.silent ?? 0} 人`, value: toNumber(tiers.silent) }
  ].filter(d => d.value > 0)
  tierChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 人 ({d}%)' },
    legend: { bottom: 0, itemWidth: 12, itemHeight: 8, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: data.map(d => ({ ...d, itemStyle: { color: TIER_COLORS[d.name.split(' ')[0]] } }))
    }]
  })
}

const renderAllCharts = async () => {
  await nextTick()
  ensureCharts()
  renderTrend()
  renderProblems()
  renderTiers()
}

// dispose 后必须置 null：否则下次 renderAllCharts 里的 `!trendChart` 判断会认为
// 实例还在，直接对已销毁的实例 setOption 而画不出图。
const disposeCharts = () => {
  trendChart?.dispose(); trendChart = null
  problemChart?.dispose(); problemChart = null
  tierChart?.dispose(); tierChart = null
}

const handleResize = () => {
  trendChart?.resize()
  problemChart?.resize()
  tierChart?.resize()
}

// ---------------- data ----------------
const load = async () => {
  if (!props.classId) return
  loading.value = true
  aiOutput.value = ''
  try {
    const res = await getClassInsight(props.classId, days.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '获取班级学情失败'))
      stats.value = null
      return
    }
    stats.value = unwrapResultData(res) || null
    renderAllCharts()
  } catch (e) {
    console.error(e)
    ElMessage.error('获取班级学情失败')
    stats.value = null
  } finally {
    loading.value = false
  }
}

// AI 解读要花钱且耗时，重复点击会重复计费，因此用 aiLoading 做互斥。
const runAi = async () => {
  if (!hasData.value || aiLoading.value) return
  aiLoading.value = true
  try {
    const res = await generateClassInsightAi(props.classId, days.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '解读生成失败'))
      return
    }
    aiOutput.value = unwrapResultData(res) || '（空）'
  } catch (e) {
    console.error(e)
    ElMessage.error('解读生成失败，请稍后重试')
  } finally {
    aiLoading.value = false
  }
}

// 班级或统计天数变化 → 重新拉取并重绘（天数是后端聚合的维度，前端无法本地重算）
watch(() => props.classId, () => { load() })
watch(days, () => { load() })

onMounted(() => {
  load()
  // ECharts 不监听容器尺寸变化，窗口缩放后必须手动 resize，否则图表被裁切
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  disposeCharts()
})
</script>

<style scoped>
.class-insight-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 200px;
  max-width: 1200px;
  margin: 0 auto;
  padding: 4px 0;
}
.insight-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.insight-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.insight-sub {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: -6px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  min-height: 62px;
}
.ov-card {
  background: var(--bg-surface-soft);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  padding: 14px 8px;
  text-align: center;
}
.ov-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}
.ov-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-secondary);
}
.ov-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 4px;
}

.chart-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 14px 16px 10px;
}
.chart-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.chart-sub {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.charts-grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 12px;
}
.chart-box {
  width: 100%;
  height: 240px;
  margin-top: 6px;
}
.chart-box-wide {
  height: 240px;
}
@media (max-width: 900px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .overview-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.ai-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 14px 16px;
}
.ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ai-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.ai-placeholder {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
  padding: 12px 2px 4px;
}
.ai-output {
  font-size: 13px;
  color: var(--text-primary);
  line-height: 1.8;
  padding-top: 10px;
}
</style>
