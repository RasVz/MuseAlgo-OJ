<template>
  <div>
    <div v-if="std.code" class="result-meta">
      <el-tag type="success" effect="light">复杂度 {{ std.complexity || '—' }}</el-tag>
      <el-switch :model-value="editMode" @update:model-value="$emit('update:editMode', $event)" active-text="编辑" />
      <el-button size="small" @click="copyText(std.code, '代码已复制')">复制代码</el-button>
      <el-button size="small" @click="copyText(stdMarkdown, '全文已复制')">复制全文</el-button>
      <el-button size="small" type="primary" :loading="savingStd" @click="$emit('save')">保存到该题</el-button>
      <el-button size="small" type="danger" plain :loading="clearingStd" @click="$emit('clear')">清除 std</el-button>
      <el-button size="small" type="primary" plain @click="$emit('regenerate')">重新生成</el-button>
    </div>
    <el-alert v-if="savedMsg" :type="savedOk ? 'success' : 'warning'" :closable="false"
      :title="savedMsg" class="mb12" />
    <template v-if="std.code">
      <div v-if="!editMode" class="code-wrap">
        <div class="markdown-body custom-md" v-html="renderCode(std.code)" />
      </div>
      <CodeBlockEditor
        v-else
        :model-value="std.code"
        language="cpp"
        :height="280"
        @update:model-value="$emit('update:code', $event)"
      />
      <div class="sub-title">算法思路</div>
      <div class="markdown-body custom-md" v-html="renderMarkdown(std.explanation)" />
    </template>
    <el-empty v-else description="正在加载题目并生成标准答案…" :image-size="70" />
  </div>
</template>

<script setup>
// 标准答案展示/编辑面板：纯展示组件，所有 AI 调用与保存动作都在 ProblemAiDialog 里。
// 这里只负责「查看态 ↔ 编辑态」切换与 Markdown 渲染。
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { createMarkdown } from '@/utils/markdown'
import CodeBlockEditor from './CodeBlockEditor.vue'

const props = defineProps({
  std: { type: Object, default: () => ({ code: '', explanation: '', complexity: '' }) },
  editMode: { type: Boolean, default: false },
  savingStd: { type: Boolean, default: false },
  clearingStd: { type: Boolean, default: false },
  savedMsg: { type: String, default: '' },
  savedOk: { type: Boolean, default: true }
})

defineEmits(['update:editMode', 'update:code', 'save', 'clear', 'regenerate'])

const md = createMarkdown({ html: false, math: true, breaks: true })
const renderMarkdown = (text) => (text ? md.render(text) : '')
const renderCode = (code) => (code ? md.render('```cpp\n' + code + '\n```') : '')

// 展示态把代码包进 ```cpp 再渲染，是为了复用同一套高亮，
// 免得为"只读的代码块"再写一个渲染分支。
const stdMarkdown = computed(() => props.std.code
  ? `# 标准答案（std）\n\n复杂度：${props.std.complexity || '—'}\n\n\`\`\`cpp\n${props.std.code}\n\`\`\`\n\n## 算法思路\n\n${props.std.explanation}`
  : '')

const copyText = async (text, msg) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(msg || '已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择复制')
  }
}
</script>

<style scoped>
.result-meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-primary); margin: 14px 0 8px; }
.code-wrap { border: 1px solid var(--border-base); border-radius: 10px; overflow: hidden; background: var(--bg-app); max-height: 420px; overflow-y: auto; }
.code-wrap :deep(pre) { margin: 0; padding: 14px 16px; }
.mb12 { margin-bottom: 12px; }
</style>
