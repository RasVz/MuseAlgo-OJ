<template>
  <div
    ref="rootRef"
    class="wb-seg"
    :class="[`wb-seg--${size}`, { 'wb-seg--ready': ready }]"
    role="group"
    :aria-label="ariaLabel"
  >
    <span class="wb-seg__thumb" :style="thumbStyle" aria-hidden="true" />
    <button
      v-for="(option, index) in options"
      :key="option.value"
      :ref="(el) => setOptionRef(el, index)"
      type="button"
      class="wb-seg__opt"
      :class="{ 'is-active': option.value === modelValue }"
      :aria-pressed="option.value === modelValue"
      :tabindex="option.value === modelValue ? 0 : -1"
      @click="select(option)"
      @keydown="onKeydown($event, index)"
    >
      <el-icon v-if="option.icon" class="wb-seg__icon"><component :is="option.icon" /></el-icon>
      <span>{{ option.label }}</span>
    </button>
  </div>
</template>

<script setup>
// 工作台分段控件：圆角轨道 + 分格线 + 选中块滑动。
// 选中块位置由 DOM 实测得出，标签变化或容器缩放时重新计算，保证与格子对齐。
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, required: true },
  size: { type: String, default: 'md' }, // md | sm
  ariaLabel: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue', 'change'])

const rootRef = ref(null)
const optionEls = ref([])
const thumbStyle = ref({ width: '0px', left: '0px' })
const ready = ref(false)
let resizeObserver = null

const setOptionRef = (el, index) => {
  if (el) optionEls.value[index] = el
}

const updateThumb = () => {
  const activeIndex = props.options.findIndex((option) => option.value === props.modelValue)
  const el = optionEls.value[activeIndex]
  if (!el) {
    thumbStyle.value = { width: '0px', left: '0px' }
    return
  }
  thumbStyle.value = {
    width: `${el.offsetWidth}px`,
    left: `${el.offsetLeft}px`
  }
}

const select = (option) => {
  if (!option || option.disabled || option.value === props.modelValue) return
  emit('update:modelValue', option.value)
  emit('change', option.value)
}

// 方向键在当前组内移动并选中，Home/End 跳到首尾；Tab 只停在选中项。
const onKeydown = (event, index) => {
  const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End']
  if (!keys.includes(event.key)) return
  event.preventDefault()
  let next = index
  if (event.key === 'ArrowRight') next = (index + 1) % props.options.length
  if (event.key === 'ArrowLeft') next = (index - 1 + props.options.length) % props.options.length
  if (event.key === 'Home') next = 0
  if (event.key === 'End') next = props.options.length - 1
  const option = props.options[next]
  if (!option) return
  select(option)
  optionEls.value[next]?.focus()
}

watch(
  () => props.modelValue,
  () => nextTick(updateThumb)
)
watch(
  () => props.options,
  () => nextTick(updateThumb),
  { deep: true }
)

onMounted(async () => {
  await nextTick()
  updateThumb()
  // 首帧先落位再开启过渡，避免页面加载时滑块从最左侧滑入。
  requestAnimationFrame(() => { ready.value = true })
  if (typeof ResizeObserver !== 'undefined' && rootRef.value) {
    resizeObserver = new ResizeObserver(updateThumb)
    resizeObserver.observe(rootRef.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
})
</script>

<style scoped>
.wb-seg {
  position: relative;
  display: inline-flex;
  align-items: stretch;
  max-width: 100%;
  padding: 3px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  background: var(--bg-app);
  vertical-align: middle;
}

.wb-seg__thumb {
  position: absolute;
  top: 3px;
  left: 0;
  height: calc(100% - 6px);
  box-sizing: border-box;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-tag);
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
  pointer-events: none;
}

/* 首帧落位后再开启滑动过渡 */
.wb-seg--ready .wb-seg__thumb {
  transition: left .24s cubic-bezier(.4, 0, .2, 1), width .24s cubic-bezier(.4, 0, .2, 1);
}

.wb-seg__opt {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 28px;
  padding: 0 14px;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: color .18s;
}

.wb-seg__opt:hover { color: var(--text-primary); }
.wb-seg__opt.is-active { color: var(--text-primary); font-weight: 600; }
.wb-seg__opt:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: 1px;
  border-radius: var(--radius-tag);
}

/* 分格线：选中格及其后一格隐藏，避免压在滑块边缘 */
.wb-seg__opt + .wb-seg__opt::before {
  content: '';
  position: absolute;
  top: 7px;
  bottom: 7px;
  left: 0;
  width: 1px;
  background: var(--border-light);
  transition: opacity .18s;
}
.wb-seg__opt.is-active::before,
.wb-seg__opt.is-active + .wb-seg__opt::before { opacity: 0; }

.wb-seg__icon { font-size: 14px; }

/* 紧凑尺寸：用于表格工具栏、排序等次级位置 */
.wb-seg--sm { padding: 2px; border-radius: 7px; }
.wb-seg--sm .wb-seg__thumb { top: 2px; height: calc(100% - 4px); }
.wb-seg--sm .wb-seg__opt { height: 24px; padding: 0 12px; font-size: 12px; }
.wb-seg--sm .wb-seg__opt + .wb-seg__opt::before { top: 6px; bottom: 6px; }
.wb-seg--sm .wb-seg__icon { font-size: 13px; }

@media (prefers-reduced-motion: reduce) {
  .wb-seg--ready .wb-seg__thumb { transition: none; }
}
</style>
