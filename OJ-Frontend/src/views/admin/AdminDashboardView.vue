<template>
  <div class="admin-dashboard admin-scroll-page" v-loading="loading">
    <h2 style="margin-bottom: 20px;">运维仪表盘</h2>

    <el-row :gutter="20">
      <el-col :span="6" v-for="card in statsCards" :key="card.label">
        <el-card shadow="hover" style="text-align: center; margin-bottom: 16px;">
          <div style="font-size: 28px; font-weight: bold; color: var(--accent-primary);">{{ card.value }}</div>
          <div style="font-size: 13px; color: var(--text-secondary); margin-top: 4px;">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>判题任务状态</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="积压任务">{{ data.pendingJudgeTasks ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="失败任务">{{ data.failedJudgeTasks ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="最近提交">{{ data.latestSubmission }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>数据库信息</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="当前时间">{{ data.dbTime }}</el-descriptions-item>
            <el-descriptions-item label="总用户数">{{ data.totalUsers }}</el-descriptions-item>
            <el-descriptions-item label="总题目数">{{ data.totalProblems }}</el-descriptions-item>
            <el-descriptions-item label="总提交数">{{ data.totalSubmissions }}</el-descriptions-item>
            <el-descriptions-item label="总比赛数">{{ data.totalContests }}</el-descriptions-item>
            <el-descriptions-item label="今日提交">{{ data.todaySubmissions }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 20px;">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>近 7 天趋势（提交 / 判题失败 / AI 调用）</span>
          <el-button size="small" @click="loadTrends">刷新</el-button>
        </div>
      </template>
      <div ref="trendChartRef" style="height: 300px;"></div>
    </el-card>
  </div>
</template>

<script setup>
// 运维仪表盘（仅管理员）：平台规模概览 + 近 7 天趋势图（提交数 / 判题失败 / AI 调用）。
// 纯只读看板，任何请求失败都只记 console 不弹 toast：
// 运维场景下页面本身要尽量保持可用，弹窗会挡住其他指标。
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { getAdminStatus, getAdminTrends } from '@/api/adminDashboard'
import echarts from '@/utils/echarts'

const loading = ref(true)
const data = ref({})
const trends = ref({})
const trendChartRef = ref(null)
let chart = null

const statsCards = computed(() => [
  { label: '用户总数', value: data.value.totalUsers ?? '-' },
  { label: '题目总数', value: data.value.totalProblems ?? '-' },
  { label: '提交总数', value: data.value.totalSubmissions ?? '-' },
  { label: '今日提交', value: data.value.todaySubmissions ?? '-' }
])

onMounted(async () => {
  try {
    const res = await getAdminStatus()
    if (res.code === 200) data.value = res.data
    loadTrends()
  } catch (e) { console.error(e) }
  finally { loading.value = false }
})

const loadTrends = async () => {
  try {
    const res = await getAdminTrends(7)
    if (res.code === 200) {
      trends.value = res.data || {}
      renderTrends()
    }
  } catch (e) { console.error(e) }
}

const renderTrends = () => {
  nextTick(() => {
    if (!trendChartRef.value) return
    if (!chart) chart = echarts.init(trendChartRef.value)
    // 后端可能只返回有数据的那几天，前端补齐完整 7 天（缺失补 0），
    // 否则 X 轴会跳过空白日，把"周三没提交"画成连续上涨的趋势。
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = new Date()
      d.setDate(d.getDate() - (6 - i))
      return `${d.getMonth() + 1}-${d.getDate()}`
    })
    const byDay = (rows) => {
      const map = {}
      ;(rows || []).forEach((r) => { map[String(r.day).slice(5)] = r.cnt ?? r.calls ?? 0 })
      return days.map((d) => map[d] ?? 0)
    }
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['提交数', '判题失败', 'AI 调用'] },
      grid: { left: 60, right: 40, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: days },
      yAxis: { type: 'value' },
      series: [
        { name: '提交数', type: 'line', smooth: true, data: byDay(trends.value.submissions) },
        { name: '判题失败', type: 'line', smooth: true, data: byDay(trends.value.judgeFailed) },
        { name: 'AI 调用', type: 'line', smooth: true, data: byDay(trends.value.ai) }
      ]
    })
  })
}

// ECharts 实例必须在卸载时 dispose，否则 ResizeObserver 与内部定时器会一直存活。
onUnmounted(() => chart && chart.dispose())
</script>

<style scoped>
.admin-dashboard { padding: 20px; }
</style>
