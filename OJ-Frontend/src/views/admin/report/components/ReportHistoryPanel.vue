<template>
  <div class="history-panel">
    <div class="toolbar">
      <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-input v-model.trim="keyword" placeholder="按学生或周期筛选" clearable style="width: 220px" />
      <span class="toolbar-hint">筛选作用于当前加载页（{{ items.length }} 条），分页由服务端返回</span>
      <div class="toolbar-actions">
        <el-button
          type="warning"
          plain
          :loading="batchRetrying"
          :disabled="!retryableSelected.length"
          @click="emit('batch-retry', retryableSelected)"
        >
          批量重试{{ retryableSelected.length ? `（${retryableSelected.length}）` : '' }}
        </el-button>
        <el-button :loading="loading" @click="emit('refresh')">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon class="mb-12">
      <template #default>
        <el-button link type="primary" @click="emit('refresh')">重试</el-button>
      </template>
    </el-alert>

    <el-table
      v-loading="loading"
      :data="filteredItems"
      stripe
      class="history-table"
      @selection-change="selectedRows = $event"
    >
      <el-table-column type="selection" width="48" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="metaOf(STATUS_META, row.status).type" size="small">{{ metaOf(STATUS_META, row.status).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="学生" min-width="150">
        <template #default="{ row }">
          <div class="student-cell">
            <span>{{ row.studentName || memberNames.get(Number(row.studentId)) || `学生 #${row.studentId}` }}</span>
            <span class="student-id">#{{ row.studentId }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="周期" min-width="190">
        <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
      </el-table-column>
      <el-table-column label="尝试" width="90" align="center">
        <template #default="{ row }">{{ row.attempts ?? 0 }} / {{ MAX_ATTEMPTS }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.status === 'FAILED'" class="failure-text">
            <span v-if="failureCode(row)" class="failure-code">{{ failureCode(row) }} </span>{{ failureMessage(row) }}
          </span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-if="row.status === 'GENERATED'" type="primary" link @click="emit('open-report', row.id)">打开报告</el-button>
          <el-button link @click="emit('inspect', row.id)">交付与提醒</el-button>
          <el-button
            v-if="row.status === 'FAILED' && canRetry(row)"
            type="warning"
            link
            :loading="retryingId === row.id"
            @click="emit('retry', row)"
          >
            重试
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="items.length ? '当前筛选无匹配记录' : '该班级暂无学习周报'" :image-size="80" />
      </template>
    </el-table>

    <div v-if="total > 0" class="pager">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="(value) => emit('update:page', value)"
        @size-change="(value) => emit('update:size', value)"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import {
  MAX_ATTEMPTS, STATUS_META, canRetry, failureCode, failureMessage, metaOf
} from '../composables/reportMeta'

const props = defineProps({
  items: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 10 },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  retryingId: { type: [String, Number], default: null },
  batchRetrying: { type: Boolean, default: false },
  memberNames: { type: Map, default: () => new Map() }
})
const emit = defineEmits([
  'update:page', 'update:size', 'refresh', 'retry', 'batch-retry', 'open-report', 'inspect'
])

const statusOptions = [
  { label: '已生成', value: 'GENERATED' },
  { label: '排队中', value: 'PENDING' },
  { label: '生成中', value: 'GENERATING' },
  { label: '失败', value: 'FAILED' }
]
const statusFilter = ref('')
const keyword = ref('')
const selectedRows = ref([])

const filteredItems = computed(() => {
  const needle = keyword.value.toLowerCase()
  return props.items.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (!needle) return true
    const name = String(row.studentName || props.memberNames.get(Number(row.studentId)) || '')
    return `${name} ${row.periodStart} ${row.periodEnd} #${row.studentId}`.toLowerCase().includes(needle)
  })
})

const retryableSelected = computed(() => selectedRows.value.filter((row) => canRetry(row)))

function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}
</script>

<style scoped>
.history-panel { padding-top: 4px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.toolbar-hint { font-size: 12px; color: var(--text-secondary); flex: 1; min-width: 180px; }
.toolbar-actions { display: flex; gap: 8px; }
.mb-12 { margin-bottom: 12px; }
.history-table { width: 100%; }
.student-cell { display: flex; align-items: baseline; gap: 6px; }
.student-id { font-size: 12px; color: var(--text-secondary); }
.failure-text { color: var(--text-regular); }
.failure-code { font-family: Menlo, monospace; font-size: 12px; color: var(--accent-danger); }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
</style>
