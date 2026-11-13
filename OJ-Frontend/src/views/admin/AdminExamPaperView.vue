<template>
  <div class="admin-exam-paper admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">训练试卷管理</div>
        <div class="page-desc">维护题目、发布状态和成绩记录；已有交卷记录的试卷可修改基本信息，题目请通过复制后调整</div>
      </div>
      <el-button type="primary" @click="openCreate">新建试卷</el-button>
    </div>

    <div class="filter-bar">
      <el-input v-model="keyword" clearable placeholder="搜索试卷标题" class="search-input" @keyup.enter="searchPapers" @clear="searchPapers">
        <template #append><el-button @click="searchPapers">搜索</el-button></template>
      </el-input>
      <el-select v-model="category" clearable placeholder="全部分类" class="category-select" @change="searchPapers">
        <el-option v-for="item in CATEGORIES" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <span class="list-total">{{ total }} 份试卷</span>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="papers" border stripe row-key="id">
        <el-table-column prop="id" label="#" width="70" align="center" />
        <el-table-column prop="title" label="试卷标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" underline="never" @click="openPaper(row)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="110" align="center">
          <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
        </el-table-column>
        <el-table-column prop="level" label="等级/组别" width="120" align="center" />
        <el-table-column label="题数" width="80" align="center">
          <template #default="{ row }">{{ row.problemCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="满分" width="80" align="center">
          <template #default="{ row }">{{ row.totalScore ?? 100 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isPublished === 1 ? 'success' : 'info'" size="small">
              {{ row.isPublished === 1 ? '已发布' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center" class-name="op-cell">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="primary" link @click="onCopy(row)">复制</el-button>
            <el-button type="success" link @click="openStats(row)">成绩</el-button>
            <el-button type="danger" link @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !papers.length" description="没有找到符合条件的试卷" />
      <div class="pagination-box">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="fetchList"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- 成绩统计 -->
    <el-dialog v-model="statsDialogVisible" :title="`成绩统计：${statsPaper?.title || ''}`" width="min(1100px, 94vw)" top="4vh" class="stats-dialog">
      <div v-loading="statsLoading">
        <el-row :gutter="12" class="stats-cards">
          <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="s-label">已交卷人数（每人最近一次）</div><div class="s-value">{{ stats?.attemptCount ?? 0 }}</div></el-card></el-col>
          <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="s-label">客观题均分</div><div class="s-value">{{ avg(stats?.avgObjective) }}</div></el-card></el-col>
          <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="s-label">编程题均分</div><div class="s-value">{{ avg(stats?.avgCoding) }}</div></el-card></el-col>
          <el-col :xs="12" :sm="6"><el-card shadow="never"><div class="s-label">总分均分</div><div class="s-value">{{ avg(stats?.avgTotal) }}</div></el-card></el-col>
        </el-row>

        <el-row :gutter="12" class="stats-detail-grid">
          <el-col :xs="24" :md="14">
            <div class="stats-title">每题正确率</div>
            <el-table :data="stats?.questionStats ?? []" size="small" max-height="240">
              <el-table-column prop="questionOrder" label="#" width="50" />
              <el-table-column prop="statement" label="题干" min-width="180" />
              <el-table-column label="答对/交卷人数" width="120" align="center">
                <template #default="{ row }">{{ row.correctCount ?? 0 }} / {{ row.attemptCount ?? stats?.attemptCount ?? 0 }}</template>
              </el-table-column>
              <el-table-column label="正确率" width="90" align="center">
                <template #default="{ row }">{{ (row.correctRate ?? 0).toFixed(0) }}%</template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :xs="24" :md="10">
            <div class="stats-title">得分率分布 <span class="full-score-note">按满分 {{ stats?.totalScore ?? statsPaper?.totalScore ?? '-' }} 分归一</span></div>
            <div v-for="b in stats?.distribution ?? []" :key="b.bucket" class="bucket-row">
              <span class="bucket-label">{{ b.bucket }}%</span>
              <el-progress :percentage="bucketPct(b.count)" :stroke-width="14" />
              <span class="bucket-count">{{ b.count }} 人</span>
            </div>
          </el-col>
        </el-row>

        <div class="stats-title">GESP 等级参考 <span class="full-score-note">按配置分数阈值，仅供参考</span></div>
        <div class="gesp-row">
          <el-tag
            v-for="g in stats?.gespDistribution ?? []"
            :key="g.level"
            :type="g.level === 0 ? 'info' : g.level >= 6 ? 'danger' : g.level >= 3 ? 'warning' : 'success'"
            effect="plain"
          >
            {{ g.level === 0 ? '未达一级' : `${g.level} 级` }}：{{ g.count }} 人
          </el-tag>
        </div>

        <div class="stats-title stats-actions">
          <span>成绩明细</span>
          <el-button size="small" type="success" @click="exportCsv">导出 CSV</el-button>
        </div>
        <el-table :data="attemptRows" size="small" max-height="360" v-loading="attemptLoading">
          <el-table-column prop="username" label="用户" min-width="110" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ row.status === 'TIMEOUT' ? '到时交卷' : row.status === 'SUBMITTED' ? '已交卷' : row.status }}</template>
          </el-table-column>
          <el-table-column prop="objectiveScore" label="客观" width="70" align="center" />
          <el-table-column prop="codingScore" label="编程" width="70" align="center" />
          <el-table-column prop="totalScore" label="总分" width="70" align="center" />
          <el-table-column label="GESP" width="70" align="center">
            <template #default="{ row }">{{ row.gespLevel ? `${row.gespLevel} 级` : '-' }}</template>
          </el-table-column>
          <el-table-column prop="submitTime" label="交卷时间" min-width="150" />
        </el-table>
        <div class="attempt-pagination">
          <el-pagination
            v-model:current-page="attemptPage"
            :page-size="attemptPageSize"
            :total="attemptTotal"
            layout="total, prev, pager, next"
            small
            @current-change="fetchAttempts"
          />
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
// 训练试卷管理（仅管理员）：套卷的创建、编辑、复制、删除与作答统计导出。
// 试卷分两类题目：选择题（本题内联编辑）与编程题（按题目 ID 关联题库）。
// 「复制」生成一份草稿副本而非克隆，这样改动不会影响已发布的原卷。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getExamPaperList, deleteExamPaper,
  copyExamPaper, getExamPaperStats, getExamPaperAttempts, exportExamPaperAttempts
} from '@/api/examPaper'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

