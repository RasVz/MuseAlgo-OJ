<template>
  <el-drawer
    class="class-directory-preview"
    :model-value="modelValue"
    title="班级预览"
    size="min(100vw, 520px)"
    append-to-body
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template v-if="row">
      <div class="preview-heading">
        <span class="class-mark"
          ><el-icon><School /></el-icon
        ></span>
        <div>
          <span class="eyebrow">班级 #{{ row.id }}</span>
          <h2>{{ row.name || '未命名班级' }}</h2>
        </div>
      </div>
      <dl class="preview-facts">
        <div>
          <dt>所属机构</dt>
          <dd>{{ row.school?.trim() || '未填写机构' }}</dd>
        </div>
        <div>
          <dt>创建教师</dt>
          <dd>{{ classTeacherLabel(row) }}</dd>
        </div>
        <div>
          <dt>学生人数</dt>
          <dd>{{ row.studentCount ?? 0 }} 人</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ classCreatedLabel(row.createTime) }}</dd>
        </div>
      </dl>
      <section class="preview-description">
        <h3>班级简介</h3>
        <p>{{ row.description?.trim() || '该班级暂未填写简介。' }}</p>
      </section>
    </template>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :disabled="!row" @click="$emit('manage', row)"
        >进入管理<el-icon class="action-icon"><ArrowRight /></el-icon
      ></el-button>
    </template>
  </el-drawer>
</template>

<script setup>
import { ArrowRight, School } from '@element-plus/icons-vue'
import { classCreatedLabel, classTeacherLabel } from '@/utils/adminClassesQuery'

defineProps({ modelValue: Boolean, row: { type: Object, default: null } })
defineEmits(['update:modelValue', 'manage'])
</script>

<style scoped>
.class-directory-preview {
  font-family: var(--font-family);
}
.preview-heading {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin: 4px 0 28px;
}
.class-mark {
  display: grid;
  place-items: center;
  flex: 0 0 48px;
  height: 48px;
  font-size: 24px;
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 12px;
}
.preview-heading h2 {
  margin: 6px 0 0;
  font-size: 23px;
  line-height: 1.45;
  overflow-wrap: anywhere;
  color: var(--text-primary);
}
.eyebrow {
  font: 12px var(--font-mono);
  color: var(--text-regular);
}
.preview-facts {
  margin: 0;
  border-block: 1px solid var(--border-light);
}
.preview-facts div {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 20px;
  padding: 15px 0;
}
.preview-facts dt {
  color: var(--text-regular);
  font-size: 14px;
}
.preview-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--text-primary);
  font-size: 14px;
}
.preview-description {
  margin-top: 28px;
}
.preview-description h3 {
  font-size: 14px;
  color: var(--text-primary);
}
.preview-description p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
  font-size: 14px;
  color: var(--text-regular);
}
.action-icon {
  margin-left: 8px;
}
</style>
