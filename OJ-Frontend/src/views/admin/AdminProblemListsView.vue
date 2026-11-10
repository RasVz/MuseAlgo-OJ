<template>
  <div class="admin-lists admin-scroll-page">
    <el-alert v-if="sortError" :title="sortError" type="error" :closable="false" show-icon />

    <div class="page-header">
      <div>
        <div class="page-title">官方题单管理</div>
        <div class="page-desc">维护首页题单广场的官方题单（管理员专属）</div>
      </div>
      <el-button type="primary" @click="openCreate">新建官方题单</el-button>
    </div>

    <!-- 全局汇总用单行内联指标，不再与详情指标以卡片形式重复出现 -->
    <div class="inline-stats">
      <span>全部 <b>{{ stats.total ?? 0 }}</b></span>
      <span class="sep">·</span>
      <span>公开 <b>{{ stats.publicCount ?? 0 }}</b></span>
      <span class="sep">·</span>
      <span>私密 <b>{{ stats.privateCount ?? 0 }}</b></span>
      <span class="sep">·</span>
      <span>累计收藏 <b>{{ stats.totalFavorite ?? 0 }}</b></span>
      <span class="inline-hint">表格顺序即广场展示顺序</span>
    </div>

    <div class="toolbar">
      <div class="toolbar-left">
        <el-input v-model="searchKey" placeholder="搜索标题 / 简介…" clearable class="search-input" />
        <el-radio-group v-model="privacyFilter" size="small" class="filter-group">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">公开</el-radio-button>
          <el-radio-button :value="0">私密</el-radio-button>
        </el-radio-group>
      </div>
      <div class="toolbar-right">
        <el-button size="small" @click="refreshAll">刷新</el-button>
        <el-tooltip v-if="filterActive" content="搜索或筛选状态下不能调整顺序，请先清除筛选" placement="top">
          <span class="sort-disabled-wrap">
            <el-button size="small" disabled>调整顺序</el-button>
          </span>
        </el-tooltip>
        <el-button
          v-else
          size="small"
          :type="sortMode ? 'primary' : 'default'"
          :disabled="savingSort"
          @click="toggleSortMode"
        >
          {{ sortMode ? '完成排序' : '调整顺序' }}
        </el-button>
      </div>
    </div>

    <div v-if="sortMode" class="sort-banner">
      <span>排序模式：拖拽手柄，或使用行内上移 / 下移 / 置顶，顺序自动保存。</span>
      <span v-if="savingSort" class="saving">正在保存…</span>
    </div>
    <div v-else-if="filterActive" class="filter-banner">
      当前为筛选结果，顺序调整已锁定；清除筛选后可恢复。
    </div>
    <div v-else-if="sortSaved" class="saved-banner">
      <span>顺序已保存</span>
      <el-button link type="primary" :disabled="savingSort" @click="undoSort">撤销</el-button>
      <el-button link type="info" aria-label="关闭提示" @click="sortSaved = false">关闭</el-button>
    </div>

    <ProblemListTable
      :rows="visibleLists"
      :loading="loading"
      :sort-mode="sortMode"
      :sortable-active="sortMode && !filterActive && !savingSort"
      :saving-sort="savingSort"
      :compact="compact"
      @open="openDetail"
      @edit="openEditor"
      @privacy-changed="onPrivacyChanged"
      @reorder="onReorder"
      @move="onMove"
      @pin="pinRow"
    />

    <div class="table-footer">
      <span>
        共 {{ allLists.length }} 个题单<span v-if="filterActive">，当前显示 {{ visibleLists.length }} 个</span>
      </span>
      <span class="footer-hint">题单内题目的增删在编辑页完成</span>
    </div>

    <ProblemListDetailDrawer
      v-model="drawerVisible"
      :row="selected"
      :problems="problems"
      :loading="problemsLoading"
      :full="drawerFull"
      @delete="requestDelete"
      @preview="openPreview"
      @edit="openEditor"
    />

    <el-dialog
      v-model="deleteDialogVisible"
      title="删除官方题单"
      width="440px"
      append-to-body
      destroy-on-close
      @closed="resetDeleteState"
    >
      <div class="delete-warning">
        即将删除官方题单「{{ deleteTarget?.title }}」。删除后所有用户对该题单的收藏与进度将失效，且不可撤销。
      </div>
      <div class="delete-field-label">请手动输入 <b>确认删除</b> 以启用确认按钮</div>
      <el-input v-model="confirmText" placeholder="请输入「确认删除」" clearable />
      <template #footer>
        <el-button @click="closeDeleteDialog">取消</el-button>
        <el-button
          type="danger"
          :disabled="confirmText !== DELETE_CONFIRM_TEXT || deleting"
          :loading="deleting"
          @click="confirmDelete"
        >确认删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 官方题单管理（方案 A 运营工作台）：全宽表格 + 详情抽屉。
