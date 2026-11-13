<template>
  <div class="block-editor">
    <div class="block-toolbar" @mousedown="keepSelection">
      <el-select :model-value="headingValue" size="small" class="heading-select" @change="setHeading">
        <el-option label="正文" value="paragraph" />
        <el-option label="标题 1" value="1" />
        <el-option label="标题 2" value="2" />
        <el-option label="标题 3" value="3" />
      </el-select>

      <button type="button" :class="{ active: editor?.isActive('bold') }" title="加粗" @click="run((c) => c.toggleBold())">B</button>
      <button type="button" class="italic" :class="{ active: editor?.isActive('italic') }" title="斜体" @click="run((c) => c.toggleItalic())">I</button>
      <button type="button" class="strike" :class="{ active: editor?.isActive('strike') }" title="删除线" @click="run((c) => c.toggleStrike())">S</button>
      <button type="button" :class="{ active: editor?.isActive('code') }" title="行内代码" @click="run((c) => c.toggleCode())">&lt;/&gt;</button>

      <span class="divider" />

      <button type="button" :class="{ active: editor?.isActive('bulletList') }" title="无序列表" @click="run((c) => c.toggleBulletList())">• 列表</button>
      <button type="button" :class="{ active: editor?.isActive('orderedList') }" title="有序列表" @click="run((c) => c.toggleOrderedList())">1. 列表</button>
      <button type="button" :class="{ active: editor?.isActive('blockquote') }" title="引用" @click="run((c) => c.toggleBlockquote())">引用</button>
      <button type="button" :class="{ active: editor?.isActive('codeBlock') }" title="代码块" @click="run((c) => c.toggleCodeBlock())">代码块</button>
      <button type="button" title="分割线" @click="run((c) => c.setHorizontalRule())">分割线</button>
      <button type="button" title="链接" @click="insertLink">链接</button>
      <button type="button" title="行内公式" @click="insertMath(false)">公式</button>
      <button type="button" title="块级公式" @click="insertMath(true)">公式块</button>
      <button type="button" :class="{ active: inTable }" title="插入 3×3 表格" @click="run((c) => c.insertTable({ rows: 3, cols: 3, withHeaderRow: true }))">表格</button>

      <template v-if="inTable">
        <button type="button" title="在下方插入行" @click="run((c) => c.addRowAfter())">+行</button>
        <button type="button" title="在右侧插入列" @click="run((c) => c.addColumnAfter())">+列</button>
        <button type="button" title="删除当前行" @click="run((c) => c.deleteRow())">-行</button>
        <button type="button" title="删除当前列" @click="run((c) => c.deleteColumn())">-列</button>
        <button type="button" title="删除表格" @click="run((c) => c.deleteTable())">删表</button>
      </template>

      <el-select
        v-if="inCodeBlock"
        :model-value="codeLanguage"
        size="small"
        class="language-select"
        placeholder="语言"
        @change="setLanguage"
      >
        <el-option v-for="item in languageOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>

      <span class="divider" />

      <button type="button" :disabled="uploading" title="插入图片，也可直接粘贴或拖拽" @click="pickImage">
        {{ uploading ? '上传中…' : '图片' }}
      </button>
      <input ref="fileInput" type="file" accept="image/*" hidden @change="onFileChange" />

      <span class="spacer" />

      <button type="button" :disabled="!editor?.can().undo()" title="撤销" @click="run((c) => c.undo())">撤销</button>
      <button type="button" :disabled="!editor?.can().redo()" title="重做" @click="run((c) => c.redo())">重做</button>
    </div>

    <EditorContent :editor="editor" class="block-content" />
  </div>
</template>

<script setup>
// 块编辑器：Tiptap 负责编辑交互，Markdown 负责存储与展示。
// 这里不提供"预览"标签——块编辑器本身就是所见即所得；
// 展示一致性靠 markdown-body 样式、hljs（lowlight）与 MathJax 与详情页对齐。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import Image from '@tiptap/extension-image'
import { CodeBlockLowlight } from '@tiptap/extension-code-block-lowlight'
import { common, createLowlight } from 'lowlight'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'highlight.js/styles/github.css'
import 'github-markdown-css/github-markdown.css'
import { uploadImage } from '@/api/user'
import { showApiError } from '@/utils/errorFeedback'
import { docToMarkdown, markdownToDoc } from '@/utils/markdown-blocks'
import { MathBlock, MathInline } from '../extensions/math'
import { tableExtensions } from '../extensions/table'

