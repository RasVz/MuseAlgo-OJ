<template>
  <section class="coding-pane" v-loading="loading">
    <div v-if="lastResult" class="result-strip" :class="resultClass">
      <strong>{{ resultLabel }}</strong>
      <span v-if="lastResult.runTime != null">耗时 {{ lastResult.runTime }} ms</span>
      <span v-if="lastResult.runMemory != null">内存 {{ lastResult.runMemory }} KB</span>
      <span v-if="lastResult.score != null">得分 {{ lastResult.score }}</span>
      <span v-if="lastResult.status === 0 || pending" class="pending-text">判题中，稍后自动刷新</span>
    </div>
    <pre v-if="lastResult?.errorMessage" class="error-output">{{ lastResult.errorMessage }}</pre>

    <ProblemIdeWorkspace
      v-if="workspaceProblem"
      ref="workspaceRef"
      :key="workspaceProblem.id"
      :problem="workspaceProblem"
      :samples="officialSamples"
      :initial-snapshot="null"
      :current-snapshot="snapshot"
      :submitting="submitting"
      :running-cases="runningCases"
      :test-cases="testCases"
      :case-model="caseModel"
      :draft-adapter="draftAdapter"
      :draft-save-delay="2000"
      :ai-enabled="false"
      :show-ide-toggle="false"
      layout-storage-key="musealgo:problem-ide-layout:exam"
      @snapshot="snapshot = $event"
      @submit="handleSubmit"
      @run="runSelectedCase"
      @stop="stopCases"
    />
    <el-empty v-else description="正在加载题面" :image-size="72" />
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ProblemIdeWorkspace from '@/views/problem/components/ProblemIdeWorkspace.vue'
import { useProblemWorkbench } from '@/views/problem/composables/useProblemWorkbench'

const props = defineProps({
  problem: { type: Object, default: null },
  draftAdapter: { type: Object, default: null },
  submitting: { type: Boolean, default: false },
  lastResult: { type: Object, default: null },
  pending: { type: Boolean, default: false },
  canSubmit: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['submit'])

const workspaceRef = ref(null)
const snapshot = ref(null)

const workspaceProblem = computed(() => props.problem
  ? { ...props.problem, id: props.problem.problemId }
  : null)

const {
  testCases,
  caseModel,
  officialSamples,
  runningCases,
  initializeCases,
  runSelectedCase,
  stopCases
} = useProblemWorkbench({
  getProblemId: () => props.problem?.problemId,
  getSnapshot: () => workspaceRef.value?.getSubmissionData()
})

watch(workspaceProblem, problem => {
  stopCases()
  if (problem) initializeCases(problem)
}, { immediate: true })

const STATUS_LABELS = {
  0: '判题中',
  1: '通过',
  2: '答案错误',
  3: '编译错误',
  4: '运行错误',
  5: '运行超时'
}
const resultLabel = computed(() => STATUS_LABELS[props.lastResult?.status] || '已提交')
const resultClass = computed(() => {
  if (!props.lastResult) return ''
  if (props.lastResult.status === 1) return 'result-ok'
  if (props.lastResult.status === 0) return 'result-pending'
  return 'result-bad'
})

const handleSubmit = () => {
  if (!props.canSubmit) return ElMessage.warning('当前不可提交')
  try {
    const payload = workspaceRef.value?.getSubmissionData()
    if (!payload?.code?.trim()) return ElMessage.warning('代码不能为空')
    emit('submit', payload)
  } catch (error) {
    ElMessage.info(error.message)
  }
}
</script>

<style scoped>
.coding-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.coding-pane > :deep(.ide-layout) { flex: 1; min-height: 0; }
.result-strip {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 18px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-input);
  font-size: 13px;
  flex-shrink: 0;
}
.result-ok { color: var(--accent-success, #67c23a); }
.result-bad { color: var(--accent-danger, #f56c6c); }
.result-pending { color: var(--accent-warning, #e6a23c); }
.pending-text { color: var(--text-secondary); }
.error-output {
  max-height: 140px;
  margin: 0;
  overflow: auto;
  padding: 10px 18px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-surface);
  color: var(--accent-danger, #f56c6c);
  font-size: 12px;
  white-space: pre-wrap;
}
</style>
