<template>
  <section class="diagnosis-card">
    <header class="diagnosis-header" :class="{ clickable: canToggle }" @click="toggle">
      <div class="diagnosis-title">
        <span class="title">AI 诊断</span>
        <el-tag :type="statusTagType" effect="light" size="small">{{ statusLabel }}</el-tag>
        <span class="scope">只做简要代码复查，不做教学讲解</span>
      </div>
      <div class="diagnosis-actions">
        <el-button v-if="!loading && status === 'NONE'" size="small" type="primary" plain
          :loading="generating" @click.stop="generate">生成诊断</el-button>
        <el-button v-else-if="status === 'FAILED'" size="small" :loading="generating"
          @click.stop="generate">重试</el-button>
        <el-icon v-if="canToggle" class="chevron" :class="{ open: expanded }"><ArrowDown /></el-icon>
      </div>
    </header>
    <p v-if="(errorMessage || message) && !expanded" class="diagnosis-error diagnosis-inline-error">{{ errorMessage || message }}</p>

    <div v-if="expanded" class="diagnosis-body">
      <div v-if="generating" class="diagnosis-loading" role="status">
        <span class="spinner" aria-hidden="true"></span>
        正在生成诊断…
      </div>
      <el-alert v-if="status === 'FAILED' && content" type="warning" :closable="false" show-icon
        title="以下内容未完整生成，请重试" class="diagnosis-warning" />
      <div v-if="content" class="markdown-body custom-md diagnosis-content" v-html="renderMarkdown(content)"></div>
      <p v-else-if="status === 'RUNNING' && !generating" class="diagnosis-hint">另一个页面正在生成，完成后会自动显示。</p>
      <p v-else-if="status === 'FAILED'" class="diagnosis-error">{{ errorMessage || message || '诊断生成失败，请重试' }}</p>
      <p v-else-if="!generating" class="diagnosis-hint">尚未生成诊断。</p>
      <p v-if="status === 'SUCCEEDED'" class="diagnosis-disclaimer">AI 生成，仅供参考。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/userStore'
import { generateAiDiagnosis, getAiDiagnosis } from '@/api/aiDiagnosis'
import { readAiResponse } from '@/utils/aiStream'
import { createMarkdown } from '@/utils/markdown'
import { getErrorCode } from '@/utils/apiError'

const props = defineProps({
  submissionId: { type: [Number, String], required: true }
})

const userStore = useUserStore()
const renderer = createMarkdown({ html: false, math: false, breaks: true })
const status = ref('NONE')
const content = ref('')
const message = ref('')
const errorMessage = ref('')
const expanded = ref(false)
const loading = ref(false)
const generating = ref(false)
let generation = 0
let pollTimer
let controller = null

const statusLabel = computed(() => {
  if (loading.value && status.value === 'NONE') return '读取中'
  return ({ NONE: '未生成', RUNNING: '生成中', SUCCEEDED: '已生成', FAILED: '生成失败' }[status.value] || '未生成')
})
const statusTagType = computed(() => ({
  NONE: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger'
}[status.value] || 'info'))
const canToggle = computed(() => !loading.value && status.value !== 'NONE')
const renderMarkdown = text => renderer.render(text || '')

const apply = data => {
  if (!data) {
    status.value = 'NONE'
    content.value = ''
    message.value = ''
    return
  }
  status.value = String(data.status || 'FAILED')
  content.value = data.content || ''
  message.value = data.message || ''
}

const load = async ({ expand = false } = {}) => {
  const current = ++generation
  clearTimeout(pollTimer)
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAiDiagnosis(props.submissionId)
    if (current !== generation) return
    if (response.code !== 200) throw new Error(response.message || '诊断读取失败')
    apply(response.data)
    if (expand || status.value === 'RUNNING') expanded.value = true
    if (status.value === 'RUNNING') schedulePoll()
  } catch (error) {
    if (current !== generation) return
    errorMessage.value = error.message || '暂时无法读取诊断'
  } finally {
    if (current === generation) loading.value = false
  }
}

