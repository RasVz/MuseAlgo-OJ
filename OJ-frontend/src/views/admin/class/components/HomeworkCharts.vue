<template>
  <div class="homework-charts">
    <el-card shadow="never" class="chart-card">
      <template #header>
        <div class="card-header">
          <span>单题学生提交次数</span>
          <span class="card-header-extra">
            {{ selectedProblem ? `当前题目：${selectedProblem.problemTitle}` : '请先选择题目' }}
          </span>
        </div>
      </template>

      <div v-if="selectedProblem && students.length" ref="problemChartRef" class="chart-box chart-box-bar"></div>
      <el-empty v-else description="暂无单题统计数据" />
    </el-card>

    <el-card shadow="never" class="chart-card">
      <template #header>
        <div class="card-header">
          <span>全班作业完成矩阵</span>
          <span class="card-header-extra">X 轴：题目 / Y 轴：学生 / 绿色格子表示最终 AC</span>
        </div>
      </template>

      <div v-if="students.length && problems.length" ref="heatmapChartRef" class="chart-box chart-box-heatmap"></div>
      <el-empty v-else description="暂无班级作业数据" />
    </el-card>
  </div>
</template>

<script setup>
// 作业学情图表：上方"某题全班提交次数"柱状图 + 下方"学生 × 题目"提交热力图。
//
// 两个图共享同一份 statsMap（`${studentId}_${problemId}` 为键），
// 因为后端只提供这种扁平结构，前端需要自己查表补全没有提交的格子（返回全 0 的占位）。
// 图表支持点击联动：点柱状图某根柱子或热力图某个格子都会向上抛事件，由父组件统一切换选中题目。
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  students: { type: Array, required: true },
  problems: { type: Array, required: true },
  statsMap: { type: Object, required: true },
  selectedProblem: { type: Object, default: null },
  maxSubmitCount: { type: Number, default: 0 }
})

const emit = defineEmits(['select-cell', 'select-problem-axis', 'select-student-axis', 'select-bar'])

const problemChartRef = ref(null)
const heatmapChartRef = ref(null)
let problemChart = null
let heatmapChart = null

// --- helpers ---
const toNumber = (value, defaultValue = 0) => {
  const num = Number(value)
  return Number.isFinite(num) ? num : defaultValue
}

// statsMap 里没有的格子必须补一个全 0 占位：热力图要求矩阵完整，
// 缺格子会导致整块色阶错位（学生与题目对不上号）。
const getCellStat = (studentId, problemId) => {
  return props.statsMap[`${studentId}_${problemId}`] || {
    studentId, problemId, submitCount: 0, acCount: 0, finalAc: 0, lastSubmitTime: null
  }
}

const mixChannel = (start, end, ratio) => Math.round(start + (end - start) * ratio)

const buildHeatColor = (submitCount, finalAc) => {
  if (submitCount <= 0) return finalAc === 1 ? '#dcfce7' : '#f8fafc'
  const ratio = props.maxSubmitCount > 0 ? Math.min(submitCount / props.maxSubmitCount, 1) : 0
  const finalRatio = 0.3 + ratio * 0.7
  const start = finalAc === 1 ? [220, 252, 231] : [219, 234, 254]
  const end = finalAc === 1 ? [22, 163, 74] : [29, 78, 216]
  return `rgb(${mixChannel(start[0], end[0], finalRatio)}, ${mixChannel(start[1], end[1], finalRatio)}, ${mixChannel(start[2], end[2], finalRatio)})`
}

const getHeatCellStyle = (submitCount, finalAc) => ({
  color: buildHeatColor(submitCount, finalAc),
  borderColor: finalAc === 1 ? '#16a34a' : '#bfdbfe',
  borderWidth: finalAc === 1 ? 2 : 1
})

// --- chart lifecycle ---
function ensureCharts() {
  if (problemChartRef.value && !problemChart) problemChart = echarts.init(problemChartRef.value)
  if (heatmapChartRef.value && !heatmapChart) heatmapChart = echarts.init(heatmapChartRef.value)
}

