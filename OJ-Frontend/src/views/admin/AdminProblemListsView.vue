<template>
  <div class="admin-lists admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">官方题单管理</div>
        <div class="page-desc">维护首页题单广场的官方题单（管理员专属）</div>
      </div>
      <el-button type="primary" @click="openCreate">新建官方题单</el-button>
    </div>

    <!-- 统计条 -->
    <div class="stats-row">
      <div v-for="s in statCards" :key="s.label" class="stat-card">
        <div class="stat-label">{{ s.label }}</div>
        <div class="stat-value">{{ s.value }}</div>
      </div>
    </div>

    <!-- 主从分栏 -->
    <div class="split">
      <!-- 左栏：搜索 + 筛选 + 列表（可拖拽排序） -->
      <div class="split-left">
        <div class="left-toolbar">
          <el-input
            v-model="searchKey"
            placeholder="搜索标题 / 简介…"
            clearable
            class="search-input"
          />
          <el-radio-group v-model="privacyFilter" size="small" class="filter-group">
            <el-radio-button :value="undefined">全部</el-radio-button>
            <el-radio-button :value="1">公开</el-radio-button>
            <el-radio-button :value="0">私密</el-radio-button>
          </el-radio-group>
          <div class="sort-hint" v-if="!filterActive">
            拖拽手柄或点置顶可调整广场展示顺序
          </div>
        </div>

        <div v-loading="loading" class="left-list">
          <div ref="listBodyRef" class="list-body">
            <div
              v-for="row in visibleLists"
              :key="row.id"
              class="list-row"
              :class="{ active: selected && selected.id === row.id }"
              @click="selectRow(row)"
            >
              <span v-if="!filterActive" class="drag-handle" @click.stop title="拖拽排序">
                <el-icon><Rank /></el-icon>
              </span>
              <div class="row-main">
                <div class="row-title">{{ row.title }}</div>
                <div class="row-meta">
                  {{ row.problemCount ?? 0 }} 题 · {{ row.favoriteCount ?? 0 }} 收藏 · {{ fmtDate(row.updateTime) }}
                </div>
              </div>
              <el-tooltip content="置顶" placement="top" :disabled="filterActive || visibleLists[0]?.id === row.id">
                <span
                  v-if="!filterActive && visibleLists[0]?.id !== row.id"
                  class="pin-btn"
                  @click.stop="pinRow(row)"
                >
                  <el-icon><Top /></el-icon>
                </span>
              </el-tooltip>
            </div>
          </div>
          <el-empty v-if="!loading && visibleLists.length === 0" description="暂无题单" :image-size="60" />
        </div>

        <div class="left-footer">
          <span>共 {{ allLists.length }} 个题单</span>
          <span v-if="savingSort" class="saving-tip">正在保存排序…</span>
        </div>
      </div>

      <!-- 右栏：详情面板 -->
      <div class="split-right">
        <template v-if="selected">
          <div class="detail-head">
            <div class="detail-title-line">
              <span class="detail-title">{{ selected.title }}</span>
              <el-tag size="small" effect="light" :type="selected.privacy === 1 ? 'success' : 'info'">
                {{ selected.privacy === 1 ? '公开' : '私密' }}
              </el-tag>
            </div>
            <div class="detail-meta">
              创建于 {{ fmtDate(selected.createTime) }} · 更新于 {{ fmtDate(selected.updateTime) }} · ID {{ selected.id }}
            </div>
          </div>

          <div class="detail-stats">
            <div class="d-stat">
              <div class="d-stat-label">题目数量</div>
              <div class="d-stat-value">{{ selected.problemCount ?? 0 }}</div>
            </div>
            <div class="d-stat">
              <div class="d-stat-label">关联课件</div>
              <div class="d-stat-value">{{ selected.coursewareCount ?? 0 }}</div>
            </div>
            <div class="d-stat">
              <div class="d-stat-label">累计收藏</div>
              <div class="d-stat-value">{{ selected.favoriteCount ?? 0 }}</div>
            </div>
          </div>

          <div class="detail-desc">{{ selected.description || '暂无简介' }}</div>

          <div class="detail-problems">
            <div class="dp-title">题单内题目 <span class="dp-count">共 {{ selected.problemCount ?? 0 }} 题</span></div>
            <div v-loading="problemsLoading" class="dp-list">
              <div v-for="p in problemPreview" :key="p.id" class="dp-row">
                <span class="dp-id">#{{ p.id }}</span>
                <span class="dp-name">{{ p.title }}</span>
                <span v-if="p.difficulty" class="dp-diff" :style="{ color: getDifficultyColor(p.difficulty) }">
                  {{ getDifficultyLabel(p.difficulty) }}
                </span>
              </div>
              <div v-if="!problemsLoading && problemPreview.length === 0" class="dp-empty">题单暂无题目</div>
            </div>
            <div v-if="(selected.problemCount ?? 0) > 5" class="dp-more" @click="openEditor(selected)">
              查看全部 {{ selected.problemCount }} 题 →
            </div>
          </div>

          <div class="detail-actions">
            <el-button size="small" @click="openPreview(selected)">预览</el-button>
            <el-button size="small" type="primary" @click="openEditor(selected)">编辑</el-button>
            <el-button size="small" type="danger" plain @click="onDelete(selected)">删除</el-button>
          </div>
        </template>

        <div v-else class="detail-empty">
          <el-empty description="从左侧选择一个题单查看详情" :image-size="80" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 官方题单管理：首页题单广场的官方题单维护入口，仅管理员可进入。
