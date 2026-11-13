<template>
  <div class="admin-announcements admin-scroll-page ann-page-flex">
    <div v-if="loadError" class="error-bar">
      <span>{{ loadError }}</span>
      <el-button size="small" @click="fetchList">重试</el-button>
    </div>

    <div class="page-header">
      <div>
        <h1 class="page-title">公告管理</h1>
        <div class="page-desc">发布、编辑与置顶全站公告</div>
      </div>
      <el-button type="primary" @click="openEditor(null)">发布公告</el-button>
    </div>

    <div class="inline-stats">
      <span>共 <b>{{ stats.total }}</b> 条</span>
      <span class="sep">·</span>
      <span>本页置顶 <b>{{ stats.top }}</b></span>
      <span class="sep">·</span>
      <span>本页普通 <b>{{ stats.normal }}</b></span>
      <span class="inline-hint">列表按置顶与发布时间排序；搜索与筛选仅作用于当前页</span>
    </div>

    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="searchKey"
          clearable
          placeholder="搜索本页标题 / 内容…"
          class="search-input"
          aria-label="搜索本页公告标题或内容"
        />
        <el-radio-group v-model="topFilter" size="small" aria-label="按置顶状态筛选">
          <el-radio-button :value="undefined">全部</el-radio-button>
          <el-radio-button :value="1">置顶</el-radio-button>
          <el-radio-button :value="0">普通</el-radio-button>
        </el-radio-group>
      </div>
      <div class="toolbar-right">
        <el-select v-model="size" size="small" class="page-size-select" aria-label="每页条数">
          <el-option :value="10" label="10 条/页" />
          <el-option :value="20" label="20 条/页" />
          <el-option :value="50" label="50 条/页" />
        </el-select>
        <el-button size="small" :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <AnnouncementTable
      :rows="visibleRows"
      :loading="loading"
      :compact="compact"
      :mobile="mobile"
      :top-saving-id="topSavingId"
      :empty-text="emptyText"
      @detail="openDetail"
      @edit="openEditor"
      @toggle-top="toggleTop"
    />

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :pager-count="5"
      :small="mobile"
      layout="total, prev, pager, next"
      class="pager"
    />

    <AnnouncementDetailDrawer
      v-model="detailVisible"
      :row="detailRow"
      :full="mobile"
      @delete="requestDelete"
      @preview="openPreview"
      @edit="openEditor"
    />

    <AnnouncementEditorDrawer
      v-model="editorVisible"
      :row="editorRow"
      :full="mobile"
      :saving="saving"
      @submit="submitEditor"
    />

    <el-dialog
      v-model="deleteDialogVisible"
      title="删除公告"
      width="440px"
      append-to-body
      destroy-on-close
      @closed="resetDeleteState"
    >
      <div class="delete-warning">
        即将删除公告「{{ deleteTarget?.title }}」。该操作为物理删除，删除后所有用户将不再看到该公告，且不可撤销。
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
// 公告管理（方案 B 公告运营数据表）：全宽表格 + 详情/编辑抽屉。
//
// 既有契约（见 docs/plans/plan-2026-11-11-admin-announcements-workbench.md）：
// - list 无搜索/过滤参数，后端固定「置顶降序 + 时间降序」分页，故搜索与筛选只作用于当前页；
// - save 编辑分支校验 title/content 非空且只写 id/title/content/isTop，
//   因此行内置顶必须提交完整四字段，不能只发 isTop；
// - delete 是物理删除，故用「输入确认删除」二次确认；
// - 请求层对非 200 已统一提示，异常分支用 showApiError 避免重复 toast。
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getAnnouncementList, saveAnnouncement, deleteAnnouncement } from '@/api/adminAnnouncement'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'
import AnnouncementTable from './announcements/AnnouncementTable.vue'
import AnnouncementDetailDrawer from './announcements/AnnouncementDetailDrawer.vue'
import AnnouncementEditorDrawer from './announcements/AnnouncementEditorDrawer.vue'

const router = useRouter()

const rows = ref([])
const loading = ref(false)
const loadError = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const searchKey = ref('')
const topFilter = ref(undefined)
const topSavingId = ref(null)
const saving = ref(false)

const filterActive = computed(() => !!searchKey.value.trim() || topFilter.value !== undefined)
const visibleRows = computed(() => {
  const keyword = searchKey.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (topFilter.value !== undefined && (row.isTop === 1 ? 1 : 0) !== topFilter.value) return false
    if (!keyword) return true
    return `${row.title || ''} ${row.content || ''}`.toLowerCase().includes(keyword)
  })
})
const emptyText = computed(() => (filterActive.value ? '当前筛选条件下没有公告' : '暂无公告'))
// 只有 total 是后端给出的全局值；置顶/普通统计覆盖当前页 records，文案明确标「本页」。
const stats = computed(() => ({
  total: total.value,
  top: rows.value.filter((row) => row.isTop === 1).length,
  normal: rows.value.filter((row) => row.isTop !== 1).length
}))

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getAnnouncementList({ page: page.value, size: size.value })
    if (!isResultSuccess(res)) {
      loadError.value = getResultMessage(res, '公告列表加载失败')
      return false
    }
    const data = unwrapResultData(res)
    rows.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total) || 0
    loadError.value = ''
    return true
  } catch (error) {
    showApiError(error, '公告列表加载失败')
    loadError.value = '公告列表加载失败，请点击重试'
    return false
  } finally {
    loading.value = false
  }
}

