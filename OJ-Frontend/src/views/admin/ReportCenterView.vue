<template>
  <div class="report-center admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">
          <el-icon class="title-icon"><Notebook /></el-icon>
          学习周报
        </div>
        <div class="page-desc">
          按域组织生成任务、历史报告与交付事实；报告正文在独立阅读页全宽展示，可在新标签页打开并排对比
        </div>
      </div>
      <el-button :loading="classLoading || historyLoading" @click="refreshAll">刷新</el-button>
    </div>

    <el-alert v-if="classError" type="error" :title="classError" :closable="false" show-icon class="mb-16">
      <template #default>
        <el-button link type="primary" @click="loadClassOptions">重新加载班级</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="!classLoading && classes.length === 0"
      type="warning"
      title="当前账号没有可用班级"
      description="只有本班班主任或管理员可以生成 / 查看学习周报；若刚创建班级，请刷新后重试。"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <div class="context-bar">
      <span class="context-label">当前班级</span>
      <el-select
        v-model="selectedClassId"
        placeholder="选择班级"
        filterable
        clearable
        :loading="classLoading"
        style="width: 260px"
        @change="handleClassChange"
      >
        <el-option
          v-for="item in classes"
          :key="item.id"
          :label="`${item.name}（#${item.id}）`"
          :value="item.id"
        />
      </el-select>
      <span v-if="selectedClassId" class="context-hint">
        {{ className(selectedClassId) }} · {{ members.length }} 名学生 · {{ historyTotal }} 份报告
      </span>
      <span v-else class="context-hint">选择班级后查看该班报告</span>
    </div>

    <el-tabs v-model="activeTab" class="workspace-tabs" @tab-change="handleTabChange">
      <el-tab-pane label="概览" name="overview">
        <ReportOverviewPanel
          :items="historyItems"
          :loading="historyLoading"
          :class-name="className(selectedClassId)"
          @switch-tab="switchTab"
          @open-report="openReader"
        />
      </el-tab-pane>
      <el-tab-pane label="生成任务" name="generate">
        <ReportGeneratePanel
          :class-id="selectedClassId"
          :members="members"
          :members-loading="membersLoading"
          :history="historyItems"
          @generated="handleGenerated"
          @open-report="openReader"
          @switch-tab="switchTab"
        />
      </el-tab-pane>
      <el-tab-pane label="历史报告" name="history">
        <ReportHistoryPanel
          :items="historyItems"
          :total="historyTotal"
          :page="historyPage"
          :size="historySize"
          :loading="historyLoading"
          :error="historyError"
          :retrying-id="retryingId"
          :batch-retrying="batchRetrying"
          :member-names="memberNameMap"
          @update:page="handlePageChange"
          @update:size="handleSizeChange"
          @refresh="loadHistory"
          @retry="handleRetry"
          @batch-retry="handleBatchRetry"
          @open-report="openReader"
          @inspect="inspectJob"
        />
      </el-tab-pane>
      <el-tab-pane label="交付与提醒" name="delivery">
        <ReportDeliveryPanel :job-id="selectedJobId" @open-report="openReader" @switch-tab="switchTab" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
// 学习周报工作区（方案 B）。
// 报告域 / 通知域事实按域聚合到 Tab，不再塞进单份报告的详情弹窗；
// 正文由 /admin/reports/view/:jobId 全屏阅读页承载。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Notebook } from '@element-plus/icons-vue'
import { getClassMembers, getMyClasses, getTeacherClassList } from '@/api/class'
import { getAdminClasses } from '@/api/adminClass'
import { listReportJobs, retryReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useUserStore } from '@/stores/userStore'
import { showApiError } from '@/utils/errorFeedback'
import { canRetry, failureMessage } from './report/composables/reportMeta'
import ReportOverviewPanel from './report/components/ReportOverviewPanel.vue'
import ReportGeneratePanel from './report/components/ReportGeneratePanel.vue'
import ReportHistoryPanel from './report/components/ReportHistoryPanel.vue'
import ReportDeliveryPanel from './report/components/ReportDeliveryPanel.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.role >= 3)

const TABS = ['overview', 'generate', 'history', 'delivery']
function normalizeTab(value) {
  return TABS.includes(value) ? value : 'overview'
}

