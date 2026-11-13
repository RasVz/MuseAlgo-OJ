<template>
  <div class="st">
    <div class="st-head">
      <div class="st-state">
        <el-tag size="small" :type="stateTagType" effect="light">{{ stateLabel }}</el-tag>
        <span v-if="dirty" class="st-dirty">{{ changedKeys.length }} 处改动未保存</span>
        <span v-else class="st-clean">未修改</span>
        <button v-if="issues.length" type="button" class="st-issue-toggle" @click="issueOpen = !issueOpen">
          {{ issueSummary }} {{ issueOpen ? '收起' : '展开' }}
        </button>
      </div>
      <div class="st-actions">
        <el-button
          size="small"
          :icon="MagicStick"
          :loading="optimizing"
          :disabled="loading || optimizing"
          @click="optimize"
        >
          优化这一版
        </el-button>
        <el-button size="small" :disabled="!dirty || saving" @click="discardDraft">放弃修改</el-button>
      </div>
    </div>

    <div v-if="aiDraft" class="st-banner">
      <span class="st-banner-mark">AI 草稿</span>
      <span>AI 已经把内容写进编辑区，还没有成为版本。你可以逐段修改，满意后再保存。</span>
    </div>

    <div v-if="runningJob" class="st-running">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>AI 正在基于 v{{ baseVersionNo }} 生成草稿，可以离开页面，任务在服务端继续。</span>
    </div>

    <div v-if="issueOpen && issues.length" class="st-issues">
      <div v-for="issue in issues" :key="issue.code + issue.message" class="st-issue" :class="issue.severity.toLowerCase()">
        <span class="st-issue-dot" />
        <span>{{ issue.message }}</span>
        <code>{{ issue.code }}</code>
      </div>
    </div>

    <div class="st-body">
      <div class="st-doc" v-loading="loading">
        <section
          v-for="block in BLOCKS"
          :key="block.key"
          class="st-block"
          :class="{ changed: isChanged(block.key), editing: editingKey === block.key }"
        >
          <header class="st-block-head">
            <span class="st-block-label">{{ block.label }}</span>
            <span v-if="isChanged(block.key)" class="st-chip">已改</span>
            <span class="st-block-actions">
              <el-button link size="small" @click="toggleEdit(block.key)">
                {{ editingKey === block.key ? '完成' : '编辑' }}
              </el-button>
              <el-button v-if="isChanged(block.key)" link size="small" @click="revertBlock(block.key)">恢复</el-button>
              <el-button
                v-if="isChanged(block.key) && supportsDiff(block.key)"
                link
                size="small"
                @click="toggleDiff(block.key)"
              >
                {{ diffKey === block.key ? '收起差异' : '差异' }}
              </el-button>
            </span>
          </header>

          <el-input
            v-if="editingKey === block.key"
            v-model="draft[block.key]"
            type="textarea"
            :rows="block.rows"
            class="st-textarea"
            :placeholder="`${block.label}：支持 Markdown，公式用 $...$`"
          />
          <div v-else class="markdown-body st-render" v-html="render(draft[block.key])" />

          <div v-if="diffKey === block.key" class="st-diff">
            <div v-for="(row, index) in diffRows(block.key)" :key="index" class="st-diff-row" :class="row.type">
              <span class="st-diff-sign">{{ row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' ' }}</span>
              <pre class="st-diff-text">{{ row.text || ' ' }}</pre>
            </div>
          </div>
        </section>

        <section class="st-block" :class="{ changed: isChanged('limits') }">
          <header class="st-block-head">
            <span class="st-block-label">时限与内存</span>
            <span v-if="isChanged('limits')" class="st-chip">已改</span>
            <span class="st-block-actions">
              <el-button v-if="isChanged('limits')" link size="small" @click="revertLimits">恢复</el-button>
            </span>
          </header>
          <div class="st-limits">
            <label class="st-limit">
              <span>时间限制</span>
              <el-input-number v-model="draft.timeLimit" :min="100" :step="100" size="small" controls-position="right" />
              <em>ms</em>
            </label>
            <label class="st-limit">
              <span>内存限制</span>
              <el-input-number v-model="draft.memoryLimit" :min="16" :step="16" size="small" controls-position="right" />
              <em>MB</em>
            </label>
            <span class="st-limit-base">基线 v{{ baseVersionNo }}：{{ baseline.timeLimit }} ms / {{ baseline.memoryLimit }} MB</span>
          </div>
        </section>

        <section class="st-block" :class="{ changed: isChanged('samples') }">
          <header class="st-block-head">
            <span class="st-block-label">样例（{{ (draft.samples || []).length }} 组）</span>
            <span v-if="isChanged('samples')" class="st-chip">已改</span>
            <span class="st-block-actions">
              <el-button v-if="isChanged('samples')" link size="small" @click="revertSamples">恢复</el-button>
              <el-button link size="small" @click="addSample">添加一组</el-button>
            </span>
          </header>
          <div class="st-samples">
            <div v-for="(sample, index) in draft.samples" :key="index" class="st-sample">
              <div class="st-sample-head">
                <span>样例 #{{ index + 1 }}</span>
                <el-button link size="small" type="danger" @click="removeSample(index)">删除</el-button>
              </div>
              <el-input v-model="sample.input" type="textarea" :rows="3" class="st-textarea" placeholder="样例输入" />
              <el-input v-model="sample.output" type="textarea" :rows="3" class="st-textarea" placeholder="样例输出" />
            </div>
            <p v-if="!(draft.samples || []).length" class="st-empty">还没有样例，点右上角「添加一组」。</p>
          </div>
        </section>
      </div>

      <aside class="st-rail" v-loading="loading">
        <div class="st-rail-head">
          <span>版本</span>
          <span class="st-rail-count">{{ versions.length }}</span>
        </div>

        <button v-if="dirty" type="button" class="st-rail-card is-draft" @click="scrollTop">
          <span class="st-rail-title">草稿</span>
          <span class="st-rail-meta">{{ changedKeys.length }} 处改动 · 未保存</span>
        </button>

        <button
          v-for="version in versions"
          :key="version.id"
          type="button"
          class="st-rail-card"
          :class="{ active: version.id === baseVersionId, live: version.live, candidate: version.status === 'CANDIDATE' }"
          @click="loadVersion(version)"
        >
          <span class="st-rail-line">
            <span class="st-rail-title">v{{ version.versionNo }}</span>
            <el-tag v-if="version.live" size="small" type="success" effect="plain">当前</el-tag>
            <el-tag v-else-if="version.status === 'CANDIDATE'" size="small" type="warning" effect="plain">待审</el-tag>
            <span v-if="version.issueErrors" class="st-dot is-error">{{ version.issueErrors }}</span>
            <span v-else-if="version.issueWarnings" class="st-dot is-warn">{{ version.issueWarnings }}</span>
          </span>
          <span class="st-rail-meta">{{ sourceLabel(version.source) }} · {{ formatTime(version.createTime) }}</span>
        </button>
        <el-empty v-if="!loading && !versions.length" description="还没有版本" :image-size="56" />
      </aside>
    </div>
  </div>
