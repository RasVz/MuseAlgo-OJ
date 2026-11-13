<template>
  <div class="student-picker">
    <!-- 触发器只展示摘要；真正的选择在弹窗内完成，人数多时可搜索与滚动。 -->
    <div
      class="picker-trigger"
      :class="{ 'is-disabled': disabled, 'is-empty': selectedStudents.length === 0 }"
      role="button"
      :tabindex="disabled ? -1 : 0"
      :aria-disabled="disabled"
      aria-haspopup="dialog"
      :aria-expanded="visible"
      :title="selectedNames"
      @click="open"
      @keydown.enter.prevent="open"
      @keydown.space.prevent="open"
    >
      <div v-if="selectedStudents.length === 0" class="picker-placeholder">
        {{ loading ? '正在加载学生…' : '选择一名或多名学生' }}
      </div>
      <div v-else class="picker-values">
        <el-tag v-for="student in visibleSelected" :key="student.studentId" size="small" effect="plain">
          {{ student.name }}
        </el-tag>
        <span v-if="hiddenSelectedCount" class="picker-more">+{{ hiddenSelectedCount }}</span>
      </div>
      <el-icon class="picker-arrow"><ArrowDown /></el-icon>
    </div>

    <el-dialog
      v-model="visible"
      class="student-picker-dialog"
      title="选择学生"
      width="820px"
      top="5vh"
      append-to-body
      :close-on-click-modal="false"
      @opened="focusSearch"
      @closed="handleClosed"
    >
      <template #header>
        <div class="picker-header">
          <span class="picker-title">选择学生</span>
          <span class="picker-subtitle">本班共 {{ members.length }} 人 · 已选 {{ draft.size }} 人</span>
        </div>
      </template>

      <div class="picker-toolbar">
        <el-input
          ref="searchRef"
          v-model="keyword"
          placeholder="搜索姓名、用户名、备注或 ID"
          clearable
          prefix-icon="Search"
          style="width: 280px"
        />
        <div class="picker-toolbar-actions">
          <el-button
            link
            type="primary"
            :disabled="filteredMembers.length === 0 || allFilteredSelected"
            @click="selectFiltered"
          >
            全选{{ isFiltering ? '筛选结果' : '' }}（{{ filteredMembers.length }}）
          </el-button>
          <el-button link :disabled="draft.size === 0" @click="clearAll">清空</el-button>
        </div>
      </div>

      <el-table
        :data="tableRows"
        max-height="52vh"
        stripe
        row-key="userId"
        empty-text="没有匹配的学生"
        class="picker-table"
        @row-click="handleRowClick"
      >
        <el-table-column width="56" align="center" label="选择">
          <template #default="{ row }">
            <el-checkbox
              :model-value="row._selected"
              @click.stop
              @change="setSelected(row.userId, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column label="学生" min-width="220">
          <template #default="{ row }">
            <div class="picker-student">
              <el-avatar :size="26" :src="row.avatar">{{ labelOf(row).charAt(0) }}</el-avatar>
              <div class="picker-student-text">
                <span class="picker-student-name">{{ labelOf(row) }}</span>
                <span v-if="row.email" class="picker-student-meta">{{ row.email }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="用户名" min-width="150">
          <template #default="{ row }">
            <span class="picker-username">@{{ row.username }}</span>
          </template>
        </el-table-column>
        <el-table-column label="ID" width="90" align="center">
          <template #default="{ row }">
            <span class="picker-id">#{{ row.userId }}</span>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <div class="picker-footer">
          <span class="picker-footer-hint">确定后按所选学生受理；单次最多 50 份，超出自动分批。</span>
          <div class="picker-footer-actions">
            <el-button @click="visible = false">取消</el-button>
            <el-button type="primary" @click="confirm">
              确定{{ draft.size ? `（${draft.size} 人）` : '' }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 学习周报的学生选择弹窗：搜索、逐行勾选、全选筛选结果、清空、确定。
// 选择草稿在弹窗内独立维护，取消不会改动表单；确定时按成员顺序回写 ID。
import { computed, ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { filterStudents } from '../composables/reportBatch'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  members: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const MAX_VISIBLE_TAGS = 2

const visible = ref(false)
const keyword = ref('')
const draft = ref(new Set())
const searchRef = ref(null)

const memberMap = computed(() => new Map(props.members.map((member) => [Number(member.userId), member])))

const selectedStudents = computed(() =>
  props.modelValue
    .map((id) => memberMap.value.get(Number(id)))
    .filter(Boolean)
    .map((member) => ({ studentId: Number(member.userId), name: labelOf(member) }))
)
const visibleSelected = computed(() => selectedStudents.value.slice(0, MAX_VISIBLE_TAGS))
const hiddenSelectedCount = computed(() => Math.max(0, selectedStudents.value.length - MAX_VISIBLE_TAGS))
const selectedNames = computed(() => selectedStudents.value.map((student) => student.name).join('、'))

const normalizedKeyword = computed(() => keyword.value.trim().toLowerCase())
const isFiltering = computed(() => normalizedKeyword.value !== '')
const filteredMembers = computed(() => filterStudents(props.members, keyword.value))
const allFilteredSelected = computed(
  () => filteredMembers.value.length > 0 &&
    filteredMembers.value.every((member) => draft.value.has(Number(member.userId)))
)
// 表格数据必须随选择态重建：el-table 的单元格插槽在子组件内求值，
// 父级 draft 变化不会自动触发其重新渲染，直接把 _selected 放进行数据。
const tableRows = computed(() =>
  filteredMembers.value.map((member) => ({
    ...member,
    _selected: draft.value.has(Number(member.userId))
  }))
)

function labelOf(member) {
  const remark = typeof member?.remark === 'string' ? member.remark.trim() : ''
  return remark || member?.username || `学生 #${member?.userId}`
}

function open() {
  if (props.disabled || props.loading) return
  draft.value = new Set(props.modelValue.map((id) => Number(id)))
  keyword.value = ''
  visible.value = true
}

function focusSearch() {
  searchRef.value?.focus()
}

function setSelected(userId, checked) {
  const id = Number(userId)
  const next = new Set(draft.value)
  if (checked) next.add(id)
  else next.delete(id)
  draft.value = next
}

function handleRowClick(row) {
  setSelected(row.userId, !draft.value.has(Number(row.userId)))
}

function toggleFiltered(checked) {
  const next = new Set(draft.value)
  for (const member of filteredMembers.value) {
    const id = Number(member.userId)
    if (checked) next.add(id)
    else next.delete(id)
  }
  draft.value = next
}

function selectFiltered() {
  toggleFiltered(true)
}

function clearAll() {
  draft.value = new Set()
}

function confirm() {
  const ids = props.members
    .filter((member) => draft.value.has(Number(member.userId)))
    .map((member) => member.userId)
  emit('update:modelValue', ids)
  visible.value = false
}

function handleClosed() {
  keyword.value = ''
  draft.value = new Set()
}
</script>

<style scoped>
.student-picker { display: inline-block; max-width: 100%; }

.picker-trigger {
  position: relative;
  display: flex;
  align-items: center;
  width: 320px;
  max-width: 100%;
  min-height: 32px;
  padding: 2px 30px 2px 10px;
  border: 1px solid var(--border-base);
  border-radius: var(--radius-md, 6px);
  background: var(--bg-surface);
  cursor: pointer;
  transition: border-color 0.2s;
}
.picker-trigger:hover { border-color: var(--accent-primary); }
.picker-trigger:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 1px; }
.picker-trigger.is-disabled {
  cursor: not-allowed;
  background: var(--bg-surface-hover);
  border-color: var(--border-light);
}
.picker-trigger.is-disabled:hover { border-color: var(--border-light); }
.picker-trigger.is-empty { color: var(--text-placeholder); }

.picker-placeholder { font-size: 13px; color: var(--text-placeholder); line-height: 24px; }
.picker-values {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
}
.picker-values :deep(.el-tag) { max-width: 96px; }
.picker-values :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker-more { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.picker-arrow {
  position: absolute;
  right: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}

.picker-header { display: flex; align-items: baseline; gap: 10px; }
.picker-title { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.picker-subtitle { font-size: 12px; color: var(--text-secondary); }

.picker-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.picker-toolbar-actions { display: flex; align-items: center; gap: 4px; }

.picker-table :deep(.el-table__row) { cursor: pointer; }
.picker-student { display: flex; align-items: center; gap: 8px; min-width: 0; }
.picker-student-text { display: flex; flex-direction: column; min-width: 0; line-height: 1.35; }
.picker-student-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker-student-meta { font-size: 12px; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.picker-username { font-size: 13px; color: var(--text-regular); }
.picker-id { font-size: 12px; color: var(--text-secondary); }

.picker-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.picker-footer-hint { font-size: 12px; color: var(--text-secondary); }
.picker-footer-actions { display: flex; gap: 8px; }

:global(.student-picker-dialog) { max-width: 94vw; }
:global(.student-picker-dialog .el-dialog__body) { padding-top: 8px; }
</style>