//
// 既有契约（见 docs/plans/plan-2026-11-10-admin-problem-lists-workbench.md）：
// - type=2 官方题单一次全量拉取，筛选在前端完成；
// - update-sort 必须提交全量有序 ids，筛选态禁用排序，失败按服务端重拉回滚；
// - 新建/编辑/预览沿用新标签页（编辑带 ?official=1）；删除在详情抽屉触发，须手动输入「确认删除」。
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getProblemLists,
  getProblemListStats,
  getListProblems,
  deleteProblemList,
  updateProblemListSort
} from '@/api/problemList'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'
import ProblemListTable from './problem-lists/ProblemListTable.vue'
import ProblemListDetailDrawer from './problem-lists/ProblemListDetailDrawer.vue'

const router = useRouter()

const stats = ref({ total: 0, publicCount: 0, privateCount: 0, totalFavorite: 0 })
const allLists = ref([])
const loading = ref(false)
const searchKey = ref('')
const privacyFilter = ref(undefined)
const sortError = ref('')

const filterActive = computed(() => !!searchKey.value.trim() || privacyFilter.value !== undefined)
const visibleLists = computed(() => {
  const keyword = searchKey.value.trim().toLowerCase()
  let list = allLists.value
  if (privacyFilter.value !== undefined) {
    list = list.filter((row) => (row.privacy ?? 0) === privacyFilter.value)
  }
  if (keyword) {
    list = list.filter((row) =>
      `${row.title || ''} ${row.description || ''}`.toLowerCase().includes(keyword)
    )
  }
  return list
})

const selected = ref(null)
const drawerVisible = ref(false)
const problems = ref([])
const problemsLoading = ref(false)

const fetchStats = async () => {
  const res = await getProblemListStats({ type: 2 })
  const data = unwrapResultData(res)
  if (data) stats.value = data
}

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getProblemLists({ page: 1, size: 200, type: 2 })
    const data = unwrapResultData(res)
    allLists.value = data?.records ?? []
    // 重拉后把选中项指向新对象；题单已删除时关闭抽屉，避免停在过期快照上。
    if (selected.value) {
      const fresh = allLists.value.find((row) => row.id === selected.value.id)
      if (fresh) selected.value = fresh
      else {
        selected.value = null
        problems.value = []
        drawerVisible.value = false
      }
    }
  } finally {
    loading.value = false
  }
}

const refreshAll = async () => {
  await Promise.all([fetchStats(), fetchList()])
}

const loadProblems = async (row) => {
  problemsLoading.value = true
  try {
    const res = await getListProblems({ listId: row.id })
    problems.value = unwrapResultData(res) ?? []
  } finally {
    problemsLoading.value = false
  }
}

const openDetail = (row) => {
  selected.value = row
  drawerVisible.value = true
  problems.value = []
  loadProblems(row)
}

