<template>
  <section v-if="user.introduction?.trim() || isSelf" class="intro-card">
    <div class="section-header">
      <h2>个人介绍</h2>
      <el-button v-if="isSelf" text type="primary" @click="edit">{{
        user.introduction?.trim() ? '编辑介绍' : '添加介绍'
      }}</el-button>
    </div>
    <template v-if="user.introduction?.trim()">
      <div ref="content" class="intro-content" :class="{ collapsed: canExpand && !expanded }">
        <ProfileMarkdown :source="user.introduction" />
      </div>
      <el-button
        v-if="canExpand"
        text
        type="primary"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
        >{{ expanded ? '收起介绍' : '展开全文' }}</el-button
      >
    </template>
    <div v-else class="intro-empty">
      <span class="intro-empty-mark">Aa</span>
      <div>
        <p>让大家认识你</p>
        <span>写下你的学习方向、经历，或分享最近的目标。</span>
      </div>
    </div>
    <el-dialog
      v-model="dialog"
      title="编辑个人介绍"
      width="1120px"
      align-center
      class="profile-edit-dialog introduction-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving && !uploading"
      :show-close="!saving && !uploading"
    >
      <ProfileMarkdownEditor
        v-if="dialog"
        v-model="draft"
        :disabled="saving"
        @uploading="uploading = $event"
      />
      <p v-if="error" role="alert" class="save-error">{{ error }}</p>
      <template #footer
        ><el-button :disabled="saving || uploading" @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" :disabled="uploading" @click="save"
          >保存介绍</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>
<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { updateUserProfile } from '@/api/user'
import { ElMessage } from 'element-plus'
import ProfileMarkdown from './ProfileMarkdown.vue'
import ProfileMarkdownEditor from './ProfileMarkdownEditor.vue'
const props = defineProps({ user: { type: Object, required: true }, isSelf: Boolean })
const emit = defineEmits(['updated'])
const dialog = ref(false),
  draft = ref(''),
  saving = ref(false),
  uploading = ref(false),
  error = ref('')
const expanded = ref(false),
  canExpand = ref(false),
  content = ref(null)
let observer
const measure = () => {
  canExpand.value = (content.value?.scrollHeight || 0) > 340
}
onMounted(() => {
  observer = new ResizeObserver(measure)
  if (content.value) observer.observe(content.value)
  measure()
})
onBeforeUnmount(() => observer?.disconnect())
watch(
  () => props.user.introduction,
  async () => {
    expanded.value = false
    await nextTick()
    observer?.disconnect()
    if (content.value) observer?.observe(content.value)
    measure()
  },
)
function edit() {
  draft.value = props.user.introduction || ''
  error.value = ''
  dialog.value = true
}
async function save() {
  saving.value = true
  error.value = ''
  try {
    const submitted = draft.value
    const result = await updateUserProfile({ introduction: submitted })
    if (result.code !== 200) throw new Error(result.message || '介绍保存失败')
    emit('updated', { introduction: submitted })
    dialog.value = false
    ElMessage.success('个人介绍已更新')
  } catch (cause) {
    error.value = cause.message || '介绍保存失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>
<style scoped>
.intro-card {
  padding: 22px 24px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 14px;
  min-width: 0;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
}
.intro-content {
  min-width: 0;
}
.intro-content.collapsed {
  max-height: 320px;
  overflow: hidden;
  mask-image: linear-gradient(#000 80%, transparent);
}
.intro-empty {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 0 14px;
  color: var(--text-secondary);
  font-size: 13px;
}
.intro-empty p {
  margin: 0 0 4px;
  font-size: 14px;
  color: var(--text-regular);
}
.intro-empty-mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: 12px;
  background: var(--bg-app);
  color: var(--el-color-primary);
  font-size: 22px;
  font-weight: 600;
}
.save-error {
  color: var(--el-color-danger);
}

:global(.profile-edit-dialog.introduction-dialog) {
  display: flex;
  flex-direction: column;
  height: min(780px, calc(100dvh - 48px));
  max-width: calc(100vw - 48px);
  margin: auto;
  box-sizing: border-box;
  --el-dialog-padding-primary: 24px;
}
:global(.introduction-dialog .el-dialog__header),
:global(.introduction-dialog .el-dialog__footer) {
  flex-shrink: 0;
}
:global(.introduction-dialog .el-dialog__body) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
:global(.introduction-dialog .el-dialog__footer) {
  padding-top: 16px;
  border-top: 1px solid var(--border-light);
}
@media (max-width: 680px) {
  :global(.profile-edit-dialog.introduction-dialog) {
    height: calc(100dvh - 24px);
    max-width: calc(100vw - 24px);
    --el-dialog-padding-primary: 16px;
  }
}
</style>
