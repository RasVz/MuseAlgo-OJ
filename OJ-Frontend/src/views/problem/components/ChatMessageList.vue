<template>
  <div class="chat-history" ref="chatHistoryRef">
    <div
      v-for="(msg, index) in messages"
      :key="index"
      :class="['message-row', msg.role === 'user' ? 'user-row' : 'ai-row']"
    >
      <div class="avatar-box">
        <el-avatar v-if="msg.role === 'ai'" :size="36" class="ai-avatar">🤖</el-avatar>
        <el-avatar v-else :size="36" class="user-avatar">
          <el-icon><UserFilled /></el-icon>
        </el-avatar>
      </div>

      <div :class="['chat-bubble', msg.role === 'user' ? 'user-bubble' : 'ai-bubble']">
        <div
          v-if="msg.role === 'ai'"
          class="markdown-body custom-md"
          v-html="renderMarkdown(msg.content)"
        ></div>
        <div v-else class="bubble-content">{{ msg.content }}</div>
      </div>
    </div>

    <div v-if="thinking && !streamingContent" class="message-row ai-row">
      <div class="avatar-box">
        <el-avatar :size="36" class="ai-avatar">🤖</el-avatar>
      </div>
      <div class="chat-bubble ai-bubble thinking">
        <span class="typing-dots"><i></i><i></i><i></i></span>
        <span class="typing-text">教练正在思考中…</span>
      </div>
    </div>

    <div v-if="streamingContent" class="message-row ai-row">
      <div class="avatar-box">
        <el-avatar :size="36" class="ai-avatar">🤖</el-avatar>
      </div>
      <div class="chat-bubble ai-bubble">
        <div class="markdown-body custom-md" v-html="renderMarkdown(streamingContent)"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
// AI 对话消息列表：渲染历史消息与流式中的半成品消息，并在新消息到达时自动滚到底。
import { ref, watch, nextTick } from 'vue'
import { UserFilled } from '@element-plus/icons-vue'
import { createMarkdown } from '@/utils/markdown'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  thinking: { type: Boolean, default: false },
  streamingContent: { type: String, default: '' }
})

const chatHistoryRef = ref(null)

// 统一走全站 Markdown 工厂：放行 HTML + LaTeX + 换行转 <br>（聊天气泡）+ 代码高亮
const md = createMarkdown({ html: true, math: true, breaks: true })

const renderMarkdown = (text) => {
  if (!text) return ''
  return md.render(text)
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatHistoryRef.value) {
      chatHistoryRef.value.scrollTop = chatHistoryRef.value.scrollHeight
    }
  })
}

// streamingContent 也在监听范围内：AI 流式输出时消息条数不变，
// 只看 messages.length 会导致内容增长时页面不跟随滚动。
// nextTick 等 DOM 更新完再设 scrollTop，否则量到的是旧高度。
watch(
  () => [props.messages.length, props.streamingContent, props.thinking],
  scrollToBottom,
  { deep: true }
)
</script>

<style scoped>
.chat-history {
  flex: 1;
  overflow-y: auto;
  padding: 22px 20px 26px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.message-row { display: flex; align-items: flex-start; gap: 10px; width: 100%; }
.user-row { flex-direction: row-reverse; }
.ai-row { flex-direction: row; }
.avatar-box { flex-shrink: 0; }

.ai-avatar {
  background: linear-gradient(135deg, #6366f1, #8b5cf6);
  font-size: 18px;
  box-shadow: 0 3px 10px rgba(99, 102, 241, 0.28);
}

.user-avatar {
  background: linear-gradient(135deg, var(--text-secondary), var(--text-secondary));
  color: var(--bg-surface);
  font-size: 17px;
}

.chat-bubble {
  max-width: 78%;
  padding: 12px 16px;
  border-radius: 14px;
  font-size: 15px;
  line-height: 1.65;
  word-break: break-word;
}

.ai-bubble {
  background-color: var(--bg-surface);
  color: var(--text-primary);
  border: 1px solid var(--border-light);
  border-top-left-radius: 4px;
  box-shadow: 0 2px 10px rgba(31, 41, 55, 0.05);
}

.user-bubble {
  background: linear-gradient(135deg, #6366f1, #7c5cf6);
  color: var(--bg-surface);
  border-top-right-radius: 4px;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.22);
}

.bubble-content { white-space: pre-wrap; }

.custom-md { background-color: transparent !important; font-size: 15px; line-height: 1.65; color: var(--text-primary); }
:deep(.custom-md p) { margin-bottom: 10px; }
:deep(.custom-md p:last-child) { margin-bottom: 0; }
:deep(.custom-md pre) { background-color: #282c34 !important; color: #abb2bf; border-radius: 10px; padding: 12px 14px; margin: 10px 0; }
:deep(.custom-md code) { font-family: Consolas, Monaco, monospace; }
:deep(.custom-md p > code) { background-color: var(--bg-surface-hover); color: var(--accent-primary-dark); padding: 2px 6px; border-radius: 5px; font-size: 0.9em; }
:deep(.custom-md h1), :deep(.custom-md h2), :deep(.custom-md h3) { color: var(--text-primary); }

.thinking {
  background-color: transparent;
  border: none;
  box-shadow: none;
  padding-left: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.typing-dots { display: inline-flex; gap: 4px; }
.typing-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a5b4fc;
  display: inline-block;
  animation: ai-dot-blink 1.2s infinite ease-in-out;
}
.typing-dots i:nth-child(2) { animation-delay: 0.18s; }
.typing-dots i:nth-child(3) { animation-delay: 0.36s; }
@keyframes ai-dot-blink {
  0%, 70%, 100% { opacity: 0.35; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}
.typing-text { color: var(--text-secondary); font-size: 13px; }
</style>
