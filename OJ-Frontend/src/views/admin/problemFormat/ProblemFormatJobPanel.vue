<template>
  <el-drawer
    :model-value="modelValue"
    :with-header="false"
    size="640px"
    class="format-job-panel"
    :close-on-click-modal="false"
    @update:model-value="(value) => emit('update:modelValue', value)"
  >
    <div class="fj">
      <header class="fj-head">
        <div>
          <div class="fj-eyebrow"><span class="fj-badge">批量优化</span></div>
          <h2 class="fj-title">题面优化任务</h2>
          <p class="fj-sub">{{ headerSub }}</p>
        </div>
        <el-button text @click="close">关闭</el-button>
      </header>

      <div v-if="!job" class="fj-setup">
        <div class="fj-count">
          <strong>{{ problemIds.length }}</strong>
          <span>道题目</span>
        </div>
        <p class="fj-setup-desc">
          每题一次 AI 调用，2 个并发执行，通常 30 题在 10–20 分钟内跑完。任务在服务端执行，关闭页面不会中断。
        </p>
        <div class="fj-option">
          <div>
            <div class="fj-option-title">无阻断问题时自动应用</div>
            <div class="fj-option-desc">
              未自动应用的原因是：题名为空/超长、正文为空或明显被截断、正文残留时限声明、代码块未闭合、样例减少或重复；这些题目仍会生成 AI 整理版本。
            </div>
          </div>
          <el-switch v-model="autoApply" />
        </div>
        <div class="fj-option">
          <div>
            <div class="fj-option-title">IP 改名</div>
            <div class="fj-option-desc">把题面里的人名、地名、机构等替换为睿思星球风格名称，与整理同一次调用完成。</div>
          </div>
          <el-switch v-model="ipMode" />
        </div>
        <p class="fj-tip">生成后可在任务里逐题打开工作台核对，只写入你勾选的字段。</p>
      </div>

      <div v-else class="fj-body">
        <div class="fj-summary">
          <el-tag :type="jobTagType" effect="dark" size="small">{{ jobStatusLabel }}</el-tag>
          <span class="fj-progress-text">{{ job.processed }} / {{ job.total }}</span>
          <span class="fj-summary-detail">
            已应用 {{ job.applied }} · 仅生成版本 {{ job.review }} · 失败 {{ job.failed }} · 跳过 {{ job.skipped }}
          </span>
        </div>
        <el-progress
          :percentage="percent"
          :status="job.status === 'FAILED' ? 'exception' : job.status === 'DONE' ? 'success' : undefined"
          :stroke-width="8"
        />
        <p v-if="job.message" class="fj-message">{{ job.message }}</p>
        <p v-if="pausedText" class="fj-message is-warn">{{ pausedText }}</p>

        <div class="fj-items">
          <button
            v-for="item in job.items"
            :key="item.problemId"
            type="button"
            class="fj-item"
            :class="{ 'is-clickable': canOpen(item) }"
            @click="openItem(item)"
          >
            <span class="fj-item-id">#{{ item.problemId }}</span>
            <span class="fj-item-title" :title="item.title">{{ item.title || '（无题名）' }}</span>
            <span v-if="item.issueErrors" class="fj-item-issue is-error">{{ item.issueErrors }} 阻断</span>
            <span v-else-if="item.issueWarnings" class="fj-item-issue is-warn">{{ item.issueWarnings }} 提醒</span>
            <el-tag size="small" :type="itemTagType(item)" effect="light">{{ itemStatusLabel(item) }}</el-tag>
            <el-icon v-if="canOpen(item)" class="fj-item-arrow"><ArrowRight /></el-icon>
          </button>
        </div>
      </div>

      <footer class="fj-foot">
        <div class="fj-foot-info">
          <span v-if="job && isActive">任务在服务端继续执行，可随时关闭本面板</span>
          <span v-else-if="job && job.failed">{{ job.failed }} 道题失败，可只重试这些题目</span>
          <span v-else-if="!job">开始后会自动打开进度</span>
        </div>
        <div class="fj-foot-actions">
          <el-button v-if="job && isActive" :loading="canceling" @click="cancel">取消任务</el-button>
          <el-button v-if="job && !isActive && job.failed" type="warning" plain :loading="retrying" @click="retry">
            重试失败项（{{ job.failed }}）
          </el-button>
          <el-button v-if="!job" type="primary" :loading="creating" :disabled="!problemIds.length" @click="create">
            开始整理
          </el-button>
          <el-button v-else type="primary" @click="close">完成</el-button>
        </div>
      </footer>
    </div>
  </el-drawer>
</template>