const router = useRouter()

const CATEGORIES = [
  { value: 'GESP', label: 'GESP' },
  { value: 'CSP_J', label: 'CSP-J' },
  { value: 'CSP_S', label: 'CSP-S' },
  { value: 'NOIP', label: 'NOIP' },
  { value: 'MOCK', label: '模拟' }
]
const categoryLabel = (c) => CATEGORIES.find((x) => x.value === c)?.label || c || '套卷'

const papers = ref([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const category = ref('')
const statsDialogVisible = ref(false)
const statsLoading = ref(false)
const attemptLoading = ref(false)
const statsPaper = ref(null)
const stats = ref(null)
const attemptRows = ref([])
const attemptPage = ref(1)
const attemptPageSize = 20
const attemptTotal = ref(0)
let statsRequestSequence = 0

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getExamPaperList({ page: currentPage.value, size: pageSize.value, keyword: keyword.value.trim() || undefined, category: category.value || undefined })
    const data = unwrapResultData(res) || {}
    papers.value = data.records ?? []
    total.value = data.total ?? 0
  } catch (error) {
    ElMessage.error(error?.message || '获取试卷失败，请重试')
  } finally {
    loading.value = false
  }
}

const searchPapers = () => {
  currentPage.value = 1
  fetchList()
}

const handleSizeChange = () => {
  currentPage.value = 1
  fetchList()
}

const openCreate = () => {
  const { href } = router.resolve({ name: 'AdminExamPaperCreate' })
  window.open(href, '_blank')
}

const openEdit = (row) => {
  const { href } = router.resolve({ name: 'AdminExamPaperEdit', params: { id: row.id } })
  window.open(href, '_blank')
}

const openPaper = (row) => {
  const { href } = router.resolve({ path: `/training/${row.id}` })
  window.open(href, '_blank')
}

const onDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除试卷「${row.title}」？删除后无法恢复。已有交卷记录的试卷不能删除。`, '删除确认', { type: 'warning', confirmButtonText: '删除试卷', cancelButtonText: '取消' })
    const res = await deleteExamPaper(row.id)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    ElMessage.success('已删除')
    if (papers.value.length === 1 && currentPage.value > 1) currentPage.value -= 1
    fetchList()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '删除失败')
  }
}

const onCopy = async (row) => {
  const res = await copyExamPaper(row.id)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '复制失败'))
    return
  }
  ElMessage.success('已复制为草稿副本')
  fetchList()
}

const openStats = async (row) => {
  const sequence = ++statsRequestSequence
  statsPaper.value = row
  statsDialogVisible.value = true
  statsLoading.value = true
  stats.value = null
  attemptRows.value = []
  attemptTotal.value = 0
  attemptPage.value = 1
  fetchStats(row.id, sequence)
}

const fetchStats = async (paperId, sequence) => {
  try {
    const result = await getExamPaperStats(paperId)
    if (sequence === statsRequestSequence) stats.value = unwrapResultData(result) || null
  } catch (error) {
    if (sequence === statsRequestSequence) ElMessage.error(error?.message || '获取成绩统计失败')
  } finally {
    if (sequence === statsRequestSequence) statsLoading.value = false
  }
  fetchAttempts(attemptPage.value, sequence)
}

const fetchAttempts = async (page = attemptPage.value, sequence = statsRequestSequence) => {
  if (!statsPaper.value) return
  attemptPage.value = page
  attemptLoading.value = true
  try {
    const result = await getExamPaperAttempts(statsPaper.value.id, { page, size: attemptPageSize })
    if (sequence !== statsRequestSequence) return
    const data = unwrapResultData(result) || {}
    attemptRows.value = data.records ?? []
    attemptTotal.value = data.total ?? 0
  } catch (error) {
    if (sequence === statsRequestSequence) ElMessage.error(error?.message || '获取成绩明细失败')
  } finally {
    if (sequence === statsRequestSequence) attemptLoading.value = false
  }
}

const exportCsv = () => statsPaper.value && exportExamPaperAttempts(statsPaper.value.id)

const avg = (v) => (v ?? 0).toFixed(1)
const bucketPct = (count) => (stats.value?.attemptCount ? Math.round((count / stats.value.attemptCount) * 100) : 0)

onMounted(fetchList)
</script>

<style scoped>
.admin-exam-paper { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 14px; }
.search-input { width: min(420px, 100%); }
.category-select { width: 160px; }
.list-total { margin-left: auto; color: var(--text-secondary); font-size: 13px; }
.pagination-box { display: flex; justify-content: center; padding-top: 16px; }
.attempt-pagination { display: flex; justify-content: flex-end; padding-top: 12px; }
.stats-detail-grid { align-items: stretch; }
/* 操作列四个按钮保持单行，不换行 */
.admin-exam-paper :deep(.op-cell .cell) { white-space: nowrap; }
.stats-cards { margin-bottom: 14px; }
.s-label { color: var(--text-secondary); font-size: 12px; }
.s-value { font-size: 22px; font-weight: 700; margin-top: 4px; }
.stats-title { font-weight: 600; margin: 10px 0 8px; }
.full-score-note { font-size: 12px; font-weight: 400; color: var(--text-secondary); margin-left: 6px; }
.stats-actions { display: flex; justify-content: space-between; align-items: center; }
.bucket-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.bucket-label { width: 58px; font-size: 12px; color: var(--text-regular); }
.bucket-count { width: 40px; font-size: 12px; color: var(--text-secondary); }
.gesp-row { display: flex; flex-wrap: wrap; gap: 8px; }
@media (max-width: 720px) {
  .admin-exam-paper { padding: 12px; }
  .page-header { align-items: flex-start; gap: 10px; }
  .page-desc { max-width: 56vw; line-height: 1.5; }
  .filter-bar { align-items: stretch; }
  .search-input, .category-select { width: 100%; }
  .list-total { margin-left: 0; }
  .pagination-box :deep(.el-pagination) { flex-wrap: wrap; justify-content: center; }
  .attempt-pagination :deep(.el-pagination) { flex-wrap: wrap; justify-content: flex-end; }
}
</style>