//
// 页面采用主从分栏：左栏一次拉全量题单（官方题单量级小），框内滚动 + 拖拽排序；
// 右栏展示选中题单的详情与题目预览。题单内题目的增删排序在独立的编辑页完成。
// 依赖 getProblemListStats / getProblemLists / getListProblems /
// deleteProblemList / updateProblemListSort 五个接口。
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Sortable from 'sortablejs'
import { Rank, Top } from '@element-plus/icons-vue'
import { getProblemLists, getProblemListStats, getListProblems, deleteProblemList, updateProblemListSort } from '@/api/problemList'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const router = useRouter()

// 统计条
const stats = ref({ total: 0, publicCount: 0, privateCount: 0, totalFavorite: 0 })
const statCards = computed(() => [
  { label: '全部题单', value: stats.value.total ?? 0 },
  { label: '公开', value: stats.value.publicCount ?? 0 },
  { label: '私密', value: stats.value.privateCount ?? 0 },
  { label: '累计收藏', value: stats.value.totalFavorite ?? 0 }
])

// 全量列表（服务端已按 sort_order 排序；官方题单量小，一次拉全量，左栏框内滚动）
const allLists = ref([])
const loading = ref(false)
const searchKey = ref('')
const privacyFilter = ref(undefined) // undefined=全部, 1=公开, 0=私密

// 搜索/筛选在前端过滤（排序始终基于全量，筛选激活时禁用拖拽与置顶）
const filterActive = computed(() => !!searchKey.value.trim() || privacyFilter.value !== undefined)
const visibleLists = computed(() => {
  const kw = searchKey.value.trim().toLowerCase()
  let list = allLists.value
  if (privacyFilter.value !== undefined) {
    list = list.filter((r) => (r.privacy ?? 0) === privacyFilter.value)
  }
  if (kw) {
    list = list.filter((r) => (r.title || '').toLowerCase().includes(kw))
  }
  return list
})

// 选中详情
const selected = ref(null)
const problems = ref([])
const problemsLoading = ref(false)
const problemPreview = computed(() => problems.value.slice(0, 5))

const fetchStats = async () => {
  // type=2 表示"官方题单"，与个人题单区分
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
    // 重新拉取后，若之前选中的题单已被删除，必须清空右栏，
    // 否则右栏会停在一个已不存在的题单快照上。
    if (selected.value && !allLists.value.some((r) => r.id === selected.value.id)) {
      selected.value = null
      problems.value = []
    }
  } finally {
    loading.value = false
  }
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