const lowlight = createLowlight(common)

// 只暴露常用语言，避免下拉框过长；都包含在 lowlight 的 common 集合里。
const languageOptions = [
  { value: '', label: '自动' },
  { value: 'javascript', label: 'JavaScript' },
  { value: 'typescript', label: 'TypeScript' },
  { value: 'python', label: 'Python' },
  { value: 'cpp', label: 'C++' },
  { value: 'c', label: 'C' },
  { value: 'java', label: 'Java' },
  { value: 'go', label: 'Go' },
  { value: 'rust', label: 'Rust' },
  { value: 'sql', label: 'SQL' },
  { value: 'bash', label: 'Bash' },
  { value: 'json', label: 'JSON' },
  { value: 'yaml', label: 'YAML' },
  { value: 'xml', label: 'HTML/XML' },
  { value: 'css', label: 'CSS' },
  { value: 'markdown', label: 'Markdown' },
  { value: 'plaintext', label: '纯文本' }
]

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '开始写作…支持 Markdown、LaTeX 与代码块' }
})

const emit = defineEmits(['update:modelValue'])

const uploading = ref(false)
const fileInput = ref(null)
const revision = ref(0)

const isImageFile = (file) => Boolean(file?.type?.startsWith('image/'))

const editor = useEditor({
  content: markdownToDoc(props.modelValue),
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
      codeBlock: false,
      link: { openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' } }
    }),
    CodeBlockLowlight.configure({ lowlight, defaultLanguage: null }),
    Placeholder.configure({ placeholder: props.placeholder }),
    Image.configure({ inline: true, allowBase64: false }),
    MathInline,
    MathBlock,
    ...tableExtensions
  ],
  editorProps: {
    attributes: { class: 'block-editor-content markdown-body' },
    handlePaste: (view, event) => {
      const files = event.clipboardData?.files
      if (!files?.length || !Array.from(files).some(isImageFile)) return false
      event.preventDefault()
      insertImageFiles(files)
      return true
    },
    handleDrop: (view, event, slice, moved) => {
      if (moved) return false
      const files = event.dataTransfer?.files
      if (!files?.length || !Array.from(files).some(isImageFile)) return false
      event.preventDefault()
      const pos = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos
      insertImageFiles(files, pos)
      return true
    }
  },
  onTransaction: () => { revision.value += 1 },
  onUpdate: ({ editor: instance }) => {
    emit('update:modelValue', docToMarkdown(instance.getJSON()))
  },
  onCreate: ({ editor: instance }) => {
    // 仅开发态暴露，供浏览器脚本核对文档结构与往返结果；生产构建会被移除。
    if (import.meta.env.DEV) window.__blockEditor = instance
  }
})

// 图片上传：工具栏选择、粘贴、拖拽共用同一条路径。
// pos 为空时插入当前选区；拖拽时优先插到落点。
const insertImageFiles = async (files, pos) => {
  const images = Array.from(files || []).filter(isImageFile)
  if (!images.length) return false
  uploading.value = true
  try {
    const nodes = []
    for (const file of images) {
      const res = await uploadImage(file)
      if (res.code !== 200 || !res.data) throw new Error(res.message || '上传失败')
      nodes.push({ type: 'image', attrs: { src: res.data, alt: file.name } })
    }
    const chain = editor.value.chain().focus()
    if (Number.isInteger(pos)) chain.insertContentAt(pos, nodes).run()
    else chain.insertContent(nodes).run()
    ElMessage.success(images.length > 1 ? `已插入 ${images.length} 张图片` : '图片已插入')
  } catch (error) {
    showApiError(error, error.message || '图片上传失败')
  } finally {
    uploading.value = false
  }
  return true
}

const currentMarkdown = () => (editor.value ? docToMarkdown(editor.value.getJSON()) : '')

// 只在外部内容确实变化时重置，避免父组件回写导致的循环与光标跳动。
watch(() => props.modelValue, (value) => {
  if (!editor.value) return
  if ((value || '') === currentMarkdown()) return
  editor.value.commands.setContent(markdownToDoc(value), false)
})

const headingValue = computed(() => {
  revision.value
  if (!editor.value) return 'paragraph'
  for (const level of [1, 2, 3]) {
    if (editor.value.isActive('heading', { level })) return String(level)
  }
  return 'paragraph'
})

const inCodeBlock = computed(() => {
  revision.value
  return Boolean(editor.value?.isActive('codeBlock'))
})

