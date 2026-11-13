<template>
  <div class="st">
    <div class="st-toolbar">
      <el-button @click="emit('go-back')">返回题库</el-button>
      <el-button type="primary" :loading="saving" :disabled="optimizing" @click="save('SAVE')">保存修改</el-button>
      <el-button type="primary" plain :loading="saving" :disabled="optimizing" @click="save('SAVE_AS_NEW')">
        另存为新版本
      </el-button>
      <el-button :disabled="!dirty || saving" @click="discardDraft">放弃修改</el-button>
      <el-button :icon="MagicStick" :loading="optimizing" :disabled="saving" @click="optimize">优化这一版</el-button>
      <span v-if="optimizing" class="st-optimizing">AI 正在服务端生成新版本，可以离开页面</span>
    </div>

    <div class="st-body">
      <div class="st-doc" v-loading="loading">
        <section
          v-for="block in BLOCKS"
          :key="block.key"
          class="st-block"
          :class="{ editing: editingKey === block.key }"
        >
          <header class="st-block-head">
            <span class="st-block-label">{{ block.label }}</span>
            <span class="st-block-actions">
              <el-button link size="small" @click="toggleEdit(block.key)">
                {{ editingKey === block.key ? '完成' : '编辑' }}
              </el-button>
              <el-button link size="small" @click="openDiff(block.key, block.label)">差异</el-button>
            </span>
          </header>

          <template v-if="block.key === 'limits'">
            <div v-if="editingKey === 'limits'" class="st-limits">
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
            </div>
            <div v-else class="st-render st-plain">{{ draft.timeLimit }} ms / {{ draft.memoryLimit }} MB</div>
          </template>

          <template v-else-if="block.key === 'samples'">
            <div v-if="editingKey === 'samples'" class="st-samples">
              <div v-for="(sample, index) in draft.samples" :key="index" class="st-sample">
                <div class="st-sample-head">
                  <span>样例 #{{ index + 1 }}</span>
                  <el-button link size="small" type="danger" @click="removeSample(index)">删除</el-button>
                </div>
                <el-input v-model="sample.input" type="textarea" :rows="3" class="st-textarea" placeholder="样例输入" />
                <el-input v-model="sample.output" type="textarea" :rows="3" class="st-textarea" placeholder="样例输出" />
              </div>
              <el-button size="small" @click="addSample">添加一组样例</el-button>
              <p v-if="!draft.samples.length" class="st-empty">还没有样例</p>
            </div>
            <div v-else class="st-samples st-samples-readonly">
              <div v-for="(sample, index) in draft.samples" :key="index" class="st-sample">
                <div class="st-sample-head"><span>样例 #{{ index + 1 }}</span></div>
                <pre class="st-code">{{ sample.input || '（空）' }}</pre>
                <pre class="st-code">{{ sample.output || '（空）' }}</pre>
              </div>
              <p v-if="!draft.samples.length" class="st-empty">还没有样例</p>
            </div>
          </template>

          <template v-else>
            <el-input
              v-if="editingKey === block.key"
              v-model="draft[block.key]"
              type="textarea"
              :rows="block.rows"
              class="st-textarea"
              :placeholder="`${block.label}：支持 Markdown，公式用 $...$`"
            />
            <div v-else class="markdown-body st-render" v-html="render(draft[block.key])" />
          </template>
        </section>
      </div>

      <aside class="st-rail" v-loading="loading">
        <div class="st-rail-head">
          <span>版本</span>
          <span class="st-rail-count">{{ versions.length }}</span>
        </div>
        <button
          v-for="version in versions"
          :key="version.id"
          type="button"
          class="st-rail-card"
          :class="{ active: version.id === baseVersionId, live: version.live }"
          @click="loadVersion(version)"
        >
          <span class="st-rail-line">
            <span class="st-rail-title">v{{ version.versionNo }}</span>
            <el-tag v-if="version.live" size="small" type="success" effect="plain">当前</el-tag>
          </span>
          <span class="st-rail-meta">{{ sourceLabel(version.source) }}</span>
          <span class="st-rail-meta">{{ formatTime(version.createTime) }}</span>
        </button>
        <el-empty v-if="!loading && !versions.length" description="还没有版本" :image-size="56" />
      </aside>
    </div>

    <el-dialog
      v-model="diffVisible"
      :title="`差异 · ${diffTitle}`"
      width="88%"
      top="4vh"
      class="st-diff-dialog"
      destroy-on-close
    >
      <div class="st-diff-panes">
        <div class="st-diff-pane">
          <div class="st-diff-pane-head">
            <strong>版本内容</strong>
            <span>v{{ baseVersionNo }}</span>
          </div>
          <div class="st-diff-lines">
            <div v-for="(row, index) in diffRows" :key="'l' + index" class="st-diff-line" :class="row.type === 'del' ? 'is-del' : ''">
              <span class="st-diff-sign">{{ row.type === 'del' ? '-' : '' }}</span>
              <pre>{{ row.type === 'add' ? '' : (row.text || ' ') }}</pre>
            </div>
          </div>
        </div>
        <div class="st-diff-pane">
          <div class="st-diff-pane-head">
            <strong>编辑区内容</strong>
            <span>当前</span>
          </div>
          <div class="st-diff-lines">
            <div v-for="(row, index) in diffRows" :key="'r' + index" class="st-diff-line" :class="row.type === 'add' ? 'is-add' : ''">
              <span class="st-diff-sign">{{ row.type === 'add' ? '+' : '' }}</span>
              <pre>{{ row.type === 'del' ? '' : (row.text || ' ') }}</pre>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="diffVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 题面工作区：每个版本都是一份独立、完整的内容。
