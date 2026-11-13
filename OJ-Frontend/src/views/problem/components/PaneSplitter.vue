<template>
  <div
    class="pane-splitter"
    :class="[orientation, { dragging }]"
    role="separator"
    tabindex="0"
    :aria-orientation="orientation"
    :aria-label="label"
    :aria-valuenow="Math.round(value)"
    :aria-valuemin="Math.round(min)"
    :aria-valuemax="Math.round(max)"
    :title="`${label}：拖动调整大小，双击恢复默认`"
    @pointerdown="startDrag"    @pointermove="moveDrag"
    @pointerup="endDrag"
    @pointercancel="endDrag"
    @lostpointercapture="endDrag"
    @keydown="handleKeydown"
    @dblclick="$emit('reset')"
  >
    <span class="pane-splitter-line" aria-hidden="true"></span>
  </div>
</template>

<script setup>
// 通用拖拽分隔条：orientation="vertical" 表示竖向分隔条（调整左右宽度），
// "horizontal" 表示横向分隔条（调整上下高度）。
// 组件只负责把合法尺寸发出去并做键盘/指针交互，尺寸持久化由父级决定。
import { onBeforeUnmount, ref } from 'vue'

const props = defineProps({
  orientation: { type: String, default: 'vertical', validator: value => ['vertical', 'horizontal'].includes(value) },
  value: { type: Number, default: 0 },
  min: { type: Number, default: 0 },
  max: { type: Number, default: 10000 },
  label: { type: String, default: '调整面板大小' },
  // 被调整的面板位于分隔条之后（右侧/下方）时，指针与方向键的含义要反过来。
  invert: { type: Boolean, default: false }
})

const emit = defineEmits(['update:value', 'drag-end', 'reset'])

const dragging = ref(false)
let startPosition = 0
let startValue = 0
let pointerId = null

const clamp = value => Math.min(Math.max(value, props.min), Math.max(props.min, props.max))
const axisPosition = event => (props.orientation === 'vertical' ? event.clientX : event.clientY)

const startDrag = event => {
  if (event.button !== undefined && event.button !== 0) return
  dragging.value = true
  startPosition = axisPosition(event)
  startValue = props.value
  pointerId = event.pointerId
  event.currentTarget.setPointerCapture?.(pointerId)
  document.body.classList.add(props.orientation === 'vertical' ? 'pane-resizing-col' : 'pane-resizing-row')
  event.preventDefault()
}

const moveDrag = event => {
  if (!dragging.value) return
  const delta = (axisPosition(event) - startPosition) * (props.invert ? -1 : 1)
  emit('update:value', clamp(startValue + delta))
}

const endDrag = event => {
  if (!dragging.value) return
  dragging.value = false
  if (event?.currentTarget?.releasePointerCapture && pointerId != null) {
    try {
      event.currentTarget.releasePointerCapture(pointerId)
    } catch {
      // 指针已经释放时忽略
    }
  }
  pointerId = null
  document.body.classList.remove('pane-resizing-col', 'pane-resizing-row')
  emit('drag-end')
}

const handleKeydown = event => {
  const step = event.shiftKey ? 48 : 16
  const vertical = props.orientation === 'vertical'
  const forwardKey = vertical ? 'ArrowRight' : 'ArrowDown'
  const backwardKey = vertical ? 'ArrowLeft' : 'ArrowUp'
  const increaseKey = props.invert ? backwardKey : forwardKey
  const decreaseKey = props.invert ? forwardKey : backwardKey
  let next = null
  if (event.key === decreaseKey) next = clamp(props.value - step)
  else if (event.key === increaseKey) next = clamp(props.value + step)
  else if (event.key === 'Home') next = clamp(props.min)
  else if (event.key === 'End') next = clamp(props.max)
  if (next === null) return
  event.preventDefault()
  emit('update:value', next)
  emit('drag-end')
}

onBeforeUnmount(() => {
  document.body.classList.remove('pane-resizing-col', 'pane-resizing-row')
})
</script>

<style scoped>
.pane-splitter {
  position: relative;
  z-index: 6;
  flex: 0 0 6px;
  align-self: stretch;
  background: transparent;
  touch-action: none;
}
.pane-splitter::before {
  content: '';
  position: absolute;
  inset: -4px;
}
.pane-splitter.vertical {
  cursor: col-resize;
}
.pane-splitter.horizontal {
  cursor: row-resize;
}
.pane-splitter-line {
  position: absolute;
  background: var(--border-light);
  transition: background-color 0.12s ease;
}
.vertical .pane-splitter-line {
  top: 0;
  bottom: 0;
  left: 50%;
  width: 1px;
  transform: translateX(-50%);
}
.horizontal .pane-splitter-line {
  top: 50%;
  left: 0;
  right: 0;
  height: 1px;
  transform: translateY(-50%);
}
.pane-splitter:hover .pane-splitter-line,
.pane-splitter.dragging .pane-splitter-line,
.pane-splitter:focus-visible .pane-splitter-line {
  background: var(--accent-primary);
}
.vertical:hover .pane-splitter-line,
.vertical.dragging .pane-splitter-line,
.vertical:focus-visible .pane-splitter-line {
  width: 2px;
}
.horizontal:hover .pane-splitter-line,
.horizontal.dragging .pane-splitter-line,
.horizontal:focus-visible .pane-splitter-line {
  height: 2px;
}
.pane-splitter:focus-visible {
  outline: none;
}
:global(body.pane-resizing-col),
:global(body.pane-resizing-col *) {
  cursor: col-resize !important;
  user-select: none !important;
}
:global(body.pane-resizing-row),
:global(body.pane-resizing-row *) {
  cursor: row-resize !important;
  user-select: none !important;
}
</style>
