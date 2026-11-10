<template>
  <el-drawer :model-value="visible" @update:model-value="emit('update:visible', $event)"
    size="min(720px, 100vw)" direction="rtl" append-to-body class="ai-chat-drawer">
    <template #header>
      <div class="drawer-heading">
        <strong class="drawer-title">AI 教学助教</strong>
        <span class="drawer-subtitle">结合当前题目与最新代码，按你的需要提供帮助</span>
      </div>
    </template>
    <div class="chat-container">
      <div class="conversation-actions">
        <span>当前题目 · {{ problem.title }}</span>
        <el-button :loading="state.clearing" :disabled="state.loading" @click="clearHistory">清空本题对话</el-button>
      </div>
      <p v-if="!state.messages.length && !state.loading" class="welcome">可以直接描述问题，也可以选择帮助方式。完整讲解与代码随时可用。</p>
      <p v-if="state.loading" role="status">正在恢复本题对话…</p>
      <div v-if="state.busy" class="diagnostic-info" role="status">
        <span>{{ diagnosisStatus || '正在分析当前代码…' }}</span>
      </div>
      <ChatMessageList :messages="state.messages" :thinking="state.busy" :streaming-content="state.text" />
      <div v-if="state.error" role="alert" class="request-error">
        {{ state.error }}
        <el-button v-if="!state.ready" link @click="openConversation">重新恢复对话</el-button>
        <el-button v-else-if="state.lastQuestion" link :disabled="state.busy || state.clearing" @click="ask(state.lastQuestion)">重试上次提问</el-button>
      </div>
      <div class="chat-input-area">
        <TutorModeControls v-model="helpMode" :disabled="state.busy || sending" @append="appendMode" />
        <label for="tutor-question">你的问题</label>
        <el-input id="tutor-question" v-model="question" type="textarea" :rows="5" :maxlength="2000" show-word-limit
          placeholder="描述哪里卡住了；不必重复粘贴编辑器里的代码" resize="none" @keydown.enter.exact.prevent="onEnter" />
        <div class="composer-footer">
          <span class="composer-hint">Shift+Enter 换行</span>
          <span class="composer-quota" aria-live="polite">
            {{ quotaText }}
            <el-button v-if="quotaError" link class="quota-retry" @click="loadQuota">重新读取</el-button>
          </span>
          <el-button v-if="state.busy || sending" @click="stopGeneration">停止生成</el-button>
          <el-button v-else type="primary" :disabled="!state.ready || state.loading || state.clearing || sending" @click="ask()">发送</el-button>
        </div>
      </div>
    </div>
  </el-drawer>
