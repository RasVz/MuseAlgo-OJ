<template>
  <div class="comparison-chart">
    <div class="legend">
      <span class="legend-item"><i class="swatch base"></i>上周基数</span>
      <span class="legend-item"><i class="swatch growth"></i>本周增长</span>
      <span class="legend-hint">满值 100 分，柱长为当前掌握度</span>
    </div>
    <div v-for="(row, index) in rows" :key="`${row.label}-${index}`" class="bar-row">
      <span class="bar-label" :title="row.label">{{ row.label }}</span>
      <div class="bar-track">
        <div class="bar-base" :style="{ width: row.base + '%' }"></div>
        <div class="bar-growth" :style="{ width: row.growth + '%' }"></div>
      </div>
      <span class="bar-value">{{ row.total }}</span>
      <span v-if="row.growth > 0" class="bar-delta">+{{ row.growth }}</span>
      <span v-else class="bar-delta muted">—</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  dimensions: { type: Array, default: () => [] },
  base: { type: Array, default: () => [] },
  growth: { type: Array, default: () => [] }
})

const clamp = (value) => Math.min(100, Math.max(0, Number(value) || 0))

const rows = computed(() =>
  props.dimensions.map((label, index) => {
    const base = clamp(props.base[index])
    const growth = clamp(props.growth[index])
    return {
      label: String(label),
      base,
      growth,
      total: Math.min(100, Math.round(base + growth))
    }
  })
)
</script>

<style scoped>
.comparison-chart { display: flex; flex-direction: column; gap: 12px; }
.legend { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; font-size: 12px; color: var(--text-secondary); }
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.legend-hint { margin-left: auto; }
.swatch { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.swatch.base { background: #cbd5e1; }
.swatch.growth { background: #3b82f6; }
.bar-row { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.bar-label { width: 92px; flex-shrink: 0; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { flex: 1; display: flex; height: 14px; background: var(--bg-surface-hover); border-radius: 999px; overflow: hidden; }
.bar-base { background: #cbd5e1; }
.bar-growth { background: #3b82f6; }
.bar-value { width: 32px; text-align: right; color: var(--text-primary); }
.bar-delta { width: 38px; text-align: right; color: var(--accent-primary); font-size: 12px; }
.bar-delta.muted { color: var(--text-secondary); }
</style>
