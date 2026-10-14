<template>
  <div class="class-homework-status" v-loading="loadingOverview">
    <!-- 头部：信息 + 题目选择器 -->
    <div class="status-header">
      <div>
        <div class="page-title">{{ homeworkInfo.title || '作业完成情况' }}</div>
        <div class="page-subtitle">时间范围：{{ homeworkTimeText }}</div>
      </div>
      <div class="header-actions">
        <el-select
          v-model="selectedProblemId"
          placeholder="切换题目查看单题统计"
          clearable
          style="width: 260px"
          @change="onProblemChange"
        >
          <el-option
            v-for="item in problems"
            :key="item.problemId"
            :label="item.problemTitle"
            :value="item.problemId"
          />
        </el-select>
        <el-button type="primary" plain :disabled="!selectedProblemData" @click="viewProblemDetails">
          查看该题记录
        </el-button>
        <el-button @click="resetDetail">查看全部记录</el-button>
        <el-button @click="fetchOverview">刷新</el-button>
      </div>
    </div>

    <!-- 概览卡片 -->
    <HomeworkSummaryCards :summary="summaryValues" />

    <!-- 操作提示 -->
    <el-alert
      class="operation-tip" type="info" :closable="false" show-icon title="操作提示"
      description="点击矩阵中的单元格可查看“某位学生 + 某道题”的全部提交记录；点击矩阵 X 轴题目名可查看该题的全班提交记录；点击矩阵 Y 轴学生名可查看该学生的全部题目提交记录；点击上方单题柱状图中的柱子也可直接定位到某位学生在当前题目的提交记录。"
    />

    <!-- 图表区 -->
    <HomeworkCharts
      ref="chartsRef"
      :students="students"
      :problems="problems"
      :stats-map="statsMap"
      :selected-problem="selectedProblemData"
      :max-submit-count="maxSubmitCount"
      @select-cell="onSelectCell"
      @select-problem-axis="onSelectProblemAxis"
      @select-student-axis="onSelectStudentAxis"
      @select-bar="onSelectBar"
    />

    <!-- 提交记录表格 -->
    <HomeworkSubmissionTable
      :list="detailList"
      :total="detailTotal"
      :loading="detailLoading"
      :page="detailPage"
      :page-size="detailPageSize"
      :mode-label="detailModeLabel"
      :tag-type="detailModeTagType"
      :summary-text="detailSummaryText"
      @refresh="fetchDetails(detailPage)"
      @reset="resetDetail"
      @page-change="onDetailPageChange"
    />
  </div>
</template>

<script setup>
// 作业完成情况面板：概览卡片 + 提交柱状图 + 学生×题目热力图 + 提交明细表。
//
// 概览接口只给汇总数字，逐题逐人的明细（students/problems/stats）单独拉一次，
// 前端再转成 `${studentId}_${problemId}` 的查表结构供图表查格——
// 后端不返回矩阵是因为矩阵稀疏，直接传会让响应体随学生数×题目数膨胀。
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getClassHomeworkStatusOverview, getClassHomeworkStatusSubmissions } from '@/api/class'
import HomeworkSummaryCards from './HomeworkSummaryCards.vue'
import HomeworkCharts from './HomeworkCharts.vue'
import HomeworkSubmissionTable from './HomeworkSubmissionTable.vue'

const props = defineProps({
  classId: { type: [String, Number], required: true },
  homeworkId: { type: [String, Number], required: true }
})

// --- 数据 ---
const loadingOverview = ref(false)
const detailLoading = ref(false)
const homeworkInfo = ref({})
const summaryInfo = ref({})
const students = ref([])
const problems = ref([])
const stats = ref([])
const selectedProblemId = ref(null)
const selectedStudentId = ref(null)
const detailMode = ref('all')
const detailList = ref([])
const detailTotal = ref(0)
const detailPage = ref(1)
const detailPageSize = 10
const chartsRef = ref(null)

// --- 工具函数 ---
const toNumber = (v, d = 0) => { const n = Number(v); return Number.isFinite(n) ? n : d }

const normalizeStudents = list => (list || []).map(i => ({ studentId: toNumber(i.studentId), studentName: i.studentName || `用户${i.studentId}` }))
const normalizeProblems = list => (list || []).map(i => ({ problemId: toNumber(i.problemId), problemTitle: i.problemTitle || `题目${i.problemId}`, sort: toNumber(i.sort) }))
const normalizeStats = list => (list || []).map(i => ({ studentId: toNumber(i.studentId), problemId: toNumber(i.problemId), submitCount: toNumber(i.submitCount), acCount: toNumber(i.acCount), finalAc: toNumber(i.finalAc), lastSubmitTime: i.lastSubmitTime || null }))

const statsMap = computed(() => {
  const m = {}
  stats.value.forEach(s => { m[`${s.studentId}_${s.problemId}`] = s })
  return m
})

const maxSubmitCount = computed(() => {
  let max = 0
  stats.value.forEach(s => { max = Math.max(max, s.submitCount) })
  return max
})

const selectedProblemData = computed(() => problems.value.find(p => p.problemId === selectedProblemId.value) || null)
const selectedStudentData = computed(() => students.value.find(s => s.studentId === selectedStudentId.value) || null)

const homeworkTimeText = computed(() => {
  const s = homeworkInfo.value?.startTime, e = homeworkInfo.value?.endTime
  if (!s && !e) return '长期有效'
  const fmt = v => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'
  return s && e ? `${fmt(s)} 至 ${fmt(e)}` : s ? `开始于 ${fmt(s)}` : `截止于 ${fmt(e)}`
})

