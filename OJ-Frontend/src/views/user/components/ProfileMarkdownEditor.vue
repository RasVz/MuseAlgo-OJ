<template>
  <div class="introduction-editor">
    <div class="editor-toolbar">
      <el-radio-group
        v-model="mode"
        size="small"
        :disabled="uploading || disabled"
        aria-label="个人介绍编辑模式"
      >
        <el-radio-button value="edit">编辑</el-radio-button>
        <el-radio-button value="preview">预览</el-radio-button>
      </el-radio-group>
      <el-button
        size="small"
        :loading="uploading"
        :disabled="mode !== 'edit' || disabled"
        @click="chooseImage"
        >上传图片</el-button
      >
      <input
        ref="imageInput"
        class="visually-hidden"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        aria-label="个人介绍图片"
        @change="upload"
      />
    </div>
    <textarea
      v-if="mode === 'edit'"
      ref="textarea"
      class="editor-surface"
      :value="modelValue"
      :disabled="uploading || disabled"
      :maxlength="INTRODUCTION_LIMIT"
      rows="13"
      aria-label="Markdown个人介绍"
      placeholder="用 Markdown 介绍自己：学习方向、经历、目标，或分享一张图片…"
      @input="$emit('update:modelValue', $event.target.value)"
    ></textarea>
    <div v-else class="editor-preview editor-surface">
      <ProfileMarkdown v-if="modelValue.trim()" :source="modelValue" />
      <p v-else class="editor-hint">写一点内容后，就能在这里预览。</p>
    </div>
    <div class="editor-footer">
      <span>支持 Markdown · 图片支持 JPG / PNG / WebP，最大 5MB</span
      ><span>{{ modelValue.length }} / {{ INTRODUCTION_LIMIT }}</span>
    </div>
    <p v-if="error" role="alert" class="editor-error">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, nextTick } from 'vue'
import { uploadImage } from '@/api/user'
import {
  INTRODUCTION_LIMIT,
  validateProfileImage,
  insertProfileImage,
} from '@/utils/profileMarkdown'
import ProfileMarkdown from './ProfileMarkdown.vue'
const props = defineProps({ modelValue: { type: String, default: '' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue', 'uploading'])
const mode = ref('edit'),
  uploading = ref(false),
  error = ref(''),
  textarea = ref(null),
  imageInput = ref(null)
let selection = [0, 0]
function chooseImage() {
  selection = [
    textarea.value?.selectionStart ?? props.modelValue.length,
    textarea.value?.selectionEnd ?? props.modelValue.length,
  ]
  imageInput.value.click()
}
async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  error.value = validateProfileImage(file)
  if (error.value) return
  uploading.value = true
  emit('uploading', true)
  try {
    const result = await uploadImage(file)
    if (result.code !== 200 || !result.data) throw new Error('图片上传失败，请重试')
    const insertion = insertProfileImage(props.modelValue, result.data, ...selection)
    if (insertion.text.length > INTRODUCTION_LIMIT) throw new Error('插入图片后超出介绍字数限制')
    emit('update:modelValue', insertion.text)
    uploading.value = false
    emit('uploading', false)
    await nextTick()
    textarea.value?.focus()
    textarea.value?.setSelectionRange(insertion.cursor, insertion.cursor)
  } catch (cause) {
    error.value = cause.message || '图片上传失败，请重试'
  } finally {
    uploading.value = false
    emit('uploading', false)
  }
}
</script>
<style scoped>
.introduction-editor {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-shrink: 0;
}
.editor-surface {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
  overflow: auto;
  border: 1px solid var(--border-base);
  border-radius: 10px;
  padding: 20px;
}
textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  resize: none;
  padding: 16px;
  font:
    14px/1.7 'SFMono-Regular',
    Consolas,
    monospace;
  border: 1px solid var(--border-base);
  border-radius: 10px;
  color: var(--text-primary);
  background: var(--bg-app);
}
textarea:focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.editor-preview {
  background: var(--bg-surface);
}
.editor-footer {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.editor-error {
  color: var(--el-color-danger);
  font-size: 13px;
}
.editor-hint {
  color: var(--text-secondary);
}
.visually-hidden {
  display: none;
}
</style>
