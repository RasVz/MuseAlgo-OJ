<template>
  <div class="sv">
    <div class="sv-toolbar">
      <div class="sv-toolbar-info">
        <strong>{{ versions.length }}</strong> 个版本
        <template v-if="liveVersion"> · 当前使用 v{{ liveVersion.versionNo }}</template>
        <template v-if="candidateCount"> · {{ candidateCount }} 个待审阅</template>
        <span v-if="dirtyHint" class="sv-dirty">{{ dirtyHint }}</span>
      </div>
      <div class="sv-toolbar-actions">
        <label class="sv-ip">
          <el-switch v-model="ipMode" size="small" :disabled="starting" />
          <span>IP 改名</span>
        </label>
        <el-button size="small" :loading="recording" @click="recordCurrent">记录当前题面</el-button>
        <el-button
          type="primary"
          size="small"
          :icon="MagicStick"
          :loading="starting"
          @click="optimize(selectedVersion || liveVersion)"
        >
          基于所选版本优化
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="liveVersion?.drifted"
      class="sv-alert"
      type="warning"
      :closable="false"
      show-icon
      title="当前题面有未记录的修改"
      description="题面在基本信息里被改过，还没有记录成版本。记录后才会出现在时间线上；不记录也不影响使用。"
    >
      <template #default>
        <el-button size="small" :loading="recording" @click="recordCurrent">记录为新版本</el-button>
      </template>
    </el-alert>

    <div v-if="runningJob" class="sv-running">
      <div class="sv-running-main">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>AI 正在基于 <strong>v{{ baseVersionNo }}</strong> 整理题面，可离开此页面，任务在服务端继续执行。</span>
      </div>
      <el-progress class="sv-running-progress" :percentage="jobPercent" :stroke-width="6" :show-text="false" />
    </div>

    <div class="sv-body">
      <aside class="sv-timeline" v-loading="loading">
        <button
          v-for="version in versions"
          :key="version.id"
          type="button"
          class="sv-item"
          :class="{ active: version.id === selectedId, live: version.live }"
          @click="selectVersion(version.id)"
        >
          <span class="sv-item-no">v{{ version.versionNo }}</span>
          <span class="sv-item-main">
            <span class="sv-item-title">{{ version.title || '（无题名）' }}</span>
            <span class="sv-item-meta">{{ sourceLabel(version.source) }} · {{ formatTime(version.createTime) }}</span>
            <span class="sv-item-summary">{{ version.summary || '（空正文）' }}</span>
          </span>
          <span class="sv-item-badges">
            <el-tag v-if="version.live" size="small" type="success" effect="light">当前</el-tag>
            <el-tag v-else-if="version.status === 'CANDIDATE'" size="small" type="warning" effect="light">待审</el-tag>
            <el-tag v-else-if="version.status === 'DISCARDED'" size="small" type="info" effect="plain">已丢弃</el-tag>
            <span v-if="version.issueErrors" class="sv-dot is-error">{{ version.issueErrors }}</span>
            <span v-else-if="version.issueWarnings" class="sv-dot is-warn">{{ version.issueWarnings }}</span>
          </span>
        </button>
        <el-empty v-if="!loading && !versions.length" description="还没有版本" :image-size="64" />
      </aside>

      <section class="sv-detail" v-loading="detailLoading">
        <template v-if="detail">
          <div class="sv-detail-head">
            <div>
              <h3 class="sv-detail-title">v{{ detail.versionNo }} · {{ detail.title }}</h3>
              <div class="sv-detail-meta">
                <el-tag size="small" :type="statusTagType(detail.status)" effect="light">{{ statusLabel(detail.status) }}</el-tag>
                <span>{{ sourceLabel(detail.source) }}</span>
                <span>{{ formatTime(detail.createTime) }}</span>
                <span v-if="detail.baseVersionId">基线 v{{ versionNoOf(detail.baseVersionId) }}</span>
              </div>
            </div>
            <div class="sv-detail-actions">
              <el-button v-if="detail.status === 'CANDIDATE'" type="primary" :icon="MagicStick" @click="openReview(detail)">
                审阅并应用
              </el-button>
              <el-button v-else type="primary" plain :icon="MagicStick" :loading="starting" @click="optimize(detail)">
                基于此版本优化
              </el-button>
              <el-button
                v-if="detail.status !== 'LIVE' && detail.status !== 'CANDIDATE'"
                :loading="activating"
                @click="activate(detail)"
              >
                设为当前版本
              </el-button>
              <el-button v-if="detail.status === 'CANDIDATE'" type="danger" plain @click="discard(detail)">丢弃</el-button>
            </div>
          </div>

          <div v-if="detailIssues.length" class="sv-issues">
            <div v-for="issue in detailIssues" :key="issue.code + issue.message" class="sv-issue" :class="issue.severity.toLowerCase()">
              <span class="sv-issue-dot" />
              <span>{{ issue.message }}</span>
              <code>{{ issue.code }}</code>
            </div>
          </div>

          <div class="sv-facts">
            <div class="sv-fact"><span>时限</span><strong>{{ detail.timeLimit }} ms</strong></div>
            <div class="sv-fact"><span>内存</span><strong>{{ detail.memoryLimit }} MB</strong></div>
            <div class="sv-fact"><span>样例</span><strong>{{ detail.samples?.length || 0 }} 组</strong></div>
            <div class="sv-fact"><span>正文</span><strong>{{ (detail.content || '').length }} 字</strong></div>
          </div>

          <div class="sv-sections">
            <div class="sv-section">
              <h4>题目正文</h4>
              <div class="markdown-body sv-markdown" v-html="render(detail.content)" />
            </div>
            <div class="sv-section">
              <h4>输入描述</h4>
              <div class="markdown-body sv-markdown" v-html="render(detail.inputDesc)" />
            </div>
            <div class="sv-section">
              <h4>输出描述</h4>
              <div class="markdown-body sv-markdown" v-html="render(detail.outputDesc)" />
            </div>
            <div v-if="detail.hint" class="sv-section">
              <h4>提示</h4>
              <div class="markdown-body sv-markdown" v-html="render(detail.hint)" />
            </div>
            <div class="sv-section">
              <h4>样例</h4>
              <div v-if="detail.samples?.length" class="sv-samples">
                <div v-for="(sample, index) in detail.samples" :key="index" class="sv-sample">
                  <div class="sv-sample-title">样例 #{{ index + 1 }}</div>
                  <pre class="sv-code">{{ sample.input || '（空）' }}</pre>
                  <pre class="sv-code">{{ sample.output || '（空）' }}</pre>
                </div>
              </div>
              <p v-else class="sv-empty">该版本没有样例</p>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="选择左侧的一个版本查看内容" />
      </section>
    </div>

    <ProblemFormatWorkbench
      v-model="workbenchVisible"
      :key="reviewVersionId || 'none'"
      :problem-id="Number(problemId)"
      :version-id="reviewVersionId"
      @applied="handleApplied"
    />
  </div>
