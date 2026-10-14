<template>
  <div class="admin-exam-paper admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">训练试卷管理</div>
        <div class="page-desc">GESP / CSP / NOIP 真题与模拟套卷的收集与维护</div>
      </div>
      <el-button type="primary" @click="openCreate">新建试卷</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="papers" border stripe>
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
    </el-card>

    <!-- 成绩统计 -->
    <el-dialog v-model="statsDialogVisible" :title="`成绩统计：${statsPaper?.title || ''}`" width="880px" top="5vh">
      <div v-loading="statsLoading">
        <el-row :gutter="12" class="stats-cards">
          <el-col :span="6"><el-card shadow="never"><div class="s-label">作答人数</div><div class="s-value">{{ stats?.attemptCount ?? 0 }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="s-label">客观题均分</div><div class="s-value">{{ avg(stats?.avgObjective) }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="s-label">编程题均分</div><div class="s-value">{{ avg(stats?.avgCoding) }}</div></el-card></el-col>
          <el-col :span="6"><el-card shadow="never"><div class="s-label">总分均分</div><div class="s-value">{{ avg(stats?.avgTotal) }}</div></el-card></el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="14">
            <div class="stats-title">每题正确率</div>
            <el-table :data="stats?.questionStats ?? []" size="small" max-height="240">
              <el-table-column prop="questionOrder" label="#" width="50" />
              <el-table-column prop="statement" label="题干" min-width="180" />
              <el-table-column prop="correctCount" label="答对/作答" width="90" align="center" />
              <el-table-column label="正确率" width="90" align="center">
                <template #default="{ row }">{{ (row.correctRate ?? 0).toFixed(0) }}%</template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="10">
            <div class="stats-title">分数分布</div>
            <div v-for="b in stats?.distribution ?? []" :key="b.bucket" class="bucket-row">
              <span class="bucket-label">{{ b.bucket }} 分</span>
              <el-progress :percentage="bucketPct(b.count)" :stroke-width="14" />
              <span class="bucket-count">{{ b.count }} 人</span>
            </div>
          </el-col>
        </el-row>

        <div class="stats-title">GESP 等第预估分布（阈值可配：system_config exam.gesp-thresholds）</div>
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
        <el-table :data="attemptRows" size="small" max-height="220">
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
const statsDialogVisible = ref(false)
const statsLoading = ref(false)
const statsPaper = ref(null)
const stats = ref(null)
const attemptRows = ref([])

const fetchList = async () => {
  loading.value = true
  try {
    const res = await getExamPaperList({ page: 1, size: 100 })
    papers.value = unwrapResultData(res)?.records ?? []
  } finally {
    loading.value = false
  }
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
  await ElMessageBox.confirm(`确定删除试卷「${row.title}」？`, '删除确认', { type: 'warning' })
  const res = await deleteExamPaper(row.id)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '删除失败'))
    return
  }
  ElMessage.success('已删除')
  fetchList()
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
  statsPaper.value = row
  statsDialogVisible.value = true
  statsLoading.value = true
  try {
    stats.value = unwrapResultData(await getExamPaperStats(row.id)) || null
    const res = await getExamPaperAttempts(row.id, { page: 1, size: 50 })
    attemptRows.value = unwrapResultData(res)?.records ?? []
  } finally {
    statsLoading.value = false
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
/* 操作列四个按钮保持单行，不换行 */
.admin-exam-paper :deep(.op-cell .cell) { white-space: nowrap; }
.stats-cards { margin-bottom: 14px; }
.s-label { color: var(--text-secondary); font-size: 12px; }
.s-value { font-size: 22px; font-weight: 700; margin-top: 4px; }
.stats-title { font-weight: 600; margin: 10px 0 8px; }
.stats-actions { display: flex; justify-content: space-between; align-items: center; }
.bucket-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.bucket-label { width: 58px; font-size: 12px; color: var(--text-regular); }
.bucket-count { width: 40px; font-size: 12px; color: var(--text-secondary); }
.gesp-row { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