//
// - 编辑区永远只有一份草稿；点版本轨只是「把那一版读进编辑区」。
// - 保存修改 = 覆盖当前打开的版本；另存为新版本 = 新建一条版本。
// - 优化 = 异步生成一条「AI 整理」版本，读进编辑区后可继续改。
// - 不做状态提示条、不做质量清单、不做区块恢复：版本之间彼此独立，
//   想回到旧内容直接点那一版即可。
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
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
  /** 父级表单对象：题面字段的唯一数据源 */
  form: { type: Object, required: true },
  /** 需要载入的版本（来自 ?version= 或批量任务跳转） */
  initialVersionId: { type: [Number, String], default: null },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['dirty-change', 'update:form', 'save', 'go-back'])

const md = createMarkdown({ html: true, math: true })

const BLOCKS = [
  { key: 'title', label: '题名', rows: 2 },
  { key: 'content', label: '题目正文', rows: 14 },
  { key: 'inputDesc', label: '输入描述', rows: 4 },
  { key: 'outputDesc', label: '输出描述', rows: 4 },
  { key: 'hint', label: '提示', rows: 3 },
  { key: 'limits', label: '时限与内存' },
  { key: 'samples', label: '样例' }
]

const SOURCE_LABELS = {
  INITIAL: '初始题面',
  AI: 'AI 整理',
  AI_APPLIED: 'AI 整理',
  MANUAL: '人工编辑'
}

const versions = ref([])
const baseline = ref(emptySnapshot())
const baseVersionId = ref(null)
const editingKey = ref(null)
const loading = ref(false)
const optimizing = ref(false)
const diffVisible = ref(false)
const diffTitle = ref('')
const diffRows = ref([])

let pollTimer = null
let pollGeneration = 0

const baseVersionNo = computed(() => versions.value.find((v) => v.id === baseVersionId.value)?.versionNo ?? '-')
const dirty = computed(() => !sameSnapshot(baseline.value, currentSnapshot()))

const draft = reactive({
  title: '', content: '', inputDesc: '', outputDesc: '', hint: '',
  timeLimit: 1000, memoryLimit: 512, samples: []
})

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

function sourceLabel(source) {
  return SOURCE_LABELS[source] || '人工编辑'
}

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(5, 16)
}

function toggleEdit(key) {
  editingKey.value = editingKey.value === key ? null : key
}

