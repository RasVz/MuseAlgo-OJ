<template>
  <el-form-item class="settings-row" :prop="prop">
    <template #label>
      <div class="settings-row-label">
        <span class="settings-row-title">{{ title }}</span>
        <span v-if="description" :id="descId" class="settings-row-description">{{ description }}</span>
      </div>
    </template>
    <div class="settings-row-control">
      <slot :described-by="describedBy" />
    </div>
  </el-form-item>
</template>

<script setup>
import { getCurrentInstance } from 'vue'

// 一行设置项：左侧「标题 + 说明」，右侧控件。
// 根仍是 el-form-item，保留表单校验（prop）与既有浏览器用例的 .el-form-item 选择方式。
const props = defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' },
  prop: { type: String, default: '' }
})

// 说明文本需要被控件用 aria-describedby 引用；实例 uid 保证同页多行不冲突。
const descId = `settings-row-desc-${getCurrentInstance()?.uid ?? 0}`
const describedBy = props.description ? descId : undefined
</script>

<style scoped>
.settings-row {
  align-items: center;
  min-height: 56px;
  margin-bottom: 0;
  padding: 12px 0;
  border-bottom: 1px solid var(--border-light);
}
.settings-row:last-child {
  border-bottom: none;
}
.settings-row :deep(.el-form-item__label) {
  display: flex;
  align-items: center;
  padding: 0 16px 0 0;
  line-height: 1.5;
}
.settings-row-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.settings-row-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}
.settings-row-description {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-regular);
}
.settings-row :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  min-height: 44px;
  line-height: 1.5;
}
.settings-row-control {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  width: 100%;
  min-height: 44px;
}
@media (max-width: 680px) {
  .settings-row {
    display: block;
    padding: 12px 0;
  }
  .settings-row :deep(.el-form-item__label) {
    margin-bottom: 6px;
  }
  .settings-row :deep(.el-form-item__content) {
    margin-left: 0 !important;
  }
}
</style>
