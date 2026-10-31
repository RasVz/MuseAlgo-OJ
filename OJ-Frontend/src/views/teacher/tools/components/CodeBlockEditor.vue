<template>
  <div class="code-block" :style="{ height: height + 'px' }">
    <vue-monaco-editor
      v-model:value="inner"
      :language="language"
      theme="vs-light"
      :options="options"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { VueMonacoEditor } from '@guolao/vue-monaco-editor'

/**
 * 轻量 Monaco 代码块（教师工具用）：可编辑或只读，无提交/调试等学生端功能。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: 'cpp' },
  height: { type: Number, default: 200 },
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const inner = ref(props.modelValue)
// 外部重置（如"重新生成"）时同步到内部缓冲：
// 不加这个 watch，重新生成的内容不会出现在编辑器里。
watch(() => props.modelValue, (v) => { inner.value = v })
// 只在内容真有差异时 emit：外部同步进来的值会再次触发这个 watch，
// 不加比较会形成父子互相 emit 的回环。
watch(inner, (v) => { if (v !== props.modelValue) emit('update:modelValue', v) })

const options = computed(() => ({
  fontSize: 13,
  automaticLayout: true,
  minimap: { enabled: false },
  folding: true,
  lineNumbers: 'on',
  readOnly: props.readonly,
  scrollBeyondLastLine: false,
  tabSize: 4,
  wordWrap: 'off'
}))
</script>

<style scoped>
.code-block { border: 1px solid var(--border-light); border-radius: 8px; overflow: hidden; }
</style>
