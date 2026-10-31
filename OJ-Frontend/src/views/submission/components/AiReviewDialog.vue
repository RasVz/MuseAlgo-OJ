<template>
  <el-dialog v-model="dialogVisible" title="AI 代码复盘" width="720px" class="review-dialog">
    <div v-loading="reviewing" class="review-content">
      <div v-if="reviewContent" class="markdown-body custom-md" v-html="renderMarkdown(reviewContent)"></div>
      <div v-else-if="reviewing" class="review-thinking">教练正在分析你的代码与评测结果…</div>
    </div>
  </el-dialog>
</template>

<script setup>
// AI 复盘弹窗：对一次未通过的提交做错误分析，走 SSE 流式返回。
// 每次打开弹窗就重新请求一次（不复用上次结果）：复盘针对的是"这次提交"，
// 而提交状态可能已经变了，缓存旧结论会误导学生。
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'
import { createMarkdown } from '@/utils/markdown'

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

watch(
  () => props.visible,
  (val) => {
    dialogVisible.value = val
    if (val) startReview()
  }
)

// v-model 的手动转发：el-dialog 自身关闭（点遮罩/按 ESC）时要通知父组件收起。
watch(dialogVisible, (val) => emit('update:visible', val))

const renderMarkdown = (text) => (text ? md.render(text) : '')

// 直接用 fetch 而非 axios 封装：axios 拦截器会尝试解包 JSON，
// 遇到 SSE 流会破坏分块结构，拿不到逐段推送的效果。
const startReview = async () => {
  if (!props.submissionId) return
  reviewContent.value = ''
  reviewing.value = true
  try {
    const token = useUserStore().getAccessToken()
    const resp = await fetch('/api/ai/review', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ submissionId: Number(props.submissionId), problemId: props.problemId ?? null })
    })
    if (!resp.ok || !resp.body) {
      ElMessage.error('AI 复盘请求失败，请稍后重试')
      return
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let fullText = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
// buffer.pop() 把最后一段留回缓冲区：SSE 事件可能跨 chunk 到达，
    // 不留残段会把一条完整事件截成两半。
    const events = buffer.split('\n\n')
    buffer = events.pop()
    for (const event of events) {
      const dataLines = event.split('\n').filter((l) => l.startsWith('data:'))
      // 只去掉 "data:" 后的一个分隔空格，绝不能 trim()：
      // 代码行开头的缩进会被 trim 掉，复盘里给出的代码就全乱了
      const chunkText = dataLines.map((l) => l.slice(5).replace(/^ /, '')).join('\n')
        if (chunkText && chunkText !== '[DONE]') {
          fullText += chunkText
          reviewContent.value = fullText
        }
      }
    }
    if (!fullText.trim()) {
      ElMessage.error('教练没有回复，请稍后重试')
    }
  } catch (e) {
    console.error('AI 复盘请求错误:', e)
    ElMessage.error('网络异常，请重试')
  } finally {
    reviewing.value = false
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
