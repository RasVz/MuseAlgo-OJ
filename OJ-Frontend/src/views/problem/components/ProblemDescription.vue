<template>
  <div class="problem-description-box">
    <div class="header-row">
      <h1 class="problem-title">{{ props.problem.title }}</h1>
      <div class="button-group">
        <el-button v-if="aiEnabled" size="small" round class="ai-helper-btn" @click="$emit('open-ai')">
          <svg class="ai-helper-icon" width="15" height="15" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 3l1.4 4.1 4.1 1.4-4.1 1.4L12 14l-1.4-4.1-4.1-1.4 4.1-1.4L12 3z" />
            <path d="M18 14l.8 2.2L21 17l-2.2.8L18 20l-.8-2.2L15 17l2.2-.8L18 14z" />
          </svg>
          <span>AI 助教</span>
        </el-button>
        <el-button
          :type="isIdeMode ? 'danger' : 'primary'"
          plain
          round
          size="small"
          @click="$emit('toggle-ide')"
        >
          <template v-if="isIdeMode">
            <el-icon class="el-icon--left"><CloseBold /></el-icon> 退出 IDE 模式
          </template>
          <template v-else>
            <el-icon class="el-icon--left"><Monitor /></el-icon> 进入 IDE 模式
          </template>
        </el-button>
      </div>
    </div>

    <div class="markdown-body content-area" v-html="renderMarkdown(props.problem.content)"></div>

    <div class="io-section">
      <div class="io-block">
        <h3 class="sub-title">输入描述</h3>
        <div class="markdown-body" v-html="renderMarkdown(props.problem.inputDesc)"></div>
      </div>
      <div class="io-block">
        <h3 class="sub-title">输出描述</h3>
        <div class="markdown-body" v-html="renderMarkdown(props.problem.outputDesc)"></div>
      </div>
    </div>

    <ProblemSamples :samples="samples" />

    <div v-if="props.problem.hint" class="hint-section">
      <h3 class="sub-title">提示</h3>
      <div class="markdown-body hint-body" v-html="renderMarkdown(props.problem.hint)"></div>
    </div>
  </div>
</template>

<script setup>
// 题面渲染组件：把 Markdown 题面转成 HTML，并承载"进入 IDE / 打开 AI"两个动作。
// 官方样例由 ProblemSamples 只读展示；自测面板属于编辑器工作台，不在这里挂载。
import { Monitor, CloseBold } from '@element-plus/icons-vue'
import ProblemSamples from './ProblemSamples.vue'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'

const props = defineProps({
  aiEnabled: { type: Boolean, default: true },
  problem: { type: Object, default: () => ({}) },
  samples: { type: Array, default: () => [] },
  isIdeMode: { type: Boolean, default: false }
})

defineEmits(['toggle-ide', 'open-ai'])

// 统一走全站 Markdown 工厂：放行 HTML + LaTeX + 代码高亮。
// 放行 HTML 是因为 FPS 导入的题面以 HTML 存储；因此渲染前先按白名单消毒，
// 保证历史数据里残留的 script/onerror 等内容不会被 v-html 执行。
const md = createMarkdown({ html: true, math: true })

const renderMarkdown = text => {
  if (!text) return ''
  return md.render(sanitizeStatementMarkup(text))
}
</script>

<style scoped>
.problem-description-box {
  color: var(--text-primary);
}
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.button-group {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-shrink: 0;
}
.ai-helper-btn {
  height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  background: var(--accent-primary-light);
  color: var(--accent-primary-dark);
  box-shadow: none;
  font-size: 13px;
  font-weight: 500;
}
.ai-helper-btn:hover,
.ai-helper-btn:focus {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-8);
  color: var(--accent-primary-dark);
}
.ai-helper-btn:focus-visible {
  outline: 2px solid var(--accent-primary);
  outline-offset: 2px;
}
.ai-helper-icon {
  flex-shrink: 0;
}
.dark .ai-helper-btn {
  border-color: #31527f;
  background: rgba(37, 99, 235, 0.18);
  color: #93c5fd;
}
.dark .ai-helper-btn:hover,
.dark .ai-helper-btn:focus {
  border-color: #3b82f6;
  background: rgba(37, 99, 235, 0.28);
  color: #bfdbfe;
}
.problem-title {
  font-size: 26px;
  font-weight: 600;
  margin: 0;
  color: var(--text-primary);
  line-height: 1.2;
  min-width: 0;
  overflow-wrap: anywhere;
}
.sub-title {
  font-size: 16px;
  border-left: 4px solid var(--el-color-primary);
  padding-left: 10px;
  margin: 25px 0 12px;
  color: var(--text-primary);
  font-weight: 600;
}
.content-area {
  font-size: 17px;
  line-height: 1.8;
  color: var(--text-regular);
}
.io-section {
  display: block;
}
.io-block {
  min-width: 0;
}
.hint-body {
  background: var(--accent-primary-light);
  padding: 15px;
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 14px;
}
/* 深色主题：浅色强调底仍是 #eff6ff，配 --text-primary 会变成浅底浅字。
   换成跟随主题的输入框底色，并保留左侧色条表达"提示"语义。 */
.dark .hint-body {
  background: var(--bg-input);
  border-left: 3px solid var(--accent-primary);
}
</style>
