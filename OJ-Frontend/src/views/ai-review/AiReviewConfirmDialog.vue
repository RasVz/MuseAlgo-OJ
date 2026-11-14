<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle()"
    width="min(620px, 94vw)"
    @update:model-value="$emit('update:visible', $event)"
  >
    <el-alert type="info" :closable="false" show-icon class="scope-alert">
      <template #title>分析历史提交 #{{ detail?.id }}，不会读取当前编辑器草稿</template>
      <template #default>
        将使用这次提交的代码、题目和评测摘要，并调用已配置的 AI 服务；会按现有 AI 使用额度计入。
      </template>
    </el-alert>
    <el-descriptions v-if="detail" :column="2" border class="submission-summary">
      <el-descriptions-item label="题目">P{{ detail.problemId }} {{ detail.problemTitle || '' }}</el-descriptions-item>
      <el-descriptions-item label="语言">{{ detail.language || '未知' }}</el-descriptions-item>
      <el-descriptions-item label="评测结果">{{ statusText(detail.status) }}</el-descriptions-item>
      <el-descriptions-item label="提交编号">#{{ detail.id }}</el-descriptions-item>
    </el-descriptions>
    <div class="review-input">
      <label for="review-mode">讲解方式</label>
      <el-select id="review-mode" v-model="helpMode" class="full-width">
        <el-option v-for="option in modes" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <label for="review-focus">希望重点分析什么（选填）</label>
      <el-input id="review-focus" v-model="question" type="textarea" :rows="3" :maxlength="2000"
        placeholder="例如：请重点解释复杂度和边界条件" />
    </div>
    <template #footer>
      <el-button @click="$emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">确认并开始</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  detail: { type: Object, default: null },
  relationType: { type: String, default: 'INITIAL' },
  initialOptions: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'confirm'])
const modes = [
  { value: 'AUTO', label: '自由分析' },
  { value: 'CHECK_APPROACH', label: '检查思路' },
  { value: 'DEBUG_CODE', label: '排查代码' },
  { value: 'READ_PROBLEM', label: '读懂题目' },
  { value: 'GET_HINT', label: '给我提示' },
  { value: 'FULL_SOLUTION', label: '完整讲解' }
]
const helpMode = ref('AUTO')
const question = ref('')
watch(() => [props.visible, props.relationType], ([visible]) => {
  if (visible) {
    helpMode.value = props.initialOptions?.helpMode || 'AUTO'
    question.value = props.initialOptions?.studentQuestion || ''
  }
})
const titleByRelation = {
  INITIAL: '确认生成 AI 代码复盘',
  REANALYZE: '确认重新分析',
  RETRY: '确认重试复盘'
}
const dialogTitle = () => titleByRelation[props.relationType] || '确认生成 AI 代码复盘'
const statusText = status => ({ 1: '通过', 2: '答案错误', 3: '编译错误', 4: '运行错误', 5: '时间超限' }[Number(status)] || '未知')
const submit = () => emit('confirm', { helpMode: helpMode.value, studentQuestion: question.value.trim() })
</script>

<style scoped>
.scope-alert { margin-bottom: 16px; }
.submission-summary { margin-bottom: 18px; }
.review-input { display: grid; gap: 8px; }
.review-input label { margin-top: 6px; color: var(--text-regular); font-size: 13px; }
.full-width { width: 100%; }
</style>
