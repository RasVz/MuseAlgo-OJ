<template>
  <svg class="radar-svg" viewBox="0 0 420 400" role="img" aria-label="六维能力雷达图">
    <polygon
      v-for="(points, index) in gridPolygons"
      :key="`grid-${index}`"
      :points="points"
      fill="none"
      stroke="var(--border-base)"
      stroke-width="1"
    />
    <line
      v-for="(axis, index) in axes"
      :key="`axis-${index}`"
      :x1="axis.x1"
      :y1="axis.y1"
      :x2="axis.x2"
      :y2="axis.y2"
      stroke="var(--border-base)"
      stroke-width="1"
    />
    <polygon :points="dataPolygon" fill="rgba(59,130,246,0.22)" stroke="#3b82f6" stroke-width="2" />
    <circle
      v-for="(dot, index) in dataDots"
      :key="`dot-${index}`"
      :cx="dot.x"
      :cy="dot.y"
      r="3.5"
      fill="#3b82f6"
    />
    <text
      v-for="(label, index) in labels"
      :key="`label-${index}`"
      :x="label.x"
      :y="label.y"
      :text-anchor="label.anchor"
      dominant-baseline="middle"
      font-size="13"
      fill="var(--text-primary)"
    >
      {{ label.text }} {{ label.value }}
    </text>
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  dimensions: { type: Array, default: () => [] },
  values: { type: Array, default: () => [] }
})

const CX = 210
const CY = 200
const RADIUS = 140

const angles = computed(() => {
  const total = props.dimensions.length || 1
  return props.dimensions.map((_, index) => -Math.PI / 2 + (index * 2 * Math.PI) / total)
})

function point(index, ratio) {
  const angle = angles.value[index] || 0
  return [CX + Math.cos(angle) * RADIUS * ratio, CY + Math.sin(angle) * RADIUS * ratio]
}

const gridPolygons = computed(() =>
  [0.25, 0.5, 0.75, 1].map((level) =>
    props.dimensions.map((_, index) => point(index, level).join(',')).join(' ')
  )
)

const axes = computed(() =>
  props.dimensions.map((_, index) => {
    const [x, y] = point(index, 1)
    return { x1: CX, y1: CY, x2: x, y2: y }
  })
)

const ratios = computed(() =>
  props.dimensions.map((_, index) => {
    const value = Number(props.values[index]) || 0
    return Math.min(1, Math.max(0, value / 100))
  })
)

const dataPolygon = computed(() =>
  props.dimensions.map((_, index) => point(index, ratios.value[index]).join(',')).join(' ')
)

const dataDots = computed(() =>
  props.dimensions.map((_, index) => {
    const [x, y] = point(index, ratios.value[index])
    return { x, y }
  })
)

const labels = computed(() =>
  props.dimensions.map((text, index) => {
    const [x, y] = point(index, 1.2)
    return {
      text,
      x,
      y,
      value: Number(props.values[index]) || 0,
      anchor: x > CX + 12 ? 'start' : x < CX - 12 ? 'end' : 'middle'
    }
  })
)
</script>

<style scoped>
.radar-svg { display: block; width: 100%; max-width: 560px; height: auto; margin: 0 auto; }
</style>
