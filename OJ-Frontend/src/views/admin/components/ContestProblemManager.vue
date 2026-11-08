<template>
  <el-card shadow="never" class="panel-card" v-loading="loading">
    <template #header>
      <div class="card-header-with-btn">
        <span>题目管理</span>
        <div class="problem-tools">
          <el-input
            v-model.trim="inputText"
            placeholder="输入题目ID，支持逗号/空格分隔"
            style="width: 280px"
            clearable
            @keyup.enter="$emit('add', parsedIds)"
          />
          <el-button type="primary" :loading="adding" @click="$emit('add', parsedIds)">添加题目</el-button>
          <el-button @click="$emit('refresh')">刷新题目</el-button>
        </div>
      </div>
    </template>

    <div class="drag-tip">可直接拖拽整行调整题目顺序，拖拽完成后会自动保存顺序。</div>

    <el-table ref="tableRef" :data="problems" row-key="relationId" style="width: 100%">
      <el-table-column label="拖拽" width="80" align="center">
        <template #default><span class="drag-handle">☰</span></template>
      </el-table-column>
      <el-table-column label="题目ID" width="120">
        <template #default="{ row }">{{ row.problemId ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="题目标题" min-width="260">
        <template #default="{ row }">{{ row.title || '-' }}</template>
      </el-table-column>
      <el-table-column label="分值" width="130" align="center">
        <template #default="{ row }">
          <el-input-number v-model="row.score" :min="0" :step="10" :precision="0" controls-position="right" />
        </template>
      </el-table-column>
      <el-table-column label="可见性" width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="Number(row.visible) === 1 ? 'success' : 'info'">
            {{ Number(row.visible) === 1 ? '可见' : '隐藏' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="savingId === row.relationId" @click="$emit('save-config', row)">
            保存配置
          </el-button>
          <el-button link type="warning" :loading="togglingId === row.relationId" @click="$emit('toggle-visible', row)">
            {{ Number(row.visible) === 1 ? '隐藏' : '显示' }}
          </el-button>
          <el-button link type="danger" :loading="removingId === row.relationId" @click="$emit('remove', row)">
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="problems.length === 0" class="empty-tip">当前比赛暂无题目</div>
  </el-card>
</template>

<script setup>
// 比赛题目管理表格：展示比赛题目、支持拖拽排序、按行切换可见性与移除。
// 本组件是无状态的受控组件——数据与全部写操作都在父级 ContestEditorView /
// useContestProblems 里，本组件只负责把用户意图以事件抛上去，
// 这样拖拽的乐观更新与失败回滚只需在一处实现。
import { computed, onBeforeUnmount, ref, watch, nextTick } from 'vue'
import Sortable from 'sortablejs'

const props = defineProps({
  problems: { type: Array, required: true },
  loading: { type: Boolean, default: false },
  adding: { type: Boolean, default: false },
  savingId: { type: [Number, null], default: null },
  togglingId: { type: [Number, null], default: null },
  removingId: { type: [Number, null], default: null }
})

const emit = defineEmits(['add', 'refresh', 'save-config', 'toggle-visible', 'remove', 'reorder'])

const inputText = ref('')
const tableRef = ref(null)
let sortableInstance = null

// 支持空格/逗号/中文逗号混排输入（老师习惯从表格里直接粘贴一列 ID），
// 去重且过滤非法值，避免把 undefined 传进添加接口。
const parsedIds = computed(() => {
  if (!inputText.value) return []
  const arr = inputText.value
    .split(/[\s,，]+/g)
    .map(v => Number(v.trim()))
    .filter(v => Number.isInteger(v) && v > 0)
  return Array.from(new Set(arr))
})

function destroySortable() {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
}

// Sortable 必须挂在 el-table 内部的 tbody 上，而不是外层容器：
// el-table 自己管理滚动与 DOM 复用，挂错位置会导致拖拽时行错位。
function initSortable() {
  destroySortable()
  if (!props.problems.length) return

  const tableEl = tableRef.value?.$el
  const tbody = tableEl?.querySelector('.el-table__body-wrapper tbody')
  if (!tbody) return

  sortableInstance = Sortable.create(tbody, {
    animation: 180,
    handle: '.drag-handle',
    ghostClass: 'sortable-ghost',
    chosenClass: 'sortable-chosen',
    dragClass: 'sortable-drag',
    onEnd: evt => {
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
      emit('reorder', { oldIndex, newIndex })
    }
  })
}

// 题目条数变化后必须重建 Sortable：tbody 的子节点集合变了，
// 旧的实例仍持有旧的行引用，拖起来会拖错题。
watch(() => props.problems.length, async () => {
  await nextTick()
  initSortable()
})

// 卸载时 destroy：Sortable 在 document 上挂了全局监听，不清理会持续响应已销毁的 DOM。
onBeforeUnmount(() => destroySortable())

// 父组件添加成功后调用，清空输入框避免重复提交同一批 ID
defineExpose({ clearInput: () => { inputText.value = '' } })
</script>

<style scoped>
.panel-card { margin-bottom: 16px; }
.card-header-with-btn { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.problem-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.drag-tip { margin-bottom: 12px; font-size: 13px; color: var(--text-secondary); }
.drag-handle { cursor: move; user-select: none; color: var(--text-regular); font-size: 18px; line-height: 1; }
.empty-tip { text-align: center; color: var(--text-secondary); padding: 12px 0 4px; }
:deep(.sortable-ghost) { opacity: 0.6; background: var(--bg-surface-hover); }
:deep(.sortable-chosen) { background: var(--el-color-primary-light-9); }
:deep(.sortable-drag) { background: var(--bg-surface); }
</style>