// 页码或每页条数变化统一在此触发一次拉取，避免 current-change 与 size-change 重复请求。
watch([page, size], () => { fetchList() })

const detailVisible = ref(false)
const detailRow = ref(null)
const editorVisible = ref(false)
const editorRow = ref(null)

const openDetail = (row) => {
  detailRow.value = row
  detailVisible.value = true
}
const openEditor = (row) => {
  if (detailVisible.value) detailVisible.value = false
  editorRow.value = row || null
  editorVisible.value = true
}
const openPreview = (row) => {
  if (!row?.id) return
  const target = router.resolve({ name: 'PostDetail', params: { id: row.id } })
  window.open(target.href, '_blank', 'noopener')
}

// 置顶会改变后端排序，成功后重拉列表而不是只改本地一行；失败按服务端列表回滚。
const toggleTop = async (row, isTop) => {
  if (topSavingId.value !== null) return
  topSavingId.value = row.id
  try {
    const res = await saveAnnouncement({
      id: row.id,
      title: row.title,
      content: row.content,
      isTop
    })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '置顶状态保存失败'))
      await fetchList()
      return
    }
    ElMessage.success(isTop === 1 ? '已置顶' : '已取消置顶')
    if (detailRow.value?.id === row.id) detailRow.value = { ...detailRow.value, isTop }
    await fetchList()
  } catch (error) {
    showApiError(error, '置顶状态保存失败')
    await fetchList()
  } finally {
    topSavingId.value = null
  }
}

const submitEditor = async (form) => {
  if (saving.value) return
  saving.value = true
  try {
    const res = await saveAnnouncement(form)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }
    ElMessage.success('保存成功')
    editorVisible.value = false
    if (detailRow.value?.id === form.id) {
      detailRow.value = { ...detailRow.value, title: form.title, content: form.content, isTop: form.isTop }
    }
    await fetchList()
  } catch (error) {
    showApiError(error, '保存失败')
  } finally {
    saving.value = false
  }
}

// 物理删除不可恢复，沿用官方题单页的输入确认口径。
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
    const res = await deleteAnnouncement(row.id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    ElMessage.success('已删除')
    deleteDialogVisible.value = false
    detailVisible.value = false
    detailRow.value = null
    // 删掉末页最后一条时回退页码，避免停在空页；页码变化由 watch 触发拉取。
    if (rows.value.length === 1 && page.value > 1) page.value -= 1
    else await fetchList()
  } catch (error) {
    showApiError(error, '删除失败')
  } finally {
    deleting.value = false
  }
}

// 列在中等宽度收起、抽屉在手机宽度占满；监听需在卸载时移除。
const compact = ref(false)
const mobile = ref(false)
let cleanupMedia = null

onMounted(() => {
  const narrow = window.matchMedia('(max-width: 1199px)')
  const phone = window.matchMedia('(max-width: 767px)')
  const sync = () => {
    compact.value = narrow.matches
    mobile.value = phone.matches
  }
  sync()
  narrow.addEventListener('change', sync)
  phone.addEventListener('change', sync)
  cleanupMedia = () => {
    narrow.removeEventListener('change', sync)
    phone.removeEventListener('change', sync)
  }
  fetchList()
})

onBeforeUnmount(() => cleanupMedia?.())
</script>

<style scoped>
/* 根改为 flex 纵向：行数不足一屏时，由 .pager 的 margin-top:auto 吸收剩余空间，
   把翻页按钮推到页面底部；表格本身不设 flex:1，不会被拉伸成原「撑满留白」的样子。
   多一个类选择器是为了覆盖 AdminLayout 对 .admin-scroll-page 强制的 display:block。
   内容超过一屏时剩余空间为 0，分页仍紧随表格，整体在滚动区内自然增高。 */
.admin-announcements.admin-scroll-page.ann-page-flex {
  padding: 16px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}
.ann-page-flex > :deep(*) { flex-shrink: 0; }
.error-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border-base);
  border-radius: var(--radius-md);
  background: var(--accent-danger-light);
  color: var(--accent-danger);
  font-size: 13px;
}
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 10px; }
.page-title { margin: 0; font-size: 20px; font-weight: 600; color: var(--text-primary); }
.page-desc { color: var(--text-regular); font-size: 13px; margin-top: 4px; }

.inline-stats {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border-light);
  color: var(--text-regular);
  font-size: 13px;
}
.inline-stats b { color: var(--text-primary); font-weight: 600; }
.sep { color: var(--text-secondary); }
.inline-hint { margin-left: auto; font-size: 12px; color: var(--text-regular); }

.toolbar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 14px 0; }
.toolbar-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.toolbar-right { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.search-input { width: 250px; max-width: 100%; }
.page-size-select { width: 112px; }
.pager { margin-top: auto; padding-top: 14px; justify-content: center; flex-wrap: wrap; }

.delete-warning {
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--accent-danger-light);
  color: var(--accent-danger);
  font-size: 13px;
  line-height: 1.7;
}
.delete-field-label { margin: 14px 0 8px; font-size: 13px; color: var(--text-primary); }

@media (max-width: 767px) {
  .admin-announcements.admin-scroll-page { padding: 12px; }
  .toolbar-right { margin-left: 0; width: 100%; }
  .search-input { width: 100%; }
  .inline-hint { margin-left: 0; width: 100%; }
}
</style>