const activeTab = ref(normalizeTab(route.query.tab))
const classes = ref([])
const classLoading = ref(false)
const classError = ref('')
const selectedClassId = ref(null)
const members = ref([])
const membersLoading = ref(false)
const historyItems = ref([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = ref(10)
const historyLoading = ref(false)
const historyError = ref('')
const retryingId = ref(null)
const batchRetrying = ref(false)
const selectedJobId = ref(null)
let historyRequestSerial = 0

const memberNameMap = computed(() => {
  const map = new Map()
  for (const member of members.value) {
    map.set(Number(member.userId), String(member.remark || '').trim() || member.username || `学生 #${member.userId}`)
  }
  return map
})

function className(classId) {
  return classes.value.find((item) => Number(item.id) === Number(classId))?.name || ''
}

// 班级下拉：教师补拉自己创建的班级，非管理员只保留本人任班主任的班级；
// 管理员无班级时回退到全部班级。与后端 requireClassTeacher 同口径。
async function loadClassOptions() {
  classLoading.value = true
  classError.value = ''
  try {
    const optionMap = new Map()
    const joined = unwrapResultData(await getMyClasses({ page: 1, size: 200 }))
    for (const item of joined?.records || []) {
      if (item?.id != null) optionMap.set(Number(item.id), item)
    }
    if (userStore.isTeacher) {
      const owned = unwrapResultData(await getTeacherClassList(userStore.userId)) || []
      for (const item of owned) {
        if (item?.id != null) optionMap.set(Number(item.id), item)
      }
    }
    let options = [...optionMap.values()]
    if (!isAdmin.value) {
      options = options.filter((item) => Number(item.teacherId) === Number(userStore.userId))
    } else if (options.length === 0) {
      const all = unwrapResultData(await getAdminClasses({ page: 1, size: 200 }))
      options = all?.records || []
    }
    options.sort((a, b) => Number(a.id) - Number(b.id))
    classes.value = options
    if (selectedClassId.value && !options.some((item) => Number(item.id) === Number(selectedClassId.value))) {
      resetClassContext()
    }
  } catch (e) {
    classes.value = []
    classError.value = e?.response?.data?.message || e?.message || '班级列表加载失败'
  } finally {
    classLoading.value = false
  }
}

function resetClassContext() {
  historyRequestSerial += 1
  selectedClassId.value = null
  members.value = []
  historyItems.value = []
  historyTotal.value = 0
  historyLoading.value = false
  historyError.value = ''
  selectedJobId.value = null
}

async function fetchAllMembers(classId) {
  const size = 100
  const list = []
  let page = 1
  let total = 0
  do {
    const data = unwrapResultData(await getClassMembers({ classId, page, size })) || {}
    const records = Array.isArray(data.records) ? data.records : []
    list.push(...records)
    total = Number(data.total) || list.length
    if (records.length === 0 || page >= 50) break
    page += 1
  } while (list.length < total)
  return list
}

async function loadMembers() {
  const classId = Number(selectedClassId.value)
  if (!classId) {
    members.value = []
    return
  }
  membersLoading.value = true
  try {
    const list = await fetchAllMembers(classId)
    if (Number(selectedClassId.value) === classId) members.value = list
  } catch (e) {
    if (Number(selectedClassId.value) === classId) {
      members.value = []
      showApiError(e, '班级成员加载失败')
    }
  } finally {
    if (Number(selectedClassId.value) === classId) membersLoading.value = false
  }
}

async function loadHistory() {
  const requestSerial = ++historyRequestSerial
  if (!selectedClassId.value) {
    historyItems.value = []
    historyTotal.value = 0
    historyLoading.value = false
    historyError.value = ''
    return
  }
  const classId = Number(selectedClassId.value)
  const isCurrent = () => requestSerial === historyRequestSerial && Number(selectedClassId.value) === classId
  historyLoading.value = true
  historyError.value = ''
  try {
    const data = unwrapResultData(
      await listReportJobs({ classId, page: historyPage.value, size: historySize.value })
    ) || {}
    if (!isCurrent()) return
    historyItems.value = Array.isArray(data.records) ? data.records : []
    historyTotal.value = Number(data.total) || 0
  } catch (e) {
    if (isCurrent()) {
      historyItems.value = []
      historyTotal.value = 0
      historyError.value = e?.response?.data?.message || e?.message || '历史报告加载失败'
    }
  } finally {
    if (isCurrent()) historyLoading.value = false
  }
}

function handleClassChange() {
  historyRequestSerial += 1
  members.value = []
  historyItems.value = []
  historyTotal.value = 0
  historyPage.value = 1
  historyError.value = ''
  historyLoading.value = false
  selectedJobId.value = null
  if (!selectedClassId.value) return
  Promise.all([loadMembers(), loadHistory()])
}

function handlePageChange(page) {
  historyPage.value = page
  loadHistory()
}

function handleSizeChange(size) {
  historySize.value = size
  historyPage.value = 1
  loadHistory()
}

function handleTabChange(name) {
  const tab = normalizeTab(name)
  if (route.query.tab === tab) return
  // push 而非 replace：切换 Tab 产生历史记录，浏览器返回可回到上一个 Tab。
  router.push({ path: route.path, query: { ...route.query, tab } })
}

function switchTab(tab) {
  activeTab.value = normalizeTab(tab)
  handleTabChange(activeTab.value)
}

function inspectJob(jobId) {
  selectedJobId.value = Number(jobId) || null
  switchTab('delivery')
}

function openReader(jobId) {
  const id = Number(jobId)
  if (!Number.isInteger(id) || id <= 0) return
  const href = router.resolve({ name: 'AdminWeeklyReportReader', params: { jobId: id } }).href
  window.open(href, '_blank', 'noopener')
}

async function handleGenerated(job) {
  historyPage.value = 1
  await loadHistory()
  if (job) selectedJobId.value = Number(job.id) || null
}

async function handleRetry(row) {
  if (!row?.id || retryingId.value) return
  retryingId.value = row.id
  try {
    const job = unwrapResultData(await retryReportJob(row.id))
    if (job) {
      if (job.status === 'GENERATED') ElMessage.success('报告已生成')
      else if (job.status === 'FAILED') ElMessage.error(failureMessage(job))
      else ElMessage.success('已重新排队，报告将由后台生成')
    }
  } catch {
    /* 全局请求拦截器已统一提示错误；刷新列表同步服务端 canRetry */
  } finally {
    retryingId.value = null
    await loadHistory()
  }
}

async function handleBatchRetry(rows) {
  if (batchRetrying.value || !rows.length) return
  batchRetrying.value = true
  let done = 0
  try {
    for (const row of rows) {
      if (!canRetry(row)) continue
      try {
        const job = unwrapResultData(await retryReportJob(row.id))
        if (job) done += 1
      } catch {
        /* 单条失败不阻断其余任务，失败原因由列表刷新后的服务端状态呈现 */
      }
    }
    if (done) ElMessage.success(`已重新受理 ${done} 份报告`)
    else ElMessage.warning('所选任务均不满足重试条件')
  } finally {
    batchRetrying.value = false
    await loadHistory()
  }
}

async function selectFirstClass() {
  if (selectedClassId.value || classes.value.length === 0) return
  selectedClassId.value = classes.value[0].id
  await Promise.all([loadMembers(), loadHistory()])
}

async function refreshAll() {
  await loadClassOptions()
  if (selectedClassId.value) {
    await Promise.all([loadMembers(), loadHistory()])
    return
  }
  await selectFirstClass()
}

function parseQueryJobId() {
  const raw = Array.isArray(route.query.jobId) ? route.query.jobId[0] : route.query.jobId
  const jobId = Number(raw)
  return Number.isInteger(jobId) && jobId > 0 ? jobId : null
}

watch(() => route.query.tab, (value) => {
  const tab = normalizeTab(value)
  if (tab !== activeTab.value) activeTab.value = tab
})

onMounted(async () => {
  // 通知中心深链：直接进入独立阅读页，不再弹出详情窗。
  const queryJobId = parseQueryJobId()
  if (queryJobId) {
    await router.replace({ name: 'AdminWeeklyReportReader', params: { jobId: queryJobId } })
    return
  }
  await loadClassOptions()
  await selectFirstClass()
})
</script>

<style scoped>
.report-center { padding: 20px 24px 32px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.page-title { display: flex; align-items: center; gap: 6px; font-size: 22px; font-weight: 700; color: var(--text-primary); }
.title-icon { color: #6366f1; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.mb-16 { margin-bottom: 16px; }
.context-bar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 12px;
  background: var(--bg-surface); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: 12px 16px; margin-bottom: 16px;
}
.context-label { font-size: 13px; color: var(--text-regular); white-space: nowrap; }
.context-hint { font-size: 12px; color: var(--text-secondary); }
.workspace-tabs :deep(.el-tabs__item) { font-size: 14px; }
</style>