// 排序模式：筛选激活时不允许进入；排序过程中输入筛选则自动退出。
const sortMode = ref(false)
const savingSort = ref(false)
const sortSaved = ref(false)
const lastSavedIds = ref(null)

const toggleSortMode = () => {
  if (savingSort.value || filterActive.value) return
  sortMode.value = !sortMode.value
  if (sortMode.value) {
    sortSaved.value = false
    sortError.value = ''
  }
}
watch(filterActive, (active) => {
  if (active && sortMode.value) sortMode.value = false
})

// 每次重排前记录原始 ids，供保存成功后的「撤销」与失败回滚使用。
const applyOrder = (mutator) => {
  if (savingSort.value || !sortMode.value || filterActive.value) return
  const before = allLists.value.map((row) => row.id)
  const next = [...allLists.value]
  mutator(next)
  const after = next.map((row) => row.id)
  if (after.length !== before.length || after.every((id, index) => id === before[index])) return
  allLists.value = next
  saveSort(before)
}

const saveSort = async (previousIds) => {
  if (savingSort.value) return
  savingSort.value = true
  sortError.value = ''
  sortSaved.value = false
  try {
    const res = await updateProblemListSort({ type: 2, ids: allLists.value.map((row) => row.id) })
    if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '排序保存失败'))
    lastSavedIds.value = previousIds
    sortSaved.value = true
  } catch (error) {
    showApiError(error, '排序保存失败')
    sortError.value = '排序未确认保存，已重新读取列表'
    lastSavedIds.value = null
    try {
      await fetchList()
    } catch (reloadError) {
      allLists.value = []
      sortError.value = reloadError.message || '排序保存失败且列表刷新失败，请重试刷新列表'
    }
  } finally {
    savingSort.value = false
  }
}

const undoSort = async () => {
  if (!lastSavedIds.value || savingSort.value) return
  const ids = lastSavedIds.value
  savingSort.value = true
  sortError.value = ''
  try {
    const res = await updateProblemListSort({ type: 2, ids })
    if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '撤销失败'))
    sortSaved.value = false
    lastSavedIds.value = null
    await fetchList()
  } catch (error) {
    showApiError(error, '撤销失败')
    sortError.value = '撤销未确认保存，已重新读取列表'
    await fetchList()
  } finally {
    savingSort.value = false
  }
}

const onReorder = ({ oldIndex, newIndex }) => {
  applyOrder((next) => {
    const [moved] = next.splice(oldIndex, 1)
    next.splice(newIndex, 0, moved)
  })
}
const onMove = (row, direction) => {
  applyOrder((next) => {
    const index = next.findIndex((item) => item.id === row.id)
    const target = index + direction
    if (index < 0 || target < 0 || target >= next.length) return
    ;[next[index], next[target]] = [next[target], next[index]]
  })
}
const pinRow = (row) => {
  applyOrder((next) => {
    const index = next.findIndex((item) => item.id === row.id)
    if (index <= 0) return
    const [moved] = next.splice(index, 1)
    next.unshift(moved)
  })
}

const onPrivacyChanged = (id, privacy) => {
  const target = allLists.value.find((row) => row.id === id)
  if (target) target.privacy = privacy
  if (selected.value?.id === id) selected.value = { ...selected.value, privacy }
  fetchStats()
}

// 编辑/预览/新建沿用既有新标签页行为；编辑页是重表单，避免丢失未保存内容。
const openCreate = () => {
  const target = router.resolve({
    name: 'ProblemListEdit',
    params: { id: 'create' },
    query: { official: '1' }
  })
  window.open(target.href, '_blank')
}
const openEditor = (row) => {
  const target = router.resolve({
    name: 'ProblemListEdit',
    params: { id: row.id },
    query: { official: '1' }
  })
  window.open(target.href, '_blank')
}
const openPreview = (row) => {
  const target = router.resolve({
    name: 'ProblemListDetail',
    params: { id: row.id }
  })
  window.open(target.href, '_blank')
}