const selectRow = (row) => {
  selected.value = row
  problems.value = []
  loadProblems(row)
}

// 拖拽排序
const listBodyRef = ref(null)
let sortableInstance = null
const savingSort = ref(false)

// Sortable 实例挂在 DOM 上，不随组件卸载自动释放；离开页面前必须 destroy，
// 否则残留的全局监听会在下次进入时对已销毁的节点继续响应。
const destroySortable = () => {
  if (sortableInstance) {
    sortableInstance.destroy()
    sortableInstance = null
  }
}

const initSortable = () => {
  destroySortable()
  // 筛选态下 visibleLists 是 allLists 的子集，oldIndex/newIndex 与 allLists 的下标对不上，
  // 此时若照常重排会把错误的题单挪到前面，所以直接禁用拖拽。
  if (filterActive.value || !listBodyRef.value || visibleLists.value.length === 0) return
  sortableInstance = Sortable.create(listBodyRef.value, {
    animation: 180,
    handle: '.drag-handle',
    ghostClass: 'sortable-ghost',
    chosenClass: 'sortable-chosen',
    dragClass: 'sortable-drag',
    onEnd: (evt) => {
      const { oldIndex, newIndex } = evt
      if (oldIndex == null || newIndex == null || oldIndex === newIndex) return
      const arr = [...allLists.value]
      const [moved] = arr.splice(oldIndex, 1)
      arr.splice(newIndex, 0, moved)
      allLists.value = arr
      saveSort()
    }
  })
}

// 保存失败时重新拉全量来纠正顺序：本地内存里已经没有原始顺序可用，
// 与其手动还原不如以服务端为准。
const saveSort = async () => {
  // savingSort 期间忽略新的排序请求：连续拖拽会产生并发写，后到的旧结果会覆盖新顺序。
  if (savingSort.value) return
  savingSort.value = true
  try {
    const res = await updateProblemListSort({ type: 2, ids: allLists.value.map((r) => r.id) })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '排序保存失败'))
      fetchList()
      return
    }
    ElMessage.success('排序已保存')
  } catch {
    ElMessage.error('排序保存失败，已恢复原顺序')
    fetchList()
  } finally {
    savingSort.value = false
  }
}

const pinRow = (row) => {
  const arr = allLists.value.filter((r) => r.id !== row.id)
  arr.unshift(row)
  allLists.value = arr
  saveSort()
}

// 全量列表或筛选态变化时重建/销毁拖拽实例
// Sortable 绑定的是渲染后的真实 DOM，而 v-for 复用节点、筛选还会改变子节点数量，
// 数据变化后不重建就会出现拖得动但落错位的问题。nextTick 等 DOM 更新完再初始化。
watch(
  () => [allLists.value, filterActive.value],
  async () => {
    await nextTick()
    initSortable()
  }
)

// 新建/编辑/预览题单都在新标签页打开：题单编辑页是重表单，丢一次编辑不该发生。
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

// 删除题单会连带失效所有用户对它的收藏与进度，不可撤销，故必须二次确认。
// 用户点取消时 confirm 抛出的 'cancel' 被这里直接吞掉，不弹错误提示。
const onDelete = async (row) => {
  await ElMessageBox.confirm(`确定删除官方题单「${row.title}」？`, '删除确认', { type: 'warning' })
  const res = await deleteProblemList(row.id)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '删除失败'))
    return
  }
  ElMessage.success('已删除')
  selected.value = null
  problems.value = []
  fetchStats()
  fetchList()
}

const fmtDate = (v) => (v ? String(v).slice(0, 10) : '-')

onMounted(() => {
  fetchStats()
  fetchList()
})

onBeforeUnmount(() => {
  destroySortable()
})
</script>

<style scoped>
/* 特异性(0,3,0)高于 AdminLayout 的 .admin-main :deep(> .admin-scroll-page)(0,2,0)，
   确保 display:flex 不被 display:block 覆盖，页面撑满可视区高度 */
