<template>
  <div class="member-manager">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或备注..."
          prefix-icon="Search"
          style="width: 260px"
          clearable
          @keyup.enter="fetchMembers"
          @clear="fetchMembers"
        />
        <el-button
          type="warning"
          plain
          :disabled="selectedMembers.length === 0"
          :loading="batchGenerating"
          @click="handleBatchGenerate"
        >
          <el-icon><Document /></el-icon>
          批量生成周报 ({{ selectedMembers.length }})
        </el-button>
        <span v-if="pendingReportCount > 0" class="info-text report-poll-hint">
          报告状态{{ reportPolling ? '自动刷新中' : '待刷新' }}（{{ pendingReportCount }}）
        </span>
        <el-button
          v-if="pendingReportCount > 0 && !reportPolling"
          link
          type="primary"
          @click="startReportPolling"
        >
          继续刷新
        </el-button>
      </div>
      <div class="right-info">
        <span class="info-text">当前成员数: {{ total }}</span>
        <el-button icon="Refresh" circle plain @click="fetchMembers" />
      </div>
    </div>

    <el-table
      ref="tableRef"
      :data="memberList"
      v-loading="loading"
      style="width: 100%"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)' }"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column label="学生信息" min-width="200">
        <template #default="{ row }">
          <div class="user-info-cell" @click="goToProfile(row.userId)">
            <el-avatar :size="40" :src="row.avatar" >{{ row.username?.charAt(0) }}</el-avatar>
            <div class="user-text">
              <div class="username">{{ row.username }}</div>
              <div class="email" v-if="row.email">{{ row.email }}</div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="班级备注" min-width="150">
        <template #default="{ row }">
          <div class="remark-display" v-if="!row.editingRemark">
            <span v-if="row.remark" class="remark-text">{{ row.remark }}</span>
            <span v-else class="no-remark">未设置备注</span>
            <el-button link type="primary" icon="EditPen" class="edit-icon" @click="startEdit(row)" />
          </div>
          <div class="remark-edit" v-else>
            <el-input 
              v-model="row.tempRemark" 
              size="small" 
              placeholder="输入备注" 
              ref="remarkInputRef"
              @keyup.enter="saveRemark(row)"
            />
            <el-button link type="success" size="small" icon="Check" @click="saveRemark(row)" />
            <el-button link type="info" size="small" icon="Close" @click="cancelEdit(row)" />
          </div>
        </template>
      </el-table-column>

      <el-table-column prop="joinTime" label="加入时间" width="180" align="center">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.joinTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="报告状态" width="130" align="center">
        <template #default="{ row }">
          <template v-if="reportState(row.userId)">
            <el-tooltip
              v-if="reportStatusOf(row.userId) === 'FAILED'"
              :content="reportFailureText(reportState(row.userId))"
              placement="top"
            >
              <el-tag type="danger" size="small">生成失败</el-tag>
            </el-tooltip>
            <el-tag
              v-else
              :type="reportStatusMeta(reportState(row.userId)).type"
              size="small"
            >
              {{ reportStatusMeta(reportState(row.userId)).label }}
            </el-tag>
          </template>
          <span v-else class="no-remark">-</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="160" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            icon="Document"
            @click="handlePreviewReport(row)"
          >
            报告
          </el-button>
          <el-button
            type="danger"
            link
            icon="Remove"
            @click="handleKick(row)"
          >
            移出
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-box">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 报告预览对话框 -->
    <ReportPreviewDialog ref="reportDialogRef" :class-id="classId" />
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 班级成员管理：查看成员、改备注、移出班级，以及批量生成学习周报。
// 备注采用行内编辑（editingRemark + tempRemark 两个 UI 状态字段），
// 编辑态挂在行数据上而不是全局 state，才能做到同时只编辑一行。
//
// 周报生成是 enqueue-only：批量 POST 只受理/复用任务，PENDING/GENERATING 不是失败。
// 本组件用一个共享轮询循环（班级报告列表，每 2 秒一次）更新每名学生的任务状态，
// 不为每个学生各自发请求；列表行从不加载 HTML。弹窗负责受理与快速预览，
// 完整正文（目录、结构化视图、打印导出）统一交给 /admin/reports/view/:jobId。
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Document } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getClassMembers, updateMemberRemark, removeMember } from '@/api/class'
import { generateBatchReports, listReportJobs } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import ReportPreviewDialog from './ReportPreviewDialog.vue'
import dayjs from 'dayjs'