// 删除会连带失效所有用户的收藏与进度且不可撤销，因此把入口收进详情抽屉，
// 并要求手动输入完整确认文本才允许点击确认按钮（严格相等，不做 trim）。
const DELETE_CONFIRM_TEXT = '确认删除'
const deleteDialogVisible = ref(false)
const deleteTarget = ref(null)
const confirmText = ref('')
const deleting = ref(false)

const requestDelete = (row) => {
  if (!row) return
  deleteTarget.value = row
  confirmText.value = ''
  deleteDialogVisible.value = true
}
const closeDeleteDialog = () => {
  if (deleting.value) return
  deleteDialogVisible.value = false
}
const resetDeleteState = () => {
  deleteTarget.value = null
  confirmText.value = ''
}
const confirmDelete = async () => {
  if (confirmText.value !== DELETE_CONFIRM_TEXT || !deleteTarget.value || deleting.value) return
  const row = deleteTarget.value
  deleting.value = true
  try {
    const res = await deleteProblemList(row.id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    ElMessage.success('已删除')
    selected.value = null
    problems.value = []
    drawerVisible.value = false
    deleteDialogVisible.value = false
    refreshAll()
  } catch (error) {
    showApiError(error, '删除失败')
  } finally {
    deleting.value = false
  }
}

// 列在窄屏收起，抽屉在手机宽度占满；监听需在卸载时移除。
const compact = ref(false)
const drawerFull = ref(false)
let cleanupMedia = null

onMounted(() => {
  const narrow = window.matchMedia('(max-width: 1199px)')
  const mobile = window.matchMedia('(max-width: 767px)')
  const sync = () => {
    compact.value = narrow.matches
    drawerFull.value = mobile.matches
  }
  sync()
  narrow.addEventListener('change', sync)
  mobile.addEventListener('change', sync)
  cleanupMedia = () => {
    narrow.removeEventListener('change', sync)
    mobile.removeEventListener('change', sync)
  }
  refreshAll()
})

onBeforeUnmount(() => cleanupMedia?.())
</script>

<style scoped>
/* 特异性高于 AdminLayout 的 .admin-main :deep(> .admin-scroll-page)，
   保证页面根保持 flex 纵向布局而不被强制为 display:block */
.admin-lists.admin-scroll-page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  min-height: 100%;
  box-sizing: border-box;
}
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 10px; }
.page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }

.inline-stats {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 12px;
}
.inline-stats b { color: var(--text-primary); font-size: 13px; font-weight: 600; margin-left: 2px; }
.inline-stats .sep { color: var(--border-base); }
.inline-hint { margin-left: auto; font-size: 11px; }

.toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 12px; }
.toolbar-left, .toolbar-right { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.search-input { width: 240px; }
.sort-disabled-wrap { display: inline-flex; cursor: not-allowed; }

.sort-banner, .filter-banner, .saved-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 10px;
  border-radius: 6px;
  font-size: 12px;
}
.sort-banner { background: var(--accent-primary-light); color: var(--text-regular); }
.filter-banner { background: var(--bg-surface-hover); color: var(--text-secondary); }
.saved-banner { background: var(--accent-success-light); color: var(--text-regular); }
.saving { color: var(--accent-primary); }

.table-footer {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 2px;
  font-size: 12px;
  color: var(--text-secondary);
}
.delete-warning {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
  background: var(--accent-danger-light);
  border-radius: 6px;
  padding: 10px 12px;
}
.delete-field-label { font-size: 12px; color: var(--text-secondary); margin: 14px 0 8px; }
.delete-field-label b { color: var(--accent-danger); }

@media (max-width: 767px) {
  .admin-lists.admin-scroll-page { padding: 12px; }
  .page-header { align-items: flex-start; flex-direction: column; }
  .inline-hint, .footer-hint { display: none; }
  .search-input { width: 100%; }
  .toolbar-left, .toolbar-right { width: 100%; }
}
</style>
