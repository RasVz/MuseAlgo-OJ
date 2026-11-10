<template>
  <div class="pl-table-wrap">
    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      row-key="id"
      class="pl-table"
      style="width: 100%"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
      @row-click="onRowClick"
    >
      <el-table-column v-if="sortMode" width="44" align="center">
        <template #default>
          <span class="drag-handle" title="拖拽调整顺序" aria-label="拖拽排序手柄">
            <el-icon><Rank /></el-icon>
          </span>
        </template>
      </el-table-column>

      <el-table-column label="#" width="52" align="center">
        <template #default="{ $index }">
          <span class="order-no">{{ $index + 1 }}</span>
        </template>
      </el-table-column>

      <el-table-column label="题单" min-width="220">
        <template #default="{ row }">
          <div class="cell-main">
            <el-button
              link
              type="primary"
              class="row-link"
              :aria-label="`查看「${row.title}」详情`"
              @click.stop="emit('open', row)"
            >
              {{ row.title }}
            </el-button>
            <div class="row-sub" :title="row.description || ''">
              {{ row.description || '暂无简介' }}
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="状态" width="106" align="center">
        <template #default="{ row }">
          <span @click.stop>
            <el-switch
              :model-value="row.privacy === 1"
              :before-change="() => beforePrivacyChange(row)"
              inline-prompt
              active-text="公开"
              inactive-text="私密"
              :aria-label="`切换「${row.title}」的公开状态`"
            />
          </span>
        </template>
      </el-table-column>

      <el-table-column label="题数" width="76" align="center">
        <template #default="{ row }">{{ row.problemCount ?? 0 }}</template>
      </el-table-column>

      <el-table-column v-if="!compact" label="收藏" width="76" align="center">
        <template #default="{ row }">{{ row.favoriteCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column v-if="!compact" label="关联课件" width="92" align="center">
        <template #default="{ row }">{{ row.coursewareCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column v-if="!compact" label="更新时间" width="112" align="center">
        <template #default="{ row }">{{ fmtDate(row.updateTime) }}</template>
      </el-table-column>

      <el-table-column label="操作" :width="sortMode ? 168 : 120" align="right">
        <template #default="{ row, $index }">
          <div class="row-actions">
            <template v-if="sortMode">
              <el-button
                link
                type="primary"
                :disabled="savingSort || $index === 0"
                :aria-label="`上移「${row.title}」`"
                @click.stop="emit('move', row, -1)"
              >上移</el-button>
              <el-button
                link
                type="primary"
                :disabled="savingSort || $index === rows.length - 1"
                :aria-label="`下移「${row.title}」`"
                @click.stop="emit('move', row, 1)"
              >下移</el-button>
              <el-button
                link
                type="primary"
                :disabled="savingSort || $index === 0"
                :aria-label="`置顶「${row.title}」`"
                @click.stop="emit('pin', row)"
              >置顶</el-button>
            </template>
            <template v-else>
              <el-button link type="primary" @click.stop="emit('open', row)">详情</el-button>
              <el-button link type="primary" @click.stop="emit('edit', row)">编辑</el-button>
            </template>
          </div>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="暂无官方题单" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<script setup>
// 官方题单表格：行内状态切换与拖拽绑定内聚在本组件，顺序变更通过 reorder/move/pin
// 上抛给页面壳统一提交全量 ids（update-sort 要求全量有序列表）。
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'
import Sortable from 'sortablejs'
import { Rank } from '@element-plus/icons-vue'
import { updateProblemList } from '@/api/problemList'
import { isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  loading: Boolean,
  sortMode: Boolean,
  sortableActive: Boolean,
  savingSort: Boolean,
  compact: Boolean
})
const emit = defineEmits([
  'open',
  'edit',
  'privacy-changed',
  'reorder',
  'move',
  'pin'
])

const tableRef = ref(null)
let sortableInstance = null

const fmtDate = (v) => (v ? String(v).slice(0, 10) : '-')

const onRowClick = (row) => {
  if (!props.sortMode) emit('open', row)
}

// 状态开关：接口确认成功后才允许切换并上抛变更，失败时开关停在原值。
// 后端 update 使用 updateById（跳过 null 字段）且强制置空归属字段，
// 因此提交 { id, privacy } 是安全的局部更新，不会改写标题/类型/创建人。
const pendingPrivacy = ref(new Set())
const beforePrivacyChange = async (row) => {
  if (pendingPrivacy.value.has(row.id)) return false
  pendingPrivacy.value.add(row.id)
  const next = row.privacy === 1 ? 0 : 1
  try {
    const res = await updateProblemList({ id: row.id, privacy: next })
    if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '状态更新失败'))
    emit('privacy-changed', row.id, next)
    return true
  } catch (error) {
    showApiError(error, '状态更新失败')
    return false
  } finally {
    pendingPrivacy.value.delete(row.id)
  }
}

// Sortable 绑定 el-table 渲染后的 tbody。数据或排序模式变化后必须重建实例：
// v-for 复用节点会改变子节点数量，不重建会出现拖得动但下标错位。
const destroySortable = () => {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
}
const initSortable = () => {
  destroySortable()
  if (!props.sortableActive || !tableRef.value) return
  const tbody = tableRef.value.$el?.querySelector('.el-table__body tbody')
  if (!tbody) return
  sortableInstance = Sortable.create(tbody, {
    handle: '.drag-handle',
    animation: 160,
    ghostClass: 'sortable-ghost',
    chosenClass: 'sortable-chosen',
    onEnd: (evt) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
      emit('reorder', { oldIndex, newIndex })
    }
  })
}
watch(
  () => [props.rows, props.sortableActive],
  async () => {
    await nextTick()
    initSortable()
  },
  { immediate: true }
)
onBeforeUnmount(destroySortable)
</script>

<style scoped>
.pl-table-wrap {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 8px;
  overflow: hidden;
}
.pl-table { --el-table-border-color: var(--border-light); }
.cell-main { min-width: 0; }
.row-link {
  max-width: 100%;
  font-size: 13px;
  justify-content: flex-start;
  padding: 0;
  text-align: left;
}
.row-link :deep(> span) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-sub {
  font-size: 11px;
  color: var(--text-secondary);
  line-height: 1.5;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.order-no { font-size: 12px; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.drag-handle { display: inline-flex; align-items: center; cursor: grab; color: var(--text-secondary); padding: 6px; }
.drag-handle:hover { color: var(--accent-primary); }
.row-actions { display: flex; justify-content: flex-end; align-items: center; gap: 4px; }
:deep(.el-table__row) { cursor: pointer; }
:deep(.el-table__cell) { padding: 7px 0; }
:deep(.sortable-ghost) { background: var(--accent-primary-light); opacity: 0.6; }
:deep(.sortable-chosen) { background: var(--bg-surface-hover); }
</style>
