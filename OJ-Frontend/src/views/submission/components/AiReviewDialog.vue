<template>
  <el-dialog v-model="dialogVisible" title="AI 代码复盘" width="720px" class="review-dialog">
    <div v-loading="reviewing" class="review-content">
      <div v-if="reviewContent" class="markdown-body custom-md" v-html="renderMarkdown(reviewContent)"></div>
      <div v-else-if="reviewing" class="review-thinking">教练正在分析你的代码与评测结果…</div>
    </div>
    <p v-if="reviewIncomplete" class="review-incomplete">回复未完成，请重试</p>
  </el-dialog>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// AI 复盘弹窗：对一次未通过的提交做错误分析，走 SSE 流式返回。
// 每次打开弹窗就重新请求一次（不复用上次结果）：复盘针对的是"这次提交"，
// 而提交状态可能已经变了，缓存旧结论会误导学生。
import { ref, watch, onBeforeUnmount } from 'vue'
import { useUserStore } from '@/stores/userStore'
import { createMarkdown } from '@/utils/markdown'
import { readAiResponse } from '@/utils/aiStream'

const props = defineProps({
  visible: { type: Boolean, default: false },
  submissionId: { type: [Number, String], default: null },
  problemId: { type: [Number, String], default: null }
})
const emit = defineEmits(['update:visible'])

const md = createMarkdown({ html: false, math: true, breaks: true })
const dialogVisible = ref(false)
const reviewContent = ref('')
const reviewing = ref(false)
const reviewIncomplete = ref(false)
let reviewController = null
let reviewGeneration = 0
const cancelReview = () => {
  reviewGeneration++
  reviewController?.abort()
  reviewing.value = false
}
onBeforeUnmount(cancelReview)

watch(
  () => props.visible,
  (val) => {
    dialogVisible.value = val
    if (val) startReview()
  }
)

// v-model 的手动转发：el-dialog 自身关闭（点遮罩/按 ESC）时要通知父组件收起。
watch(dialogVisible, (val) => {
  if (!val) cancelReview()
  emit('update:visible', val)
})

const renderMarkdown = (text) => (text ? md.render(text) : '')

// 直接用 fetch 而非 axios 封装：axios 拦截器会尝试解包 JSON，
// 遇到 SSE 流会破坏分块结构，拿不到逐段推送的效果。
const startReview = async () => {
  if (!props.submissionId) return
  reviewController?.abort()
  const generation = ++reviewGeneration
  const controller = new AbortController()
  reviewController = controller
  reviewContent.value = ''
  reviewIncomplete.value = false
  reviewing.value = true
  try {
    const token = useUserStore().getAccessToken()
    const resp = await fetch('/api/ai/review', {
      method: 'POST',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ submissionId: Number(props.submissionId) })
    })
    await readAiResponse(resp, text => {
      if (generation === reviewGeneration) reviewContent.value = text
    })
  } catch (error) {
    if (generation !== reviewGeneration || controller.signal.aborted) return
    reviewIncomplete.value = true
    showApiError(error, error.message || 'AI 复盘请求失败，请稍后重试')
  } finally {
    if (generation === reviewGeneration) reviewing.value = false
  }
}
</script>

<style scoped>
.review-content {
  min-height: 160px;
  max-height: 55vh;
  overflow-y: auto;
}

.review-thinking {
  color: var(--text-secondary);
  text-align: center;
  padding: 40px 0;
  font-size: 14px;
}
</style>
