<template>
  <div class="histogram-chart">
    <div class="histogram-y-axis" aria-hidden="true">
      <span>{{ maxCount }}</span
      ><span>{{ Math.round(maxCount / 2) }}</span
      ><span>0</span>
    </div>
    <div class="histogram-plot" role="img" :aria-label="summary">
      <div
        v-for="(item, index) in data"
        :key="item.rating"
        class="bar-col"
        :tabindex="item.count > 0 ? 0 : undefined"
        :title="`${item.rating} 分：${item.count} 题`"
      >
        <span
          v-if="item.count > 0"
          class="bar-value"
          :style="{ bottom: `calc(${(item.count / maxCount) * 100}% + 4px)` }"
          >{{ item.count }}</span
        >
        <div
          class="bar"
          :style="{
            height: `${(item.count / maxCount) * 100}%`,
            backgroundColor: getDifficultyColor(item.rating),
          }"
        ></div>
        <span
          v-if="index === 0 || index === data.length - 1 || index % labelStep === 0"
          class="rating-label"
          >{{ item.rating }}</span
        >
      </div>
    </div>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { getDifficultyColor } from '@/utils/difficulty'
const props = defineProps({ data: { type: Array, default: () => [] } })
const maxCount = computed(() => Math.max(1, ...props.data.map((item) => item.count)))
const labelStep = computed(() => Math.max(1, Math.ceil(props.data.length / 6)))
const summary = computed(
  () =>
    `难度分布，${props.data
      .filter((item) => item.count > 0)
      .map((item) => `${item.rating}分${item.count}题`)
      .join('，')}`,
)
</script>
<style scoped>
.histogram-chart {
  height: 200px;
  display: flex;
  gap: 12px;
  padding: 28px 6px 30px 0;
  min-width: 0;
  box-sizing: content-box;
}
.histogram-y-axis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  width: 22px;
  text-align: right;
  color: var(--text-secondary);
  font-size: 11px;
}
.histogram-plot {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: 5px;
  border-bottom: 1px solid var(--border-base);
  background: linear-gradient(var(--border-light) 1px, transparent 1px);
  background-size: 100% 50%;
}
.bar-col {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  flex: 1;
  min-width: 0;
  height: 100%;
}
.bar {
  width: 65%;
  max-width: 52px;
  border-radius: 4px 4px 0 0;
}
.bar-value {
  position: absolute;
  font-size: 11px;
  color: var(--text-regular);
}
.rating-label {
  position: absolute;
  bottom: -24px;
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.bar-col:focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
@media (max-width: 600px) {
  .histogram-chart {
    height: 160px;
  }
  .histogram-plot {
    gap: 2px;
  }
  .rating-label {
    font-size: 10px;
  }
}
</style>
