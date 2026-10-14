<template>
  <div class="donut-layout">
    <div class="chart-box">
      <svg viewBox="0 0 40 40" class="donut-svg">
        <circle cx="20" cy="20" r="15.9" fill="transparent" stroke="var(--border-light)" stroke-width="5" />
        <circle 
          v-for="(seg, i) in segments" :key="i"
          cx="20" cy="20" r="15.9" 
          fill="transparent" 
          :stroke="seg.color" 
          stroke-width="5" 
          :stroke-dasharray="`${seg.percent} ${100 - seg.percent}`"
          :stroke-dashoffset="seg.offset"
        />
      </svg>
      <div class="center-text">Tags</div>
    </div>
    
    <div class="legend-list scrollbar-custom">
      <div v-for="item in data" :key="item.name" class="legend-item">
        <span class="dot" :style="{ background: item.color }"></span>
        <span class="name">{{ item.name }}</span>
        <span class="count">{{ item.count }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 标签分布甜甜圈图（纯 SVG 手绘，未引入图表库）。
 *
 * 圆环的实现技巧：所有分段都是同一个 <circle>，靠 stroke-dasharray 控制可见弧长、
 * 靠 stroke-dashoffset 把各自弧段平移到圆周上的不同起点。
 * 由于圆环本身由 stroke 构成，fill 必须是 transparent，否则会被实心圆盖住。
 *
 * 契约：data 形如 [{ name, count, color }]，color 由调用方指定——
 * 标签颜色与题目知识点色板是两套东西，不要在本组件里另配。
 */
import { computed } from 'vue'

// 未给默认值：调用方必须传 data，空数组会导致 total 为 0、百分比为 NaN 而不渲染
const props = defineProps(['data'])

const total = computed(() => props.data.reduce((acc, cur) => acc + cur.count, 0))

/**
 * 计算每个分段应占的百分比与弧段偏移。
 *
 * stroke-dasharray 用「实线 空白」的百分比长度画弧，dashoffset 决定这段从圆周哪点开始。
 * offset 以 25 起算是为了抵消 SVG 圆形的默认起点（3 点方向），
 * 让第一段落在 12 点方向、与视觉上的时钟表盘一致。
 *
 * 注意：dasharray 的单位是视窗百分比而非像素（viewBox 40x40，r=15.9 恰好接近 2πr/10 的比例），
 * 因此改动 r 或 stroke-width 时必须同步验证 dasharray 的百分比是否还对得上，否则分段会错位。
 */
const segments = computed(() => {
  let accumulated = 0
  return props.data.map(item => {
    const percent = (item.count / total.value) * 100
    const offset = 25 - accumulated // 25 to start at 12 o'clock
    accumulated += percent
    return { ...item, percent, offset }
  })
})
</script>

<style scoped>
.donut-layout { display: flex; align-items: center; gap: 20px; height: 100%; }
.chart-box { width: 160px; height: 160px; position: relative; flex-shrink: 0; }
.donut-svg { width: 100%; height: 100%; transform: rotate(0deg); }
.center-text { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-weight: bold; color: var(--text-regular); font-size: 14px; }

.legend-list { flex: 1; height: 200px; overflow-y: auto; padding-right: 5px; }
.legend-item { display: flex; align-items: center; font-size: 13px; margin-bottom: 6px; }
.dot { width: 10px; height: 10px; margin-right: 8px; border-radius: 2px; flex-shrink: 0; }
.name { flex: 1; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.count { font-weight: bold; color: var(--text-regular); }
</style>