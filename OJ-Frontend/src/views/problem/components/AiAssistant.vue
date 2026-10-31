<template>
  <el-drawer
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    size="700px"
    direction="rtl"
    :append-to-body="true"
    class="ai-chat-drawer"
  >
    <template #header>
      <div class="ai-drawer-header">
        <div class="ai-brand">
          <div class="ai-logo">
            <span class="ai-logo-emoji">🤖</span>
          </div>
          <div class="ai-brand-text">
            <div class="ai-brand-title">AI 教学教练</div>
            <div class="ai-brand-sub">睿思学院 · 你的专属编程助教</div>
          </div>
        </div>
        <el-tag size="small" effect="light" round class="ai-online-tag">
          <span class="ai-online-dot"></span>
          <span>在线</span>
        </el-tag>
      </div>
    </template>

    <div class="chat-container">
      <ChatMessageList
        :messages="chatMessages"
        :thinking="isAiThinking"
        :streaming-content="streamingContent"
      />

      <div class="chat-input-area">
        <HintLadderBar
          :level-names="HINT_LEVEL_NAMES"
          :hint-level="hintLevel"
          :unlocked-level="unlockedLevel"
          :disabled="isAiThinking"
          @toggle="toggleHintLevel"
        />
        <div class="composer">
          <el-input
            v-model="currentQuestion"
            type="textarea"
            :rows="3"
            placeholder="告诉教练你的思路，或描述哪里卡住了..."
            resize="none"
            class="custom-chat-input"
            @keyup.enter.exact="askAi"
          />
          <div class="composer-footer">
            <span class="composer-hint">Enter 发送 · Shift+Enter 换行</span>
            <el-button
              type="primary"
              circle
              class="send-btn"
              :loading="isAiThinking"
              @click="askAi"
            >
              <el-icon><Promotion /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
// AI 答疑抽屉：以「提示阶梯」的方式引导学生，而不是直接给答案。
//
// Hint Ladder 分四级（思路方向 → 关键观察 → 伪代码 → 完整题解），
// 一次只能取一级，且解锁级别存在服务端——刷新页面或换设备不会退回 L1，
// 避免学生靠刷新反复免费拿下一级。
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/userStore'
import { getHintLevel } from '@/api/ai'
import ChatMessageList from './ChatMessageList.vue'
import HintLadderBar from './HintLadderBar.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  problem: { type: Object, default: () => ({}) },
  studentCode: { type: String, default: '' }
})

defineEmits(['update:visible'])

const isAiThinking = ref(false)
const currentQuestion = ref('')
const streamingContent = ref('')

// Hint Ladder：hintLevel 0=默认引导（不给提示）；unlockedLevel 为服务端同步的已解锁级别（初始 L1）。
// 两者分开：unlockedLevel 是"最多能给到第几级"，hintLevel 是"这次给第几级"，
// 前端只能在 unlockedLevel 范围内选，越界的请求后端会直接拒绝。
const HINT_LEVEL_NAMES = ['思路方向', '关键观察', '伪代码', '完整题解']
const hintLevel = ref(0)
const unlockedLevel = ref(1)

// 挂载/切题时调用 GET /ai/hint/level 恢复解锁状态（未解锁返回 1，失败静默保持 L1）
// 注意：必须在下方 watch(immediate) 之前定义——immediate 回调在 setup 同步阶段
// 立即执行，const 函数声明在其后会被 TDZ（暂时性死区）拦截，抛 ReferenceError
const restoreHintLevel = async (problemId) => {
  if (!problemId) return
  try {
    const res = await getHintLevel(problemId)
    if (res?.code === 200 && res?.data >= 1 && res?.data <= 4) {
      unlockedLevel.value = res.data
    }
  } catch (error) {
    console.warn('Hint Ladder 解锁状态恢复失败:', error)
  }
}

// 切换题目时重置阶梯选择，并从服务端恢复该题已解锁级别（刷新/换设备不丢）
// 先本地重置为 L1，再异步拉服务端状态：接口慢或失败时至少保证抽屉能正常打开，
// 而不是卡在"加载中"让用户以为功能坏了。
watch(
  () => props.problem?.id,
  (problemId) => {
    hintLevel.value = 0
    unlockedLevel.value = 1
    restoreHintLevel(problemId)
  },
  { immediate: true }
)

const toggleHintLevel = (level) => {
  hintLevel.value = hintLevel.value === level ? 0 : level
}

const chatMessages = ref([
  { role: 'ai', content: '你好！我是你的专属编程教练。\n\n写代码遇到卡壳了吗？不用把代码发给我（我已经能看到了），直接告诉我你的疑惑吧！' }
])

// 每次提问都带上题面与学生当前代码：AI 是无状态的，
// 不给上下文就只能给出泛泛而谈的建议，无法定位学生卡在哪一步。
const getProblemContext = () => {
  return {
    problemTitle: props.problem?.title || '未知题目',
    problemContent: props.problem?.description || props.problem?.content || '未知描述',
    studentCode: props.studentCode || '学生还未编写任何代码' 
  }
}

