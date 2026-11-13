<template>
  <section class="objective-pane">
    <div class="question-head">
      <span class="question-order">第 {{ question.questionOrder ?? index + 1 }} 题</span>
      <el-tag size="small" effect="plain">{{ typeLabel }}</el-tag>
      <el-tag size="small" effect="plain">{{ question.score ?? 0 }} 分</el-tag>
      <el-tag v-if="answered" size="small" type="info" effect="plain">已作答</el-tag>
      <el-tag v-if="question.correct === true" size="small" type="success">正确</el-tag>
      <el-tag v-else-if="question.correct === false" size="small" type="danger">错误</el-tag>
    </div>

    <div class="question-statement markdown-body" v-html="renderedStatement"></div>

    <div class="question-options">
      <el-radio-group
        v-if="question.questionType === 'SINGLE'"
        :model-value="modelValue"
        :disabled="disabled"
        class="option-group"
        @update:model-value="$emit('update:modelValue', $event)"
      >
        <el-radio v-for="option in options" :key="option.key" :value="option.key" class="option-row">
          <span class="markdown-body option-text" v-html="renderOption(option)"></span>
        </el-radio>
      </el-radio-group>

      <el-checkbox-group
        v-else-if="question.questionType === 'MULTIPLE'"
        :model-value="modelValue"
        :disabled="disabled"
        class="option-group"
        @update:model-value="$emit('update:modelValue', $event)"
      >
        <el-checkbox v-for="option in options" :key="option.key" :value="option.key" class="option-row">
          <span class="markdown-body option-text" v-html="renderOption(option)"></span>
        </el-checkbox>
      </el-checkbox-group>

      <el-radio-group
        v-else
        :model-value="modelValue"
        :disabled="disabled"
        class="option-group"
        @update:model-value="$emit('update:modelValue', $event)"
      >
        <el-radio value="T" class="option-row">对</el-radio>
        <el-radio value="F" class="option-row">错</el-radio>
      </el-radio-group>
    </div>

    <div v-if="question.correct != null" class="question-result" :class="question.correct ? 'result-ok' : 'result-bad'">
      {{ question.correct ? '回答正确' : '回答错误' }}，得分 {{ question.gainedScore ?? 0 }} 分
      <span class="result-hint">交卷后可查看正确答案与解析</span>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'

const props = defineProps({
  question: { type: Object, required: true },
  modelValue: { type: [String, Array], default: '' },
  disabled: { type: Boolean, default: false },
  index: { type: Number, default: 0 }
})

defineEmits(['update:modelValue'])

const md = createMarkdown({ html: true, math: true })
const render = text => md.render(sanitizeStatementMarkup(text || ''))

const renderedStatement = computed(() => render(props.question.statement))
const options = computed(() => props.question.options || [])
const answered = computed(() => Array.isArray(props.modelValue)
  ? props.modelValue.length > 0
  : String(props.modelValue ?? '').trim().length > 0)
const typeLabel = computed(() => ({
  SINGLE: '单选题',
  MULTIPLE: '多选题',
  JUDGE: '判断题'
})[props.question.questionType] || '客观题')

const renderOption = option => render(`${option.key}. ${option.text}`)
</script>

<style scoped>
.objective-pane {
  height: 100%;
  overflow-y: auto;
  padding: 24px 32px 60px;
  background: var(--bg-surface);
  color: var(--text-primary);
}
.question-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.question-order { font-weight: 700; }
.question-statement { margin: 18px 0 8px; line-height: 1.8; }
.question-options { margin-top: 14px; }
.option-group { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.option-row {
  width: 100%;
  margin-right: 0;
  padding: 10px 12px;
  border: 1px solid var(--border-light);
  border-radius: 6px;
  white-space: normal;
}
.option-row:hover { border-color: var(--accent-primary); }
.option-text { display: inline; }
.question-result {
  margin-top: 18px;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
}
.result-ok { background: var(--el-color-success-light-9, #f0f9eb); color: var(--el-color-success, #67c23a); }
.result-bad { background: var(--el-color-danger-light-9, #fef0f0); color: var(--el-color-danger, #f56c6c); }
.result-hint { margin-left: 8px; color: var(--text-secondary); }
</style>
