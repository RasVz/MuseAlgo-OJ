<template>
  <div class="contest-manager">
    <div class="page-header">
      <div>
        <div class="page-title">🏆 比赛管理</div>
        <div class="page-desc">管理全站所有比赛（列表、搜索、删除、跳转编辑）</div>
      </div>
    </div>

    <div class="filter-wrapper">
      <div class="toolbar">
        <div class="toolbar-left">
          <el-input
            v-model.trim="keyword"
            placeholder="搜索比赛名称..."
            style="width: 320px"
            clearable
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          />
          <el-button @click="handleSearch">查询</el-button>
        </div>
        <div class="toolbar-right">
          <el-button type="primary" @click="openCreatePage">
            新建比赛
          </el-button>
        </div>
      </div>
    </div>

    <el-card shadow="never" class="list-card">
      <el-table :data="list" v-loading="loading" style="width: 100%" height="100%">
        <el-table-column prop="id" label="ID" width="100" align="center" />
        <el-table-column prop="title" label="比赛名称" min-width="300" />

        <el-table-column label="起止时间" width="280">
          <template #default="{ row }">
            <div class="time-range">
              <div><span class="label">始</span> {{ formatTime(row.startTime) }}</div>
              <div><span class="label">终</span> {{ formatTime(row.endTime) }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row)">{{ getStatusText(row) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" min-width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditPage(row)">编辑</el-button>
            <el-button link type="success" @click="exportRank(row)">导出成绩</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-box">
        <el-pagination
          background
          layout="prev, pager, next, total"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 比赛管理（仅管理员）：比赛检索、删除与成绩导出。
// 编辑与题目配置在 ContestEditorView，本页只负责列表级操作。
// 删除比赛会连带删除其提交记录，不可恢复，故二次确认。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { getContestList, deleteContest, exportContestRank } from '@/api/contest'

const router = useRouter()

const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const keyword = ref('')

function unwrapResult(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) {
    return resp.data
  }
  return resp
}

// 响应包装判定的本地副本：部分历史接口仍用 code=0 表示成功，
// 与 utils/response 的判定不完全一致，故本页不共用。
function isSuccess(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp) {
    return Number(resp.code) === 200 || Number(resp.code) === 0
  }
  return true
}

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getContestList({
      currentPage: currentPage.value,
      pageSize,
      keyword: keyword.value || undefined
    })

    if (!isSuccess(res)) {
      ElMessage.error(res?.msg || '获取比赛列表失败')
      return
    }

    const data = unwrapResult(res) || {}
    list.value = Array.isArray(data.records) ? data.records : []
    total.value = Number(data.total || 0)
  } catch (e) {
    ElMessage.error(e?.message || '获取比赛列表失败')
  } finally {
    loading.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

const handleSearch = () => {
  currentPage.value = 1
  fetchData()
}

const openCreatePage = () => {
  const target = router.resolve({ name: 'AdminContestCreate' })
  window.open(target.href, '_blank')
}

const openEditPage = (row) => {
  if (!row?.id) return
  const target = router.resolve({
    name: 'AdminContestEdit',
    params: { id: row.id }
  })
  window.open(target.href, '_blank')
}

const exportRank = (row) => {
  if (!row?.id) return
  exportContestRank(row.id)
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定要删除比赛「${row.title || row.id}」吗？`, '提示', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  }).then(async () => {
    const res = await deleteContest(row.id)
    if (!isSuccess(res)) {
      ElMessage.error(res?.msg || '删除失败')
      return
    }
    ElMessage.success('删除成功')
    fetchData()
  }).catch(() => {})
}

// 辅助函数
const formatTime = (t) => {
  if (!t) return '-'
  const d = dayjs(t)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : '-'
}

const getStatusType = (row) => {
  const now = dayjs()
  const start = dayjs(row.startTime)
  const end = dayjs(row.endTime)
  if (!start.isValid() || !end.isValid()) return 'info'
  if (now.isBefore(start)) return 'info'    // 未开始
  if (now.isAfter(end)) return 'danger'     // 已结束
  return 'success'                          // 进行中
}

const getStatusText = (row) => {
  const type = getStatusType(row)
  const map = { info: '未开始', success: '进行中', danger: '已结束' }
  return map[type] || '未知'
}

onMounted(() => fetchData())
</script>

<style scoped>
.contest-manager { padding: 16px; gap: 16px; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; }
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.filter-wrapper { flex-shrink: 0; background: var(--bg-surface); padding: 15px; border-radius: 8px; border: 1px solid var(--border-light); }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.toolbar-left { display: flex; align-items: center; gap: 8px; }
.toolbar-right { display: flex; align-items: center; }
.list-card { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.list-card :deep(.el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.list-card :deep(.el-table) { flex: 1; min-height: 0; }
.time-range { font-size: 13px; font-family: monospace; color: var(--text-regular); line-height: 1.5; }
.time-range .label { color: var(--text-secondary); margin-right: 4px; }
.pagination-box { margin-top: auto; padding-top: 14px; display: flex; justify-content: center; }
</style>
