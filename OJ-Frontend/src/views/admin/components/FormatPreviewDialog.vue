<template>
  <el-dialog
    :model-value="modelValue"
    title="格式化预览"
    width="min(760px, 92vw)"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div v-if="preview" class="format-preview">
      <template v-if="preview.success && preview.formatted">
        <div class="preview-row">
          <span class="preview-label">题名</span>
          <span class="preview-value">{{ preview.formatted.title }}</span>
        </div>
        <div class="preview-row">
          <span class="preview-label">时限 / 内存</span>
          <span class="preview-value">
            {{ preview.formatted.timeLimitMs }} ms / {{ preview.formatted.memoryLimitMb }} MB
          </span>
        </div>
        <div class="preview-row">
          <span class="preview-label">正文</span>
          <div class="preview-block">{{ preview.formatted.content }}</div>
        </div>
        <div class="preview-row">
          <span class="preview-label">输入格式</span>
          <div class="preview-block">{{ preview.formatted.inputDesc || '（空）' }}</div>
        </div>
        <div class="preview-row">
          <span class="preview-label">输出格式</span>
          <div class="preview-block">{{ preview.formatted.outputDesc || '（空）' }}</div>
        </div>
        <div class="preview-row">
          <span class="preview-label">提示</span>
          <div class="preview-block">{{ preview.formatted.hint || '（空）' }}</div>
        </div>
        <div class="preview-row">
          <span class="preview-label">样例</span>
          <div v-if="preview.formatted.samples?.length" class="preview-samples">
            <div v-for="(sample, idx) in preview.formatted.samples" :key="idx" class="preview-sample">
              <div class="preview-sample-title">样例 #{{ idx + 1 }}</div>
              <pre class="preview-code">{{ sample.input }}</pre>
              <pre class="preview-code">{{ sample.output }}</pre>
            </div>
          </div>
          <span v-else class="preview-value">（无样例）</span>
        </div>
      </template>
      <el-alert v-else :title="preview.message || '格式化失败'" type="error" :closable="false" />
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="applying"
        :disabled="!preview?.success || !preview?.formatted"
        @click="$emit('apply')"
      >
        应用并保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** AI 单题格式化预览弹窗（展示组件）；点击"应用并保存"上抛 apply */
defineProps({
  modelValue: { type: Boolean, default: false },
  preview: { type: Object, default: null },
  applying: { type: Boolean, default: false }
})

defineEmits(['update:modelValue', 'apply'])
</script>

<style scoped>
.format-preview {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 6px;
}

.preview-row {
  margin-bottom: 12px;
}

.preview-label {
  display: inline-block;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.preview-value {
  color: var(--text-primary);
  word-break: break-word;
}

.preview-block {
  background: var(--bg-app);
  border: 1px solid var(--border-light);
  border-radius: 6px;
  padding: 10px 12px;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  max-height: 180px;
  overflow-y: auto;
}

.preview-samples {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.preview-sample-title {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-regular);
  margin-bottom: 4px;
}

.preview-code {
  margin: 0 0 6px;
  background: var(--bg-app);
  border: 1px solid var(--border-light);
  border-radius: 6px;
  padding: 8px 10px;
  font-family: Consolas, monospace;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-regular);
  max-height: 140px;
  overflow-y: auto;
}
</style>