</template>

<script setup>
// 题面版本面板：编辑页「题面与版本」Tab 的内容。
//
// 版本是题面唯一的可回溯记录：AI 整理的结果先成为「待审候选」，人工勾选字段后
// 才提升为当前版本。把面板放进编辑页而不是单独开一个页面，是为了让「改题面」
// 只有一个入口——基本信息编辑当前内容，这里管理历史与 AI 整理，两者互不跳转。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, MagicStick } from '@element-plus/icons-vue'
import {
  activateStatementVersion,
  discardStatementVersion,
  getActiveFormatJob,
  getFormatJob,
  getStatementVersion,
  listStatementVersions,
  optimizeStatementVersion,
  recordCurrentStatement
} from '@/api/problem'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'
import { showApiError } from '@/utils/errorFeedback'
import ProblemFormatWorkbench from './ProblemFormatWorkbench.vue'

const props = defineProps({
  problemId: { type: [Number, String], default: null },
  /** 基本信息是否有未保存修改：有的话在应用版本前提醒会被覆盖 */
  dirty: { type: Boolean, default: false }
})

const emit = defineEmits(['changed'])

const md = createMarkdown({ html: true, math: true })

const versions = ref([])
const detail = ref(null)
const selectedId = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const recording = ref(false)
const activating = ref(false)
const starting = ref(false)
const runningJob = ref(null)
const baseVersionNo = ref(null)
const ipMode = ref(false)
const workbenchVisible = ref(false)
const reviewVersionId = ref(null)

