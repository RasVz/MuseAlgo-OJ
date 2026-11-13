<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="90%"
    top="3vh"
    :close-on-click-modal="false"
    destroy-on-close
    @close="cancelPolling"
    @closed="handleClosed"
  >
    <!-- 学生切换栏 -->
    <div v-if="students.length > 1" class="student-tabs">
      <el-tag
        v-for="s in students"
        :key="s.studentId"
        :type="s.studentId === currentStudentId ? 'primary' : 'info'"
        :effect="s.studentId === currentStudentId ? 'dark' : 'plain'"
        class="student-tab"
        @click="switchStudent(s.studentId)"
      >
        {{ s.name }}
        <el-icon v-if="tabIconState(s) === 'loading'" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="tabIconState(s) === 'success'"><CircleCheckFilled /></el-icon>
        <el-icon v-else-if="tabIconState(s) === 'error'"><CircleCloseFilled /></el-icon>
      </el-tag>
    </div>

    <!-- 提交中 -->
    <div v-if="currentSubmitting" class="loading-box">
      <el-icon class="is-loading" :size="32"><Loading /></el-icon>
      <p>正在提交报告任务...</p>
    </div>

    <!-- 已受理，后台排队/生成中 -->
    <div v-else-if="currentQueued && !currentHtml" class="loading-box">
      <el-icon class="is-loading" :size="32"><Loading /></el-icon>
      <p>{{ currentStudent?.status === 'PENDING' ? '报告已受理，正在排队...' : '报告正在后台生成...' }}</p>
      <p class="hint">页面每 2 秒自动刷新当前任务；关闭弹窗只停止刷新，不会取消已受理任务。</p>
      <p v-if="currentStudent?.errorMessage" class="hint error-hint">{{ currentStudent.errorMessage }}</p>
      <el-button
        v-if="currentStudent?.errorMessage && currentStudent?.jobId"
        size="small"
        @click="beginPolling(currentStudent, { immediate: true })"
      >
        继续刷新
      </el-button>
    </div>

    <!-- 失败 / 未受理 / 无权 -->
    <div v-else-if="currentError && !currentHtml" class="error-box">
      <el-result
        icon="error"
        :title="currentStudent?.forbidden ? '无权查看该报告' : (currentStudent?.status === 'FAILED' ? '报告生成失败' : '报告未能显示')"
        :sub-title="currentError"
      >
        <template #extra>
          <el-button
            v-if="currentCanRetry"
            type="primary"
            :loading="currentStudent?.retrying"
            @click="retryCurrent"
          >
            重新生成
          </el-button>
          <el-button
            v-else-if="!currentStudent?.jobId && !currentStudent?.forbidden"
            type="primary"
            :loading="currentSubmitting"
            @click="loadCurrent"
          >
            生成报告
          </el-button>
        </template>
      </el-result>
      <p v-if="currentStudent?.failure?.code" class="hint">错误码：{{ currentStudent.failure.code }}</p>
      <p v-if="currentStudent?.status === 'FAILED'" class="hint">
        已消耗 {{ currentStudent?.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次生成预算。
        <template v-if="!currentCanRetry">该错误类型或剩余预算不允许再次重试。</template>
      </p>
    </div>

    <!-- 报告快速预览：弹窗内保留即时预览，完整正文交给独立全屏阅读页 -->
    <div v-else-if="currentHtml" class="report-preview">
      <div class="preview-toolbar">
        <span class="preview-hint">此处为快速预览；目录、结构化正文、打印与导出在独立阅读页</span>
        <el-button type="primary" plain size="small" @click="openFullReport">打开完整报告</el-button>
      </div>
      <div class="report-frame-wrapper">
        <iframe
          ref="reportIframeRef"
          :srcdoc="reportDocument(currentHtml)"
          class="report-iframe"
          title="学生周报预览"
          sandbox="allow-scripts"
        />
      </div>
    </div>

    <!-- 尚未提交 -->
    <el-empty v-else description="点击「生成报告」受理该学生的学习周报任务" :image-size="72" />

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button
          v-if="currentHtml"
          type="success"
          :loading="exporting"
          @click="onExport"
        >
          导出图片
        </el-button>
        <el-button
          v-if="currentStudent && !currentStudent.status && !currentSubmitting && !currentStudent.forbidden"
          type="primary"
          @click="loadCurrent"
        >
          生成报告
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { getErrorCode } from '@/utils/apiError'
// 学习周报预览弹窗：教师查看或受理单个学生的学习周报，并可导出 PNG。
//
// 后端为 enqueue-only：提交只受理/复用任务，弹窗轮询该 job（2 秒、单个在途 GET），
// 只有 status=GENERATED 才渲染 HTML。PENDING/GENERATING 继续等待，FAILED 仅在
// 服务端 canRetry=true 时显示重试；切换学生立即停止旧轮询且内容按学生各自保存，
// 不会把 A 的 HTML/错误显示到 B 上。关闭弹窗只停止轮询，不取消已受理任务。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Loading, CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { getReportJob, requestReportJob, retryReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useReportExport } from '../composables/useReportExport'
import { reportDocument } from '../composables/reportIsolation'

const props = defineProps({
  /** 班级 ID */
  classId: { type: [String, Number], required: true }
})

const POLL_INTERVAL_MS = 2000
const MAX_NOT_READY_RETRIES = 5
// 与后端报告生成预算一致，仅用于展示 attempts/max；重试入口只认服务端 canRetry。
const MAX_ATTEMPTS = 3

const visible = ref(false)
const currentStudentId = ref(null)
const reportIframeRef = ref(null)

// 学生状态列表：
// [{ studentId, name, jobId, status, attempts, canRetry, failure, errorMessage,
//    html, submitting, retrying, forbidden, rejected }]
const students = ref([])

let pollTimer = null
let pollGeneration = 0
let disposed = false
let notReadyRetries = 0
const jobControllers = new Map()
// 同一 job 的 GET 不重叠；用集合而非单值，快速切换学生再切回也不会叠请求。
const inFlightJobIds = new Set()
// 详情 GET 共用同一个在途 promise，轮询与 GENERATED 正文读取不会并发请求同一 job。
const jobRequests = new Map()

// 报告导出（html2canvas iframe 截图）
const { exporting, exportImage } = useReportExport()

const currentStudent = computed(() =>
  students.value.find(s => s.studentId === currentStudentId.value)
)

const currentHtml = computed(() => {
  const s = currentStudent.value
  return s && s.status === 'GENERATED' && !s.forbidden && s.html ? s.html : ''
})

const currentSubmitting = computed(() => Boolean(currentStudent.value?.submitting))

const currentQueued = computed(() => isInProgress(currentStudent.value?.status))

const currentCanRetry = computed(
  () => currentStudent.value?.status === 'FAILED' && currentStudent.value?.canRetry === true
)

const currentError = computed(() => {
  const s = currentStudent.value
  if (!s) return ''
  if (s.forbidden) return s.errorMessage || '当前账号无权查看该报告内容。'
  if (s.status === 'FAILED') return s.failure?.safeMessage || s.errorMessage || '报告生成失败'
  if (s.rejected) return s.errorMessage || '报告请求未被受理'
  return s.errorMessage || ''
})

const dialogTitle = computed(() => {
  const s = currentStudent.value
  return s ? `${s.name} · 学习周报` : '学习周报'
})

const router = useRouter()
const onExport = () => exportImage(currentHtml.value, currentStudent.value?.name)

// 完整正文统一由 /admin/reports/view/:jobId 承载；弹窗只负责受理、进度与快速预览。
function openFullReport() {
  const jobId = Number(currentStudent.value?.jobId)
  if (!Number.isInteger(jobId) || jobId <= 0) return
  const href = router.resolve({ name: 'AdminWeeklyReportReader', params: { jobId } }).href
  window.open(href, '_blank', 'noopener')
}

function isInProgress(status) {
  return status === 'PENDING' || status === 'GENERATING'
}

function isForbidden(error) {
  return Number(error?.response?.status) === 403 || Number(error?.response?.data?.code) === 403
}

// 旧 preview/受理端点以 409 REPORT_NOT_READY 表示任务尚未完成，不是失败。
function isNotReady(error) {
  const code = getErrorCode(error)
  return code === 'REPORT_NOT_READY'
}

function errorText(error, fallback) {
  return error?.response?.data?.message || error?.response?.data?.msg || error?.message || fallback
}

function tabIconState(student) {
  if (!student) return 'idle'
  if (student.submitting || isInProgress(student.status)) return 'loading'
  if (student.status === 'GENERATED' && !student.forbidden) return 'success'
  if (student.status === 'FAILED' || student.rejected || student.forbidden) return 'error'
  return 'idle'
}

function findStudent(studentId) {
  return students.value.find(s => s.studentId === studentId)
}

function isActiveStudent(student) {
  return Boolean(student) && students.value.includes(student)
}

function applyJob(student, job) {
  if (!student || !job) return
  student.jobId = job.id
  student.status = job.status || ''
  student.attempts = job.attempts ?? 0
  student.canRetry = job.status === 'FAILED' && job.canRetry === true
  student.failure = job.generationFailure || null
  student.errorMessage = job.errorMessage || ''
  student.forbidden = false
  student.rejected = false
  if (job.status !== 'GENERATED') {
    // 进行态/失败态不得沿用旧正文；切换到别的学生更不会共享这份状态。
    student.html = ''
  } else if (job.html) {
    student.html = job.html
  }
}

function cancelPolling() {
  pollGeneration += 1
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  for (const controller of jobControllers.values()) controller.abort()
  jobControllers.clear()
  jobRequests.clear()
}

function schedulePoll(studentId, jobId, generation, delay = POLL_INTERVAL_MS) {
  if (disposed || !visible.value || generation !== pollGeneration) return
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = setTimeout(() => {
    pollTimer = null
    pollJob(studentId, jobId, generation)
  }, delay)
}

function fetchJobDetail(jobId) {
  const id = Number(jobId)
  const existing = jobRequests.get(id)
  if (existing) return existing
  const controller = new AbortController()
  jobControllers.set(id, controller)
  const tracked = getReportJob(id, { silent: true, signal: controller.signal }).finally(() => {
    if (jobControllers.get(id) === controller) jobControllers.delete(id)
    if (jobRequests.get(id) === tracked) jobRequests.delete(id)
  })
  jobRequests.set(id, tracked)
  return tracked
}

async function pollJob(studentId, jobId, generation) {
  if (disposed || !visible.value || generation !== pollGeneration) return
  const student = findStudent(studentId)
  if (!student || currentStudentId.value !== studentId) return
  if (document.hidden) return
  if (inFlightJobIds.has(jobId)) {
    schedulePoll(studentId, jobId, generation)
    return
  }
  inFlightJobIds.add(jobId)
  try {
    const job = unwrapResultData(await fetchJobDetail(jobId))
    if (disposed || !visible.value || generation !== pollGeneration || currentStudentId.value !== studentId) return
    applyJob(student, job)
    notReadyRetries = 0
    if (job.status === 'GENERATED' && !job.html) {
      student.errorMessage = '报告已生成但正文未返回，请重新打开'
    }
    if (isInProgress(job.status)) {
      if (document.hidden) return
      schedulePoll(studentId, jobId, generation)
      return
    }
    cancelPolling()
  } catch (e) {
    if (disposed || !visible.value || generation !== pollGeneration || currentStudentId.value !== studentId) return
    if (isForbidden(e)) {
      handleLoadError(student, e)
      return
    }
    if (isNotReady(e) && notReadyRetries < MAX_NOT_READY_RETRIES) {
      const delay = POLL_INTERVAL_MS * 2 ** notReadyRetries++
      student.errorMessage = `报告尚未就绪，${delay / 1000} 秒后重试。`
      schedulePoll(studentId, jobId, generation, delay)
      return
    }
    cancelPolling()
    if (isNotReady(e)) {
      student.errorMessage = '报告尚未就绪，自动刷新已暂停，可手动继续刷新。'
      return
    }
    student.errorMessage = errorText(e, '报告状态刷新失败')
  } finally {
    inFlightJobIds.delete(jobId)
  }
}

function beginPolling(student, { immediate = false } = {}) {
  if (disposed || !visible.value || !isActiveStudent(student) || student.studentId !== currentStudentId.value) return
  if (!student.jobId || !isInProgress(student.status) || document.hidden) return
  cancelPolling()
  notReadyRetries = 0
  const generation = pollGeneration
  const studentId = student.studentId
  const jobId = Number(student.jobId)
  if (immediate) pollJob(studentId, jobId, generation)
  else schedulePoll(studentId, jobId, generation)
}

function handleLoadError(student, error) {
  if (!isActiveStudent(student)) return
  if (isForbidden(error)) {
    if (student.studentId === currentStudentId.value) cancelPolling()
    student.forbidden = true
    student.canRetry = false
    student.html = ''
    student.errorMessage = '当前账号无权查看该报告内容，已停止刷新。'
    return
  }
  if (isNotReady(error)) {
    if (student.jobId) {
      student.status = 'PENDING'
      student.errorMessage = ''
      beginPolling(student)
    } else {
      student.errorMessage = '报告尚未就绪，请稍后重试'
    }
    return
  }
  if (student.studentId === currentStudentId.value) cancelPolling()
  student.errorMessage = errorText(error, '报告请求失败')
}

async function loadGeneratedHtml(student) {
  if (!student?.jobId) return
  try {
    const job = unwrapResultData(await fetchJobDetail(student.jobId))
    if (isActiveStudent(student) && currentStudentId.value === student.studentId) {
      applyJob(student, job)
      if (job.status === 'GENERATED' && !job.html) {
        student.errorMessage = '报告已生成但正文未返回，请重新打开'
      }
    }
  } catch (e) {
    if (isActiveStudent(student) && currentStudentId.value === student.studentId) {
      handleLoadError(student, e)
    }
  }
}

async function submitStudent(student) {
  if (!student || student.submitting) return
  student.submitting = true
  student.errorMessage = ''
  student.failure = null
  student.forbidden = false
  student.rejected = false
  cancelPolling()
  try {
    const job = unwrapResultData(
      await requestReportJob({
        studentId: student.studentId,
        classId: Number(props.classId)
      })
    )
    if (!job?.id) {
      student.errorMessage = '接口未返回报告任务'
      return
    }
    if (!isActiveStudent(student)) return
    applyJob(student, job)
    if (job.status === 'GENERATED') {
      // 受理响应不携带 HTML；只有详情 GET 才返回 GENERATED 正文。
      if (!student.html) await loadGeneratedHtml(student)
      return
    }
    if (isInProgress(student.status)) beginPolling(student)
  } catch (e) {
    handleLoadError(student, e)
  } finally {
    student.submitting = false
  }
}

async function retryCurrent() {
  const student = currentStudent.value
  if (!student?.jobId || !currentCanRetry.value || student.retrying) return
  student.retrying = true
  try {
    const job = unwrapResultData(await retryReportJob(student.jobId))
    if (!isActiveStudent(student)) return
    applyJob(student, job)
    if (isInProgress(student.status)) {
      beginPolling(student, { immediate: true })
    } else if (student.status === 'GENERATED' && !student.html) {
      await loadGeneratedHtml(student)
    }
  } catch (e) {
    if (!isActiveStudent(student)) return
    if (isForbidden(e)) {
      handleLoadError(student, e)
      return
    }
    const code = getErrorCode(e)
    if (code === 'ATTEMPTS_EXHAUSTED' || code === 'RETRY_NOT_ALLOWED') {
      student.canRetry = false
    } else if (isNotReady(e)) {
      student.status = 'PENDING'
      student.errorMessage = ''
      beginPolling(student, { immediate: true })
    } else {
      student.errorMessage = errorText(e, '重试失败')
    }
  } finally {
    student.retrying = false
  }
}

// 切换学生只切换展示源：先停旧轮询，再按新学生自己的 job 状态恢复。
function switchStudent(studentId) {
  if (studentId === currentStudentId.value) return
  cancelPolling()
  currentStudentId.value = studentId
  const student = currentStudent.value
  if (!student) return
  if (student.status === 'GENERATED' && !student.html) {
    loadGeneratedHtml(student)
  } else if (isInProgress(student.status)) {
    beginPolling(student, { immediate: true })
  }
}

function loadCurrent() {
  const student = currentStudent.value
  if (!student) return
  submitStudent(student)
}

/**
 * 打开对话框，为指定学生受理/复用报告任务。
 * @param {{ studentId: number, name: string }[]} studentList
 */
function open(studentList) {
  if (!studentList || studentList.length === 0) return
  cancelPolling()
  students.value = studentList.map(s => ({
    studentId: Number(s.studentId),
    name: s.name || `学生 ${s.studentId}`,
    jobId: null,
    status: '',
    attempts: 0,
    canRetry: false,
    failure: null,
    errorMessage: '',
    html: '',
    submitting: false,
    retrying: false,
    forbidden: false,
    rejected: false
  }))
  currentStudentId.value = students.value[0].studentId
  visible.value = true
  // 自动为第一个学生受理任务；其余学生切到标签时再按需受理，避免整班一次性生成。
  submitStudent(students.value[0])
}

function handleClosed() {
  cancelPolling()
  students.value = []
  currentStudentId.value = null
}

function handleVisibilityChange() {
  if (document.hidden) {
    cancelPolling()
    return
  }
  const student = currentStudent.value
  if (visible.value && student && isInProgress(student.status)) {
    beginPolling(student, { immediate: true })
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  disposed = true
  cancelPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

defineExpose({ open })
</script>

<style scoped>
.student-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}

.student-tab {
  cursor: pointer;
  user-select: none;
}

.student-tab:hover {
  opacity: 0.85;
}

.loading-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 40vh;
  color: var(--text-secondary);
  gap: 12px;
}

.hint {
  color: var(--text-secondary);
  font-size: 12px;
  margin: 0;
}

.error-hint {
  color: var(--danger-base, #f56c6c);
}

.error-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
}

.report-preview { display: flex; flex-direction: column; gap: 10px; }
.preview-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.preview-hint { font-size: 12px; color: var(--text-secondary); }
.report-frame-wrapper {
  width: 100%;
  height: 68vh;
  border: 1px solid var(--border-base);
  border-radius: 6px;
  overflow: hidden;
}

.report-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
