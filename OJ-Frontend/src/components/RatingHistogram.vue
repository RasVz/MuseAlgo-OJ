<template>
  <div class="histogram-chart">
    <div class="bars-container">
      <div v-for="item in data" :key="item.rating" class="bar-col">
        <el-tooltip :content="`${item.rating}: ${item.count} 题`" placement="top">
          <div 
            class="bar" 
            :style="{ 
              height: getHeight(item.count), 
              backgroundColor: getColor(item.rating) 
            }"
          ></div>
        </el-tooltip>
        <span class="rating-label">{{ item.rating }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 题目难度（rating）分布直方图，用 flex 布局的 div 实现而非图表库。
 *
 * 本组件只负责「把每个 rating 桶的题数画成相对高度的柱子」，
 * 分桶方式由调用方决定（前端传入已分好桶的 [{ rating, count }]），
 * 组件内不做区间聚合，避免同一份 rating 在多个页面被切成不同粒度。
 *
 * 契约：rating 是 100 分步长的数值（用于取难度配色），
 * 不是难度名称；数组顺序即 X 轴从左到右的顺序，组件不排序。
 */
import { computed } from 'vue'
import { getDifficultyColor } from '@/utils/difficulty'

const props = defineProps({
  data: { type: Array, default: () => [] } // [{rating: 800, count: 20}, ...]
})

// Math.max 的种子取 1：data 为空时 max 会得到 -Infinity，导致后续除法产出 NaN 高度。
// 兜成 1 后空数据渲染为空图（不报错），符合「无数据不展示」的预期。
const maxCount = computed(() => Math.max(1, ...(props.data.map(d => d.count))))

// 高度用百分比而非像素：柱子高度随容器高度自适应，父容器调整尺寸时无需重新计算。
// 相对最大值归一，保证最高的桶恰好顶满容器。
const getHeight = (val) => `${(val / maxCount.value) * 100}%`

// 直接复用难度色板，让直方图与题目列表、雷达图的难度配色完全一致；
// 别在本组件内硬编码颜色，否则色板调整时这里会漏改。
const getColor = getDifficultyColor
</script>

<style scoped>
.histogram-chart { height: 220px; width: 100%; padding-top: 20px; }
.bars-container { display: flex; align-items: flex-end; justify-content: space-between; height: 100%; border-bottom: 1px solid #eee; padding-bottom: 5px; }
.bar-col { display: flex; flex-direction: column; align-items: center; flex: 1; height: 100%; justify-content: flex-end; gap: 5px; }
.bar { width: 70%; border-radius: 2px 2px 0 0; transition: height 0.5s ease; min-height: 2px; border: 1px solid rgba(0,0,0,0.1); cursor: pointer; }
.bar:hover { opacity: 0.8; }
.rating-label { font-size: 10px; color: #909399; transform: scale(0.9); }
</style>