const renderProblemChart = async () => {
  await nextTick()
  ensureCharts()
  if (!problemChart || !props.selectedProblem) return

  const rows = props.students.map(student => {
    const cell = getCellStat(student.studentId, props.selectedProblem.problemId)
    return { studentId: student.studentId, studentName: student.studentName, submitCount: toNumber(cell.submitCount), finalAc: toNumber(cell.finalAc) }
  })

  // 点击监听必须先 off 再 on：selectedProblem 变化会反复重跑本函数，
// 不解绑会叠加多个处理器，导致一次点击触发多次 emit。
  problemChart.off('click')
  problemChart.on('click', params => {
    if (params.componentType === 'series') {
      const data = params.data || {}
      emit('select-bar', { studentId: data.studentId, problemId: props.selectedProblem.problemId })
    }
  })

  problemChart.setOption({
    animation: true,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: params => {
        const item = params?.[0]
        if (!item) return ''
        const d = item.data || {}
        return `学生：${d.studentName || '-'}<br/>提交次数：${d.value ?? 0}<br/>最终 AC：${d.finalAc ? '是' : '否'}`
      }
    },
    grid: { top: 40, left: 50, right: 20, bottom: props.students.length > 8 ? 90 : 50 },
    xAxis: { type: 'category', data: rows.map(r => r.studentName), axisLabel: { interval: 0, rotate: props.students.length > 8 ? 35 : 0 } },
    yAxis: { type: 'value', name: '提交次数', minInterval: 1 },
    dataZoom: props.students.length > 12 ? [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', xAxisIndex: 0, height: 12, bottom: 10 }] : [],
    series: [{
      name: '提交次数', type: 'bar', barMaxWidth: 36,
      label: { show: true, position: 'top' },
      data: rows.map(item => ({
        value: item.submitCount, studentId: item.studentId, studentName: item.studentName,
        problemId: props.selectedProblem.problemId, finalAc: item.finalAc,
        itemStyle: { color: item.finalAc ? '#67C23A' : '#409EFF' }
      }))
    }]
  }, true)
}

