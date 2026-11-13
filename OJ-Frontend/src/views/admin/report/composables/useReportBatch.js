// 批量受理学习周报：分批提交 + 班级报告列表共享轮询。
//
// 后端仍是 enqueue-only：POST 只受理/复用任务，PENDING / GENERATING 是正常进行态。
// 轮询复用班级报告列表（一次 GET 更新全部在途任务），不为每个学生各发一个详情请求；
// 列表行不含 html，正文统一由阅读页按 jobId 读取。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { generateBatchReports, listReportJobs, retryReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { getErrorCode } from '@/utils/apiError'
import { errorMessage, isInProgress } from './reportMeta'
import {
  chunkStudents, mergeJobIntoItem, normalizeBatchResult,
  normalizeStudents, rejectedItem, summarizeBatchItems
} from './reportBatch'

const POLL_INTERVAL_MS = 2000
// 仅防止异常情况下无限请求；不是完成 SLA，排队/生成可能更久。
const POLL_MAX_TICKS = 150
const LIST_PAGE_SIZE = 100
const LIST_MAX_PAGES = 5
const PAUSED_TEXT = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'

export function useReportBatch() {
  const items = ref([])
  const submitting = ref(false)
  const refreshing = ref(false)
  const polling = ref(false)
  const pausedMessage = ref('')
  const summary = computed(() => summarizeBatchItems(items.value))

  let disposed = false
  let timer = null
  let pollingGeneration = 0
  let ticks = 0
  let inFlight = false
  let pollClassId = null

  function pendingItems() {
    return items.value.filter((item) => item.jobId && isInProgress(item.status))
  }

  function findItem(studentId) {
    return items.value.find((item) => item.studentId === studentId)
  }

  function cancelTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  function stopPolling() {
    pollingGeneration += 1
    cancelTimer()
    polling.value = false
    pausedMessage.value = ''
  }

  function pausePolling(message) {
    pollingGeneration += 1
    cancelTimer()
    polling.value = false
    pausedMessage.value = message
  }

  function schedule(generation) {
    if (disposed || generation !== pollingGeneration) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      pollTick(generation)
    }, POLL_INTERVAL_MS)
    polling.value = true
  }

  function startPolling() {
    if (disposed || pendingItems().length === 0) return
    if (document.hidden) {
      pausedMessage.value = PAUSED_TEXT
      return
    }
    stopPolling()
    ticks = 0
    pausedMessage.value = ''
    schedule(pollingGeneration)
  }

  // 一次列表请求覆盖整批在途任务；按 jobId 精确匹配，避免同名学生串行。
  async function mergeJobs(targets) {
    const classId = pollClassId
    if (!classId || targets.length === 0 || inFlight) return
    inFlight = true
    try {
      const wanted = new Set(targets.map((item) => Number(item.jobId)))
      const found = new Map()
      for (let page = 1; page <= LIST_MAX_PAGES; page += 1) {
        const data = unwrapResultData(
          await listReportJobs({ classId, page, size: LIST_PAGE_SIZE }, { silent: true })
        ) || {}
        const records = Array.isArray(data.records) ? data.records : []
        for (const job of records) {
          if (wanted.has(Number(job.id))) found.set(Number(job.id), job)
        }
        const allFound = [...wanted].every((id) => found.has(id))
        const total = Number(data.total) || 0
        if (allFound || records.length === 0 || page * LIST_PAGE_SIZE >= total) break
      }
      if (disposed) return
      for (const item of items.value) {
        const job = found.get(Number(item.jobId))
        if (job) mergeJobIntoItem(item, job)
      }
    } catch (e) {
      if (disposed || document.hidden) return
      pausePolling(`自动刷新已暂停：${errorMessage(e, '网络异常')}`)
    } finally {
      inFlight = false
    }
  }

  async function pollTick(generation) {
    if (disposed || generation !== pollingGeneration) return
    if (pendingItems().length === 0) {
      stopPolling()
      return
    }
    if (document.hidden) {
      pausePolling(PAUSED_TEXT)
      return
    }
    if (ticks >= POLL_MAX_TICKS) {
      pausePolling('自动刷新已达到本次上限，可手动刷新状态。')
      return
    }
    ticks += 1
    await mergeJobs(pendingItems())
    if (disposed || generation !== pollingGeneration) return
    if (pendingItems().length > 0) schedule(generation)
    else stopPolling()
  }

  /**
   * 批量受理多名学生。按后端 50 人上限分批调用批量接口，任一批失败只影响该批学生；
   * 单人受理由报告面板单独走任务详情接口，保持既有单人卡片与轮询行为。
   * @param {{ classId: number|string, students: object[], periodStart?: string, periodEnd?: string }} payload
   */
  async function submit({ classId, students, periodStart, periodEnd }) {
    stopPolling()
    const roster = normalizeStudents(students)
    pollClassId = Number(classId) || null
    items.value = roster.map((student) => ({
      studentId: student.studentId,
      name: student.name,
      jobId: null,
      status: '',
      accepted: false,
      rejected: false,
      attempts: 0,
      canRetry: false,
      failure: null,
      errorMessage: '',
      retrying: false
    }))
    if (roster.length === 0) return summary.value

    submitting.value = true
    try {
      for (const chunk of chunkStudents(roster)) {
        try {
          const data = unwrapResultData(
            await generateBatchReports({
              classId,
              studentIds: chunk.map((student) => student.studentId),
              periodStart,
              periodEnd
            })
          )
          const results = Array.isArray(data) ? data : []
          const byStudent = new Map(results.map((result) => [Number(result?.studentId), result]))
          for (const student of chunk) {
            const target = findItem(student.studentId)
            if (!target) continue
            const result = byStudent.get(student.studentId)
            Object.assign(
              target,
              result
                ? normalizeBatchResult(result, student)
                : rejectedItem(student, '批量接口未返回该学生结果')
            )
          }
        } catch (e) {
          for (const student of chunk) {
            const target = findItem(student.studentId)
            if (target) Object.assign(target, rejectedItem(student, errorMessage(e, '批量受理失败')))
          }
        }
      }
      // 批量响应只带错误码，不带 canRetry；失败任务用一次列表同步补服务端重试判定。
      const failed = items.value.filter((item) => item.jobId && item.status === 'FAILED')
      if (failed.length > 0) await mergeJobs(failed)
      if (pendingItems().length > 0) startPolling()
      return summary.value
    } finally {
      submitting.value = false
    }
  }

  /** 手动刷新：同步全部已有 jobId 的任务（含终态），不改用详情接口。 */
  async function refresh() {
    if (refreshing.value || !pollClassId) return
    refreshing.value = true
    try {
      const targets = items.value.filter((item) => item.jobId)
      if (targets.length > 0) await mergeJobs(targets)
      if (!document.hidden && pendingItems().length > 0) startPolling()
    } finally {
      refreshing.value = false
    }
  }

  async function retry(item) {
    if (!item?.jobId || item.retrying) return null
    item.retrying = true
    try {
      const job = unwrapResultData(await retryReportJob(item.jobId))
      if (job) mergeJobIntoItem(item, job)
      if (!document.hidden && pendingItems().length > 0) startPolling()
      return job
    } catch (e) {
      const code = getErrorCode(e)
      if (code === 'ATTEMPTS_EXHAUSTED' || code === 'RETRY_NOT_ALLOWED') item.canRetry = false
      throw e
    } finally {
      item.retrying = false
    }
  }

  function clear() {
    stopPolling()
    items.value = []
    pollClassId = null
  }

  function handleVisibilityChange() {
    if (document.hidden) {
      if (polling.value || timer) pausePolling(PAUSED_TEXT)
      return
    }
    if (pendingItems().length > 0) startPolling()
  }

  onMounted(() => document.addEventListener('visibilitychange', handleVisibilityChange))
  onBeforeUnmount(() => {
    disposed = true
    stopPolling()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return {
    items,
    submitting,
    refreshing,
    polling,
    pausedMessage,
    summary,
    submit,
    refresh,
    retry,
    clear,
    resume: startPolling,
    stop: stopPolling
  }
}