const props = defineProps({
  classId: { type: String, required: true }
})

const router = useRouter()
const loading = ref(false)
const memberList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const keyword = ref('')
const remarkInputRef = ref(null)
const tableRef = ref(null)
const reportDialogRef = ref(null)
const selectedMembers = ref([])
const batchGenerating = ref(false)

// 学生 -> 报告任务状态。key 为 studentId 字符串，与分页刷新解耦。
// { jobId, status, accepted, rejected, error, failure, canRetry, attempts }
const reportStates = ref({})
const REPORT_POLL_INTERVAL_MS = 2000
// 仅防止异常情况下无限请求；这不是完成 SLA，预生成/排队可能持续更久。
const REPORT_POLL_MAX_TICKS = 150
const REPORT_LIST_PAGE_SIZE = 100
const REPORT_LIST_MAX_PAGES = 5
let reportPollTimer = null
let reportPollGeneration = 0
let reportPollTicks = 0
let reportPollInFlight = false
let disposed = false
const reportPolling = ref(false)

const REPORT_STATUS_META = {
  PENDING: { label: '排队中', type: 'info' },
  GENERATING: { label: '生成中', type: 'warning' },
  GENERATED: { label: '已生成', type: 'success' },
  FAILED: { label: '生成失败', type: 'danger' },
  REJECTED: { label: '未受理', type: 'danger' }
}

const pendingReportCount = computed(
  () => Object.values(reportStates.value).filter((state) => state.jobId && isInProgress(state.status)).length
)

function isInProgress(status) {
  return status === 'PENDING' || status === 'GENERATING'
}

function setReportState(studentId, patch) {
  const key = String(studentId)
  reportStates.value[key] = { ...(reportStates.value[key] || {}), ...patch }
}

function reportState(studentId) {
  return reportStates.value[String(studentId)]
}

function reportStatusOf(studentId) {
  const state = reportState(studentId)
  if (!state) return ''
  return state.rejected ? 'REJECTED' : state.status
}

function reportStatusMeta(state) {
  if (!state) return REPORT_STATUS_META.REJECTED
  if (state.rejected) return REPORT_STATUS_META.REJECTED
  return REPORT_STATUS_META[state.status] || { label: state.status || '未知', type: 'info' }
}

function reportFailureText(state) {
  if (state?.failure?.safeMessage) return state.failure.safeMessage
  if (state?.failure?.code) return `错误码：${state.failure.code}`
  if (state?.error) return `错误码：${state.error}`
  return '报告生成失败，打开报告详情查看原因'
}

function pendingReportStates() {
  return Object.values(reportStates.value).filter((state) => state.jobId && isInProgress(state.status))
}

function stopReportPolling() {
  reportPollGeneration += 1
  if (reportPollTimer) {
    clearTimeout(reportPollTimer)
    reportPollTimer = null
  }
  reportPolling.value = false
}

function scheduleReportPoll(generation) {
  if (reportPollTimer) clearTimeout(reportPollTimer)
  reportPollTimer = setTimeout(() => {
    reportPollTimer = null
    pollReportStates(generation)
  }, REPORT_POLL_INTERVAL_MS)
  reportPolling.value = true
}

function startReportPolling() {
  if (disposed || pendingReportStates().length === 0 || document.hidden) {
    stopReportPolling()
    return
  }
  stopReportPolling()
  reportPollTicks = 0
  reportPollGeneration += 1
  const generation = reportPollGeneration
  scheduleReportPoll(generation)
}