.admin-lists.admin-scroll-page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  min-height: 100%;
  box-sizing: border-box;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }

/* 统计条 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.stat-card {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  padding: 12px 16px;
}
.stat-label { font-size: 12px; color: var(--text-secondary); }
.stat-value { font-size: 24px; font-weight: 600; margin-top: 2px; color: var(--text-primary); }

/* 主从分栏 */
.split {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 14px;
  align-items: stretch;
}

/* 左栏 */
.split-left {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  overflow: hidden;
}
.left-toolbar {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-light);
}
.search-input { margin-bottom: 8px; }
.filter-group { display: flex; }
.sort-hint {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 8px;
  line-height: 1.5;
}
.left-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px;
}
/* 空态时列表区占满并居中 */
.left-list:has(.el-empty) {
  display: flex;
  align-items: center;
  justify-content: center;
}
.left-list:has(.el-empty) .list-body {
  display: none;
}
.list-body { min-height: 40px; }
.list-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.list-row:hover { background: var(--bg-hover, rgba(0, 0, 0, 0.03)); }
.list-row.active { background: var(--bg-active, rgba(99, 102, 241, 0.1)); }
.row-main {
  flex: 1;
  min-width: 0;
}
.row-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-meta { font-size: 11px; color: var(--text-secondary); margin-top: 3px; }
.drag-handle {
  flex-shrink: 0;
  color: var(--text-secondary);
  cursor: grab;
  display: flex;
  align-items: center;
  opacity: 0.5;
}
.drag-handle:hover { opacity: 1; color: var(--accent, #6366f1); }
.pin-btn {
  flex-shrink: 0;
  color: var(--text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  opacity: 0.5;
}
.pin-btn:hover { opacity: 1; color: var(--accent, #6366f1); }
.left-footer {
  border-top: 1px solid var(--border-light);
  padding: 8px 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  min-height: 44px;
}
.saving-tip { color: var(--accent, #6366f1); }
.sortable-ghost { background: var(--bg-active, rgba(99, 102, 241, 0.12)); opacity: 0.6; }
.sortable-chosen { background: var(--bg-hover, rgba(0, 0, 0, 0.03)); }
.sortable-drag { box-shadow: var(--shadow-md, 0 4px 6px -1px rgba(0, 0, 0, 0.1)); background: var(--bg-surface); }

/* 右栏 */
.split-right {
  flex: 1;
  min-width: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 16px;
  overflow-y: auto;
}
.detail-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.detail-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.detail-title { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.detail-meta { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }

.detail-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0;
}
.d-stat {
  background: var(--bg-page, var(--bg-app));
  border-radius: 8px;
  padding: 10px 12px;
}
.d-stat-label { font-size: 11px; color: var(--text-secondary); }
.d-stat-value { font-size: 20px; font-weight: 600; color: var(--text-primary); }

.detail-desc {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
  border-bottom: 1px solid var(--border-light);
  padding-bottom: 12px;
}

.detail-problems { margin-top: 12px; }
.dp-title { font-size: 13px; font-weight: 500; color: var(--text-primary); margin-bottom: 8px; }
.dp-count { font-size: 11px; color: var(--text-secondary); font-weight: 400; margin-left: 4px; }
.dp-list { min-height: 40px; }
.dp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 4px;
  font-size: 13px;
  border-bottom: 1px dashed var(--border-light);
}
.dp-id { color: var(--text-secondary); font-size: 12px; width: 48px; flex-shrink: 0; }
.dp-name {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dp-diff { font-size: 11px; flex-shrink: 0; }
.dp-empty { font-size: 12px; color: var(--text-secondary); padding: 10px 4px; }
.dp-more {
  margin-top: 10px;
  font-size: 12px;
  color: var(--accent, #6366f1);
  cursor: pointer;
}
.dp-more:hover { text-decoration: underline; }

.detail-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
