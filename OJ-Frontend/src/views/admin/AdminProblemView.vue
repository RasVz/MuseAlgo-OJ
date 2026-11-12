<template>
  <el-config-provider>
    <div class="page-container admin-scroll-page">
      <div class="page-header">
        <div>
          <div class="page-title">📂 {{ isAdmin ? '题库管理' : '我的题库管理' }}</div>
          <div class="page-desc">{{ isAdmin ? '全量题库（含私有，管理员）' : '仅管理我创建的题目' }}</div>
        </div>
      </div>
      <div class="filter-wrapper">
        <div class="toolbar">
          <div class="toolbar-left">
            <el-input
              v-model="keyword"
              placeholder="输入题目名称、ID或标签搜索..."
              prefix-icon="Search"
              style="width: 320px"
              clearable
              @keyup.enter="fetchData"
              @clear="fetchData"
            />
            <el-radio-group v-model="statusFilter" class="ml-12" @change="handleStatusChange">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button label="public">公开</el-radio-button>
              <el-radio-button label="private">私有</el-radio-button>
            </el-radio-group>
          </div>
          <div class="toolbar-right">
            <el-button
              :icon="MagicStick"
              :disabled="selectedProblems.length === 0"
              @click="openFormatJob"
            >
              批量优化{{ selectedProblems.length ? ` (${selectedProblems.length})` : '' }}
            </el-button>
            <el-popover trigger="click" placement="bottom-end" width="170">
              <template #reference>
                <el-button :icon="Setting">列设置</el-button>
              </template>
              <div class="col-setting">
                <div class="col-setting-title">显示列（默认隐藏）</div>
                <div v-for="item in columnOptions" :key="item.key" class="col-setting-item">
                  <el-checkbox v-model="colVisible[item.key]" @change="persistColumns">
                    {{ item.label }}
                  </el-checkbox>
                </div>
              </div>
            </el-popover>
            <input
              ref="fpsFileInput"
              class="visually-hidden"
              type="file"
              accept=".xml,.fps,.zip,text/xml,application/xml,application/zip"
              @change="handleFpsFileSelected"
            />
            <el-button :icon="Upload" :loading="importing" @click="chooseFpsFile">
              导入 FPS
            </el-button>
            <el-button type="primary" icon="Plus" @click="handleCreate">
              新建题目
            </el-button>
          </div>
        </div>
      </div>

      <div class="table-wrapper">
        <el-table
          :data="list"
          v-loading="loading"
          style="width: 100%"
          stripe
          @sort-change="handleSortChange"
          :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="44" align="center" />
          <el-table-column prop="id" label="ID" width="80" align="center" sortable="custom" />

          <el-table-column prop="title" label="题目名称" min-width="195" show-overflow-tooltip>
            <template #default="{ row }">
              <el-link type="primary" underline="never" @click="handlePreview(row)">{{ row.title }}</el-link>
            </template>
          </el-table-column>

          <el-table-column prop="difficulty" label="难度" width="100" align="center">
            <template #default="{ row }">
              <span :style="{ color: getDiffColor(row.difficulty), border: `1px solid ${getDiffColor(row.difficulty)}`, background: '#f8fafc', padding: '1px 8px', borderRadius: '10px', fontSize: '12px', fontWeight: '600' }">
                {{ getDiffLabel(row.difficulty) }} {{ row.difficulty ?? '-' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column prop="isPublic" label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="isRowPublic(row)" type="primary" size="small">公开</el-tag>
              <el-tag v-else type="info" size="small">私有</el-tag>
            </template>
          </el-table-column>

          <el-table-column prop="testCaseCount" label="测试数据" width="100" align="center">
            <template #default="{ row }">
              {{ row.testCaseCount || 0 }} 条
            </template>
          </el-table-column>

          <el-table-column v-if="colVisible.passRatio" label="提交 / 通过" width="120" align="center">
            <template #default="{ row }">
              <span>{{ row.totalCount || 0 }}</span>
              <span class="divider">/</span>
              <span>{{ row.passCount || 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column v-if="colVisible.source" prop="source" label="来源" width="120" show-overflow-tooltip />

          <el-table-column v-if="colVisible.tags" prop="tags" label="标签" width="150" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag v-if="row.tags" size="small" type="info" effect="light">{{ formatTags(row.tags) }}</el-tag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="300" align="right" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" icon="MagicStick" @click="openFormatWorkbench(row)">优化</el-button>
              <el-button link type="success" icon="View" @click="handlePreview(row)">预览</el-button>
              <el-button link type="primary" icon="Edit" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" icon="Delete" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          background
          layout="total, prev, pager, next, jumper"
          :total="total"
          v-model:current-page="currentPage"
          :page-size="pageSize"
          @current-change="handlePageChange"
        />
      </div>

      <FpsImportResultDialog v-model="importResultVisible" :result="importResult" />
      <ProblemFormatJobPanel
        v-model="jobPanelVisible"
        :problem-ids="batchProblemIds"
        @open-item="openJobItem"
        @finished="handleJobFinished"
      />
      <ProblemFormatWorkbench
        v-model="workbenchVisible"
        :problem-id="workbenchProblemId"
        :job-id="workbenchJobId"
        @applied="handleWorkbenchApplied"
      />
    </div>
  </el-config-provider>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 题库管理：教师与管理员共用，按角色走不同的数据源。
// 管理员（role >= 3）可见全站题目，教师只能看到自己创建的题目。
// 除了常规增删改查，这里还挂了两条批处理链路：FPS 题集文件导入（useFpsImport）
// 与 AI 批量题面优化（ProblemFormatJobPanel，服务端异步任务）。两者都可能耗时数分钟。
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, ElConfigProvider } from 'element-plus'
import { Upload, Setting, MagicStick } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { deleteProblem, getAdminProblemList, getMyCreatedProblemList } from '@/api/problem'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'
import {
  ADMIN_PROBLEM_COLUMN_OPTIONS,
  loadColumnVisibility,
  saveColumnVisibility
} from '@/utils/adminProblemColumns'
import FpsImportResultDialog from './components/FpsImportResultDialog.vue'
import ProblemFormatJobPanel from './problemFormat/ProblemFormatJobPanel.vue'
import ProblemFormatWorkbench from './problemFormat/ProblemFormatWorkbench.vue'
import { useFpsImport } from './composables/useFpsImport'

const getDiffColor = getDifficultyColor
const getDiffLabel = getDifficultyLabel

// ---- 列显隐（默认三列全隐藏，localStorage 持久化；读写与容错见 utils/adminProblemColumns） ----
const columnOptions = ADMIN_PROBLEM_COLUMN_OPTIONS
const columnStorage = () => {
  try {
    return window.localStorage
  } catch {
    return null // 隐私模式下访问 localStorage 会抛错，降级为不持久化
  }
}
const colVisible = reactive(loadColumnVisibility(columnStorage()))
const loadColumns = () => Object.assign(colVisible, loadColumnVisibility(columnStorage()))
const persistColumns = () => saveColumnVisibility(columnStorage(), colVisible)

const router = useRouter()
const isAdmin = computed(() => useUserStore().role >= 3)
const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 50
const keyword = ref('')
const statusFilter = ref('all')
const sortOrder = ref('ascending')

// ---- 批量优化（异步任务）与单题工作台 ----
const selectedProblems = ref([])
const handleSelectionChange = (rows) => { selectedProblems.value = rows }
const jobPanelVisible = ref(false)
const batchProblemIds = ref([])
const workbenchVisible = ref(false)
const workbenchProblemId = ref(null)
const workbenchJobId = ref(null)

const openFormatJob = () => {
  batchProblemIds.value = selectedProblems.value.map((item) => item.id).filter(Boolean)
  if (!batchProblemIds.value.length) {
    ElMessage.warning('请先勾选需要优化的题目')
    return
  }
  jobPanelVisible.value = true
}

const openFormatWorkbench = (row) => {
  workbenchProblemId.value = row.id
  workbenchJobId.value = null
  workbenchVisible.value = true
}

// 任务里的待审项：直接读任务保存的提案，不重新调用模型
const openJobItem = ({ jobId, problemId }) => {
  workbenchJobId.value = jobId
  workbenchProblemId.value = problemId
  workbenchVisible.value = true
}

const handleWorkbenchApplied = () => {
  fetchData()
}

const handleJobFinished = () => {
  fetchData()
}

// isPublic 在后端可能以布尔、0/1 或 '0'/'1' 多种形态返回，
// 归一成布尔后再判断，否则公开题会被误判为私有。
const isRowPublic = (row) => {
  return row?.isPublic === true || row?.isPublic === 1 || row?.isPublic === '1'
}

// 函数声明（提升）：onMounted 与任务完成回调都在下方引用，保持声明式写法避免 TDZ
async function fetchData() {
  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize,
      sortBy: 'id',
      sortOrder: sortOrder.value,
      keyword: keyword.value ? keyword.value.trim() : undefined
    }

    // 公开性筛选要同时传 privacy 与 isPublic：两个字段是同义冗余，
    // 后端在不同分支里读的是不同那个，只传一个会出现筛选不生效。
    if (statusFilter.value === 'public') {
      params.privacy = 1
      params.isPublic = true
    } else if (statusFilter.value === 'private') {
      params.privacy = 0
      params.isPublic = false
    }

    const res = isAdmin.value
      ? await getAdminProblemList(params)
      : await getMyCreatedProblemList(params)
    if (res.code === 200) {
      const records = Array.isArray(res?.data?.records) ? res.data.records : []
      // 同理，列表回填时 isPublic 要把后端可能返回的各种形态统一成布尔。
      list.value = records.map(item => ({
        ...item,
        isPublic: item?.isPublic === true || item?.isPublic === 1 || item?.isPublic === '1' || item?.privacy === 1 || item?.privacy === '1',
        testCaseCount: Number(item?.testCaseCount) || 0
      }))
      total.value = res?.data?.total || 0
    } else {
      ElMessage.error(res.message || '获取题目列表失败')
    }
  } catch (caughtError) {
    showApiError(caughtError, '获取题目列表失败')
  } finally {
    loading.value = false
  }
}

const refreshFirstPage = async () => {
  currentPage.value = 1
  await fetchData()
}

const {
  fpsFileInput, importing, importResultVisible, importResult,
  chooseFpsFile, handleFpsFileSelected
} = useFpsImport({ onImported: refreshFirstPage })

const handleStatusChange = () => {
  currentPage.value = 1
  fetchData()
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

const handleSortChange = ({ prop, order }) => {
  if (prop !== 'id') return
  sortOrder.value = order === 'descending' ? 'descending' : 'ascending'
  currentPage.value = 1
  fetchData()
}

// 新建/编辑/预览都在新标签页打开：题目表单含测试用例（可能几十组），
// 误关标签页会丢失全部未保存内容。
const handleCreate = () => {
  const routeData = router.resolve({ path: '/admin/problem/edit' })
  window.open(routeData.href, '_blank')
}

const handleEdit = (row) => {
  const routeData = router.resolve({ path: `/admin/problem/edit/${row.id}` })
  window.open(routeData.href, '_blank')
}

const handlePreview = (row) => {
  const routeData = router.resolve({
    path: `/problem/${row.id}`
  })
  window.open(routeData.href, '_blank')
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定要删除题目 "${row.title}" 吗？删除后不可恢复。`, '警告', {
    type: 'warning'
  }).then(async () => {
    const res = await deleteProblem(row.id)
    if (res.code === 200) {
      ElMessage.success('删除成功')
      fetchData()
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  }).catch(() => {})
}

const formatTags = (tagsJson) => {
  try {
    if (!tagsJson) return ''
    if (tagsJson.startsWith('[')) {
      const arr = JSON.parse(tagsJson)
      return arr.join(', ')
    }
    return tagsJson
  } catch {
    return tagsJson
  }
}

onMounted(() => {
  loadColumns()
  fetchData()
})
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header .page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-header .page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }

.page-container {
  height: auto;
  display: flex;
  flex-direction: column;
  padding: 16px;
  box-sizing: border-box;
  max-width: 1600px;
  margin: 0 auto;
  width: 100%;
}

.filter-wrapper {
  flex-shrink: 0;
  background: var(--bg-surface);
  padding: 15px;
  border-radius: 8px 8px 0 0;
  border: 1px solid var(--border-light);
  border-bottom: none;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.toolbar-left { display: flex; align-items: center; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.visually-hidden { position: fixed; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }

.ml-12 { margin-left: 12px; }

.table-wrapper {
  border: 1px solid var(--border-light);
  border-top: none;
  border-bottom: none;
  background: var(--bg-surface);
}

.pagination-wrapper {
  flex-shrink: 0;
  padding: 12px 20px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 0 0 8px 8px;
  display: flex;
  justify-content: center;
}

.divider { margin: 0 4px; color: var(--border-base); }
.ml-2 { margin-left: 8px; }
.col-setting-title { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
.col-setting-item { padding: 4px 0; }

@media (max-width: 768px) {
  .toolbar { align-items: stretch; flex-direction: column; gap: 12px; }
  .toolbar-left { align-items: stretch; flex-wrap: wrap; gap: 8px; }
  .toolbar-left :deep(.el-input) { width: 100% !important; }
  .toolbar-right { justify-content: flex-end; }
  .ml-12 { margin-left: 0; }
}
</style>
