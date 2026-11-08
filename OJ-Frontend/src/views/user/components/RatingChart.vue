<template>
  <el-card shadow="never" class="chart-card mb-20" :body-style="{ padding: '0' }">
    <div class="cf-chart-header">
      <span class="chart-title"><el-icon><TrendCharts /></el-icon> 积分趋势 (Rating)</span>
      <div class="header-info">
        <span class="tip-text" v-if="data.length > 0">
          <el-icon class="icon-tip"><Mouse /></el-icon> 滚轮双轴缩放 / 按住全向拖拽 / 双击重置
        </span>
        <span class="max-rating">最高: <span class="highlight">{{ maxRatingVal }}</span></span>
        <span class="curr-rating">当前: <span class="highlight">{{ currentRatingVal }}</span></span>
        <span class="tip-text" v-if="data.length === 0">暂无比赛记录</span>
      </div>
    </div>
    
    <div 
      class="cf-chart-container" 
      ref="chartContainer" 
      :style="{ cursor: isDragging ? 'grabbing' : 'grab' }"
      @wheel.prevent="handleWheel" 
      @mousedown.prevent="startDrag"
      @mousemove.prevent="onDrag"
      @mouseup="stopDrag"
      @mouseleave="stopDrag"
      @dblclick="resetZoom"
    >
      
      <div v-if="hoverData && !isDragging" class="my-tooltip" :style="tooltipStyle">
        <div class="tt-name">{{ hoverData.contestName }}</div>
        <div class="tt-info">
          Rating: <b>{{ hoverData.rating }}</b> ({{ hoverData.ratingChange > 0 ? '+' + hoverData.ratingChange : hoverData.ratingChange }})
        </div>
        <div class="tt-date">排名: {{ hoverData.rank }}</div>
        <div class="tt-date">{{ hoverData.date }}</div>
      </div>

      <svg viewBox="0 0 800 300" class="cf-svg" preserveAspectRatio="none">
        <rect x="0" y="0" width="800" height="300" fill="#ffffff" />

        <rect 
          v-for="band in visibleBands" 
          :key="band.color"
          x="0" 
          :y="band.y" 
          width="800" 
          :height="band.height" 
          :fill="band.color" 
          :opacity="band.opacity"
        />

        <line 
          v-for="band in visibleBands" 
          :key="'line-'+band.color"
          x1="0" 
          :y1="band.bottomY" 
          x2="800" 
          :y2="band.bottomY" 
          stroke="#fff" 
          stroke-width="1" 
          stroke-opacity="0.5" 
          stroke-dasharray="4 4"
        />

        <text
          v-for="band in visibleBands"
          v-show="band.min > 0" 
          :key="'label-'+band.min"
          x="5"
          :y="band.bottomY - 3"
          class="rating-label"
        >
          {{ band.min }}
        </text>

        <line 
           v-for="line in gridLinesX"
           :key="'grid-'+line.x"
           :x1="line.x"
           y1="0"
           :x2="line.x"
           y2="300"
           stroke="#000"
           stroke-opacity="0.15"
           stroke-width="1"
        />

        <polyline 
          fill="none" 
          stroke="#ffcc00" 
          stroke-width="3" 
          :points="chartPoints" 
          style="filter: drop-shadow(1px 1px 2px rgba(0,0,0,0.3));"
        />

        <circle 
          v-for="pt in visiblePoints" 
          :key="pt.index" 
          :cx="pt.x" 
          :cy="pt.y" 
          r="4" 
          fill="#fff" 
          stroke="#ffcc00" 
          stroke-width="2"
          class="chart-dot"
          @mouseenter="handleMouseEnter($event, pt.originData, pt)" 
          @mouseleave="handleMouseLeave"
          @click="goToContestDetail(pt.originData)"
        />

        <!-- 空状态占位 -->
        <g v-if="data.length === 0">
          <text x="400" y="145" text-anchor="middle" fill="#c0c4cc" font-size="14" font-family="Arial, sans-serif">暂无比赛记录</text>
          <text x="400" y="168" text-anchor="middle" fill="#dcdfe6" font-size="11" font-family="Arial, sans-serif">参加比赛后将显示 Rating 变化曲线</text>
        </g>
      </svg>
    </div>
  </el-card>
</template>