let pollTimer = null
let pollGeneration = 0

const liveVersion = computed(() => versions.value.find((version) => version.live) || null)
const selectedVersion = computed(() => versions.value.find((version) => version.id === selectedId.value) || null)
const candidateCount = computed(() => versions.value.filter((version) => version.status === 'CANDIDATE').length)
const detailIssues = computed(() => (Array.isArray(detail.value?.issues) ? detail.value.issues : []))
const jobPercent = computed(() => {
  const job = runningJob.value
  if (!job || !job.total) return 8
  return Math.min(99, Math.round((job.processed / job.total) * 100))
})
const dirtyHint = computed(() => (props.dirty ? '· 基本信息有未保存修改' : ''))

const SOURCE_LABELS = {
  INITIAL: '初始题面',
  MANUAL: '手工记录',
  AI: 'AI 整理',
  AI_APPLIED: 'AI 整理已应用'
}
const STATUS_LABELS = {
  LIVE: '当前使用',
  ARCHIVED: '历史版本',
  CANDIDATE: '待审阅',
  DISCARDED: '已丢弃'
}

function sourceLabel(source) {
  return SOURCE_LABELS[source] || source || '未知来源'
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

function statusTagType(status) {
  return { LIVE: 'success', CANDIDATE: 'warning', ARCHIVED: 'info', DISCARDED: 'info' }[status] || 'info'
}

function versionNoOf(versionId) {
  return versions.value.find((version) => version.id === versionId)?.versionNo ?? '?'
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

function render(text) {
  if (!text || !String(text).trim()) return '<p class="sv-empty">（空）</p>'
  return md.render(sanitizeStatementMarkup(String(text)))
}

async function loadVersions({ keepSelection = true } = {}) {
  if (!props.problemId) return
  loading.value = true
  try {
    const res = await listStatementVersions(props.problemId)
    versions.value = Array.isArray(res?.data) ? res.data : []
    const stillThere = versions.value.some((version) => version.id === selectedId.value)
    if (!keepSelection || !stillThere) {
      const preferred = versions.value.find((version) => version.status === 'CANDIDATE')
        || liveVersion.value || versions.value[0]
      selectedId.value = preferred?.id || null
    }
    if (selectedId.value) await loadDetail(selectedId.value)
    else detail.value = null
  } catch (error) {
    showApiError(error, '题面版本加载失败')
  } finally {
    loading.value = false
  }
}

async function loadDetail(versionId) {
  detailLoading.value = true
  try {
    const res = await getStatementVersion(props.problemId, versionId)
    detail.value = res?.data ?? null
  } catch (error) {
    showApiError(error, '版本详情加载失败')
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function selectVersion(versionId) {
  if (versionId === selectedId.value) return
  selectedId.value = versionId
  await loadDetail(versionId)
}

async function recordCurrent() {
  recording.value = true
  try {
    const res = await recordCurrentStatement(props.problemId)
    ElMessage.success('已记录为新版本')
    emit('changed')
    await loadVersions({ keepSelection: false })
    if (res?.data?.id) {
      selectedId.value = res.data.id
      await loadDetail(res.data.id)
    }
  } catch (error) {
    showApiError(error, '记录失败')
  } finally {
    recording.value = false
  }
}

async function optimize(version) {
  if (!version) {
    ElMessage.warning('请先选择一个版本')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将以 v${version.versionNo} 为基线调用 AI 整理题面${ipMode.value ? '（同时做 IP 改名）' : ''}，`
      + '约需 20~150 秒。任务在服务端执行，可以离开页面；生成后会作为「待审阅」版本出现在左侧，不会自动改动题目。',
      `基于 v${version.versionNo} 优化`,
      { confirmButtonText: '开始整理', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  starting.value = true
  try {
    const res = await optimizeStatementVersion(props.problemId, version.id, ipMode.value)
    baseVersionNo.value = version.versionNo
    runningJob.value = res?.data ?? null
    if (runningJob.value?.id) startPolling(runningJob.value.id)
  } catch (error) {
    showApiError(error, '发起整理失败')
  } finally {
    starting.value = false
  }
}

function startPolling(jobId) {
  stopPolling()
  const generation = pollGeneration
  const tick = async () => {
    if (generation !== pollGeneration) return
    if (document.hidden) {
      pollTimer = setTimeout(tick, 2000)
      return
    }
    try {
      const res = await getFormatJob(jobId, { silent: true })
      const job = res?.data ?? res
      if (generation !== pollGeneration) return
      runningJob.value = job
      if (job && !['PENDING', 'RUNNING'].includes(job.status)) {
        stopPolling()
        await handleJobFinished(job)
        return
      }
    } catch {
      // 网络抖动不终止轮询，下一轮继续
    }
    pollTimer = setTimeout(tick, 2000)
  }
  pollTimer = setTimeout(tick, 2000)
}

function stopPolling() {
  pollGeneration += 1
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function handleJobFinished(job) {
  const finished = job?.data ?? job
  runningJob.value = null
  const resultVersionId = finished?.items?.[0]?.resultVersionId || null
  await loadVersions()
  if (resultVersionId) {
    selectedId.value = resultVersionId
    await loadDetail(resultVersionId)
    if (!document.hidden) {
      reviewVersionId.value = resultVersionId
      workbenchVisible.value = true
    } else {
      ElMessage.success('AI 整理完成，候选版本已生成，回来后可继续审阅')
    }
  } else if (finished?.status === 'FAILED') {
    ElMessage.error(finished.message || '整理失败，请重试')
  }
}

async function resumeActiveJob() {
  try {
    const res = await getActiveFormatJob({ silent: true })
    const job = res?.data ?? res
    if (!job || job.items?.[0]?.problemId !== Number(props.problemId)) return
    baseVersionNo.value = versionNoOf(job.items[0].baseVersionId)
    runningJob.value = job
    startPolling(job.id)
  } catch {
    // 没有进行中的任务属于正常情况
  }
}

function openReview(version) {
  reviewVersionId.value = version.id
  workbenchVisible.value = true
}

async function handleApplied() {
  await loadVersions()
  emit('changed')
}

async function activate(version) {
  try {
    await ElMessageBox.confirm(
      `将把 v${version.versionNo} 的内容写回题目，当前版本会转为历史版本。`
      + '题面、样例、时限与内存都会以该版本为准。',
      '设为当前版本',
      { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  activating.value = true
  try {
    await activateStatementVersion(props.problemId, version.id)
    ElMessage.success('已切换当前版本')
    await loadVersions()
    emit('changed')
  } catch (error) {
    showApiError(error, '切换失败')
  } finally {
    activating.value = false
  }
}

async function discard(version) {
  try {
    await ElMessageBox.confirm('丢弃后该候选版本会标记为已丢弃，不会影响题目内容。', '丢弃候选版本', {
      type: 'warning',
      confirmButtonText: '丢弃',
      cancelButtonText: '保留'
    })
  } catch {
    return
  }
  try {
    await discardStatementVersion(props.problemId, version.id)
    ElMessage.success('已丢弃')
    await loadVersions()
  } catch (error) {
    showApiError(error, '丢弃失败')
  }
}

watch(
  () => props.problemId,
  (id) => {
    selectedId.value = null
    detail.value = null
    if (!id) {
      versions.value = []
      return
    }
    loadVersions({ keepSelection: false }).then(resumeActiveJob)
  },
  { immediate: true }
)

onBeforeUnmount(stopPolling)

defineExpose({ reload: () => loadVersions() })
</script>

<style scoped>
.sv { display: flex; flex-direction: column; gap: 12px; }

.sv-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.sv-toolbar-info { font-size: 13px; color: var(--text-secondary); }
.sv-toolbar-info strong { color: var(--text-primary); font-size: 15px; }
.sv-dirty { color: var(--el-color-warning-dark-2); margin-left: 6px; }
.sv-toolbar-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sv-ip { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--text-regular); }

.sv-alert { margin: 0; }
.sv-running {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  font-size: 13px;
  color: var(--text-regular);
}
.sv-running-main { display: flex; align-items: center; gap: 8px; }
.sv-running-progress { flex: 1; max-width: 280px; }

.sv-body { display: grid; grid-template-columns: 320px 1fr; gap: 14px; align-items: start; }

.sv-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 10px;
  max-height: 640px;
  overflow-y: auto;
}
.sv-item {
  display: flex;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  padding: 9px 10px;
  cursor: pointer;
  color: var(--text-regular);
}
.sv-item:hover { background: var(--bg-surface-hover); }
.sv-item.active {
  background: var(--bg-surface-hover);
  border-color: var(--border-base);
  box-shadow: inset 2px 0 0 var(--accent-primary);
}
.sv-item-no { font-size: 12px; font-weight: 700; color: var(--text-primary); width: 30px; flex-shrink: 0; }
.sv-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.sv-item-title {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sv-item-meta { font-size: 11px; color: var(--text-secondary); }
.sv-item-summary {
  font-size: 11.5px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sv-item-badges { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.sv-dot {
  min-width: 16px;
  height: 16px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  padding: 0 4px;
  color: #fff;
}
.sv-dot.is-error { background: var(--el-color-danger); }
.sv-dot.is-warn { background: var(--el-color-warning); }

.sv-detail {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 16px 20px 22px;
  min-height: 320px;
}
.sv-detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}
.sv-detail-title { margin: 0; font-size: 16px; color: var(--text-primary); }
.sv-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.sv-detail-actions { display: flex; gap: 8px; flex-shrink: 0; flex-wrap: wrap; }

.sv-issues { display: flex; flex-direction: column; gap: 6px; margin: 12px 0; }
.sv-issue {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  padding: 7px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  color: var(--text-regular);
}
.sv-issue-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; }
.sv-issue.error { background: rgba(220, 38, 38, 0.08); border-color: rgba(220, 38, 38, 0.35); }
.sv-issue.error .sv-issue-dot { background: var(--el-color-danger); }
.sv-issue.warn { background: rgba(217, 119, 6, 0.08); border-color: rgba(217, 119, 6, 0.35); }
.sv-issue.warn .sv-issue-dot { background: var(--el-color-warning); }
.sv-issue.info .sv-issue-dot { background: var(--el-color-info); }
.sv-issue code { font-size: 11px; color: var(--text-secondary); }

.sv-facts { display: flex; gap: 10px; flex-wrap: wrap; margin: 14px 0 6px; }
.sv-fact {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 14px;
  border-radius: 8px;
  background: var(--bg-app);
  border: 1px solid var(--border-light);
}
.sv-fact span { font-size: 11px; color: var(--text-secondary); }
.sv-fact strong { font-size: 14px; color: var(--text-primary); }

.sv-sections { display: flex; flex-direction: column; gap: 18px; margin-top: 16px; }
.sv-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--text-primary);
  border-left: 3px solid var(--accent-primary);
  padding-left: 8px;
}
.sv-markdown { font-size: 14px; line-height: 1.75; color: var(--text-regular); }
.sv-samples { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.sv-sample { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; }
.sv-sample-title { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.sv-code {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--bg-app);
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-regular);
}
.sv-empty { color: var(--text-secondary); font-size: 13px; }

.dark .sv-item.active { background: #1c2129; border-color: #2f4a85; box-shadow: inset 2px 0 0 #58a6ff; }
.dark .sv-issue.error { background: rgba(248, 81, 73, 0.12); border-color: rgba(248, 81, 73, 0.35); }
.dark .sv-issue.warn { background: rgba(210, 153, 34, 0.12); border-color: rgba(210, 153, 34, 0.35); }
.dark .sv-dirty { color: #d29922; }

@media (max-width: 1100px) {
  .sv-body { grid-template-columns: 1fr; }
  .sv-timeline { max-height: 320px; }
}
</style>
