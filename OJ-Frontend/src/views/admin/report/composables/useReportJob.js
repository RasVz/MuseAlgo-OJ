// 单个报告任务的详情加载与状态轮询。
//
// 后端为 enqueue-only：PENDING / GENERATING 是正常进行态，只有 status=GENERATED
// 的详情响应才带 html。轮询沿用「单一定时器 + 世代号 + 同 job GET 去重」，
// 生命周期从「弹窗关闭」改为「组件卸载」；页面隐藏暂停、返回立即取最新状态。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { errorMessage, isForbidden, isInProgress } from './reportMeta'

const POLL_INTERVAL_MS = 2000
const PAUSED_TEXT = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'

export function useReportJob({ onTerminal } = {}) {
  const job = ref(null)
  const jobId = ref(null)
  const loading = ref(false)
  const error = ref('')
  const forbidden = ref(false)
  const pollActive = ref(false)
  const pollPausedMessage = ref('')

  let disposed = false
  let timer = null
  let generation = 0
  const controllers = new Map()
  const requests = new Map()
  const inFlight = new Set()

  const hasHtml = computed(() => job.value?.status === 'GENERATED' && Boolean(job.value?.html))
  const inProgress = computed(() => isInProgress(job.value?.status))

  function cancelPolling() {
    generation += 1
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    for (const controller of controllers.values()) controller.abort()
    controllers.clear()
    requests.clear()
    pollActive.value = false
  }

  function fetchDetail(id) {
    const existing = requests.get(id)
    if (existing) return existing
    const controller = new AbortController()
    controllers.set(id, controller)
    const tracked = getReportJob(id, { silent: true, signal: controller.signal }).finally(() => {
      if (controllers.get(id) === controller) controllers.delete(id)
      if (requests.get(id) === tracked) requests.delete(id)
    })
    requests.set(id, tracked)
    return tracked
  }

  function schedule(id, gen) {
    if (disposed || jobId.value !== id || gen !== generation) return
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      poll(id, gen)
    }, POLL_INTERVAL_MS)
    pollActive.value = true
  }

  function startPolling(id, { immediate = false } = {}) {
    if (disposed || jobId.value !== id || !isInProgress(job.value?.status)) return
    if (document.hidden) {
      pollPausedMessage.value = PAUSED_TEXT
      return
    }
    pollPausedMessage.value = ''
    pollActive.value = true
    const gen = generation
    if (immediate) poll(id, gen)
    else schedule(id, gen)
  }

  async function poll(id, gen) {
    if (disposed || gen !== generation || jobId.value !== id) return
    if (document.hidden) {
      pollActive.value = false
      pollPausedMessage.value = PAUSED_TEXT
      return
    }
    if (inFlight.has(id)) {
      schedule(id, gen)
      return
    }
    inFlight.add(id)
    try {
      const data = unwrapResultData(await fetchDetail(id))
      if (disposed || gen !== generation || jobId.value !== id) return
      if (!data) {
        cancelPolling()
        job.value = null
        error.value = '报告任务不存在'
        return
      }
      job.value = data
      if (isInProgress(data.status)) {
        if (document.hidden) {
          pollActive.value = false
          pollPausedMessage.value = PAUSED_TEXT
          return
        }
        schedule(id, gen)
        return
      }
      cancelPolling()
      onTerminal?.(data)
    } catch (e) {
      if (disposed || gen !== generation || jobId.value !== id) return
      if (isForbidden(e)) {
        cancelPolling()
        forbidden.value = true
        error.value = '当前账号无权查看该报告内容，已停止自动刷新。'
        return
      }
      cancelPolling()
      pollPausedMessage.value = `自动刷新已暂停：${errorMessage(e, '网络异常')}`
    } finally {
      inFlight.delete(id)
    }
  }

  function resume() {
    const id = jobId.value
    if (!id || disposed || document.hidden) return
    if (!isInProgress(job.value?.status)) return
    cancelPolling()
    pollPausedMessage.value = ''
    startPolling(id, { immediate: true })
  }

  async function load(id) {
    const parsed = Number(id)
    if (!Number.isInteger(parsed) || parsed <= 0) return null
    cancelPolling()
    jobId.value = parsed
    loading.value = true
    error.value = ''
    forbidden.value = false
    job.value = null
    pollPausedMessage.value = ''
    try {
      const data = unwrapResultData(await fetchDetail(parsed))
      if (disposed || jobId.value !== parsed) return null
      if (!data) {
        error.value = '报告任务不存在'
        return null
      }
      job.value = data
      if (data.status === 'GENERATED' && !data.html) {
        error.value = '报告已生成但正文未返回，请重新加载'
      }
      if (isInProgress(data.status)) startPolling(parsed)
      else onTerminal?.(data)
      return data
    } catch (e) {
      if (disposed || jobId.value !== parsed) return null
      if (isForbidden(e)) {
        forbidden.value = true
        error.value = '当前账号无权查看该报告内容。'
        return null
      }
      error.value = errorMessage(e, '报告详情加载失败')
      return null
    } finally {
      if (jobId.value === parsed) loading.value = false
    }
  }

  function reload() {
    if (jobId.value) return load(jobId.value)
    return Promise.resolve(null)
  }

  // POST 受理 / 重试 / dry-run 已返回最新任务时直接接管，无需再发一次 GET。
  function adopt(data) {
    if (!data) return
    cancelPolling()
    jobId.value = Number(data.id)
    loading.value = false
    error.value = ''
    forbidden.value = false
    pollPausedMessage.value = ''
    job.value = data
    if (isInProgress(data.status)) startPolling(Number(data.id))
    else onTerminal?.(data)
  }

  function apply(data) {
    if (!data) return
    if (Number(data.id) !== jobId.value) {
      adopt(data)
      return
    }
    job.value = data
    if (isInProgress(data.status)) startPolling(jobId.value)
  }

  function handleVisibilityChange() {
    if (document.hidden) {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      if (pollActive.value || inFlight.size > 0) pollPausedMessage.value = PAUSED_TEXT
      pollActive.value = false
      return
    }
    resume()
  }

  onMounted(() => document.addEventListener('visibilitychange', handleVisibilityChange))
  onBeforeUnmount(() => {
    disposed = true
    cancelPolling()
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  return {
    job, jobId, loading, error, forbidden, pollActive, pollPausedMessage, hasHtml, inProgress,
    load, reload, apply, adopt, resume, stop: cancelPolling
  }
}
