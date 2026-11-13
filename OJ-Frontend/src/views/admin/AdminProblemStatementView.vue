<template>
  <div class="page-container statement-page admin-scroll-page">
    <header class="sp-head">
      <div class="sp-head-main">
        <div class="sp-crumb">
          <el-button link @click="goBack">题库管理</el-button>
          <span class="sp-crumb-sep">/</span>
          <span>题面版本</span>
        </div>
        <h1 class="sp-title">
          <span class="sp-id">#{{ problemId }}</span>
          {{ problemTitle || '题面版本' }}
        </h1>
        <p class="sp-sub">
          {{ versions.length }} 个版本
          <template v-if="liveVersion"> · 当前使用 v{{ liveVersion.versionNo }}</template>
          <template v-if="candidateCount"> · {{ candidateCount }} 个待审阅</template>
        </p>
      </div>
      <div class="sp-head-actions">
        <label class="sp-ip">
          <el-switch v-model="ipMode" size="small" :disabled="starting" />
          <span>IP 改名</span>
        </label>
        <el-button :icon="EditPen" @click="goEdit">编辑题目</el-button>
        <el-button :loading="recording" @click="recordCurrent">记录当前题面</el-button>
        <el-button type="primary" :icon="MagicStick" :loading="starting" @click="optimize(selectedVersion || liveVersion)">
          基于所选版本优化
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="liveVersion?.drifted"
      class="sp-alert"
      type="warning"
      :closable="false"
      show-icon
      title="当前题面在编辑器里被改过，还没有记录成版本"
      description="记录后才会出现在时间线上；不记录也不影响使用，只是历史里查不到这次修改。"
    >
      <template #default>
        <el-button size="small" :loading="recording" @click="recordCurrent">记录为新版本</el-button>
      </template>
    </el-alert>

    <div v-if="runningJob" class="sp-running">
      <div class="sp-running-main">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>
          AI 正在基于 <strong>v{{ baseVersionNo }}</strong> 整理题面，可离开此页面，任务在服务端继续执行。
        </span>
      </div>
      <el-progress
        class="sp-running-progress"
        :percentage="jobPercent"
        :stroke-width="6"
        :show-text="false"
      />
    </div>

    <div class="sp-body">
      <aside class="sp-timeline" v-loading="loading">
        <button
          v-for="version in versions"
          :key="version.id"
          type="button"
          class="sp-item"
          :class="{ active: version.id === selectedId, live: version.live, candidate: version.status === 'CANDIDATE' }"
          @click="selectVersion(version.id)"
        >
          <span class="sp-item-no">v{{ version.versionNo }}</span>
          <span class="sp-item-main">
            <span class="sp-item-title">{{ version.title || '（无题名）' }}</span>
            <span class="sp-item-meta">{{ sourceLabel(version.source) }} · {{ formatTime(version.createTime) }}</span>
            <span class="sp-item-summary">{{ version.summary || '（空正文）' }}</span>
          </span>
          <span class="sp-item-badges">
            <el-tag v-if="version.live" size="small" type="success" effect="light">当前</el-tag>
            <el-tag v-else-if="version.status === 'CANDIDATE'" size="small" type="warning" effect="light">待审</el-tag>
            <el-tag v-else-if="version.status === 'DISCARDED'" size="small" type="info" effect="plain">已丢弃</el-tag>
            <span v-if="version.issueErrors" class="sp-dot is-error">{{ version.issueErrors }}</span>
            <span v-else-if="version.issueWarnings" class="sp-dot is-warn">{{ version.issueWarnings }}</span>
          </span>
        </button>
        <el-empty v-if="!loading && !versions.length" description="还没有版本" :image-size="64" />
      </aside>

      <section class="sp-detail" v-loading="detailLoading">
        <template v-if="detail">
          <div class="sp-detail-head">
            <div>
              <h2 class="sp-detail-title">v{{ detail.versionNo }} · {{ detail.title }}</h2>
              <div class="sp-detail-meta">
                <el-tag size="small" :type="statusTagType(detail.status)" effect="light">{{ statusLabel(detail.status) }}</el-tag>
                <span>{{ sourceLabel(detail.source) }}</span>
                <span>{{ formatTime(detail.createTime) }}</span>
                <span v-if="detail.baseVersionId">基线 v{{ versionNoOf(detail.baseVersionId) }}</span>
              </div>
            </div>
            <div class="sp-detail-actions">
              <el-button
                v-if="detail.status === 'CANDIDATE'"
                type="primary"
                :icon="MagicStick"
                @click="openReview(detail)"
              >
                审阅并应用
              </el-button>
              <el-button
                v-else
                type="primary"
                plain
                :icon="MagicStick"
                :loading="starting"
                @click="optimize(detail)"
              >
                基于此版本优化
              </el-button>
              <el-button
                v-if="detail.status !== 'LIVE' && detail.status !== 'CANDIDATE'"
                :loading="activating"
                @click="activate(detail)"
              >
                设为当前版本
              </el-button>
              <el-button v-if="detail.status === 'CANDIDATE'" type="danger" plain @click="discard(detail)">
                丢弃
              </el-button>
            </div>
          </div>

          <div v-if="detailIssues.length" class="sp-issues">
            <div v-for="issue in detailIssues" :key="issue.code + issue.message" class="sp-issue" :class="issue.severity.toLowerCase()">
              <span class="sp-issue-dot" />
              <span>{{ issue.message }}</span>
              <code>{{ issue.code }}</code>
            </div>
          </div>

          <div class="sp-facts">
            <div class="sp-fact"><span>时限</span><strong>{{ detail.timeLimit }} ms</strong></div>
            <div class="sp-fact"><span>内存</span><strong>{{ detail.memoryLimit }} MB</strong></div>
            <div class="sp-fact"><span>样例</span><strong>{{ detail.samples?.length || 0 }} 组</strong></div>
            <div class="sp-fact"><span>正文</span><strong>{{ (detail.content || '').length }} 字</strong></div>
          </div>

          <div class="sp-sections">
            <div class="sp-section">
              <h3>题目正文</h3>
              <div class="markdown-body sp-markdown" v-html="render(detail.content)" />
            </div>
            <div class="sp-section">
              <h3>输入描述</h3>
              <div class="markdown-body sp-markdown" v-html="render(detail.inputDesc)" />
            </div>
            <div class="sp-section">
              <h3>输出描述</h3>
              <div class="markdown-body sp-markdown" v-html="render(detail.outputDesc)" />
            </div>
            <div v-if="detail.hint" class="sp-section">
              <h3>提示</h3>
              <div class="markdown-body sp-markdown" v-html="render(detail.hint)" />
            </div>
            <div class="sp-section">
              <h3>样例</h3>
              <div v-if="detail.samples?.length" class="sp-samples">
                <div v-for="(sample, index) in detail.samples" :key="index" class="sp-sample">
                  <div class="sp-sample-title">样例 #{{ index + 1 }}</div>
                  <pre class="sp-code">{{ sample.input || '（空）' }}</pre>
                  <pre class="sp-code">{{ sample.output || '（空）' }}</pre>
                </div>
              </div>
              <p v-else class="sp-empty">该版本没有样例</p>
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
// 题面版本页：所有「优化」都从这里发起，先选版本、再异步生成候选、最后审阅应用。
//
// 为什么不在列表页直接调 AI：一次整理 20~150 秒，教师需要看到「基于哪一版改的」、
// 「改成了什么」、「要不要用」。把动作拆到版本页后，等待变成后台任务，
// 每次结果都留下一条可回退的版本记录。
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Loading, MagicStick } from '@element-plus/icons-vue'
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
import ProblemFormatWorkbench from './problemFormat/ProblemFormatWorkbench.vue'

