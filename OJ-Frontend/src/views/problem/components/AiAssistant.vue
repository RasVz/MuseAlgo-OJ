<template>
  <el-drawer :model-value="visible" @update:model-value="emit('update:visible', $event)"
    size="min(720px, 100vw)" direction="rtl" append-to-body class="ai-chat-drawer">
    <template #header>
      <div><strong>AI 教学助教</strong><div class="drawer-subtitle">结合当前题目与最新代码，按你的需要提供帮助</div></div>
    </template>
    <div class="chat-container">
      <div class="conversation-actions">
        <span>当前题目 · {{ problem.title }}</span>
        <el-button :loading="state.clearing" :disabled="state.loading" @click="clearHistory">清空本题对话</el-button>
      </div>
      <p v-if="!state.messages.length && !state.loading" class="welcome">可以直接描述问题，也可以选择帮助方向。完整讲解与代码随时可用。</p>
      <p v-if="state.loading" role="status">正在恢复本题对话…</p>
      <ChatMessageList :messages="state.messages" :thinking="state.busy" :streaming-content="state.text" />
      <div v-if="state.error" role="alert" class="request-error">
        {{ state.error }}
        <el-button v-if="!state.ready" link @click="openConversation">重新恢复对话</el-button>
        <el-button v-else-if="state.lastQuestion" link :disabled="state.busy || state.clearing" @click="ask(state.lastQuestion)">重试上次提问</el-button>
      </div>
      <div class="chat-input-area">
        <TutorPreferenceControls v-model="preferences" :disabled="state.busy" />
        <label for="tutor-question">你的问题</label>
        <el-input id="tutor-question" v-model="question" type="textarea" :rows="3" :maxlength="2000" show-word-limit
          placeholder="描述哪里卡住了；不必重复粘贴编辑器里的代码" resize="none" @keydown.enter.exact.prevent="onEnter" />
        <div class="composer-footer">
          <el-button :disabled="state.busy || !selected.question" @click="fillQuestion">填入示例问题</el-button>
          <span>Shift+Enter 换行</span>
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
import { getConversation, clearConversation, streamTeaching } from '@/api/ai'
import { createTutorConversation } from '@/utils/tutorConversation'
import { HELP_DIRECTIONS, defaultPreferences } from '@/utils/tutorPreferences'
import ChatMessageList from './ChatMessageList.vue'
import TutorPreferenceControls from './TutorPreferenceControls.vue'
const props = defineProps({ visible: Boolean, problem: { type: Object, default: () => ({}) }, getStudentContext: { type: Function, required: true } })
const emit = defineEmits(['update:visible'])
const userStore = useUserStore()
const question = ref('')
const preferences = ref(defaultPreferences())
const selected = computed(() => HELP_DIRECTIONS.find(item => item.value === preferences.value.helpDirection) || HELP_DIRECTIONS[0])
const state = shallowRef({ messages: [], loading: false, busy: false, ready: false })
const conversation = createTutorConversation({ load: getConversation, clear: clearConversation,
  send: (payload, signal, onChunk) => streamTeaching('ask/stream', payload, userStore.getAccessToken(), signal, onChunk),
  onChange: value => { state.value = value }, onError: error => showApiError(error, error.message || 'AI 请求失败') })
const identity = computed(() => `${userStore.userInfo?.id || 'guest'}:${props.problem?.id}`)
const openConversation = () => {
  if (props.visible && props.problem?.id) return conversation.open(identity.value, props.problem.id)
}
let snapshotGeneration = 0
watch(() => [props.visible, identity.value], ([visible, key], previous) => {
  snapshotGeneration++
  conversation.stop()
  if (previous && key !== previous[1]) { question.value = ''; preferences.value = defaultPreferences() }
  if (visible) openConversation()
}, { immediate: true })
onBeforeUnmount(() => conversation.stop())
const sending = ref(false)
const ask = async (retryQuestion) => {
  if (sending.value || state.value.busy || !state.value.ready) return
  const text = (typeof retryQuestion === 'string' ? retryQuestion : question.value).trim()
  if (!text) return ElMessage.info('请先输入问题，或填入示例问题')
  const key = identity.value
  const version = ++snapshotGeneration
  const selectedPreferences = { ...preferences.value }
  sending.value = true
  try {
    const snapshot = await props.getStudentContext()
    if (version !== snapshotGeneration || key !== identity.value || !props.visible) return
    if (question.value.trim() === text) question.value = ''
    await conversation.ask({ problemId: props.problem.id, studentQuestion: text,
      studentCode: snapshot.code || '', language: snapshot.language || 'cpp', ...selectedPreferences })
  } catch (error) { showApiError(error, '读取当前草稿失败，请重试') }
  finally { sending.value = false }
}
const fillQuestion = () => { question.value = selected.value.question }
const onEnter = event => { if (!event.isComposing) ask() }
const stopGeneration = () => { snapshotGeneration++; conversation.stop() }
const clearHistory = () => { snapshotGeneration++; return conversation.clear() }
</script>
<style scoped>
:global(.ai-chat-drawer .el-drawer__header) { margin: 0; padding: 18px 20px; border-bottom: 1px solid var(--border-light); }
:global(.ai-chat-drawer .el-drawer__body) { padding: 0; overflow: hidden; }
.chat-container { height: 100%; display: flex; flex-direction: column; min-height: 0; background: var(--bg-app); }
.drawer-subtitle, .welcome { font-size: 13px; color: var(--text-secondary); line-height: 1.6; }
.drawer-subtitle { margin-top: 6px; }
.welcome { margin: 12px 20px; }
.conversation-actions { display: flex; gap: 12px; align-items: center; justify-content: space-between; padding: 10px 20px; font-size: 13px; }
.conversation-actions > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-input-area { padding: 16px 20px; border-top: 1px solid var(--border-light); display: grid; gap: 10px; background: var(--bg-surface); max-height: 55%; overflow-y: auto; }
.chat-input-area > label { font-size: 13px; }
.composer-footer { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.composer-footer > span { flex: 1; font-size: 12px; color: var(--text-secondary); }
.request-error { padding: 8px 20px; color: var(--el-color-danger); font-size: 13px; }
@media (max-width: 480px) { .chat-input-area { padding: 12px; } .composer-footer > span { display: none; } }
</style>