// 先把用户问题入列再清空输入框：即使请求失败，这条提问也留在聊天记录里，
// 学生可以直接重试而不必重新打字。isAiThinking 同时兼作并发互斥。
const askAi = async () => {
  if (!currentQuestion.value.trim() || isAiThinking.value) return
  
  const questionText = currentQuestion.value
  chatMessages.value.push({ role: 'user', content: questionText })
  currentQuestion.value = ''
  isAiThinking.value = true
  streamingContent.value = ''

  try {
    const context = getProblemContext()
    await streamAsk({
      problemId: props.problem?.id,
      problemTitle: context.problemTitle,
      problemContent: context.problemContent,
      studentCode: context.studentCode,
      studentQuestion: questionText,
      hintLevel: hintLevel.value
    })
  } catch (error) {
    console.error('AI 请求错误:', error)
    ElMessage.error('网络异常，请重试')
  } finally {
    isAiThinking.value = false
    streamingContent.value = ''
  }
}

/**
 * 流式调用：直接 fetch（axios 拦截器会解包 JSON，不适合 SSE），
 * 手动带 Token，逐段解析 data: 行并实时渲染。
 */
const streamAsk = async (payload) => {
  const token = useUserStore().getAccessToken()
  const resp = await fetch('/api/ai/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload)
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let fullText = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 事件用空行分隔；一个事件里可能有多个 data: 行（内容含换行时）
    // buffer.pop() 把最后一段（可能还没收完的半条事件）留回缓冲区，
    // 否则跨 chunk 到达的事件会被截断成两半。
    const events = buffer.split('\n\n')
    buffer = events.pop()
    for (const event of events) {
      const dataLines = event.split('\n').filter(l => l.startsWith('data:'))
      // 注意：只能去掉 "data:" 后的一个分隔空格，绝不能 trim()——
      // 代码行开头的缩进空格会被 trim 掉，导致 AI 给出的代码全部失去缩进
      const chunkText = dataLines.map(l => l.slice(5).replace(/^ /, '')).join('\n')
      if (chunkText && chunkText !== '[DONE]') {
        fullText += chunkText
        streamingContent.value = fullText
      }
    }
  }

  // 流结束后才把内容落进 messages：流式内容由 streamingContent 单独驱动，
// 中途入列会产生一条空消息 + 一条完整消息的重复。
  if (!fullText.trim()) {
    ElMessage.error('教练没有回复，请稍后重试')
  } else {
    chatMessages.value.push({ role: 'ai', content: fullText })
    // 成功获得当前级别引导后才解锁下一级（上限 4 级），与后端 unlockNextHintLevel 保持一致
    if (hintLevel.value > 0) {
      unlockedLevel.value = Math.max(unlockedLevel.value, Math.min(hintLevel.value + 1, 4))
    }
  }
}
</script>

<style scoped>
/* 去掉 el-drawer 默认留白，让内部容器接管空间 */
:global(.ai-chat-drawer .el-drawer__header) {
  margin-bottom: 0 !important;
  padding: 16px 20px !important;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-surface);
}
:global(.ai-chat-drawer .el-drawer__body) {
  padding: 0 !important;
  background: var(--bg-app);
}

/* 顶部品牌区 */
.ai-drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}
.ai-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-logo {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 5px 14px rgba(99, 102, 241, 0.32);
  flex-shrink: 0;
}
.ai-logo-emoji {
  font-size: 21px;
  line-height: 1;
}
.ai-brand-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.25;
}
.ai-brand-sub {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 3px;
}
.ai-online-tag {
  background: #eef2ff !important;
  border: none !important;
  color: #4f46e5 !important;
  font-size: 12px;
  padding: 4px 10px;
}
.ai-online-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-success);
  margin-right: 5px;
  vertical-align: 1px;
}

/* 聊天区整体 */
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: linear-gradient(180deg, var(--bg-app) 0%, var(--bg-surface-hover) 100%);
}

/* 底部输入区 */
.chat-input-area {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
}

.composer {
  border: 1px solid var(--border-base);
  border-radius: 16px;
  padding: 8px 12px 8px 14px;
  background: var(--bg-surface);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.composer:focus-within {
  border-color: #a5b4fc;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
}
:deep(.custom-chat-input .el-textarea__inner) {
  background-color: transparent;
  border: none;
  box-shadow: none;
  padding: 6px 4px;
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.6;
}
:deep(.custom-chat-input .el-textarea__inner:focus) {
  box-shadow: none;
}
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}
.composer-hint {
  font-size: 11px;
  color: var(--text-placeholder);
}
.send-btn {
  width: 36px;
  height: 36px;
  border: none;
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.35);
  font-size: 16px;
}
.send-btn:hover {
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
  box-shadow: 0 6px 16px rgba(99, 102, 241, 0.42);
}
</style>
