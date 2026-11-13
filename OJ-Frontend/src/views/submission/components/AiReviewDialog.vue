<template>
  <el-dialog v-model="dialogVisible" title="AI 提交复盘" width="min(760px, 95vw)" class="review-dialog">
    <p class="review-scope">分析对象：历史提交 #{{ submissionId }}，不是当前编辑器草稿。</p>
    <div class="review-content">
      <div v-if="reviewContent" class="markdown-body custom-md" v-html="renderMarkdown(reviewContent)"></div>
      <div v-else-if="reviewing" role="status" class="review-thinking">正在分析代码、题目与评测结果…</div>
    </div>
    <p v-if="reviewIncomplete" class="review-incomplete" role="status">{{ reviewNotice || '回复未完成，请重试' }}</p>
    <TutorModeControls v-model="helpMode" :disabled="reviewing" @append="appendMode" />
    <label for="review-question">继续提问</label>
    <el-input id="review-question" v-model="question" type="textarea" :rows="2" :maxlength="2000" placeholder="描述你想进一步了解的地方" />
    <template #footer>
      <el-button v-if="reviewing" @click="stopReview">停止生成</el-button>
      <template v-else>
        <el-button @click="startReview">重新分析</el-button>
        <el-button type="primary" :disabled="!question.trim()" @click="startReview">发送追问</el-button>
      </template>
    </template>
  </el-dialog>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { showApiError } from '@/utils/errorFeedback'
import { useUserStore } from '@/stores/userStore'
import { createMarkdown } from '@/utils/markdown'
import { readAiResponse } from '@/utils/aiStream'
import { AUTO_MODE, appendTutorPrompt, isContestScene } from '@/utils/tutorModes'
import TutorModeControls from '@/views/problem/components/TutorModeControls.vue'
const props = defineProps({ visible: Boolean, submissionId: { type: [Number, String], default: null }, contestId: { type: [Number, String], default: null } })
const emit = defineEmits(['update:visible'])
const md = createMarkdown({ html: false, math: true, breaks: true })
const dialogVisible = ref(false)
const reviewContent = ref('')
const reviewing = ref(false)
const reviewIncomplete = ref(false)
const reviewNotice = ref('')
const question = ref('')
const helpMode = ref(AUTO_MODE)
let lastAnswer = ''
let reviewController = null
let reviewGeneration = 0
const cancelReview = () => { reviewGeneration++; reviewController?.abort(); reviewing.value = false }
const stopReview = () => { lastAnswer = reviewContent.value ? '【前次回复未完成】\n' + reviewContent.value : ''; cancelReview(); reviewIncomplete.value = true; reviewNotice.value = '已停止生成，当前回复未完成。' }
onBeforeUnmount(cancelReview)
watch(() => [props.visible, props.submissionId, props.contestId], ([visible, id], previous) => {
  cancelReview()
  if (previous && id !== previous[1]) { lastAnswer = ''; question.value = ''; helpMode.value = AUTO_MODE; reviewContent.value = '' }
  dialogVisible.value = visible && !isContestScene(props.contestId)
  if (dialogVisible.value) startReview()
})
watch(dialogVisible, value => { if (!value) cancelReview(); emit('update:visible', value) })
const renderMarkdown = text => text ? md.render(text) : ''
const appendMode = mode => { question.value = appendTutorPrompt(question.value, mode.question) }
const startReview = async () => {
  if (!props.submissionId || isContestScene(props.contestId)) return
  reviewController?.abort()
  const generation = ++reviewGeneration
  const controller = new AbortController()
  reviewController = controller
  const followup = question.value.trim()
  reviewContent.value = ''
  reviewIncomplete.value = false
  reviewNotice.value = ''
  reviewing.value = true
  try {
    const token = useUserStore().getAccessToken()
    const resp = await fetch('/api/ai/review', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ submissionId: Number(props.submissionId), helpMode: helpMode.value,
        studentQuestion: followup || undefined,
        previousAnswer: followup && lastAnswer ? (lastAnswer.length <= 11000 ? lastAnswer : '【前次回复过长，以下为开头节选】\n' + lastAnswer.slice(0, 10900)) : undefined })
    })
    const answer = await readAiResponse(resp, text => { if (generation === reviewGeneration) reviewContent.value = text })
    if (generation === reviewGeneration) {
      lastAnswer = answer
      if (question.value.trim() === followup) question.value = ''
    }
  } catch (error) {
    if (generation !== reviewGeneration || controller.signal.aborted) return
    lastAnswer = reviewContent.value ? '【前次回复未完成】\n' + reviewContent.value : ''
    reviewIncomplete.value = true
    reviewNotice.value = error.message || 'AI 复盘请求失败，请重试'
    showApiError(error, reviewNotice.value)
  } finally { if (generation === reviewGeneration) reviewing.value = false }
}
</script>
<style scoped>
.review-scope { font-size: 13px; color: var(--text-secondary); }
.review-content { min-height: 120px; max-height: 40vh; overflow-y: auto; margin-bottom: 16px; }
.review-thinking { color: var(--text-secondary); padding: 32px 0; text-align: center; }
.review-incomplete { color: var(--el-color-warning-dark-2); }
label { display: block; margin: 12px 0 8px; font-size: 13px; }
</style>
