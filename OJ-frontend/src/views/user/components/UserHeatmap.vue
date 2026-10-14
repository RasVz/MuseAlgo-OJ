<template>
  <el-card shadow="never" class="heatmap-card mb-20">
    <div class="heatmap-header">
      <span class="heatmap-title">活跃度（最近 365 天）</span>
      <span class="heatmap-sub">过去一年共提交 {{ displayTotal }} 次</span>
    </div>
    <div class="heatmap-scroll">
      <div class="heatmap-grid">
        <el-tooltip 
          v-for="(day, idx) in heatmapData" 
          :key="idx"
          :content="`${day.date}: ${day.count} 次提交`"
          placement="top"
          :show-after="500"
        >
          <div class="h-cell" :style="{ background: getHeatmapColor(day.count) }"></div>
        </el-tooltip>
      </div>
    </div>
    <div class="heatmap-legend">
      <span>Less</span>
      <div class="l-cell level-0"></div>
      <div class="l-cell level-1"></div>
      <div class="l-cell level-2"></div>
      <div class="l-cell level-3"></div>
      <div class="l-cell level-4"></div>
      <span>More</span>
    </div>
  </el-card>
</template>

<script setup>
// 提交热力图（GitHub 风格）：展示最近 365 天每天的提交量。
// 固定 365 格而不是按自然年对齐：日历热力图的列必须从固定星期几开始，
// 按天倒推会随日期漂移，整张图无法横向比较。
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getUserHeatmap } from '@/api/user'

const props = defineProps({
  userId: { type: [Number, String], default: null },
  total: { type: Number, default: 0 },
  year: { type: Number, default: new Date().getFullYear() }
})

const route = useRoute()
const heatmapData = ref([])
const displayTotal = ref(0)

// userId 允许不传而从路由推导，这样组件可以直接用在个人设置页等无路由 userId 的场景。
const resolvedUserId = computed(() => {
  const value = props.userId ?? route.params.id ?? route.params.userId ?? route.query.userId
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
})

const formatDate = (date) => {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

// 接口无数据或失败时也要画满 365 格：
// 空白热力图和"加载失败"在这里长得一样，用户反而看不出图是否正常。
// 365 = 52 周 × 7 天 + 1，保证整周对齐。
const buildEmptyHeatmap = () => {
  const data = []
  const today = new Date()

  for (let offset = 364; offset >= 0; offset--) {
    const date = new Date(today)
    date.setDate(today.getDate() - offset)
    data.push({
      date: formatDate(date),
      count: 0
    })
  }

  heatmapData.value = data
  displayTotal.value = 0
}

const loadHeatmap = async () => {
  if (!resolvedUserId.value) {
    buildEmptyHeatmap()
    return
  }

  try {
    const res = await getUserHeatmap(resolvedUserId.value)
    if (res.code === 200 && res.data) {
      heatmapData.value = Array.isArray(res.data.days) ? res.data.days : []
      displayTotal.value = Number(res.data.total ?? 0)

      if (!heatmapData.value.length) {
        buildEmptyHeatmap()
      }
      return
    }
  } catch (error) {
    console.error('获取用户活跃图失败', error)
  }

  buildEmptyHeatmap()
}

// userId 变化（从 A 的资料页跳到 B）→ 重新拉热力图。
// immediate 保证首次进入也会加载。
watch(
  resolvedUserId,
  () => {
    loadHeatmap()
  },
  { immediate: true }
)

// 五档固定色阶（0 / 1-2 / 3-4 / 5-6 / 7+）：
// 不用连续插值是因为少量提交的日子必须一眼可辨，插值会让低档全是相近的浅色。
const getHeatmapColor = (count) => {
  if (count === 0) return '#ebedf0'
  if (count <= 2) return '#9be9a8'
  if (count <= 4) return '#40c463'
  if (count <= 6) return '#30a14e'
  return '#216e39'
}
</script>

<style scoped>
.heatmap-card { border-radius: 12px; border: none; }
.heatmap-header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 15px; padding: 0 5px; }
.heatmap-title { font-weight: bold; font-size: 16px; color: #303133; }
.heatmap-sub { font-size: 12px; color: #909399; }

.heatmap-scroll { overflow-x: auto; padding-bottom: 5px; }
.heatmap-grid {
  display: flex; flex-direction: column; flex-wrap: wrap; height: 100px; 
  align-content: flex-start; gap: 3px; 
}
.h-cell { width: 11px; height: 11px; border-radius: 2px; }

.heatmap-legend { display: flex; justify-content: flex-end; gap: 4px; font-size: 12px; color: #909399; padding-top: 10px; }
.l-cell { width: 11px; height: 11px; border-radius: 2px; }
.level-0 { background: #ebedf0; }
.level-1 { background: #9be9a8; }
.level-2 { background: #40c463; }
.level-3 { background: #30a14e; }
.level-4 { background: #216e39; }
.mb-20 { margin-bottom: 20px; }
</style>
