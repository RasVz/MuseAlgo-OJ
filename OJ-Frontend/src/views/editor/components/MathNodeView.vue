<template>
  <NodeViewWrapper
    :as="isBlock ? 'div' : 'span'"
    :class="['math-node', isBlock ? 'is-block' : 'is-inline', { 'is-editing': editing, 'is-selected': selected }]"
  >
    <template v-if="!editing">
      <span v-if="html" class="math-render" v-html="html" @click="startEdit" />
      <button v-else type="button" class="math-empty" @click="startEdit">空公式，点击编辑</button>
    </template>

    <textarea
      v-else-if="isBlock"
      ref="inputRef"
      v-model="draft"
      class="math-input"
      rows="3"
      spellcheck="false"
      @blur="commit"
      @keydown.esc.stop.prevent="cancel"
      @keydown.stop
    />
    <input
      v-else
      ref="inputRef"
      v-model="draft"
      class="math-input"
      spellcheck="false"
      @blur="commit"
      @keydown.enter.prevent="commit"
      @keydown.esc.stop.prevent="cancel"
      @keydown.stop
    />
  </NodeViewWrapper>
</template>

<script setup>
// 公式节点：默认显示渲染结果，点击后切为 LaTeX 源码，失焦即提交并重新渲染。
// 渲染复用 markdown-it + MathJax（与详情页同一条管线），不引入 KaTeX。
import { computed, nextTick, onMounted, ref } from 'vue'
import { NodeViewWrapper } from '@tiptap/vue-3'
import { renderMathHtml } from '@/utils/markdown-blocks'

const props = defineProps({
  node: { type: Object, required: true },
  updateAttributes: { type: Function, required: true },
  deleteNode: { type: Function, required: true },
  selected: { type: Boolean, default: false }
})

const isBlock = computed(() => props.node.type.name === 'mathBlock')
const latex = computed(() => props.node.attrs?.latex || '')
const html = computed(() => renderMathHtml(latex.value, isBlock.value))

const editing = ref(false)
const draft = ref('')
const inputRef = ref(null)

const startEdit = async () => {
  draft.value = latex.value
  editing.value = true
  await nextTick()
  // 块级公式插入后会被 ProseMirror 选中并抢回焦点，等一帧再聚焦输入框，
  // 否则刚渲染出来就被 blur，用户看到的是"点了没反应"。
  requestAnimationFrame(() => {
    inputRef.value?.focus()
    inputRef.value?.select?.()
  })
}

const commit = () => {
  if (!editing.value) return
  const value = draft.value.trim()
  editing.value = false
  // 空公式不删除节点：新建后焦点稍有抖动就删除会让用户以为"点了没反应"。
  // 清空后保留占位，由用户再次点击或按 Esc 主动移除。
  if (value !== latex.value) props.updateAttributes({ latex: value })
}

const cancel = () => {
  if (!latex.value.trim()) {
    props.deleteNode()
    return
  }
  editing.value = false
}

// 新插入的公式没有内容，直接进入编辑态，省掉一次点击。
onMounted(() => {
  if (!latex.value) startEdit()
})
</script>

<style scoped>
.math-node { cursor: pointer; }
.math-node.is-inline { display: inline-block; vertical-align: baseline; }
.math-node.is-block { display: block; margin: 12px 0; text-align: center; }
.math-render { display: inline-block; padding: 1px 3px; border-radius: 4px; transition: background 0.15s; }
.math-node:hover .math-render { background: var(--accent-primary-light, #eef4ff); }
.math-node.is-block .math-render { display: inline-block; padding: 8px 14px; }
.math-node.is-selected .math-render { outline: 1px dashed var(--accent-primary); outline-offset: 4px; }
.math-empty {
  padding: 2px 8px;
  border: 1px dashed var(--border-base);
  border-radius: 4px;
  background: transparent;
  color: var(--text-placeholder);
  font-size: 12px;
  cursor: pointer;
}
.math-input {
  width: 100%;
  min-width: 160px;
  padding: 4px 8px;
  border: 1px solid var(--accent-primary);
  border-radius: 4px;
  background: var(--bg-surface);
  color: var(--text-primary);
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  outline: none;
  resize: vertical;
}
.math-node.is-inline .math-input { min-width: 120px; }
</style>