const inTable = computed(() => {
  revision.value
  return Boolean(editor.value?.isActive('table'))
})

const codeLanguage = computed(() => {
  revision.value
  return editor.value?.getAttributes('codeBlock')?.language || ''
})

const run = (command) => {
  if (!editor.value) return
  command(editor.value.chain().focus()).run()
}

// 工具栏按钮在 mousedown 阶段就阻止默认行为：否则浏览器会把焦点移出编辑器，
// 选区在命令执行前已丢失，插入类命令会静默失败（下拉框需要正常聚焦，故只拦按钮）。
const keepSelection = (event) => {
  if (event.target.closest('button')) event.preventDefault()
}

const setHeading = (value) => {
  if (!editor.value) return
  const chain = editor.value.chain().focus()
  if (value === 'paragraph') chain.setParagraph().run()
  else chain.toggleHeading({ level: Number(value) }).run()
}

const setLanguage = (value) => {
  if (!editor.value) return
  editor.value.chain().focus().updateAttributes('codeBlock', { language: value || null }).run()
}

const insertLink = async () => {
  if (!editor.value) return
  try {
    const { value } = await ElMessageBox.prompt('输入链接地址', '插入链接', {
      inputPlaceholder: 'https://',
      confirmButtonText: '插入',
      cancelButtonText: '取消'
    })
    if (!value) return
    const href = /^https?:\/\//i.test(value) ? value : `https://${value}`
    editor.value.chain().focus().extendMarkRange('link').setLink({ href }).run()
  } catch {
    /* 取消不做处理 */
  }
}

// 插入空公式后由节点视图自动进入编辑态。
const insertMath = (block) => {
  if (!editor.value) return
  editor.value.chain().focus().insertContent({
    type: block ? 'mathBlock' : 'mathInline',
    attrs: { latex: '' }
  }).run()
}

const pickImage = () => fileInput.value?.click()

const onFileChange = async (event) => {
  const files = event.target.files
  event.target.value = ''
  if (files?.length) await insertImageFiles(files)
}

onBeforeUnmount(() => editor.value?.destroy())

defineExpose({
  getMarkdown: currentMarkdown,
  getDoc: () => editor.value?.getJSON() ?? null,
  focus: () => editor.value?.commands.focus()
})
</script>

<style scoped>
.block-editor {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.block-toolbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-surface);
  flex-wrap: wrap;
}
.block-toolbar button {
  min-width: 28px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--text-regular);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.block-toolbar button:hover:not(:disabled) { background: var(--bg-surface-hover); }
.block-toolbar button.active { background: var(--accent-primary-light); color: var(--accent-primary); }
.block-toolbar button:disabled { color: var(--text-placeholder); cursor: default; }
.block-toolbar .italic { font-style: italic; }
.block-toolbar .strike { text-decoration: line-through; }
.block-toolbar .divider { width: 1px; height: 18px; margin: 0 4px; background: var(--border-light); }
.block-toolbar .spacer { flex: 1; }
.heading-select { width: 96px; margin-right: 4px; }
.language-select { width: 110px; }
.block-content { min-height: 520px; }
.block-content :deep(.block-editor-content) {
  min-height: 520px;
  padding: 24px 4px 80px;
  outline: none;
  font-size: 15px;
  line-height: 1.8;
  color: var(--text-primary);
}
.block-content :deep(.block-editor-content p.is-editor-empty:first-child::before) {
  content: attr(data-placeholder);
  float: left;
  height: 0;
  color: var(--text-placeholder);
  pointer-events: none;
}
.block-content :deep(.block-editor-content pre) {
  overflow-x: auto;
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--bg-code, #f6f8fa);
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
}
.block-content :deep(.block-editor-content pre code) {
  background: transparent;
  padding: 0;
  font-size: inherit;
}
.block-content :deep(.block-editor-content img) {
  max-width: 100%;
  border-radius: 6px;
}
.block-content :deep(.tableWrapper) { overflow-x: auto; }
.block-content :deep(.block-editor-content table) { width: 100%; border-collapse: collapse; }
.block-content :deep(.block-editor-content th),
.block-content :deep(.block-editor-content td) {
  min-width: 60px;
  padding: 6px 10px;
  border: 1px solid var(--border-light);
  vertical-align: top;
}
.block-content :deep(.block-editor-content th) { background: var(--bg-app); font-weight: 600; }
</style>