<script setup>
// 批量优化任务面板：创建 → 进度 → 逐项处理。
//
// 为什么不在前端串行调用单题接口：AI 调用单次可能 60 秒以上，30 道题会让页面
// 悬挂十几分钟，关掉标签页就全丢。任务放服务端执行，这里只轮询状态。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import {
  cancelFormatJob,
  createFormatJob,
  getActiveFormatJob,
  getFormatJob,
  retryFormatJob
} from '@/api/problem'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  problemIds: { type: Array, default: () => [] },
  jobId: { type: [Number, String], default: null }
})

const emit = defineEmits(['update:modelValue', 'open-item', 'finished'])

const POLL_INTERVAL_MS = 2000
const ACTIVE_STATUS = new Set(['PENDING', 'RUNNING'])

const job = ref(null)
const creating = ref(false)
const canceling = ref(false)
const retrying = ref(false)
const autoApply = ref(true)
const ipMode = ref(false)
const pausedText = ref('')

let timer = null
let generation = 0
let disposed = false

const isActive = computed(() => ACTIVE_STATUS.has(job.value?.status))
const percent = computed(() => {
  if (!job.value?.total) return 0
  return Math.min(100, Math.round((job.value.processed / job.value.total) * 100))
})
const headerSub = computed(() => {
  if (!job.value) return `已选择 ${props.problemIds.length} 道题目`
  return `任务 #${job.value.id} · ${job.value.ipMode ? '已开启 IP 改名' : '保留原始命名'} · ${job.value.autoApply ? '自动应用无阻断结果' : '全部人工确认'}`
})
const jobTagType = computed(() => ({
  PENDING: 'info', RUNNING: 'primary', DONE: 'success', CANCELED: 'warning', FAILED: 'danger'
}[job.value?.status] || 'info'))
const jobStatusLabel = computed(() => ({
  PENDING: '排队中', RUNNING: '执行中', DONE: '已完成', CANCELED: '已取消', FAILED: '执行失败'
}[job.value?.status] || ''))

function itemTagType(item) {
  return {
    PENDING: 'info', RUNNING: 'primary', APPLIED: 'success', REVIEW: 'warning', FAILED: 'danger', SKIPPED: 'info'
  }[item.status] || 'info'
}

function itemStatusLabel(item) {
  return {
    PENDING: '等待', RUNNING: '执行中', APPLIED: '已应用', REVIEW: '已生成版本', FAILED: '失败', SKIPPED: '已跳过'
  }[item.status] || item.status
}

function canOpen(item) {
  return Boolean(item.hasProposal) && ['REVIEW'].includes(item.status)
}

function openItem(item) {
  if (!canOpen(item)) return
  // 打开题面工作区并载入该候选版本；没有版本 id 时退化为打开题目
  emit('open-item', {
    jobId: job.value.id,
    problemId: item.problemId,
    versionId: item.resultVersionId || null
  })
}

function close() {
  emit('update:modelValue', false)
}

function stopPolling() {
  generation += 1
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

function schedule(jobId, currentGeneration) {
  if (disposed || currentGeneration !== generation) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => poll(jobId, currentGeneration), POLL_INTERVAL_MS)
}

async function poll(jobId, currentGeneration) {
  if (disposed || currentGeneration !== generation) return
  if (document.hidden) {
    pausedText.value = '页面已隐藏，自动刷新已暂停；返回后会立即获取最新进度。'
    return
  }
  try {
    const res = await getFormatJob(jobId, { silent: true })
    if (disposed || currentGeneration !== generation) return
    adopt(res?.data ?? res)
  } catch (error) {
    if (disposed || currentGeneration !== generation) return
    pausedText.value = `自动刷新已暂停：${error?.response?.data?.message || error?.message || '网络异常'}`
  }
}

function adopt(data) {
  if (!data) return
  job.value = data
  pausedText.value = ''
  if (ACTIVE_STATUS.has(data.status)) {
    schedule(data.id, generation)
    return
  }
  stopPolling()
  emit('finished', data)
}

async function loadActive() {
  try {
    const res = await getActiveFormatJob({ silent: true })
    const data = res?.data ?? res
    if (data?.id) {
      adopt(data)
      return true
    }
  } catch {
    // 没有激活任务或网络异常都按「可以新建」处理，创建失败时会给出明确提示
  }
  return false
}

async function create() {
  if (!props.problemIds.length) return
  creating.value = true
  try {
    const res = await createFormatJob(props.problemIds, { ipMode: ipMode.value, autoApply: autoApply.value })
    const data = res?.data ?? res
    stopPolling()
    adopt(data)
  } catch (error) {
    showApiError(error, '创建整理任务失败')
  } finally {
    creating.value = false
  }
}

