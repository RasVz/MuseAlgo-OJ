<template>
  <div class="tutor-preferences">
    <label :for="`${idPrefix}-direction`">帮助方向</label>
    <el-select :id="`${idPrefix}-direction`" :model-value="modelValue.helpDirection" :disabled="disabled"
      aria-label="帮助方向" @update:model-value="set('helpDirection', $event)">
      <el-option v-for="item in HELP_DIRECTIONS" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <p class="direction-description">{{ selected.description }}</p>
    <details>
      <summary>回答偏好</summary>
      <div class="preference-row">
        <span :id="`${idPrefix}-style`">回答方式</span>
        <el-radio-group :model-value="modelValue.answerStyle" :disabled="disabled" :aria-labelledby="`${idPrefix}-style`" @update:model-value="set('answerStyle', $event)">
          <el-radio-button value="DIRECT">直接说明</el-radio-button><el-radio-button value="HINT_ONLY">只给提示</el-radio-button>
        </el-radio-group>
      </div>
      <div class="preference-row">
        <span :id="`${idPrefix}-detail`">展开程度</span>
        <el-radio-group :model-value="modelValue.detailLevel" :disabled="disabled" :aria-labelledby="`${idPrefix}-detail`" @update:model-value="set('detailLevel', $event)">
          <el-radio-button value="CONCISE">简洁回答</el-radio-button><el-radio-button value="DETAILED">详细展开</el-radio-button>
        </el-radio-group>
      </div>
      <p class="direction-description">你在本轮问题中的明确要求优先。</p>
    </details>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { HELP_DIRECTIONS } from '@/utils/tutorPreferences'
const props = defineProps({ modelValue: { type: Object, required: true }, disabled: Boolean, idPrefix: { type: String, default: 'tutor' } })
const emit = defineEmits(['update:modelValue'])
const selected = computed(() => HELP_DIRECTIONS.find(item => item.value === props.modelValue.helpDirection) || HELP_DIRECTIONS[0])
const set = (key, value) => emit('update:modelValue', { ...props.modelValue, [key]: value })
</script>
<style scoped>
.tutor-preferences { display: grid; gap: 8px; }
.tutor-preferences > label, .preference-row > span { font-size: 13px; color: var(--text-primary); }
.direction-description { margin: 0; font-size: 13px; line-height: 1.5; color: var(--text-secondary); }
summary { cursor: pointer; padding: 8px 0; color: var(--text-secondary); font-size: 13px; }
summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.preference-row { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin: 8px 0; }
</style>
