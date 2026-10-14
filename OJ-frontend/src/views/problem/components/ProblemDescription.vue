<template>
  <div class="problem-description-box">
    <div class="header-row">
      <h1 class="problem-title">{{ props.problem.title }}</h1> 
      <div class="button-group">
        <el-button class="ai-helper-btn" @click="$emit('open-ai')">
          <span style="font-size: 15px; margin-right: 5px;">🤖</span> AI 助教
        </el-button>
        <el-button :type="isIdeMode ? 'danger' : 'primary'" 
          plain round size="small" @click="$emit('toggle-ide')">
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

    <template v-if="samples.length">
      <h3 class="sub-title">样例</h3>
      <div v-for="(sample, index) in samples" :key="sample.id ?? index" class="sample-item">
        <div class="sample-container">
          <div class="sample-header">
            <span class="sample-badge">样例 #{{ index + 1 }}</span>
            <div class="sample-btns">
              <el-button type="primary" link size="small" @click="handleRunSample(sample)">运行</el-button>
              <el-button link size="small" @click="copy(sample.input)">复制输入</el-button>
              <el-button link size="small" @click="copy(sample.output)">复制输出</el-button>
            </div>
          </div>
          <pre class="code-block">{{ sample.input }}</pre>

          <div class="sample-header output-header">
            <span class="sample-badge">Output</span>
          </div>
          <pre class="code-block">{{ sample.output }}</pre>
        </div>
      </div>
    </template>

    <div v-if="props.problem.hint" class="hint-section">
      <h3 class="sub-title">提示</h3>
      <div class="markdown-body hint-body" v-html="renderMarkdown(props.problem.hint)"></div>
    </div>
  </div> 
</template>

<script setup>
// 题面渲染组件：把 Markdown 题面转成 HTML，并承载"进入 IDE / 试跑样例 / 打开 AI"三个动作。
// simple 模式用于 IDE 布局下的窄栏渲染，isIdeMode 影响按钮文案与布局。
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Monitor, CloseBold } from '@element-plus/icons-vue' 
import { createMarkdown } from '@/utils/markdown'

const props = defineProps({
  problem: { type: Object, default: () => ({}) },
  simple: { type: Boolean, default: false },
  isIdeMode: { type: Boolean, default: false }
  // 删除了 studentCode，因为这由父组件和 AI 组件直接沟通
})

// 增加了 open-ai 事件
const emit = defineEmits(['toggle-ide', 'run-sample', 'open-ai'])

const samples = computed(() => {
  if (Array.isArray(props.problem.samples) && props.problem.samples.length) {
    return props.problem.samples
  }
  // 兼容旧数据：只剩一组样例时兜底展示
  if (props.problem.inputSample != null || props.problem.outputSample != null) {
    return [{ input: props.problem.inputSample || '', output: props.problem.outputSample || '' }]
  }
  return []
})

// 先发 toggle-ide 再发 run-sample：顺序不能反。
// 父组件需要先切换到 IDE 模式把编辑器渲染出来，才能往里填样例并触发运行。
const handleRunSample = (sample) => {
  emit('toggle-ide')
  emit('run-sample', {
    input: sample?.input || '',
    output: sample?.output || ''
  })
}

// 统一走全站 Markdown 工厂：放行 HTML + LaTeX + 代码高亮
const md = createMarkdown({ html: true, math: true })

const renderMarkdown = (text) => {
  if (!text) return ''
  return md.render(text)
}

const copy = (text) => {
  navigator.clipboard.writeText(text)
  ElMessage.success('已复制到剪贴板')
}
</script>

<style scoped>
.problem-description-box { color: var(--text-primary); }
.header-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; padding-bottom: 15px; border-bottom: 1px solid var(--el-border-color-lighter); }
.button-group { display: flex; gap: 10px; align-items: center; }
.ai-helper-btn {
  border: none;
  border-radius: 20px;
  padding: 9px 18px;
  background: linear-gradient(135deg, #6366f1, #a855f7);
  color: var(--text-on-accent);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
}
.ai-helper-btn:hover {
  background: linear-gradient(135deg, #4f46e5, #9333ea);
  color: var(--text-on-accent);
}
.problem-title { font-size: 26px; font-weight: 600; margin: 0; color: var(--text-primary); line-height: 1.2; }
.sub-title { font-size: 16px; border-left: 4px solid var(--el-color-primary); padding-left: 10px; margin: 25px 0 12px; color: var(--text-primary); }
.content-area { font-size: 17px; line-height: 1.8; color: var(--text-regular); }
.io-section { display: block; }
.io-block { min-width: 300px; }
.sample-container { border: 1px solid var(--border-light); border-radius: 6px; overflow: hidden; }
.sample-item { margin-bottom: 14px; }
.sample-header { background: var(--bg-input); padding: 6px 12px; border-bottom: 1px solid var(--border-light); display: flex; justify-content: space-between; align-items: center; }
.output-header { border-top: 1px solid var(--border-light); }
.sample-badge { font-size: 12px; font-weight: bold; color: var(--text-regular); }
.code-block { margin: 0; padding: 12px; background: var(--bg-surface); font-family: Consolas, monospace; font-size: 14px; white-space: pre-wrap; color: var(--text-primary); }
.hint-body { background: var(--accent-primary-light); padding: 15px; border-radius: 4px; color: var(--text-primary); font-size: 14px; }
</style>