// 单循环轮询班级报告列表：一次 GET 更新所有已提交学生，避免 N 个学生并发 N 个请求。
async function pollReportStates(generation) {
  if (disposed || generation !== reportPollGeneration) return
  const pending = pendingReportStates()
  if (pending.length === 0 || document.hidden) {
    stopReportPolling()
    return
  }
  if (reportPollInFlight) {
    scheduleReportPoll(generation)
    return
  }
  if (reportPollTicks >= REPORT_POLL_MAX_TICKS) {
    stopReportPolling()
    return
  }
  reportPollTicks += 1
  reportPollInFlight = true
  try {
    const pendingIds = new Set(pending.map((state) => Number(state.jobId)))
    const found = new Map()
    for (let page = 1; page <= REPORT_LIST_MAX_PAGES; page += 1) {
      const data =
        unwrapResultData(
          await listReportJobs(
            { classId: props.classId, page, size: REPORT_LIST_PAGE_SIZE },
            { silent: true }
          )
        ) || {}
      const records = Array.isArray(data.records) ? data.records : []
      for (const job of records) found.set(Number(job.id), job)
      const allFound = [...pendingIds].every((id) => found.has(id))
      const totalCount = Number(data.total) || 0
      if (allFound || records.length === 0 || page * REPORT_LIST_PAGE_SIZE >= totalCount) break
    }
    if (generation !== reportPollGeneration) return
    for (const state of Object.values(reportStates.value)) {
      if (!state.jobId || !isInProgress(state.status)) continue
      const job = found.get(Number(state.jobId))
      if (!job) continue
      setReportState(job.studentId, {
        status: job.status,
        accepted: job.status !== 'FAILED',
        rejected: false,
        error: job.errorMessage || '',
        failure: job.generationFailure || null,
        canRetry: job.canRetry === true,
        attempts: job.attempts ?? 0
      })
    }
    if (pendingReportStates().length === 0) stopReportPolling()
    else scheduleReportPoll(generation)
  } catch {
    if (generation === reportPollGeneration) stopReportPolling()
  } finally {
    reportPollInFlight = false
  }
}

function handleReportVisibilityChange() {
  if (document.hidden) {
    stopReportPolling()
    return
  }
  if (pendingReportStates().length > 0) startReportPolling()
}