<script setup>
// Rating 趋势图（SVG 手绘）：背景色带对应难度档位，支持滚轮缩放与拖拽平移。
// 坐标换算、双轴缩放与点集计算全部在 useRatingChart 里，本组件只负责事件绑定与 Tooltip 定位。
import { ref } from 'vue'
import { toRaw } from 'vue'
import { TrendCharts, Mouse } from '@element-plus/icons-vue'
import { useRouter } from "vue-router"
import { useRatingChart } from '../composables/useRatingChart'

const router = useRouter()

const props = defineProps({
  data: { type: Array, default: () => [] }
})

const {
  isDragging,
  visibleBands,
  gridLinesX,
  visiblePoints,
  chartPoints,
  maxRatingVal,
  currentRatingVal,
  handleWheel,
  startDrag,
  onDrag,
  stopDrag,
  resetZoom
} = useRatingChart(props.data)

// === Tooltip ===
const hoverData = ref(null)
const tooltipStyle = ref({ top: '0', left: '0' })

// 拖拽过程中屏蔽 Tooltip：这时坐标还在变，弹出来的提示会不停乱跳。
const handleMouseEnter = (e, item, point) => {
  if (isDragging.value) return
  hoverData.value = item
  // 贴边的点如果直接按固定偏移定位，Tooltip 会被裁掉或跑到画布外，
  // 所以先算好再夹到可视范围内。
  let left = point.x - 60
  let top = point.y - 80
  if (top < 10) top = point.y + 20 
  if (left < 10) left = 10
  if (left > 650) left = 650
  tooltipStyle.value = { left: left + 'px', top: top + 'px' }
}

const handleMouseLeave = () => {
  hoverData.value = null
}

// toRaw：点是从 computed 派生出来的 Proxy，直接把 Proxy 传给 router 可能触发响应式追踪开销，
// 也会让 devtools 里出现难以辨认的对象。
const goToContestDetail = (item) => {
  try {
    const itemData = toRaw(item);
    if (!itemData || !itemData.contestId) {
      console.warn('缺少竞赛ID参数', itemData);
      return;
    }
    router.push({ 
      name: 'ContestProblem',
      params: { id: itemData.contestId } 
    });
  } catch (error) {
    console.error('跳转竞赛详情页失败:', error);
  }
}
</script>

<style scoped>
/* 样式部分保持一致 */
.chart-card { border-radius: 12px; overflow: hidden; border: none; }
.cf-chart-header { padding: 15px 20px; border-bottom: 1px solid var(--border-light); display: flex; justify-content: space-between; align-items: center; background: var(--bg-surface); }
.chart-title { font-weight: bold; font-size: 16px; color: var(--text-primary); display: flex; align-items: center; gap: 6px; }
.header-info { font-size: 13px; color: var(--text-regular); display: flex; gap: 15px; align-items: center; }
.tip-text { color: var(--text-secondary); font-size: 12px; margin-right: 10px; display: flex; align-items: center; gap: 4px; }
.highlight { font-weight: bold; color: #a0a; }

.cf-chart-container { position: relative; background: var(--bg-surface); line-height: 0; cursor: grab; user-select: none; overflow: hidden; } 
.cf-chart-container:active { cursor: grabbing; }

.cf-svg { width: 100%; height: 300px; display: block; }
.chart-dot { cursor: pointer; transition: r 0.2s; }
.chart-dot:hover { r: 6; stroke: #a0a; }
.mb-20 { margin-bottom: 20px; }

/* Tooltip */
.my-tooltip {
  position: absolute;
  background: rgba(0, 0, 0, 0.7);
  color: var(--text-on-accent);
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  pointer-events: none; 
  z-index: 10;
  width: 140px;
  text-align: center;
}
.tt-name { font-weight: bold; margin-bottom: 4px; border-bottom: 1px solid rgba(255,255,255,0.3); padding-bottom: 2px; }

/* === 新增：Rating 标签样式 === */
.rating-label {
  font-size: 10px;
  font-family: Arial, sans-serif;
  fill: #444;       /* 字体颜色：深灰，对比度适中 */
  font-weight: bold;
  pointer-events: none;
  /* 文字描边：确保在深色背景上也能看清（文字周围有一圈白边） */
  stroke: #fff;
  stroke-width: 2px;
  paint-order: stroke; /* 先画描边再画字 */
  opacity: 0.8;
}
</style>
