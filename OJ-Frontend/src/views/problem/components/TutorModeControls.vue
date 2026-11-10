<template>
  <div class="tutor-prompt-buttons" role="group" aria-label="快捷提示">
    <button v-for="mode in TUTOR_MODES" :key="mode.value" type="button" class="prompt-button"
      :class="{ active: modelValue === mode.value }" :disabled="disabled" :title="mode.description"
      :aria-label="`添加提示：${mode.label}`" :aria-pressed="modelValue === mode.value" @click="choose(mode)">
      {{ mode.label }}
    </button>
  </div>
</template>
<script setup>
import { AUTO_MODE, TUTOR_MODES } from '@/utils/tutorModes'
defineProps({
  modelValue: { type: String, default: AUTO_MODE },
  disabled: Boolean
})
const emit = defineEmits(['update:modelValue', 'append'])
const choose = mode => {
  emit('update:modelValue', mode.value)
  emit('append', mode)
}
</script>
<style scoped>
.tutor-prompt-buttons { display: flex; flex-wrap: wrap; gap: 6px; }
.prompt-button { min-width: 64px; min-height: 30px; flex: 1 1 auto; padding: 3px 9px; border: 1px solid var(--border-base); border-radius: 999px; background: var(--bg-surface); color: var(--text-regular); font: inherit; font-size: 12px; line-height: 1.4; white-space: nowrap; cursor: pointer; transition: border-color 160ms ease, color 160ms ease, background 160ms ease; }
.prompt-button:hover:not(:disabled) { border-color: var(--accent-primary); color: var(--accent-primary-dark); }
.prompt-button.active { border-color: var(--accent-primary); background: var(--accent-primary-light); color: var(--accent-primary-dark); font-weight: 500; }
.prompt-button:disabled { opacity: 0.58; cursor: not-allowed; }
.prompt-button:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 2px; }
@media (max-width: 480px) { .prompt-button { min-height: 36px; } }
@media (prefers-reduced-motion: reduce) { .prompt-button { transition: none; } }
</style>