// 获取数据
const fetchMembers = async () => {
  loading.value = true
  try {
    const res = await getClassMembers({
      classId: props.classId,
      page: currentPage.value,
      size: pageSize,
      keyword: keyword.value
    })
    if (res.code === 200) {
      memberList.value = res.data.records.map(item => ({
        ...item,
        editingRemark: false, // UI 状态：是否正在编辑
        tempRemark: '' // 临时存储编辑中的备注
      }))
      total.value = res.data.total
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchMembers()
}

// 格式化时间
const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

// 跳转个人主页
const goToProfile = (userId) => {
  router.push({ name: 'UserProfile', params: { id: userId } })
}

// === 备注编辑逻辑 ===
const startEdit = (row) => {
  // 同一时刻只允许一行进入编辑态：否则多个 input 同时展开，
  // 教师很容易改错行且无从察觉。
  memberList.value.forEach(item => { item.editingRemark = false })
  
  row.tempRemark = row.remark || ''
  row.editingRemark = true
  // 自动聚焦
  nextTick(() => {
    // 简单处理，实际上可能有多个 input，这里不做复杂 DOM 查找了，用户手动点一下也行
  })
}

const cancelEdit = (row) => {
  row.editingRemark = false
}

const saveRemark = async (row) => {
  // 内容没变就不发请求：备注是高频编辑字段，多余的写入会刷满审计日志。
  const oldRemark = row.remark
  const newRemark = row.tempRemark.trim()

  if (newRemark === oldRemark) {
    row.editingRemark = false
    return
  }

  try {
    const res = await updateMemberRemark({
      classId: props.classId,
      userId: row.userId,
      remark: newRemark
    })
    
    if (res.code === 200) {
      row.remark = newRemark
      row.editingRemark = false
      ElMessage.success('备注已更新')
    }
  } catch (error) {
    console.error(error)
  }
}

// === 移出学生逻辑 ===
// 移出会同时解除学生与班级的关系并让其失去该班作业访问权限，
// 且没有回收站，故必须确认，且取消时静默（.catch(() => {})）。
const handleKick = (row) => {
  ElMessageBox.confirm(
    `确定要将学生 "${row.username}" 移出班级吗？`,
    '移出确认',
    {
      confirmButtonText: '确定移出',
      cancelButtonText: '取消',
      type: 'warning',
      confirmButtonClass: 'el-button--danger'
    }
  ).then(async () => {
    try {
      const res = await removeMember({
        classId: props.classId,
        userId: row.userId
      })
      if (res.code === 200) {
        ElMessage.success('操作成功')
        fetchMembers() // 刷新列表
      }
    } catch (error) {
      console.error(error)
    }
  }).catch(() => {})
}

// === 报告相关逻辑 ===

// 多选变化
const handleSelectionChange = (selection) => {
  selectedMembers.value = selection
}

// 批量受理周报：accepted/status/jobId 是权威字段；success=false 且 accepted=true 只是排队。
const handleBatchGenerate = async () => {
  const students = selectedMembers.value
  if (students.length === 0) {
    ElMessage.warning('请先选择学生')
    return
  }
  batchGenerating.value = true
  try {
    const studentIds = students.map(s => s.userId || s.studentId)
    const res = await generateBatchReports({
      classId: props.classId,
      studentIds
    })
    if (res.code === 200) {
      if (disposed) return
      const results = Array.isArray(res.data) ? res.data : []
      if (results.length === 0) {
        ElMessage.warning('批量接口未返回学生结果')
        return
      }
      let accepted = 0
      let queued = 0
      let generated = 0
      let failed = 0
      let rejected = 0
      for (const item of results) {
        const acceptedItem = item.accepted === true
        const status = item.status || (item.success ? 'GENERATED' : 'REJECTED')
        if (acceptedItem) {
          accepted += 1
          if (status === 'GENERATED') generated += 1
          else if (isInProgress(status)) queued += 1
        } else if (status === 'FAILED') {
          failed += 1
        } else {
          rejected += 1
        }
        setReportState(item.studentId, {
          jobId: item.jobId,
          status,
          accepted: acceptedItem,
          rejected: !acceptedItem && status !== 'FAILED',
          error: item.error || '',
          failure: status === 'FAILED' ? { code: item.error || '', safeMessage: '' } : null,
          canRetry: false,
          attempts: 0
        })
      }
      const problems = failed + rejected
      if (accepted > 0) {
        const parts = [`已受理 ${accepted} 份`]
        if (queued > 0) parts.push(`${queued} 份排队/生成中`)
        if (generated > 0) parts.push(`${generated} 份已生成`)
        if (problems > 0) parts.push(`${problems} 份未受理或生成失败`)
        ElMessage.success(parts.join('，'))
      } else {
        ElMessage.error(`本次 ${results.length} 份报告均未受理或已失败`)
      }
      if (queued > 0) startReportPolling()
      else stopReportPolling()
    }
  } catch (e) {
    showApiError(e, '批量受理失败：' + (e?.message || '网络异常'))
  } finally {
    batchGenerating.value = false
  }
}

// 单个学生预览报告（弹窗内部走单人 job 提交/轮询）
const handlePreviewReport = (row) => {
  const studentId = row.userId || row.studentId
  const name = row.remark || row.username || `学生 ${studentId}`
  reportDialogRef.value?.open([{ studentId, name }])
}

watch(
  () => props.classId,
  () => {
    stopReportPolling()
    reportStates.value = {}
  }
)

onMounted(() => {
  document.addEventListener('visibilitychange', handleReportVisibilityChange)
  fetchMembers()
})

onBeforeUnmount(() => {
  disposed = true
  stopReportPolling()
  document.removeEventListener('visibilitychange', handleReportVisibilityChange)
})
</script>

<style scoped>
.member-manager { padding: 0; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.right-info { display: flex; align-items: center; gap: 15px; }
.info-text { font-size: 13px; color: var(--text-secondary); }
.report-poll-hint { white-space: nowrap; }

.user-info-cell { display: flex; align-items: center; gap: 12px; cursor: pointer; padding: 4px 0; }
.user-info-cell:hover .username { color: var(--accent-primary); }
.user-text { display: flex; flex-direction: column; line-height: 1.3; }
.username { font-weight: 500; font-size: 14px; color: var(--text-primary); transition: color 0.2s; }
.email { font-size: 12px; color: var(--text-secondary); }

.remark-display { display: flex; align-items: center; gap: 8px; }
.remark-text { font-size: 14px; color: var(--text-regular); }
.no-remark { font-size: 13px; color: var(--text-placeholder); font-style: italic; }
.edit-icon { opacity: 0; transition: opacity 0.2s; }
.remark-display:hover .edit-icon { opacity: 1; }

.remark-edit { display: flex; align-items: center; gap: 5px; }
.time-text { font-family: 'Menlo', monospace; color: var(--text-regular); font-size: 13px; }

.pagination-box { display: flex; justify-content: center; margin-top: 20px; }
</style>