async function cancel() {
  try {
    await ElMessageBox.confirm(
      '取消后，尚未处理的题目会被跳过；已经应用的题目不会回滚。确定取消吗？',
      '取消整理任务',
      { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '继续处理' }
    )
  } catch {
    return
  }
  canceling.value = true
  try {
    const res = await cancelFormatJob(job.value.id)
    adopt(res?.data ?? res)
    ElMessage.success('已请求取消，正在停止')
  } catch (error) {
    showApiError(error, '取消失败')
  } finally {
    canceling.value = false
  }
}

async function retry() {
  retrying.value = true
  try {
    const res = await retryFormatJob(job.value.id)
    stopPolling()
    adopt(res?.data ?? res)
    ElMessage.success('已重新提交失败项')
  } catch (error) {
    showApiError(error, '重试失败')
  } finally {
    retrying.value = false
  }
}

function handleVisibility() {
  if (document.hidden) return
  if (job.value && ACTIVE_STATUS.has(job.value.status)) {
    pausedText.value = ''
    poll(job.value.id, generation)
  }
}

watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) {
      stopPolling()
      return
    }
    job.value = null
    pausedText.value = ''
    if (props.jobId) {
      try {
        const res = await getFormatJob(props.jobId)
        adopt(res?.data ?? res)
      } catch (error) {
        showApiError(error, '任务加载失败')
      }
      return
    }
    // 有进行中的任务就直接接回，避免用户以为任务丢了
    await loadActive()
  }
)

if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', handleVisibility)
}

onBeforeUnmount(() => {
  disposed = true
  stopPolling()
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', handleVisibility)
  }
})

defineExpose({ reload: () => (job.value ? getFormatJob(job.value.id).then((res) => adopt(res?.data ?? res)) : null) })
</script>

<style scoped>
.fj { display: flex; flex-direction: column; height: 100%; background: var(--bg-surface); }

.fj-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border-light);
}
.fj-eyebrow { margin-bottom: 6px; }
.fj-badge {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-primary);
  background: var(--bg-surface-hover);
  border: 1px solid var(--border-light);
  border-radius: 999px;
  padding: 2px 10px;
}
.fj-title { margin: 0; font-size: 19px; color: var(--text-primary); }
.fj-sub { margin: 6px 0 0; font-size: 12.5px; color: var(--text-secondary); }

.fj-setup { flex: 1; overflow-y: auto; padding: 20px; }
.fj-count { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; }
.fj-count strong { font-size: 34px; color: var(--text-primary); line-height: 1; }
.fj-count span { font-size: 13px; color: var(--text-secondary); }
.fj-setup-desc { margin: 0 0 18px; font-size: 13px; line-height: 1.7; color: var(--text-regular); }
.fj-option {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 14px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  margin-bottom: 12px;
  background: var(--bg-app);
}
.fj-option-title { font-size: 14px; color: var(--text-primary); font-weight: 600; }
.fj-option-desc { margin-top: 4px; font-size: 12px; line-height: 1.6; color: var(--text-secondary); }
.fj-tip { font-size: 12px; color: var(--text-secondary); }

.fj-body { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 16px 20px; overflow: hidden; }
.fj-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.fj-progress-text { font-size: 13px; font-weight: 600; color: var(--text-primary); font-variant-numeric: tabular-nums; }
.fj-summary-detail { font-size: 12px; color: var(--text-secondary); }
.fj-message { margin: 10px 0 0; font-size: 12px; color: var(--text-secondary); }
.fj-message.is-warn { color: var(--el-color-warning-dark-2); }

.fj-items { margin-top: 14px; overflow-y: auto; border-top: 1px solid var(--border-light); }
.fj-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 6px;
  border: none;
  border-bottom: 1px solid var(--border-light);
  background: transparent;
  text-align: left;
  font-size: 13px;
  color: var(--text-regular);
  cursor: default;
}
.fj-item.is-clickable { cursor: pointer; }
.fj-item.is-clickable:hover { background: var(--bg-surface-hover); }
.fj-item-id { color: var(--text-secondary); font-variant-numeric: tabular-nums; width: 56px; flex-shrink: 0; }
.fj-item-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fj-item-issue { font-size: 11px; }
.fj-item-issue.is-error { color: var(--el-color-danger); }
.fj-item-issue.is-warn { color: var(--el-color-warning-dark-2); }
.fj-item-arrow { color: var(--text-secondary); }

.fj-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-top: 1px solid var(--border-light);
}
.fj-foot-info { font-size: 12px; color: var(--text-secondary); }
.fj-foot-actions { display: flex; gap: 8px; }

.dark .fj-badge { color: #7ea6ff; border-color: #2f4a85; }
.dark .fj-message.is-warn { color: #d29922; }
.dark .fj-item-issue.is-warn { color: #d29922; }
</style>

<style>
.format-job-panel .el-drawer__body { padding: 0; display: flex; flex-direction: column; overflow: hidden; }
</style>
