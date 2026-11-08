<template>
  <el-dialog v-model="dialogVisible" title="编辑器设置" width="400px" append-to-body>
    <el-form label-width="80px">
      <el-form-item label="主题颜色">
        <el-select :model-value="settings.theme" @update:model-value="onChange({ theme: $event })">
          <el-option label="明亮 (Light)" value="vs-light" />
          <el-option label="暗黑 (Dark)" value="vs-dark" />
        </el-select>
      </el-form-item>
      <el-form-item label="代码字号">
        <el-input-number :model-value="settings.fontSize" :min="12" :max="24" @update:model-value="onChange({ fontSize: $event })" />
      </el-form-item>
      <el-form-item label="自动补全">
        <el-switch :model-value="settings.autoComplete" @update:model-value="onChange({ autoComplete: $event })" />
      </el-form-item>
      <el-form-item label="显示行号">
        <el-switch :model-value="settings.showLineNumbers" @update:model-value="onChange({ showLineNumbers: $event })" />
      </el-form-item>
    </el-form>
  </el-dialog>
</template>

<script setup>
// 编辑器设置弹窗：只发 patch 事件，不直接改 props.settings——
// 设置的持久化（本地 + 服务端）统一由 CodeEditor 的 saveSettings 处理。
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  settings: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'change'])

const dialogVisible = ref(false)
watch(() => props.modelValue, (v) => { dialogVisible.value = v })
watch(dialogVisible, (v) => emit('update:modelValue', v))

const onChange = (patch) => {
  emit('change', patch)
}
</script>
