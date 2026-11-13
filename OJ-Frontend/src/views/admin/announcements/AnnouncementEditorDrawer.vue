<template>
  <el-drawer
    :model-value="modelValue"
    :title="isEdit ? '编辑公告' : '发布公告'"
    :size="drawerSize"
    direction="rtl"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="onDrawerUpdate"
  >
    <div class="editor-body">
      <label class="field-label" for="announcement-title">标题 <span class="required">*</span></label>
      <el-input
        id="announcement-title"
        v-model="form.title"
        maxlength="120"
        show-word-limit
        placeholder="公告标题"
      />

      <div class="field-head">
        <label class="field-label" for="announcement-content">内容 <span class="required">*</span></label>
        <div class="field-tools">
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="edit">编辑</el-radio-button>
            <el-radio-button value="preview">预览</el-radio-button>
          </el-radio-group>
          <span class="word-count">{{ form.content.length }} 字</span>
        </div>
      </div>

      <el-input
        v-show="mode === 'edit'"
        id="announcement-content"
        v-model="form.content"
        type="textarea"
        :rows="14"
        placeholder="支持 Markdown"
        resize="none"
      />
      <div v-show="mode === 'preview'" class="preview-box">
        <PostContent v-if="form.content" :content="form.content" />
        <span v-else class="preview-empty">暂无可预览的内容</span>
      </div>

      <div class="top-row">
        <div class="top-copy">
          <div class="top-label">置顶公告</div>
          <div class="top-hint">置顶公告在前台公告列表中优先展示，可随时取消。</div>
        </div>
        <el-switch
          v-model="form.isTop"
          :active-value="1"
          :inactive-value="0"
          aria-label="是否置顶公告"
        />
      </div>
    </div>

    <template #footer>
      <div class="editor-footer">
        <span v-if="dirty" class="dirty-hint">有未保存的修改</span>
        <el-button :disabled="saving" @click="attemptClose">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="onSubmit">保存</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
// 编辑抽屉：表单与校验留在本组件，写库动作由父级统一发起。
// 父级保存失败时不关闭抽屉，因此内容与脏状态都能保留；关闭前对脏状态二次确认。
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PostContent from '@/views/post/components/PostContent.vue'

const props = defineProps({
  modelValue: Boolean,
  row: { type: Object, default: null },
  full: Boolean,
  saving: Boolean
})

const emit = defineEmits(['update:modelValue', 'submit'])

const emptyForm = () => ({ id: null, title: '', content: '', isTop: 0 })
const form = ref(emptyForm())
const original = ref('')
const mode = ref('edit')
// el-drawer 在关闭过程中会回显一次 update:model-value(false)，
// 父级主动关闭（保存成功）同样会有。若不标记，回显会再次进入脏状态确认，
// 造成重复弹框与遮罩残留，页面随后无法点击。
const closeSettled = ref(false)

const isEdit = computed(() => props.row?.id != null)
const drawerSize = computed(() => (props.full ? '100%' : '720px'))
const snapshot = () => JSON.stringify({ title: form.value.title, content: form.value.content, isTop: form.value.isTop })
const dirty = computed(() => snapshot() !== original.value)

const resetForm = () => {
  form.value = props.row
    ? {
        id: props.row.id,
        title: props.row.title || '',
        content: props.row.content || '',
        isTop: props.row.isTop === 1 ? 1 : 0
      }
    : emptyForm()
  mode.value = 'edit'
  original.value = snapshot()
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    closeSettled.value = false
    resetForm()
  } else {
    closeSettled.value = true
  }
})

const onDrawerUpdate = (value) => {
  if (value === props.modelValue || closeSettled.value) return
  if (value) return
  attemptClose()
}

const attemptClose = async () => {
  if (props.saving || closeSettled.value) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前有未保存的修改，关闭后将丢失。', '放弃修改？', {
        type: 'warning',
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑'
      })
    } catch {
      return
    }
  }
  closeSettled.value = true
  emit('update:modelValue', false)
}

const onSubmit = () => {
  if (props.saving) return
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('标题与内容不能为空')
    return
  }
  emit('submit', {
    id: form.value.id,
    title: form.value.title.trim(),
    content: form.value.content,
    isTop: form.value.isTop
  })
}
</script>

<style scoped>
.editor-body { display: flex; flex-direction: column; gap: 10px; }
.field-label { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.required { color: var(--accent-danger); }
.field-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 8px; }
.field-tools { display: flex; align-items: center; gap: 12px; }
.word-count { font-size: 12px; color: var(--text-regular); }
.preview-box {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  padding: 14px;
  min-height: 300px;
  max-height: 420px;
  overflow-y: auto;
  background: var(--bg-surface);
}
.preview-empty { font-size: 13px; color: var(--text-regular); }
.top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-app);
}
.top-label { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.top-hint { font-size: 12px; color: var(--text-regular); margin-top: 4px; line-height: 1.6; }
.editor-footer { display: flex; gap: 8px; justify-content: flex-end; align-items: center; flex-wrap: wrap; }
.dirty-hint { margin-right: auto; font-size: 12px; color: var(--accent-warning); }
</style>