const renderHeatmapChart = async () => {
  await nextTick()
  ensureCharts()
  if (!heatmapChart || !props.students.length || !props.problems.length) { heatmapChart?.clear(); return }

  const heatmapData = []
  props.problems.forEach((problem, xIndex) => {
    props.students.forEach((student, yIndex) => {
      const cell = getCellStat(student.studentId, problem.problemId)
      const sc = toNumber(cell.submitCount)
      const ac = toNumber(cell.finalAc)
      heatmapData.push({
        value: [xIndex, yIndex, sc],
        studentId: student.studentId, studentName: student.studentName,
        problemId: problem.problemId, problemTitle: problem.problemTitle,
        finalAc: ac, submitCount: sc,
        itemStyle: getHeatCellStyle(sc, ac)
      })
    })
  })

  heatmapChart.off('click')
  heatmapChart.on('click', params => {
    if (params.componentType === 'series') {
      const d = params.data || {}
      if (d.studentId && d.problemId) emit('select-cell', { studentId: d.studentId, problemId: d.problemId })
      return
    }
    if (params.componentType === 'xAxis') {
      const idx = params.dataIndex ?? props.problems.findIndex(p => p.problemTitle === params.value)
      const p = props.problems[idx]
      if (p) emit('select-problem-axis', p.problemId)
      return
    }
    if (params.componentType === 'yAxis') {
      const idx = params.dataIndex ?? props.students.findIndex(s => s.studentName === params.value)
      const s = props.students[idx]
      if (s) emit('select-student-axis', s.studentId)
    }
  })

  heatmapChart.setOption({
    animation: true,
    tooltip: {
      formatter: params => {
        const d = params.data || {}
        return `学生：${d.studentName || '-'}<br/>题目：${d.problemTitle || '-'}<br/>提交次数：${d.submitCount ?? 0}<br/>最终 AC：${toNumber(d.finalAc) === 1 ? '是' : '否'}`
      }
    },
    grid: { top: 50, left: 120, right: 70, bottom: 110 },
    legend: { top: 8, data: ['提交次数'] },
    xAxis: { type: 'category', data: props.problems.map(p => p.problemTitle), triggerEvent: true, splitArea: { show: true }, axisLabel: { interval: 0, rotate: props.problems.length > 6 ? 30 : 0 } },
    yAxis: { type: 'category', data: props.students.map(s => s.studentName), triggerEvent: true, splitArea: { show: true }, axisLabel: { width: 100, overflow: 'truncate' } },
    visualMap: { show: false, min: 0, max: props.maxSubmitCount > 0 ? props.maxSubmitCount : 1, dimension: 2, seriesIndex: 0, inRange: { color: ['#f8fafc', '#dbeafe', '#93c5fd', '#2563eb'] } },
    dataZoom: [
      { type: 'inside', xAxisIndex: 0 }, { type: 'inside', yAxisIndex: 0 },
      { type: 'slider', xAxisIndex: 0, bottom: 20, height: 14 }, { type: 'slider', yAxisIndex: 0, right: 20, width: 14 }
    ],
    series: [{
      name: '提交次数', type: 'heatmap', data: heatmapData,
      label: {
        show: true, position: 'inside', fontSize: 24, fontWeight: 900,
        formatter: params => {
          const count = params.data?.submitCount ?? 0
          if (count <= 0) return ''
          const isAc = toNumber(params.data?.finalAc) === 1
          const useLight = count >= Math.max(2, Math.ceil(props.maxSubmitCount * 0.3))
          if (isAc) return useLight ? `{acLight|${count}}` : `{acDark|${count}}`
          return useLight ? `{light|${count}}` : `{dark|${count}}`
        },
        rich: {
          acLight: { color: '#fff', fontSize: 24, fontWeight: 900, textShadowColor: 'rgba(0,0,0,0.45)', textShadowBlur: 10 },
          acDark: { color: '#14532d', fontSize: 24, fontWeight: 900, textBorderColor: 'rgba(255,255,255,1)', textBorderWidth: 3 },
          light: { color: '#fff', fontSize: 24, fontWeight: 900, textShadowColor: 'rgba(0,0,0,0.45)', textShadowBlur: 10 },
          dark: { color: '#0f172a', fontSize: 24, fontWeight: 900, textBorderColor: 'rgba(255,255,255,1)', textBorderWidth: 3 }
        }
      },
      emphasis: { itemStyle: { shadowBlur: 16, shadowColor: 'rgba(0,0,0,0.28)' } }
    }]
  }, true)
}

const renderAll = async () => {
  await renderProblemChart()
  await renderHeatmapChart()
}

const resize = () => {
  problemChart?.resize()
  heatmapChart?.resize()
}

// 选中题目变化只影响柱状图（热力图是全局的，不该跟着筛选）；
// 学生或题目列表变化才需要两张图一起重画。
watch(() => props.selectedProblem, () => renderProblemChart())
watch([() => props.students, () => props.problems], () => renderAll(), { deep: true })

onMounted(() => {
  window.addEventListener('resize', resize)
  nextTick(() => renderAll())
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  problemChart?.dispose()
  heatmapChart?.dispose()
  // 置 null 是为了避免热重载时复用到已销毁实例
  problemChart = null
  heatmapChart = null
})

defineExpose({ renderProblemChart, renderAll })
</script>

<style scoped>
.chart-card { border-radius: 12px; margin-bottom: 16px; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.card-header-extra { font-size: 13px; color: var(--text-secondary); }
.chart-box { width: 100%; }
.chart-box-bar { height: 360px; }
.chart-box-heatmap { height: 620px; }

@media (max-width: 768px) {
  .chart-box-bar { height: 320px; }
  .chart-box-heatmap { height: 520px; }
}
</style>