const refreshStatus = async () => {
  if (generating.value || status.value !== 'RUNNING' || document.hidden) return
  try {
    const response = await getAiDiagnosis(props.submissionId)
    if (response.code !== 200) throw new Error(response.message || '状态读取失败')
    apply(response.data)
    if (status.value === 'RUNNING') schedulePoll()
  } catch {
    schedulePoll()
  }
}

const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (status.value !== 'RUNNING' || document.hidden) return
  pollTimer = setTimeout(refreshStatus, 3000)
}

const toggle = () => {
  if (canToggle.value) expanded.value = !expanded.value
}

const generate = async () => {
  if (generating.value || status.value === 'RUNNING') return
  controller?.abort()
  const current = ++generation
  const active = new AbortController()
  controller = active
  generating.value = true
  status.value = 'RUNNING'
  expanded.value = true
  content.value = ''
  message.value = ''
  errorMessage.value = ''
  clearTimeout(pollTimer)
  try {
    const response = await generateAiDiagnosis(props.submissionId, userStore.getAccessToken(), active.signal)
    const answer = await readAiResponse(response, text => {
      if (current === generation) content.value = text
    })
    if (current !== generation) return
    content.value = answer
    status.value = 'SUCCEEDED'
  } catch (error) {
    if (current !== generation || active.signal.aborted) return
    const code = getErrorCode(error)
    if (code === 'AI_DIAGNOSIS_ALREADY_EXISTS' || code === 'AI_DIAGNOSIS_IN_PROGRESS' || error?.status === 409) {
      generating.value = false
      controller = null
      await load({ expand: true })
      return
    }
    status.value = 'FAILED'
    errorMessage.value = error.message || '诊断生成失败，请重试'
    message.value = errorMessage.value
  } finally {
    if (current === generation) {
      generating.value = false
      controller = null
    }
  }
}

const onVisibilityChange = () => {
  if (document.hidden) clearTimeout(pollTimer)
  else if (status.value === 'RUNNING') refreshStatus()
}

watch(() => props.submissionId, () => {
  generation++
  controller?.abort()
  controller = null
  generating.value = false
  expanded.value = false
  status.value = 'NONE'
  content.value = ''
  message.value = ''
  errorMessage.value = ''
  load()
}, { immediate: true })

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => {
  generation++
  clearTimeout(pollTimer)
  controller?.abort()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.diagnosis-card {
  margin-bottom: 20px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  overflow: hidden;
}
.diagnosis-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 52px;
  padding: 10px 18px;
}
.diagnosis-header.clickable { cursor: pointer; }
.diagnosis-title,
.diagnosis-actions { display: flex; align-items: center; gap: 9px; }
.diagnosis-title .title { color: var(--text-primary); font-weight: 650; }
.scope { color: var(--text-secondary); font-size: 12px; }
.chevron { color: var(--text-secondary); transition: transform .2s ease; }
.chevron.open { transform: rotate(180deg); }
.diagnosis-body {
  padding: 16px 18px 18px;
  border-top: 1px solid var(--border-light);
  background: var(--bg-app);
}
.diagnosis-loading { display: flex; align-items: center; gap: 10px; color: var(--text-secondary); font-size: 13px; }
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--el-color-primary-light-7);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: spin .9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.diagnosis-content { color: var(--text-primary); line-height: 1.8; }
.diagnosis-content :deep(p) { margin: 0 0 8px; }
.diagnosis-content :deep(p:last-child) { margin-bottom: 0; }
.diagnosis-hint, .diagnosis-disclaimer { margin: 0; color: var(--text-secondary); font-size: 13px; }
.diagnosis-disclaimer { margin-top: 10px; font-size: 12px; }
.diagnosis-error { margin: 0; color: var(--accent-danger); font-size: 13px; line-height: 1.7; }
.diagnosis-inline-error { padding: 0 18px 14px; }
.diagnosis-warning { margin-bottom: 12px; }
@media (max-width: 720px) {
  .scope { display: none; }
  .diagnosis-header { align-items: flex-start; }
}
</style>
