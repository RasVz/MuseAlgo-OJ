<template>
  <div class="radar-wrapper">
    <svg viewBox="0 0 240 240" class="radar-svg">
      <polygon v-for="i in 5" :key="`bg-${i}`" 
        :points="getPolyPoints(i * 20)" 
        class="radar-grid" 
      />
      <line v-for="(pt, i) in axisPoints" :key="`axis-${i}`"
        x1="120" y1="120" :x2="pt.x" :y2="pt.y" class="radar-axis"
      />
      <polygon :points="dataPointsStr" class="radar-area" />
      <circle v-for="(pt, i) in dataPoints" :key="`dot-${i}`"
        :cx="pt.x" :cy="pt.y" r="3" class="radar-dot"
      />
      <text v-for="(pt, i) in labelPoints" :key="`txt-${i}`"
        :x="pt.x" :y="pt.y" class="radar-label"
        text-anchor="middle" dominant-baseline="middle"
      >
        {{ data[i].name }}
      </text>
    </svg>
  </div>
</template>

<script setup>
/**
 * 能力雷达图（纯 SVG 手绘，未引入图表库）。
 *
 * 之所以不用 ECharts：这里只需要一张固定 8 维的静态图，
 * SVG 方案省掉几百 KB 的图表库依赖，且颜色直接跟随站点的 CSS 变量自动适配暗色模式。
 * 代价是维度固定——维度名与维度数量都由调用方通过 data 传入，
 * 图形本身不含任何业务语义，传入多少个维度就画多少个轴。
 *
 * 契约：data 形如 [{ name: '动态规划', value: 85 }]，
 * value 必须是 0-100 的百分比（不是题目 rating），否则算出的半径会超出画布。
 * data 为空时组件渲染为空 svg，由调用方决定是否要展示空态。
 */
import { computed } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] } // [{name: 'DP', value: 80}, ...]
})

// 画布为 240x240 的 viewBox，center 取正中，radius=80 留出四周 40 的余量给轴标签
const center = { x: 120, y: 120 }
const radius = 80
// 维度数。所有几何计算都以它为分母，必须是 computed 而不是常量：
// 调用方异步加载出数据后 count 会从 0 变为 N，届时所有点都要按新维度重算角度。
const count = computed(() => props.data.length)

/**
 * 计算第 idx 个维度、给定半径 r 处的极坐标点。
 *
 * 角度公式里的 -Math.PI / 2 不能省：SVG 的 y 轴向下增长，
 * 而雷达图约定第 0 个维度画在正上方，不做这个偏移首维会跑到正下方。
 * count 为 0 时除得 NaN，此时各计算属性都会得到 NaN 坐标导致图形不渲染，
 * 这是「无数据即不显示」的自然结果，不额外做空判断。
 */
const getPoint = (idx, r) => {
  const angle = (Math.PI * 2 * idx) / count.value - Math.PI / 2
  return {
    x: center.x + r * Math.cos(angle),
    y: center.y + r * Math.sin(angle)
  }
}

/** 半径为 r 的网格多边形顶点串（空格分隔的 "x,y"），直接喂给 polygon 的 points。 */
const getPolyPoints = (r) => {
  return Array.from({ length: count.value }, (_, i) => {
    const p = getPoint(i, r)
    return `${p.x},${p.y}`
  }).join(' ')
}

// 三组同角度、不同半径的点：外圈多边形用 radius，数据点按 value 内缩，轴标签在 radius 外侧
const axisPoints = computed(() => Array.from({ length: count.value }, (_, i) => getPoint(i, radius)))
const labelPoints = computed(() => Array.from({ length: count.value }, (_, i) => getPoint(i, radius + 20)))

// value 按 0-100 归一到半径：满值顶到网格外圈，与 utils/difficulty 的 rating 无关，两者不要混用
const dataPoints = computed(() => props.data.map((item, i) => {
  const r = (item.value / 100) * radius // value 0-100
  return getPoint(i, r)
}))
const dataPointsStr = computed(() => dataPoints.value.map(p => `${p.x},${p.y}`).join(' '))
</script>

<style scoped>
.radar-wrapper { width: 100%; display: flex; justify-content: center; }
.radar-svg { width: 300px; height: 300px; }
.radar-grid { fill: none; stroke: var(--border-light); stroke-width: 1; }
.radar-axis { stroke: var(--border-light); stroke-width: 1; }
.radar-area { fill: rgba(107, 107, 107, 0.3); stroke: var(--accent-primary); stroke-width: 2; }
.radar-dot { fill: var(--bg-surface); stroke: var(--accent-primary); stroke-width: 2; }
.radar-label { font-size: 11px; fill: var(--text-regular); }
</style>