const summaryValues = computed(() => ({
  studentCount: toNumber(summaryInfo.value?.studentCount, students.value.length),
  problemCount: toNumber(summaryInfo.value?.problemCount, problems.value.length),
  totalCellCount: toNumber(summaryInfo.value?.totalCellCount, students.value.length * problems.value.length),
  totalSubmissions: toNumber(summaryInfo.value?.totalSubmissions),
  finalAcCount: toNumber(summaryInfo.value?.finalAcCount),
  activeStudentCount: toNumber(summaryInfo.value?.activeStudentCount)
}))

const detailModeLabel = computed(() => {
  if (detailMode.value === 'pair') return '学生 + 题目'
  if (detailMode.value === 'problem') return '按题目'
  if (detailMode.value === 'student') return '按学生'
  return '全部记录'
})

const detailModeTagType = computed(() => {
  if (detailMode.value === 'pair') return 'success'
  if (detailMode.value === 'problem') return 'warning'
  if (detailMode.value === 'student') return 'primary'
  return 'info'
})

const detailSummaryText = computed(() => {
  const pt = selectedProblemData.value?.problemTitle
  const sn = selectedStudentData.value?.studentName
  if (detailMode.value === 'pair' && pt && sn) return `当前展示 ${sn} 在题目「${pt}」上的全部提交记录。`
  if (detailMode.value === 'problem' && pt) return `当前展示题目「${pt}」在该班级作业时间范围内的全部学生提交记录。`
  if (detailMode.value === 'student' && sn) return `当前展示学生「${sn}」在该作业全部题目上的提交记录。`
  return '当前展示该作业时间范围内的全部提交记录。'
})

// --- 数据获取 ---
const fetchDetails = async (page = 1) => {
  if (!props.classId || !props.homeworkId) return
  detailLoading.value = true
  detailPage.value = page
  try {
    const params = { currentPage: page, pageSize: detailPageSize }
    if ((detailMode.value === 'pair' || detailMode.value === 'student') && selectedStudentId.value) params.studentId = selectedStudentId.value
    if ((detailMode.value === 'pair' || detailMode.value === 'problem') && selectedProblemId.value) params.problemId = selectedProblemId.value
    const res = await getClassHomeworkStatusSubmissions(Number(props.classId), Number(props.homeworkId), params)
    if (res.code === 200) {
      detailList.value = res.data?.records || []
      detailTotal.value = toNumber(res.data?.total)
    } else {
      ElMessage.error(res.message || '获取提交记录失败')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('获取提交记录失败')
  } finally { detailLoading.value = false }
}

const fetchOverview = async () => {
  if (!props.classId || !props.homeworkId) return
  loadingOverview.value = true
  detailMode.value = 'all'
  selectedStudentId.value = null
  detailPage.value = 1
  try {
    const res = await getClassHomeworkStatusOverview(Number(props.classId), Number(props.homeworkId))
    if (res.code === 200) {
      homeworkInfo.value = res.data?.homework || {}
      summaryInfo.value = res.data?.summary || {}
      students.value = normalizeStudents(res.data?.students)
      problems.value = normalizeProblems(res.data?.problems)
      stats.value = normalizeStats(res.data?.stats)
      if (!problems.value.find(p => p.problemId === selectedProblemId.value)) {
        selectedProblemId.value = problems.value.length ? problems.value[0].problemId : null
      }
      await fetchDetails(1)
    } else {
      clearData()
    }
  } catch (e) {
    console.error(e)
    clearData()
  } finally { loadingOverview.value = false }
}

const clearData = () => {
  homeworkInfo.value = {}; summaryInfo.value = {}
  students.value = []; problems.value = []; stats.value = []
  detailList.value = []; detailTotal.value = 0
  selectedProblemId.value = null; selectedStudentId.value = null
  detailMode.value = 'all'
}

// --- 事件处理 ---
const onSelectCell = ({ studentId, problemId }) => {
  selectedStudentId.value = studentId
  selectedProblemId.value = problemId
  detailMode.value = 'pair'
  fetchDetails(1)
}
const onSelectProblemAxis = (problemId) => {
  selectedProblemId.value = problemId
  selectedStudentId.value = null
  detailMode.value = 'problem'
  fetchDetails(1)
}
const onSelectStudentAxis = (studentId) => {
  selectedStudentId.value = studentId
  detailMode.value = 'student'
  fetchDetails(1)
}
const onSelectBar = ({ studentId, problemId }) => {
  selectedStudentId.value = studentId
  selectedProblemId.value = problemId
  detailMode.value = 'pair'
  fetchDetails(1)
}

const onProblemChange = () => { chartsRef.value?.renderProblemChart() }
const viewProblemDetails = () => {
  if (!selectedProblemData.value) return
  selectedStudentId.value = null
  detailMode.value = 'problem'
  detailPage.value = 1
  fetchDetails(1)
}
const resetDetail = () => {
  selectedStudentId.value = null
  detailMode.value = 'all'
  detailPage.value = 1
  fetchDetails(1)
}
const onDetailPageChange = page => fetchDetails(page)

watch(() => [props.classId, props.homeworkId], () => fetchOverview(), { immediate: true })
</script>

<style scoped>
.class-homework-status { display: flex; flex-direction: column; gap: 16px; min-height: 560px; }
.status-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 20px; font-weight: 700; color: var(--text-primary); line-height: 1.4; }
.page-subtitle { margin-top: 6px; font-size: 13px; color: var(--text-secondary); }
.header-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.operation-tip { margin-top: -2px; }
</style>