</template>
<script setup>
import { ref, shallowRef, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { showApiError } from '@/utils/errorFeedback'
import { getConversation, clearConversation, streamTeaching, getMyAiQuota } from '@/api/ai'
import { createTutorConversation } from '@/utils/tutorConversation'
import { AUTO_MODE, appendTutorPrompt } from '@/utils/tutorModes'
import ChatMessageList from './ChatMessageList.vue'
import TutorModeControls from './TutorModeControls.vue'
const props = defineProps({ visible: Boolean, problem: { type: Object, default: () => ({}) }, getStudentContext: { type: Function, required: true } })
const emit = defineEmits(['update:visible', 'counterexample'])
const userStore = useUserStore()
const question = ref('')
const helpMode = ref(AUTO_MODE)
const state = shallowRef({ messages: [], loading: false, busy: false, ready: false })
const conversation = createTutorConversation({ load: getConversation, clear: clearConversation,
  send: (payload, signal, onChunk, onMetadata) => streamTeaching('ask/stream', payload, userStore.getAccessToken(), signal, onChunk, onMetadata),
  onMetadata: (name, data, payload) => {
    if (name === 'diagnostic-status') diagnosisStatus.value = typeof data.message === 'string' ? data.message : ''
    if (name === 'counterexample') emit('counterexample', { ...data, snapshot: { code: payload.studentCode, language: payload.language } })
  },
  onChange: value => { state.value = value }, onError: error => showApiError(error, error.message || 'AI 请求失败') })
const identity = computed(() => `${userStore.userInfo?.id || 'guest'}:${props.problem?.id}`)
const openConversation = () => {
  if (props.visible && props.problem?.id) return conversation.open(identity.value, props.problem.id)
}
const diagnosisStatus = ref('')
const quota = ref(null), quotaError = ref(false)
let quotaGeneration = 0
const quotaText = computed(() => quotaError.value ? '额度暂不可读取' : !quota.value ? '正在读取额度…' : quota.value.exempt ? '当前账号无需扣减学生额度' : `今日剩余 ${quota.value.remaining} / ${quota.value.dailyLimit} token · 次日重置`)
const loadQuota = async () => {
  const version = ++quotaGeneration
  try { const result = await getMyAiQuota(); if (version === quotaGeneration) { quota.value = result.data; quotaError.value = false } }
  catch { if (version === quotaGeneration) quotaError.value = true }
}
let snapshotGeneration = 0
watch(() => [props.visible, identity.value], ([visible, key], previous) => {
  snapshotGeneration++
  conversation.stop()
  if (previous && key !== previous[1]) { question.value = ''; helpMode.value = AUTO_MODE }
  if (visible) { openConversation(); loadQuota() }
}, { immediate: true })
onBeforeUnmount(() => conversation.stop())
const sending = ref(false)
const appendMode = mode => { question.value = appendTutorPrompt(question.value, mode.question) }
const ask = async (retryQuestion) => {
  if (sending.value || state.value.busy || !state.value.ready) return
  const text = (typeof retryQuestion === 'string' ? retryQuestion : question.value).trim()
  if (!text) return ElMessage.info('请先输入问题，或添加一条快捷提示')
  const key = identity.value
  const version = ++snapshotGeneration
  const selectedHelpMode = helpMode.value
  sending.value = true
  try {
    const snapshot = await props.getStudentContext()
    if (version !== snapshotGeneration || key !== identity.value || !props.visible) return
    if (question.value.trim() === text) question.value = ''
    diagnosisStatus.value = ''
    await conversation.ask({ problemId: props.problem.id, studentQuestion: text,
      studentCode: snapshot.code || '', language: snapshot.language || 'cpp', runIds: snapshot.runIds || [],
      selectedSample: snapshot.selectedSample, helpMode: selectedHelpMode })
  } catch (error) { showApiError(error, '读取当前草稿失败，请重试') }
  finally { sending.value = false; if (props.visible) loadQuota() }
}
const onEnter = event => { if (!event.isComposing) ask() }
const stopGeneration = () => { snapshotGeneration++; conversation.stop() }
const clearHistory = () => { snapshotGeneration++; return conversation.clear() }
</script>
<style scoped>
:global(.ai-chat-drawer .el-drawer__header) { margin: 0; padding: 8px 16px; border-bottom: 1px solid var(--border-light); }
:global(.ai-chat-drawer .el-drawer__close-btn) { width: 28px; height: 28px; }
:global(.ai-chat-drawer .el-drawer__body) { padding: 0; overflow: hidden; }
.chat-container { height: 100%; display: flex; flex-direction: column; min-height: 0; background: var(--bg-app); }
.drawer-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 2px 12px; min-width: 0; }
.drawer-title { font-size: 16px; font-weight: 600; line-height: 1.4; white-space: nowrap; }
.drawer-subtitle { margin: 0; font-size: 12px; line-height: 1.5; color: var(--text-secondary); }
.welcome { margin: 12px 20px; font-size: 13px; color: var(--text-secondary); line-height: 1.6; }
.conversation-actions { display: flex; gap: 12px; align-items: center; justify-content: space-between; padding: 10px 20px; font-size: 13px; }
.conversation-actions > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-input-area { padding: 10px 20px 8px; border-top: 1px solid var(--border-light); display: grid; gap: 8px; background: var(--bg-surface); max-height: 75%; overflow-y: auto; }
.chat-input-area > label { font-size: 13px; line-height: 1.4; }
.composer-footer { display: flex; align-items: center; gap: 8px; min-height: 30px; flex-wrap: nowrap; }
.composer-hint { flex: 0 0 auto; font-size: 12px; color: var(--text-secondary); }
.composer-quota { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: center; font-size: 12px; color: var(--text-secondary); }
.composer-quota :deep(.el-button) { margin-left: 2px; padding: 0; font-size: 11px; }
.diagnostic-info { display: flex; gap: 8px; padding: 4px 20px; font-size: 12px; color: var(--text-secondary); }
.request-error { padding: 8px 20px; color: var(--el-color-danger); font-size: 13px; }
@media (max-width: 480px) {
  .chat-input-area { max-height: 78%; padding: 10px 12px 8px; }
  .composer-hint { display: none; }
  .composer-footer { justify-content: flex-end; }
  .drawer-heading { gap: 2px 8px; }
  .drawer-title { font-size: 15px; }
  .drawer-subtitle { font-size: 12px; }
}
</style>