const route = useRoute()
const router = useRouter()
const md = createMarkdown({ html: true, math: true })

const problemId = computed(() => Number(route.params.id))
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
const problemTitle = computed(() => liveVersion.value?.title || versions.value[0]?.title || '')
const detailIssues = computed(() => (Array.isArray(detail.value?.issues) ? detail.value.issues : []))
const jobPercent = computed(() => {
  const job = runningJob.value
  if (!job || !job.total) return 8
  return Math.min(99, Math.round((job.processed / job.total) * 100))
})

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
  if (!text || !String(text).trim()) return '<p class="sp-empty">（空）</p>'
  return md.render(sanitizeStatementMarkup(String(text)))
}

function goBack() {
  router.push('/admin/problem')
}

function goEdit() {
  const url = router.resolve({ path: `/admin/problem/edit/${problemId.value}` })
  window.open(url.href, '_blank')
}

async function loadVersions({ keepSelection = true } = {}) {
  loading.value = true
  try {
    const res = await listStatementVersions(problemId.value)
    versions.value = Array.isArray(res?.data) ? res.data : []
    const stillThere = versions.value.some((version) => version.id === selectedId.value)
    if (!keepSelection || !stillThere) {
      const preferred = versions.value.find((version) => version.status === 'CANDIDATE') || liveVersion.value || versions.value[0]
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
    const res = await getStatementVersion(problemId.value, versionId)
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
    const res = await recordCurrentStatement(problemId.value)
    ElMessage.success('已记录为新版本')
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
    const res = await optimizeStatementVersion(problemId.value, version.id, ipMode.value)
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

/** 进入页面时接回仍在执行的任务：否则用户离开再回来会以为任务丢了。 */
async function resumeActiveJob() {
  try {
    const res = await getActiveFormatJob({ silent: true })
    const job = res?.data ?? res
    if (!job || job.items?.[0]?.problemId !== problemId.value) return
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
}

async function activate(version) {
  try {
    await ElMessageBox.confirm(
      `将把 v${version.versionNo} 的内容写回题目，当前版本会转为历史版本。题面、样例、时限与内存都会以该版本为准。`,
      '设为当前版本',
      { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  activating.value = true
  try {
    await activateStatementVersion(problemId.value, version.id)
    ElMessage.success('已切换当前版本')
    await loadVersions()
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
    await discardStatementVersion(problemId.value, version.id)
    ElMessage.success('已丢弃')
    await loadVersions()
  } catch (error) {
    showApiError(error, '丢弃失败')
  }
}

watch(problemId, () => {
  selectedId.value = null
  detail.value = null
  loadVersions({ keepSelection: false }).then(resumeActiveJob)
}, { immediate: true })

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.statement-page {
  max-width: 1500px;
  margin: 0 auto;
  padding: 16px 20px 24px;
}
.sp-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 14px;
}
.sp-crumb { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.sp-crumb-sep { margin: 0 4px; }
.sp-title { margin: 0; font-size: 22px; color: var(--text-primary); }
.sp-id { color: var(--text-secondary); font-weight: 500; margin-right: 6px; }
.sp-sub { margin: 6px 0 0; font-size: 13px; color: var(--text-secondary); }
.sp-head-actions { display: flex; gap: 8px; flex-shrink: 0; align-items: center; }
.sp-ip { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--text-regular); margin-right: 4px; }
.sp-running-progress { flex: 1; max-width: 320px; }

.sp-alert { margin-bottom: 12px; }
.sp-running {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  margin-bottom: 12px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  font-size: 13px;
  color: var(--text-regular);
}
.sp-running-main { display: flex; align-items: center; gap: 8px; }

.sp-body { display: grid; grid-template-columns: 340px 1fr; gap: 14px; align-items: start; }

.sp-timeline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 10px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
}
.sp-item {
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
.sp-item:hover { background: var(--bg-surface-hover); }
.sp-item.active {
  background: var(--bg-surface-hover);
  border-color: var(--border-base);
  box-shadow: inset 2px 0 0 var(--accent-primary);
}
.sp-item-no { font-size: 12px; font-weight: 700; color: var(--text-primary); width: 30px; flex-shrink: 0; }
.sp-item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.sp-item-title {
  font-size: 13px;
  color: var(--text-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sp-item-meta { font-size: 11px; color: var(--text-secondary); }
.sp-item-summary {
  font-size: 11.5px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sp-item-badges { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.sp-dot {
  min-width: 16px;
  height: 16px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  padding: 0 4px;
  color: #fff;
}
.sp-dot.is-error { background: var(--el-color-danger); }
.sp-dot.is-warn { background: var(--el-color-warning); }

.sp-detail {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 16px 20px 22px;
  min-height: 320px;
}
.sp-detail-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}
.sp-detail-title { margin: 0; font-size: 17px; color: var(--text-primary); }
.sp-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.sp-detail-actions { display: flex; gap: 8px; flex-shrink: 0; flex-wrap: wrap; }

.sp-issues { display: flex; flex-direction: column; gap: 6px; margin: 12px 0; }
.sp-issue {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  padding: 7px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  color: var(--text-regular);
}
.sp-issue-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; }
.sp-issue.error { background: rgba(220, 38, 38, 0.08); border-color: rgba(220, 38, 38, 0.35); }
.sp-issue.error .sp-issue-dot { background: var(--el-color-danger); }
.sp-issue.warn { background: rgba(217, 119, 6, 0.08); border-color: rgba(217, 119, 6, 0.35); }
.sp-issue.warn .sp-issue-dot { background: var(--el-color-warning); }
.sp-issue.info .sp-issue-dot { background: var(--el-color-info); }
.sp-issue code { font-size: 11px; color: var(--text-secondary); }

.sp-facts { display: flex; gap: 10px; flex-wrap: wrap; margin: 14px 0 6px; }
.sp-fact {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 14px;
  border-radius: 8px;
  background: var(--bg-app);
  border: 1px solid var(--border-light);
}
.sp-fact span { font-size: 11px; color: var(--text-secondary); }
.sp-fact strong { font-size: 14px; color: var(--text-primary); }

.sp-sections { display: flex; flex-direction: column; gap: 18px; margin-top: 16px; }
.sp-section h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--text-primary);
  border-left: 3px solid var(--accent-primary);
  padding-left: 8px;
}
.sp-markdown { font-size: 14px; line-height: 1.75; color: var(--text-regular); }
.sp-samples { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.sp-sample { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px 12px; }
.sp-sample-title { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.sp-code {
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
.sp-empty { color: var(--text-secondary); font-size: 13px; }

.dark .sp-item.active { background: #1c2129; border-color: #2f4a85; box-shadow: inset 2px 0 0 #58a6ff; }
.dark .sp-issue.error { background: rgba(248, 81, 73, 0.12); border-color: rgba(248, 81, 73, 0.35); }
.dark .sp-issue.warn { background: rgba(210, 153, 34, 0.12); border-color: rgba(210, 153, 34, 0.35); }

@media (max-width: 1100px) {
  .sp-body { grid-template-columns: 1fr; }
  .sp-timeline { max-height: 320px; }
}
</style>