</template>

<script setup>
// 题面工作区：文档式就地编辑 + 右侧版本轨。
//
// 这里刻意不区分「编辑」和「AI 优化」两套界面：
//   - 用户始终在编辑 form 的题面字段（单一数据源，保存走同一条接口）；
//   - AI 只是把生成结果写进同一块编辑区，作为「草稿」，不自动成为版本；
//   - 版本轨只是「把某一版内容读进编辑区」，读进来就能继续改。
// 这样「AI 大幅改」和「人工小幅改」只是同一条路径上的两个起点。
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, MagicStick } from '@element-plus/icons-vue'
import {
  getActiveFormatJob,
  getFormatJob,
  getStatementVersion,
  listStatementVersions,
  optimizeStatementVersion
} from '@/api/problem'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'
import { showApiError } from '@/utils/errorFeedback'
import { diffLines } from './formatReview'

const props = defineProps({
  problemId: { type: [Number, String], default: null },
  /** 父级表单对象：题面字段就是唯一数据源，编辑直接写在这里 */
  form: { type: Object, required: true },
  /** 需要载入的版本（来自 ?version= 或批量任务跳转） */
  initialVersionId: { type: [Number, String], default: null },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['dirty-change', 'update:form', 'optimize-state'])

const md = createMarkdown({ html: true, math: true })

const BLOCKS = [
  { key: 'title', label: '题名', rows: 2 },
  { key: 'content', label: '题目正文', rows: 14 },
  { key: 'inputDesc', label: '输入描述', rows: 4 },
  { key: 'outputDesc', label: '输出描述', rows: 4 },
  { key: 'hint', label: '提示', rows: 3 }
]
const DIFFABLE = ['content', 'inputDesc', 'outputDesc', 'hint']

const versions = ref([])
const baseline = ref(emptySnapshot())
const baseVersionId = ref(null)
const aiDraft = ref(false)
const issues = ref([])
const issueOpen = ref(false)
const editingKey = ref(null)
const diffKey = ref(null)
const loading = ref(false)
const optimizing = ref(false)
const runningJob = ref(null)

let pollTimer = null
let pollGeneration = 0

const baseVersionNo = computed(() => versions.value.find((v) => v.id === baseVersionId.value)?.versionNo ?? '-')
const dirty = computed(() => !sameSnapshot(baseline.value, currentSnapshot()))
const changedKeys = computed(() => {
  const now = currentSnapshot()
  const keys = []
  for (const block of BLOCKS) {
    if (!sameField(baseline.value[block.key], now[block.key])) keys.push(block.key)
  }
  if (baseline.value.timeLimit !== now.timeLimit || baseline.value.memoryLimit !== now.memoryLimit) keys.push('limits')
  if (JSON.stringify(baseline.value.samples) !== JSON.stringify(now.samples)) keys.push('samples')
  return keys
})
const stateLabel = computed(() => {
  if (aiDraft.value) return 'AI 草稿未保存'
  if (dirty.value) return '草稿未保存'
  return `已保存为 v${baseVersionNo.value}`
})
const stateTagType = computed(() => (aiDraft.value ? 'warning' : dirty.value ? 'info' : 'success'))
const issueSummary = computed(() => {
  const errors = issues.value.filter((i) => i.severity === 'ERROR').length
  const warns = issues.value.filter((i) => i.severity === 'WARN').length
  return errors ? `${errors} 项阻断 · ${warns} 项提醒` : `${warns} 项提醒`
})

// 草稿变化时通知父级（用于顶部保存按钮的可用性）
let lastDirty = null
function syncDirty() {
  if (lastDirty !== dirty.value) {
    lastDirty = dirty.value
    emit('dirty-change', { dirty: dirty.value, aiDraft: aiDraft.value })
  }
}

// 本地草稿：所有编辑都写这里，再整体回传给父级表单。
// 这样组件不直接改 prop，父级仍然持有唯一一份提交用的 form。
const draft = reactive({
  title: '', content: '', inputDesc: '', outputDesc: '', hint: '',
  timeLimit: 1000, memoryLimit: 512, samples: []
})

function emitUpdate() {
  emit('update:form', {
    title: draft.title,
    content: draft.content,
    inputDesc: draft.inputDesc,
    outputDesc: draft.outputDesc,
    hint: draft.hint,
    timeLimit: draft.timeLimit,
    memoryLimit: draft.memoryLimit,
    samples: draft.samples.map((s) => ({ input: s.input, output: s.output }))
  })
}

function emptySnapshot() {
  return { title: '', content: '', inputDesc: '', outputDesc: '', hint: '', timeLimit: 1000, memoryLimit: 512, samples: [] }
}

function currentSnapshot() {
  return {
    title: draft.title ?? '',
    content: draft.content ?? '',
    inputDesc: draft.inputDesc ?? '',
    outputDesc: draft.outputDesc ?? '',
    hint: draft.hint ?? '',
    timeLimit: Number(draft.timeLimit) || 1000,
    memoryLimit: Number(draft.memoryLimit) || 512,
    samples: (draft.samples || []).map((s) => ({ input: s?.input ?? '', output: s?.output ?? '' }))
  }
}

function sameField(a, b) {
  return String(a ?? '') === String(b ?? '')
}

function sameSnapshot(a, b) {
  return sameField(a.title, b.title)
    && sameField(a.content, b.content)
    && sameField(a.inputDesc, b.inputDesc)
    && sameField(a.outputDesc, b.outputDesc)
    && sameField(a.hint, b.hint)
    && a.timeLimit === b.timeLimit
    && a.memoryLimit === b.memoryLimit
    && JSON.stringify(a.samples) === JSON.stringify(b.samples)
}

function applySnapshot(snapshot) {
  draft.title = snapshot.title
  draft.content = snapshot.content
  draft.inputDesc = snapshot.inputDesc
  draft.outputDesc = snapshot.outputDesc
  draft.hint = snapshot.hint
  draft.timeLimit = snapshot.timeLimit
  draft.memoryLimit = snapshot.memoryLimit
  draft.samples = snapshot.samples.map((s) => ({ input: s.input, output: s.output }))
  emitUpdate()
}

function toSnapshot(detail) {
  return {
    title: detail.title ?? '',
    content: detail.content ?? '',
    inputDesc: detail.inputDesc ?? '',
    outputDesc: detail.outputDesc ?? '',
    hint: detail.hint ?? '',
    timeLimit: Number(detail.timeLimit) || 1000,
    memoryLimit: Number(detail.memoryLimit) || 512,
    samples: (detail.samples || []).map((s) => ({ input: s?.input ?? '', output: s?.output ?? '' }))
  }
}

function render(value) {
  if (!value || !String(value).trim()) return '<p class="st-empty">（空）</p>'
  return md.render(sanitizeStatementMarkup(String(value)))
}

function splitLines(value) {
  if (!value) return []
  return String(value).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
}

function diffRows(key) {
  const result = diffLines(splitLines(baseline.value[key]), splitLines(draft[key]))
  return result.truncated ? [] : result.rows
}

function supportsDiff(key) {
  return DIFFABLE.includes(key)
}

function isChanged(key) {
  if (key === 'limits') {
    return baseline.value.timeLimit !== currentSnapshot().timeLimit
      || baseline.value.memoryLimit !== currentSnapshot().memoryLimit
  }
  return changedKeys.value.includes(key)
}

function sourceLabel(source) {
  return {
    INITIAL: '初始题面', MANUAL: '手工编辑', AI: 'AI 整理', AI_APPLIED: 'AI 整理后调整'
  }[source] || source || '未知'
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(5, 16)
}

function toggleEdit(key) {
  if (editingKey.value === key) {
    editingKey.value = null
    diffKey.value = null
    return
  }
  editingKey.value = key
  diffKey.value = null
}

function toggleDiff(key) {
  diffKey.value = diffKey.value === key ? null : key
}

function revertBlock(key) {
  draft[key] = baseline.value[key]
  emitUpdate()
}

function revertLimits() {
  draft.timeLimit = baseline.value.timeLimit
  draft.memoryLimit = baseline.value.memoryLimit
  emitUpdate()
}

function revertSamples() {
  draft.samples = baseline.value.samples.map((s) => ({ ...s }))
  emitUpdate()
}

function addSample() {
  if (!Array.isArray(draft.samples)) draft.samples = []
  if (draft.samples.length >= 50) {
    ElMessage.warning('样例最多 50 组')
    return
  }
  draft.samples.push({ input: '', output: '' })
  emitUpdate()
}

function removeSample(index) {
  draft.samples.splice(index, 1)
  emitUpdate()
}

function scrollTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function discardDraft() {
  try {
    await ElMessageBox.confirm('将丢弃当前未保存的修改，回到所选版本的内容。', '放弃修改', {
      type: 'warning', confirmButtonText: '放弃', cancelButtonText: '继续编辑'
    })
  } catch {
    return
  }
  applySnapshot(baseline.value)
  aiDraft.value = false
  issues.value = []
}

async function loadVersions() {
  const res = await listStatementVersions(props.problemId)
  versions.value = Array.isArray(res?.data) ? res.data : []
}

/** 把某个版本读进编辑区；候选版本会同时读入它的基线用于对照。 */
async function loadVersion(version, { silent = false } = {}) {
  if (!silent && dirty.value) {
    try {
      await ElMessageBox.confirm(
        '当前有未保存的修改，切换版本会丢弃这些修改。确定继续？',
        '切换版本',
        { type: 'warning', confirmButtonText: '丢弃并切换', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  loading.value = true
  try {
    if (version.status === 'CANDIDATE') {
      const baseId = version.baseVersionId
      const baseDetail = baseId ? (await getStatementVersion(props.problemId, baseId))?.data : null
      const detail = (await getStatementVersion(props.problemId, version.id))?.data
      baseline.value = baseDetail ? toSnapshot(baseDetail) : toSnapshot(detail)
      applySnapshot(toSnapshot(detail))
      baseVersionId.value = baseId || version.id
      aiDraft.value = true
      issues.value = Array.isArray(detail?.issues) ? detail.issues : []
      issueOpen.value = issues.value.length > 0
    } else {
      const detail = (await getStatementVersion(props.problemId, version.id))?.data
      const snapshot = toSnapshot(detail)
      baseline.value = snapshot
      applySnapshot(snapshot)
      baseVersionId.value = version.id
      aiDraft.value = false
      issues.value = Array.isArray(detail?.issues) ? detail.issues : []
      issueOpen.value = false
    }
    editingKey.value = null
    diffKey.value = null
    syncDirty()
  } catch (error) {
    showApiError(error, '版本加载失败')
  } finally {
    loading.value = false
  }
}

/** 保存成功后由父级调用：回到新的当前版本。 */
async function reload() {
  loading.value = true
  try {
    await loadVersions()
    const live = versions.value.find((v) => v.live) || versions.value[0]
    if (live) await loadVersion(live, { silent: true })
    aiDraft.value = false
    issues.value = []
  } catch (error) {
    showApiError(error, '版本刷新失败')
  } finally {
    loading.value = false
  }
}

/** 首次渲染先用父级已经加载好的题目内容，避免等接口时编辑区是空的。 */
function seedDraftFromForm() {
  const source = props.form || {}
  draft.title = source.title ?? ''
  draft.content = source.content ?? ''
  draft.inputDesc = source.inputDesc ?? ''
  draft.outputDesc = source.outputDesc ?? ''
  draft.hint = source.hint ?? ''
  draft.timeLimit = Number(source.timeLimit) || 1000
  draft.memoryLimit = Number(source.memoryLimit) || 512
  draft.samples = (source.samples || []).map((s) => ({ input: s?.input ?? '', output: s?.output ?? '' }))
}

async function init() {
  if (!props.problemId) return
  seedDraftFromForm()
  loading.value = true
  try {
    await loadVersions()
    const wanted = props.initialVersionId ? Number(props.initialVersionId) : null
    const target = (wanted && versions.value.find((v) => v.id === wanted)) || versions.value.find((v) => v.live)
    if (target) await loadVersion(target, { silent: true })
    await resumeActiveJob()
  } catch (error) {
    showApiError(error, '题面版本加载失败')
  } finally {
    loading.value = false
  }
}

async function optimize() {
  if (!baseVersionId.value) {
    ElMessage.warning('请先选择一个版本作为基线')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将以 v${baseVersionNo.value} 为基线调用 AI 生成新草稿，约需 20~150 秒。`
      + '结果会直接写进编辑区，不会自动保存；你可以改完再保存。',
      'AI 优化这一版',
      { confirmButtonText: '开始生成', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  optimizing.value = true
  try {
    const res = await optimizeStatementVersion(props.problemId, baseVersionId.value, false)
    runningJob.value = res?.data ?? null
    emit('optimize-state', { running: true })
    if (runningJob.value?.id) startPolling(runningJob.value.id)
  } catch (error) {
    optimizing.value = false
    showApiError(error, '发起优化失败')
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
        optimizing.value = false
        emit('optimize-state', { running: false })
        await handleJobFinished(job)
        return
      }
    } catch {
      // 网络抖动继续轮询
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
  if (finished?.status === 'FAILED') {
    ElMessage.error(finished.message || 'AI 优化失败，请重试')
    return
  }
  await loadVersions()
  const candidate = versions.value.find((v) => v.id === resultVersionId)
  if (candidate) {
    await loadVersion(candidate, { silent: true })
    ElMessage.success('AI 草稿已生成，可以直接修改后保存')
  }
}

async function resumeActiveJob() {
  try {
    const res = await getActiveFormatJob({ silent: true })
    const job = res?.data ?? res
    if (!job || job.items?.[0]?.problemId !== Number(props.problemId)) return
    runningJob.value = job
    optimizing.value = true
    startPolling(job.id)
  } catch {
    // 没有进行中的任务属于正常情况
  }
}

// 题目详情异步加载，problemId 从 null 变成真实 id 时才真正拉版本
watch(() => props.problemId, (id) => { if (id) init() }, { immediate: true })
syncDirty()

// 父级 form 被外部改写（例如保存后回填）或用户输入时同步「是否有未保存修改」
watch(() => JSON.stringify(currentSnapshot()), () => {
  syncDirty()
  emitUpdate()
})

onBeforeUnmount(stopPolling)

defineExpose({ reload, isDirty: () => dirty.value, currentSnapshot, baseVersionId })
</script>

<style scoped>
.st { display: flex; flex-direction: column; gap: 12px; }

.st-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.st-state { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12.5px; color: var(--text-secondary); }
.st-dirty { color: var(--el-color-warning-dark-2); }
.st-clean { color: var(--text-secondary); }
.st-issue-toggle {
  border: none;
  background: transparent;
  color: var(--accent-primary);
  cursor: pointer;
  font-size: 12.5px;
  padding: 0;
}
.st-actions { display: flex; gap: 8px; }

.st-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--accent-warning-light, #fffbeb);
  border: 1px solid rgba(217, 119, 6, 0.35);
  font-size: 12.5px;
  color: var(--text-regular);
}
.st-banner-mark {
  font-size: 11px;
  font-weight: 600;
  color: #b45309;
  background: #fff;
  border: 1px solid rgba(217, 119, 6, 0.35);
  border-radius: 999px;
  padding: 1px 8px;
}

.st-running {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--border-light);
  background: var(--bg-surface);
  font-size: 12.5px;
  color: var(--text-regular);
}

.st-issues { display: flex; flex-direction: column; gap: 6px; }
.st-issue {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  padding: 6px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  color: var(--text-regular);
}
.st-issue-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; }
.st-issue.error { background: rgba(220, 38, 38, 0.08); border-color: rgba(220, 38, 38, 0.35); }
.st-issue.error .st-issue-dot { background: var(--el-color-danger); }
.st-issue.warn { background: rgba(217, 119, 6, 0.08); border-color: rgba(217, 119, 6, 0.35); }
.st-issue.warn .st-issue-dot { background: var(--el-color-warning); }
.st-issue.info .st-issue-dot { background: var(--el-color-info); }
.st-issue code { font-size: 11px; color: var(--text-secondary); }

.st-body { display: grid; grid-template-columns: minmax(0, 1fr) 240px; gap: 16px; align-items: start; }
.st-doc { display: flex; flex-direction: column; gap: 12px; min-width: 0; }

.st-block {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 10px 14px 14px;
  transition: border-color 0.15s ease;
}
.st-block.changed { border-color: rgba(37, 99, 235, 0.35); }
.st-block.editing { border-color: var(--accent-primary); }
.st-block-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 8px;
}
.st-block-label { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.st-chip {
  font-size: 11px;
  color: var(--accent-primary);
  background: var(--bg-surface-hover);
  border-radius: 4px;
  padding: 1px 6px;
}
.st-block-actions { margin-left: auto; display: flex; gap: 2px; }

.st-render { font-size: 14px; line-height: 1.75; color: var(--text-regular); min-height: 24px; }
.st-render :deep(p) { margin: 0 0 10px; }
.st-textarea :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  line-height: 1.7;
}

.st-diff {
  margin-top: 10px;
  border-top: 1px dashed var(--border-light);
  padding-top: 8px;
  font-family: Consolas, Monaco, monospace;
  font-size: 11.5px;
  max-height: 260px;
  overflow: auto;
}
.st-diff-row { display: flex; gap: 6px; padding: 1px 0; }
.st-diff-row.add { background: rgba(82, 196, 26, 0.12); }
.st-diff-row.del { background: rgba(255, 77, 79, 0.12); }
.st-diff-sign { width: 12px; color: var(--text-secondary); flex-shrink: 0; }
.st-diff-text { margin: 0; white-space: pre-wrap; word-break: break-word; color: var(--text-regular); }

.st-limits { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; }
.st-limit { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-secondary); }
.st-limit em { font-style: normal; color: var(--text-secondary); }
.st-limit-base { font-size: 11.5px; color: var(--text-secondary); }

.st-samples { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.st-sample { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.st-sample-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text-secondary); }
.st-empty { color: var(--text-secondary); font-size: 13px; margin: 0; }

.st-rail {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  position: sticky;
  top: 12px;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
}
.st-rail-head { display: flex; justify-content: space-between; font-size: 12px; color: var(--text-secondary); padding: 0 4px 4px; }
.st-rail-count { font-variant-numeric: tabular-nums; }
.st-rail-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  padding: 8px 10px;
  cursor: pointer;
}
.st-rail-card:hover { background: var(--bg-surface-hover); }
.st-rail-card.active { background: var(--bg-surface-hover); border-color: var(--border-base); box-shadow: inset 2px 0 0 var(--accent-primary); }
.st-rail-card.is-draft { border-style: dashed; border-color: rgba(217, 119, 6, 0.5); background: rgba(217, 119, 6, 0.06); }
.st-rail-line { display: flex; align-items: center; gap: 6px; }
.st-rail-title { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.st-rail-meta { font-size: 11px; color: var(--text-secondary); }
.st-dot {
  min-width: 15px; height: 15px; border-radius: 999px;
  font-size: 10px; line-height: 15px; text-align: center; padding: 0 4px; color: #fff;
}
.st-dot.is-error { background: var(--el-color-danger); }
.st-dot.is-warn { background: var(--el-color-warning); }

.dark .st-banner { background: rgba(210, 153, 34, 0.12); border-color: rgba(210, 153, 34, 0.4); }
.dark .st-banner-mark { background: #161b22; color: #d29922; border-color: rgba(210, 153, 34, 0.4); }
.dark .st-block.changed { border-color: rgba(88, 166, 255, 0.4); }
.dark .st-dirty { color: #d29922; }
.dark .st-rail-card.is-draft { background: rgba(210, 153, 34, 0.1); border-color: rgba(210, 153, 34, 0.45); }
.dark .st-issue.error { background: rgba(248, 81, 73, 0.12); border-color: rgba(248, 81, 73, 0.35); }
.dark .st-issue.warn { background: rgba(210, 153, 34, 0.12); border-color: rgba(210, 153, 34, 0.35); }

@media (max-width: 1180px) {
  .st-body { grid-template-columns: 1fr; }
  .st-rail { position: static; max-height: 320px; }
}
</style>