function textOf(key, snapshot) {
  if (key === 'limits') {
    return `时间限制：${snapshot.timeLimit} ms\n内存限制：${snapshot.memoryLimit} MB`
  }
  if (key === 'samples') {
    if (!snapshot.samples.length) return '（没有样例）'
    return snapshot.samples.map((s, i) =>
      `样例 #${i + 1}\n输入：\n${s.input || ''}\n输出：\n${s.output || ''}`
    ).join('\n\n')
  }
  return String(snapshot[key] ?? '')
}

function splitLines(value) {
  if (!value) return []
  return String(value).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
}

function openDiff(key, label) {
  const result = diffLines(
    splitLines(textOf(key, baseline.value)),
    splitLines(textOf(key, currentSnapshot()))
  )
  diffRows.value = result.truncated ? [] : result.rows
  diffTitle.value = label
  diffVisible.value = true
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

async function discardDraft() {
  if (!dirty.value) return
  try {
    await ElMessageBox.confirm('将丢弃当前未保存的修改，重新载入这一版的内容。', '放弃修改', {
      type: 'warning', confirmButtonText: '放弃', cancelButtonText: '继续编辑'
    })
  } catch {
    return
  }
  applySnapshot(baseline.value)
  editingKey.value = null
  ElMessage.success('已放弃修改')
}

function save(mode) {
  if (!baseVersionId.value) {
    ElMessage.warning('请先选择一个版本')
    return
  }
  const snapshot = currentSnapshot()
  const current = versions.value.find((v) => v.id === baseVersionId.value)
  emit('save', {
    mode,
    baseVersionId: baseVersionId.value,
    source: mode === 'SAVE' ? (current?.source || 'MANUAL') : 'MANUAL',
    formatted: {
      title: snapshot.title,
      content: snapshot.content,
      inputDesc: snapshot.inputDesc,
      outputDesc: snapshot.outputDesc,
      hint: snapshot.hint,
      timeLimitMs: snapshot.timeLimit,
      memoryLimitMb: snapshot.memoryLimit,
      samples: snapshot.samples
    }
  })
}

async function loadVersions() {
  const res = await listStatementVersions(props.problemId)
  versions.value = Array.isArray(res?.data) ? res.data : []
}

/** 点版本轨：把那一版完整读进编辑区。版本之间彼此独立，不做「恢复」。 */
async function loadVersion(version, { silent = false } = {}) {
  if (!silent && dirty.value) {
    try {
      await ElMessageBox.confirm('当前有未保存的修改，切换版本会丢弃这些修改。确定继续？', '切换版本', {
        type: 'warning', confirmButtonText: '丢弃并切换', cancelButtonText: '取消'
      })
    } catch {
      return
    }
  }
  loading.value = true
  try {
    const detail = (await getStatementVersion(props.problemId, version.id))?.data
    const snapshot = toSnapshot(detail)
    baseline.value = snapshot
    applySnapshot(snapshot)
    baseVersionId.value = version.id
    editingKey.value = null
  } catch (error) {
    showApiError(error, '版本加载失败')
  } finally {
    loading.value = false
  }
}

/** 保存或优化完成后，回到最新的当前版本（或指定版本）。 */
async function reload(preferredVersionId = null) {
  loading.value = true
  try {
    await loadVersions()
    const target = (preferredVersionId && versions.value.find((v) => v.id === preferredVersionId))
      || versions.value.find((v) => v.live)
      || versions.value[0]
    if (target) await loadVersion(target, { silent: true })
  } catch (error) {
    showApiError(error, '版本刷新失败')
  } finally {
    loading.value = false
  }
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
  optimizing.value = true
  try {
    const res = await optimizeStatementVersion(props.problemId, baseVersionId.value, false)
    const job = res?.data ?? null
    if (job?.id) startPolling(job.id)
    else optimizing.value = false
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
      if (job && !['PENDING', 'RUNNING'].includes(job.status)) {
        stopPolling()
        optimizing.value = false
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
  const resultVersionId = finished?.items?.[0]?.resultVersionId || null
  if (finished?.status === 'FAILED') {
    ElMessage.error(finished.message || 'AI 优化失败，请重试')
    return
  }
  await reload(resultVersionId)
  ElMessage.success('AI 整理版本已生成，可以继续编辑或保存')
}

async function resumeActiveJob() {
  try {
    const res = await getActiveFormatJob({ silent: true })
    const job = res?.data ?? res
    if (!job || job.items?.[0]?.problemId !== Number(props.problemId)) return
    optimizing.value = true
    startPolling(job.id)
  } catch {
    // 没有进行中的任务属于正常情况
  }
}

// 题目详情异步加载，problemId 从 null 变成真实 id 时才真正拉版本
watch(() => props.problemId, (id) => { if (id) init() }, { immediate: true })
watch(() => JSON.stringify(currentSnapshot()), () => {
  emit('dirty-change', { dirty: dirty.value })
  emitUpdate()
})

onBeforeUnmount(stopPolling)

defineExpose({ reload, isDirty: () => dirty.value, currentSnapshot, baseVersionId })
</script>

<style scoped>
.st { display: flex; flex-direction: column; gap: 12px; }

.st-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
}
.st-optimizing { font-size: 12px; color: var(--text-secondary); margin-left: 4px; }

.st-body { display: grid; grid-template-columns: minmax(0, 1fr) 220px; gap: 16px; align-items: start; }
.st-doc { display: flex; flex-direction: column; gap: 12px; min-width: 0; }

.st-block {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  padding: 10px 14px 14px;
}
.st-block.editing { border-color: var(--accent-primary); }
.st-block-head { display: flex; align-items: center; gap: 10px; padding-bottom: 8px; }
.st-block-label { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.st-block-actions { margin-left: auto; display: flex; gap: 2px; }

.st-render { font-size: 14px; line-height: 1.75; color: var(--text-regular); min-height: 24px; }
.st-render :deep(p) { margin: 0 0 10px; }
.st-plain { font-size: 13px; }
.st-textarea :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  line-height: 1.7;
}

.st-limits { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; }
.st-limit { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-secondary); }
.st-limit em { font-style: normal; }

.st-samples { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.st-sample { border: 1px solid var(--border-light); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.st-sample-head { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--text-secondary); }
.st-samples-readonly .st-sample { gap: 4px; }
.st-code {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--bg-app);
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-regular);
}
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
  gap: 2px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  padding: 8px 10px;
  cursor: pointer;
}
.st-rail-card:hover { background: var(--bg-surface-hover); }
.st-rail-card.active { background: var(--bg-surface-hover); border-color: var(--border-base); box-shadow: inset 2px 0 0 var(--accent-primary); }
.st-rail-line { display: flex; align-items: center; gap: 6px; }
.st-rail-title { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.st-rail-meta { font-size: 11px; color: var(--text-secondary); }

.st-diff-panes { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; height: 66vh; }
.st-diff-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  overflow: hidden;
}
.st-diff-pane-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--bg-app);
  border-bottom: 1px solid var(--border-light);
  font-size: 12.5px;
  color: var(--text-regular);
}
.st-diff-pane-head span { font-size: 11px; color: var(--text-secondary); }
.st-diff-lines { flex: 1; overflow: auto; font-family: Consolas, Monaco, monospace; font-size: 12.5px; }
.st-diff-line { display: flex; gap: 6px; padding: 1px 10px; min-height: 20px; }
.st-diff-line.is-del { background: rgba(220, 38, 38, 0.12); }
.st-diff-line.is-add { background: rgba(22, 163, 74, 0.12); }
.st-diff-sign { width: 12px; flex-shrink: 0; color: var(--text-secondary); }
.st-diff-line pre { margin: 0; white-space: pre-wrap; word-break: break-word; color: var(--text-regular); }

.dark .st-block.editing { border-color: #58a6ff; }
.dark .st-rail-card.active { background: #1c2129; border-color: #2f4a85; box-shadow: inset 2px 0 0 #58a6ff; }
.dark .st-diff-line.is-del { background: rgba(248, 81, 73, 0.16); }
.dark .st-diff-line.is-add { background: rgba(63, 185, 80, 0.16); }

@media (max-width: 1180px) {
  .st-body { grid-template-columns: 1fr; }
  .st-rail { position: static; max-height: 300px; }
  .st-diff-panes { grid-template-columns: 1fr; height: auto; }
  .st-diff-lines { max-height: 40vh; }
}
</style>
