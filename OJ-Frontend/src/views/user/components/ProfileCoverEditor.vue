<template>
  <el-button class="cover-edit-button" @click="open"
    ><el-icon><Picture /></el-icon>更换封面</el-button
  >
  <el-dialog
    v-model="dialog"
    title="设置主页封面"
    width="720px"
    class="profile-edit-dialog"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
  >
    <div class="cover-preview">
      <img
        v-if="safeUrl"
        :src="safeUrl"
        alt="封面预览"
        :style="{ objectPosition: `50% ${position}%` }"
        @error="imageFailed = true"
      /><span v-else>默认封面</span>
    </div>
    <div class="cover-controls">
      <el-button :loading="uploading" :disabled="saving" @click="fileInput.click()"
        >上传封面</el-button
      ><el-button :disabled="busy" @click="restore">恢复默认</el-button
      ><span>JPG / PNG / WebP · 最大 5MB</span>
    </div>
    <input
      ref="fileInput"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      class="hidden-input"
      aria-label="上传主页封面"
      @change="upload"
    />
    <template v-if="safeUrl"
      ><label class="position-label" for="cover-position">调整图片位置</label
      ><input
        id="cover-position"
        v-model.number="position"
        type="range"
        min="0"
        max="100"
        :disabled="busy"
        aria-label="封面垂直位置"
        class="position-slider"
      />
      <p class="cover-hint">左右拖动滑块，选择封面中希望展示的位置。</p></template
    >
    <p v-if="error || imageFailed" role="alert" class="cover-error">
      {{ error || '图片无法加载，请重新上传' }}
    </p>
    <template #footer
      ><el-button :disabled="busy" @click="dialog = false">取消</el-button
      ><el-button
        type="primary"
        :loading="saving"
        :disabled="uploading || imageFailed"
        @click="save"
        >保存封面</el-button
      ></template
    >
  </el-dialog>
</template>
<script setup>
import { ref, computed } from 'vue'
import { Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { uploadImage, updateUserProfile } from '@/api/user'
import { validateProfileImage, safeProfileImageUrl } from '@/utils/profileMarkdown'
const props = defineProps({ user: { type: Object, required: true } })
const emit = defineEmits(['updated'])
const dialog = ref(false),
  url = ref(''),
  position = ref(50),
  uploading = ref(false),
  saving = ref(false),
  error = ref(''),
  imageFailed = ref(false),
  fileInput = ref(null)
const safeUrl = computed(() => safeProfileImageUrl(url.value))
const busy = computed(() => uploading.value || saving.value)
function open() {
  url.value = props.user.coverUrl || ''
  position.value = props.user.coverPosition ?? 50
  error.value = ''
  imageFailed.value = false
  dialog.value = true
}
function restore() {
  url.value = ''
  position.value = 50
  imageFailed.value = false
  error.value = ''
}
async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  error.value = validateProfileImage(file)
  if (error.value) return
  uploading.value = true
  try {
    const result = await uploadImage(file)
    if (result.code !== 200 || !safeProfileImageUrl(result.data))
      throw new Error('封面上传失败，请重试')
    url.value = result.data
    imageFailed.value = false
  } catch (cause) {
    error.value = cause.message || '封面上传失败，请重试'
  } finally {
    uploading.value = false
  }
}
async function save() {
  saving.value = true
  error.value = ''
  try {
    const patch = { coverUrl: safeUrl.value, coverPosition: position.value }
    const result = await updateUserProfile(patch)
    if (result.code !== 200) throw new Error(result.message || '封面保存失败')
    emit('updated', patch)
    dialog.value = false
    ElMessage.success('主页封面已更新')
  } catch (cause) {
    error.value = cause.message || '封面保存失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>
<style scoped>
.cover-edit-button {
  position: absolute;
  right: 24px;
  top: 24px;
  z-index: 2;
  background: rgba(20, 30, 55, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.5);
  color: #fff;
  gap: 6px;
}
.cover-edit-button:hover {
  background: rgba(20, 30, 55, 0.8);
  color: #fff;
  border-color: #fff;
}
.cover-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 220px;
  overflow: hidden;
  border-radius: 12px;
  background: linear-gradient(120deg, #183c7a, #5576c3 65%, #8295ca);
  color: #fff;
}
.cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cover-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 18px 0 24px;
}
.cover-controls span,
.cover-hint {
  font-size: 12px;
  color: var(--text-secondary);
}
.position-label {
  display: block;
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: 8px;
}
.position-slider {
  width: 100%;
  accent-color: var(--el-color-primary);
  min-height: 32px;
}
.cover-error {
  color: var(--el-color-danger);
}
.hidden-input {
  display: none;
}
</style>
