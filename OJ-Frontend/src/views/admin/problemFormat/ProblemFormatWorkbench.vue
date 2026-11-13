<template>
  <el-drawer
    :model-value="modelValue"
    :with-header="false"
    size="94%"
    class="format-workbench"
    :close-on-click-modal="false"
    :close-on-press-escape="!applying"
    @update:model-value="handleVisibleChange"
  >
    <div class="fw">
      <header class="fw-head">
        <div class="fw-head-main">
          <div class="fw-eyebrow">
            <span class="fw-badge">题面优化</span>
            <span class="fw-id">题目 #{{ problemId ?? '-' }}</span>
            <el-tag
              v-if="preview"
              size="small"
              :type="statusTagType"
              effect="plain"
              :class="{ 'fw-tag-clickable': Boolean(firstIssueField) }"
              @click="jumpToFirstIssue"
            >
              {{ statusTagText }}
            </el-tag>
          </div>
          <h2 class="fw-title">{{ displayTitle }}</h2>
          <p class="fw-sub">{{ displaySubtitle }}</p>
        </div>
        <div class="fw-head-actions">
          <label class="fw-switch">
            <el-switch v-model="ipRename" size="small" :disabled="busy" />
            <span>IP 改名</span>
          </label>
          <el-button :icon="RefreshRight" :loading="loading" :disabled="busy" @click="runPreview">
            重新整理
          </el-button>
          <el-button text :disabled="applying" @click="close">关闭</el-button>
        </div>
      </header>

      <div v-if="!loading && preview && !hasSource" class="fw-warnbar">
        <el-alert
          type="error"
          :closable="false"
          show-icon
          title="服务端没有返回原文快照"
          description="无法与原文对照，也无法校验题目是否在整理期间被改动。通常是后端仍是旧版本：请重启或升级后端后重新整理。当前只能查看结果，不能应用。"
        />
      </div>

      <div v-if="loading" class="fw-state">
        <el-icon class="is-loading fw-state-icon"><Loading /></el-icon>
        <div>
          <p class="fw-state-title">AI 正在整理题面…</p>
          <p class="fw-state-desc">
            抽取时限、内存、输入输出格式与全部样例，并把英文题面翻译成中文。单题通常需要 20–60 秒，请不要关闭页面。
          </p>
          <p class="fw-state-desc">任务在服务端执行，中途刷新页面不会丢失结果；批量任务可随时回来查看进度。</p>
        </div>
      </div>

      <div v-else-if="failure" class="fw-state fw-state-error">
        <el-icon class="fw-state-icon"><WarningFilled /></el-icon>
        <div>
          <p class="fw-state-title">本次整理没有完成</p>
          <p class="fw-state-desc">{{ failure }}</p>
          <p class="fw-state-desc">
            可以点右上角「重新整理」再试一次；若反复失败，请联系管理员在「AI 服务」中检查模型配置。
          </p>
        </div>
      </div>

      <div v-else-if="preview" class="fw-body">
        <aside class="fw-nav">
          <div class="fw-nav-head">
            <span>字段</span>
            <span class="fw-nav-count">{{ selectedCount }}/{{ changedCount }}</span>
          </div>
          <button
            v-for="field in FORMAT_FIELDS"
            :key="field.key"
            type="button"
            class="fw-nav-item"
            :class="{ active: field.key === activeKey, blocked: hasError(field.key) }"
            @click="activeKey = field.key"
          >
            <el-checkbox
              :model-value="Boolean(selection[field.key])"
              :disabled="applying"
              @update:model-value="(value) => toggleField(field.key, value)"
              @click.stop
            />
            <span class="fw-nav-label">{{ field.label }}</span>
            <span class="fw-nav-badges">
              <span v-if="badgeOf(field.key)" class="fw-chip" :class="badgeClass(field.key)">
                {{ badgeOf(field.key) }}
              </span>
              <span v-if="errorCount(field.key)" class="fw-dot is-error">{{ errorCount(field.key) }}</span>
              <span v-else-if="warnCount(field.key)" class="fw-dot is-warn">{{ warnCount(field.key) }}</span>
            </span>
          </button>
          <div class="fw-nav-foot">
            <el-button link @click="selectChanged">全选已改</el-button>
            <el-button link @click="clearSelection">全不选</el-button>
          </div>
        </aside>

        <section class="fw-main">
          <div class="fw-field-head">
            <div class="fw-field-title">
              <h3>{{ activeField.label }}</h3>
              <span v-if="activeChange && activeChange.kind !== 'same'" class="fw-field-meta">
                {{ changeLabel(activeChange) }}
                <template v-if="lineChangeText">· {{ lineChangeText }}</template>
              </span>
              <span v-else class="fw-field-meta">与原文一致</span>
            </div>
            <div class="fw-field-actions">
              <el-radio-group
                v-if="activeField.kind === 'text' && hasSource"
                v-model="viewMode"
                size="small"
                :disabled="applying"
              >
                <el-radio-button label="compare">对照</el-radio-button>
                <el-radio-button label="result">优化后</el-radio-button>
                <el-radio-button label="source">源码</el-radio-button>
              </el-radio-group>
              <el-button
                v-if="activeField.kind === 'text'"
                size="small"
                :type="editing ? 'primary' : 'default'"
                :disabled="applying"
                @click="toggleEditing"
              >
                {{ editing ? '完成编辑' : '编辑' }}
              </el-button>
              <el-button
                v-if="activeField.kind === 'samples'"
                size="small"
                :disabled="applying"
                @click="restoreSamples"
              >
                恢复原文样例
              </el-button>
            </div>
          </div>

          <div v-if="fieldIssues.length" class="fw-issues">
            <div
              v-for="issue in fieldIssues"
              :key="issue.code + issue.message"
              class="fw-issue"
              :class="issue.severity?.toLowerCase()"
            >
              <span class="fw-issue-dot" />
              <span class="fw-issue-text">{{ issue.message }}</span>
              <code class="fw-issue-code">{{ issue.code }}</code>
            </div>
          </div>

          <div v-if="activeField.kind === 'number'" class="fw-number">
            <div class="fw-number-card">
              <span class="fw-number-label">原文</span>
              <strong>{{ sourceValues[activeField.key] }} {{ activeField.unit }}</strong>
            </div>
            <el-input-number
              v-model="values[activeField.key]"
              :min="activeField.key === 'timeLimit' ? 100 : 16"
              :step="activeField.key === 'timeLimit' ? 100 : 16"
              :disabled="applying"
              controls-position="right"
              class="fw-number-input"
            />
            <span class="fw-number-unit">{{ activeField.unit }}</span>
            <el-button
              v-if="values[activeField.key] !== sourceValues[activeField.key]"
              link
              :disabled="applying"
              @click="values[activeField.key] = sourceValues[activeField.key]"
            >
              用原文值
            </el-button>
            <span v-if="limitsSkipped.includes(activeField.key)" class="fw-limit-note">
              原文没有声明{{ activeField.label }}，这里填的是平台默认值；默认不采纳
            </span>
          </div>

          <template v-else-if="activeField.kind === 'samples'">
            <div class="fw-samples-meta">
              原文 {{ sourceValues.samples.length }} 组 → 优化后 {{ values.samples.length }} 组
            </div>
            <div class="fw-samples">
              <div v-for="row in sampleRows" :key="`${row.index}-${row.status}`" class="fw-sample" :class="row.status">
                <div class="fw-sample-head">
                  <span class="fw-sample-index">样例 #{{ row.index + 1 }}</span>
                  <el-tag size="small" :type="sampleTagType(row.status)" effect="light">
                    {{ sampleStatusLabel(row.status) }}
                  </el-tag>
                </div>
                <template v-if="row.status === 'removed'">
                  <pre class="fw-sample-readonly">{{ row.input || '（空）' }}</pre>
                  <pre class="fw-sample-readonly">{{ row.output || '（空）' }}</pre>
                </template>
                <template v-else>
                  <el-input
                    v-model="values.samples[row.index].input"
                    type="textarea"
                    :rows="4"
                    :disabled="applying"
                    placeholder="样例输入"
                    class="fw-code"
                  />
                  <el-input
                    v-model="values.samples[row.index].output"
                    type="textarea"
                    :rows="4"
                    :disabled="applying"
                    placeholder="样例输出"
                    class="fw-code"
                  />
                </template>
              </div>
              <el-empty v-if="!sampleRows.length" description="本次没有提取到样例" :image-size="72" />
            </div>
          </template>

          <template v-else>
            <div v-if="viewMode === 'source'" class="fw-source">
              <el-alert
                v-if="diffResult.truncated"
                type="info"
                :closable="false"
                show-icon
                title="内容较长，未逐行比对"
                description="可在「优化后」中直接查看全文，或切到「编辑」修改。"
              />
              <div v-else class="fw-diff">
                <div v-for="(row, index) in diffResult.rows" :key="index" class="fw-diff-row" :class="row.type">
                  <span class="fw-diff-sign">{{ row.type === 'add' ? '+' : row.type === 'del' ? '-' : ' ' }}</span>
                  <pre class="fw-diff-text">{{ row.text || ' ' }}</pre>
                </div>
              </div>
            </div>

            <div v-else-if="viewMode === 'compare'" class="fw-compare">
              <div class="fw-pane">
                <div class="fw-pane-head">原文</div>
                <div class="fw-pane-body markdown-body" v-html="renderSource(sourceValues[activeField.key])" />
              </div>
              <div class="fw-pane is-result">
                <div class="fw-pane-head">优化后</div>
                <div class="fw-pane-body markdown-body" v-html="renderMarkdown(values[activeField.key])" />
              </div>
            </div>

            <div v-else-if="editing" class="fw-edit">
              <el-input
                v-model="values[activeField.key]"
                type="textarea"
                :rows="18"
                :disabled="applying"
                class="fw-code fw-edit-area"
                placeholder="支持 Markdown 与 LaTeX，公式用 $...$"
              />
              <div class="fw-edit-tip">保存时使用这里的内容；Markdown 与 LaTeX 会按题面渲染规则展示。</div>
            </div>

            <div v-else class="fw-render markdown-body" v-html="renderMarkdown(values[activeField.key])" />
          </template>
        </section>
      </div>

      <footer class="fw-foot">
        <div class="fw-foot-info">
          <template v-if="preview">
            <span class="fw-foot-strong">已选 {{ selectedCount }} 项</span>
            <span v-if="skippedByBlocking.length" class="fw-foot-warn">
              {{ skippedByBlocking.length }} 项因阻断问题未勾选
            </span>
            <span v-else-if="changedCount === 0" class="fw-foot-muted">整理结果与原文一致，无需应用</span>
            <span v-else class="fw-foot-muted">只会写入勾选的字段，其余字段保持原样</span>
          </template>
          <span v-else class="fw-foot-muted">整理完成后可逐字段确认再保存</span>
        </div>
        <div class="fw-foot-actions">
          <el-button :disabled="applying" @click="close">取消</el-button>
          <el-button
            type="primary"
            :loading="applying"
            :disabled="!canApply"
            @click="handleApply"
          >
            {{ applyLabel }}<template v-if="selectedCount">（{{ selectedCount }}）</template>
          </el-button>
        </div>
      </footer>
    </div>
  </el-drawer>
</template>

<script setup>
// 题面优化工作台：把「AI 整理 → 人工确认 → 写入」拆成可核对的逐步操作。
//
// 关键约束：
// 1. 预览内容一律先消毒再交给 v-html，与题目详情页同一套白名单；
// 2. 应用必须带 baseHash，服务端据此拒绝基于旧内容的覆盖；
// 3. 只有勾选的字段会被写入，阻断级问题所在的字段默认不勾选。
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import {
  applyFormatProblem,
  applyStatementVersion,
  getFormatJobItem,
  getStatementVersionProposal,
  previewFormatProblem
} from '@/api/problem'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'
import { getErrorCode } from '@/utils/apiError'
import { showApiError } from '@/utils/errorFeedback'
import {
  FORMAT_FIELDS,
  buildApplyPayload,
  buildSummary,
  compareSamples,
  countSeverity,
  defaultSelection,
  describeLineChange,
  diffLines,
  groupIssuesByField,
  normalizeFormatted,
  normalizeSource,
  toFormattedPayload
} from './formatReview'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  problemId: { type: [Number, String], default: null },
  jobId: { type: [Number, String], default: null },
  /** 候选版本 id：传了就走「版本提案 → 应用为新版本」的链路 */
  versionId: { type: [Number, String], default: null },
  initialPreview: { type: Object, default: null },
  defaultIpMode: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'applied'])

const md = createMarkdown({ html: true, math: true })
// 原文多半是纯文本（FPS 导入或人工粘贴），单个换行有意义；不开启 breaks 会把
// 「Input / 4 / 2 7 11 15 / 9」挤成一行，对照时看不出原始结构。
const mdSource = createMarkdown({ html: true, math: true, breaks: true })

const loading = ref(false)
const applying = ref(false)
const failure = ref('')
const preview = ref(null)
const ipRename = ref(false)
const activeKey = ref('content')
const viewMode = ref('compare')
const editing = ref(false)
const selection = ref({})
const values = ref(normalizeFormatted(null))
const sourceValues = ref(normalizeSource(null))

const busy = computed(() => loading.value || applying.value)
// 服务端没回 source 时（例如后端版本较旧）不能假装在对照：既看不到原文，
// 也无法用哈希校验并发修改。此时明确降级为「只读结果」，并禁用应用。
const hasSource = computed(() => Boolean(preview.value?.source))
const activeField = computed(() => FORMAT_FIELDS.find((field) => field.key === activeKey.value) || FORMAT_FIELDS[1])
const summary = computed(() => buildSummary(sourceValues.value, values.value))
const changedCount = computed(() => summary.value.changedKeys.length)
const activeChange = computed(() => summary.value.fields[activeKey.value])
const selectedCount = computed(() => FORMAT_FIELDS.filter((field) => selection.value[field.key]).length)
const selectedKeys = computed(() => FORMAT_FIELDS.filter((field) => selection.value[field.key]).map((field) => field.key))

const issues = computed(() => (Array.isArray(preview.value?.issues) ? preview.value.issues : []))
const groupedIssues = computed(() => groupIssuesByField(issues.value))
const severity = computed(() => countSeverity(issues.value))
const fieldIssues = computed(() => groupedIssues.value[activeKey.value] || [])

// 状态标签把「阻断」和「提醒」分开表达：只有阻断会让应用失败，
// 但提醒也需要被看见，否则「通过质量检查」会给出错误的安心感。
const firstIssueField = computed(() => {
  const blocking = issues.value.find((issue) => issue.severity === 'ERROR')
  return blocking?.field || issues.value[0]?.field || null
})
const statusTagType = computed(() => {
  if (severity.value.error) return 'danger'
  if (severity.value.warn) return 'warning'
  return 'success'
})
const statusTagText = computed(() => {
  if (severity.value.error) return `${severity.value.error} 项阻断`
  if (severity.value.warn) return `${severity.value.warn} 项提醒`
  return '通过质量检查'
})

const skippedByBlocking = ref([])
const limitsSkipped = ref([])

const canApply = computed(() => Boolean(
  preview.value && preview.value.success && hasSource.value && selectedCount.value > 0 &&
  !busy.value && !hasSelectedBlocking.value
))
const hasSelectedBlocking = computed(() => selectedKeys.value.some((key) => errorCount(key) > 0))

const displayTitle = computed(() => {
  if (loading.value) return '正在整理题面'
  if (!preview.value) return props.problemId ? `题目 #${props.problemId}` : '题面优化'
  return preview.value.formatted?.title || preview.value.title || `题目 #${props.problemId}`
})

const applyLabel = computed(() => (props.versionId ? '应用为新版本' : '应用选中字段'))

const displaySubtitle = computed(() => {
  if (!preview.value) return 'AI 整理题面结构、抽取时限与样例，并翻译成中文；确认后才会写入。'
  if (!hasSource.value) return `${preview.value.message || '整理完成'} · 原文快照缺失，无法对照`
  const before = sourceValues.value.content.length
  const after = values.value.content.length
  return `正文 ${before} → ${after} 字 · 样例 ${sourceValues.value.samples.length} → ${values.value.samples.length} 组 · ${preview.value.message || '整理完成'}`
})

const lineChangeText = computed(() => describeLineChange(activeChange.value))

const diffResult = computed(() => {
  if (activeField.value.kind !== 'text') return { truncated: false, rows: [] }
  return diffLines(splitLines(sourceValues.value[activeKey.value]), splitLines(values.value[activeKey.value]))
})

const sampleRows = computed(() => compareSamples(sourceValues.value.samples, values.value.samples))

function splitLines(value) {
  if (!value) return []
  return String(value).replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n')
}

function renderMarkdown(value) {
  if (!value || !String(value).trim()) return '<p class="fw-empty">（空）</p>'
  return md.render(sanitizeStatementMarkup(String(value)))
}

function renderSource(value) {
  if (!value || !String(value).trim()) return '<p class="fw-empty">（空）</p>'
  return mdSource.render(sanitizeStatementMarkup(String(value)))
}

function errorCount(key) {
  return (groupedIssues.value[key] || []).filter((issue) => issue.severity === 'ERROR').length
}

function warnCount(key) {
  return (groupedIssues.value[key] || []).filter((issue) => issue.severity === 'WARN').length
}

function hasError(key) {
  return errorCount(key) > 0
}

function badgeOf(key) {
  // 没有原文基线时任何「已改」都是假的（拿默认值当原文比出来的），不显示
  if (!hasSource.value) return ''
  const change = summary.value.fields[key]
  if (!change || change.kind === 'same') return ''
  if (change.kind === 'added') return '新增'
  if (change.kind === 'removed') return '清空'
  return '已改'
}

function badgeClass(key) {
  const change = summary.value.fields[key]
  return change?.kind === 'removed' ? 'is-danger' : ''
}

function changeLabel(change) {
  if (!change) return ''
  if (change.kind === 'added') return '新增内容'
  if (change.kind === 'removed') return '结果为空'
  if (change.kind === 'modified') return '有修改'
  return '未变化'
}

function sampleTagType(status) {
  if (status === 'same') return 'info'
  if (status === 'changed') return 'warning'
  if (status === 'added') return 'success'
  return 'danger'
}

function sampleStatusLabel(status) {
  return { same: '一致', changed: '已改写', added: '新增', removed: '原文有、结果缺失' }[status] || status
}

function jumpToFirstIssue() {
  if (firstIssueField.value) activeKey.value = firstIssueField.value
}

function toggleField(key, value) {  selection.value = { ...selection.value, [key]: value }
}

function selectChanged() {
  const next = {}
  for (const field of FORMAT_FIELDS) {
    next[field.key] = summary.value.fields[field.key]?.kind !== 'same' && !hasError(field.key)
  }
  selection.value = next
}

function clearSelection() {
  const next = {}
  for (const field of FORMAT_FIELDS) next[field.key] = false
  selection.value = next
}

function toggleEditing() {
  if (!editing.value) viewMode.value = 'result'
  editing.value = !editing.value
}

function restoreSamples() {
  values.value = { ...values.value, samples: sourceValues.value.samples.map((sample) => ({ ...sample })) }
}

function adopt(data) {
  preview.value = data || null
  if (!data || !data.success || !data.formatted) {
    failure.value = data?.message || '整理失败，请稍后重试'
    return
  }
  failure.value = ''
  sourceValues.value = normalizeSource(data.source)
  values.value = normalizeFormatted(data.formatted)
  const sourceText = [
    sourceValues.value.content,
    sourceValues.value.inputDesc,
    sourceValues.value.outputDesc,
    sourceValues.value.hint
  ].join('\n')
  const next = data.source
    ? defaultSelection(summary.value, data.issues, { sourceText })
    : { selection: {}, skipped: [], limitsSkipped: [] }
  selection.value = next.selection
  skippedByBlocking.value = next.skipped
  limitsSkipped.value = next.limitsSkipped || []
  const firstChanged = FORMAT_FIELDS.find((field) => summary.value.fields[field.key]?.kind !== 'same')
  activeKey.value = firstChanged?.key || 'content'
  // 没有原文快照时「对照」没有意义，直接落到结果视图
  viewMode.value = data.source ? 'compare' : 'result'
  editing.value = false
}

async function runPreview() {
  if (!props.problemId) return
  loading.value = true
  failure.value = ''
  preview.value = null
  try {
    let data
    if (props.versionId) {
      const res = await getStatementVersionProposal(props.problemId, props.versionId)
      data = res?.data ?? res
    } else if (props.jobId) {
      const res = await getFormatJobItem(props.jobId, props.problemId)
      data = res?.data ?? res
    } else {
      const res = await previewFormatProblem(props.problemId, ipRename.value)
      data = res?.data ?? res
    }
    adopt(data)
  } catch (error) {
    failure.value = error?.response?.data?.message || error?.message || '整理失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function handleApply() {
  if (!canApply.value) return
  // 正文大幅缩短不再是阻断项（拆分输入输出与样例后变短是正常的，且版本可回退），
  // 但写入前要让教师明确确认一次。
  const contentLoss = issues.value.find(
    (issue) => issue.code === 'CONTENT_LOST' && selection.value[issue.field]
  )
  if (contentLoss) {
    try {
      await ElMessageBox.confirm(
        `${contentLoss.message}。确认按当前内容写入？`,
        '正文明显缩短',
        { type: 'warning', confirmButtonText: '确认写入', cancelButtonText: '再检查一下' }
      )
    } catch {
      return
    }
  }
  applying.value = true
  try {
    const payload = buildApplyPayload({
      problemId: Number(props.problemId),
      baseHash: preview.value.baseHash,
      selection: selection.value,
      values: values.value
    })
    const res = props.versionId
      ? await applyStatementVersion(props.problemId, props.versionId, {
        fields: payload.fields,
        formatted: payload.formatted
      })
      : await applyFormatProblem(payload.problemId, payload)
    const applied = props.versionId
      ? payload.fields
      : (res?.data?.appliedFields || payload.fields)
    ElMessage.success(props.versionId
      ? `已生成新版本，采纳 ${applied.length} 个字段`
      : `已保存 ${applied.length} 个字段`)
    emit('applied', {
      problemId: payload.problemId,
      versionId: res?.data?.id ?? props.versionId ?? null,
      appliedFields: applied
    })
    close()
  } catch (error) {
    if (getErrorCode(error) === 'PROBLEM_CONTENT_CHANGED') {
      failure.value = '题目内容在整理之后已被修改，本次结果作废。请点「重新整理」基于最新内容再生成一次。'
      preview.value = null
      return
    }
    showApiError(error, '保存失败，请稍后重试')
  } finally {
    applying.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}

function handleVisibleChange(visible) {
  if (!visible) close()
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    ipRename.value = props.defaultIpMode
    if (props.initialPreview) {
      adopt(props.initialPreview)
      return
    }
    runPreview()
  },
  // 版本页会以 :key 重挂载组件并直接传 visible=true，此时没有 false→true 的变化，
  // 必须立即执行一次，否则抽屉会是空的。
  { immediate: true }
)

// 供父组件在外部拿到当前提案（例如批量任务里预填标题）
defineExpose({
  reload: runPreview,
  currentFormatted: () => toFormattedPayload(values.value)
})
</script>

<style scoped>
.fw {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-app);
}

.fw-head {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 24px 16px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-light);
}
.fw-head-main { min-width: 0; }
.fw-eyebrow { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.fw-badge {
  font-size: 12px;
  font-weight: 600;
  color: var(--accent-primary);
  background: var(--bg-surface-hover);
  border: 1px solid var(--border-light);
  border-radius: 999px;
  padding: 2px 10px;
}
.fw-id { font-size: 12px; color: var(--text-secondary); }
.fw-tag-clickable { cursor: pointer; }
.fw-title {
  margin: 0;
  font-size: 22px;
  line-height: 1.3;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.fw-sub { margin: 6px 0 0; font-size: 13px; color: var(--text-secondary); }
.fw-head-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.fw-switch { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-regular); }

.fw-state {
  flex: 1;
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 48px 32px;
  color: var(--text-regular);
}
.fw-state-icon { font-size: 28px; color: var(--accent-primary, #2f54eb); margin-top: 2px; }
.fw-state-error .fw-state-icon { color: var(--el-color-danger); }
.fw-state-title { margin: 0 0 8px; font-size: 16px; font-weight: 600; color: var(--text-primary); }
.fw-state-desc { margin: 0 0 6px; font-size: 13px; line-height: 1.7; max-width: 720px; }

.fw-body { flex: 1; min-height: 0; display: flex; }

.fw-nav {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-light);
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
  padding: 12px 10px 8px;
  overflow-y: auto;
}
.fw-nav-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary);
  padding: 0 8px 8px;
}
.fw-nav-count { font-variant-numeric: tabular-nums; }
.fw-nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 8px;
  padding: 8px 8px;
  cursor: pointer;
  text-align: left;
  color: var(--text-regular);
  font-size: 13px;
}
.fw-nav-item:hover { background: var(--bg-surface-hover); }
.fw-nav-item.active {
  background: var(--bg-surface-hover);
  border-color: var(--border-base);
  box-shadow: inset 2px 0 0 var(--accent-primary);
  color: var(--text-primary);
  font-weight: 600;
}
.fw-nav-item.blocked .fw-nav-label { color: var(--el-color-danger); }
.fw-nav-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fw-nav-badges { display: flex; align-items: center; gap: 6px; }
.fw-chip {
  font-size: 11px;
  color: var(--text-secondary);
  background: var(--bg-app);
  border-radius: 4px;
  padding: 1px 5px;
}
.fw-chip.is-danger { color: var(--el-color-danger); }
.fw-dot {
  min-width: 16px;
  height: 16px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  padding: 0 4px;
  color: #fff;
}
.fw-dot.is-error { background: var(--el-color-danger); }
.fw-dot.is-warn { background: var(--el-color-warning); }
.fw-nav-foot {
  margin-top: auto;
  display: flex;
  gap: 4px;
  padding: 10px 4px 0;
  border-top: 1px solid var(--border-light);
}

.fw-main { flex: 1; min-width: 0; overflow-y: auto; padding: 18px 24px 28px; }
.fw-field-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 12px;
}
.fw-field-title h3 { margin: 0; font-size: 16px; color: var(--text-primary); }
.fw-field-meta { font-size: 12px; color: var(--text-secondary); }
.fw-field-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

.fw-issues {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}
.fw-issue {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  padding: 7px 10px;
  border-radius: 6px;
  border: 1px solid transparent;
  background: var(--bg-surface);
}
.fw-issue-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; }
.fw-issue.error { border-color: rgba(220, 38, 38, 0.35); background: rgba(220, 38, 38, 0.08); }
.fw-issue.error .fw-issue-dot { background: var(--el-color-danger); }
.fw-issue.warn { border-color: rgba(217, 119, 6, 0.35); background: rgba(217, 119, 6, 0.08); }
.fw-issue.warn .fw-issue-dot { background: var(--el-color-warning); }
.fw-issue.info .fw-issue-dot { background: var(--el-color-info); }
.fw-issue-text { flex: 1; color: var(--text-regular); }
.fw-issue-code { font-size: 11px; color: var(--text-secondary); }

.fw-compare { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.fw-pane {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  overflow: hidden;
}
.fw-pane.is-result { border-color: var(--accent-primary); }
.fw-pane-head {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-surface-hover);
  border-bottom: 1px solid var(--border-light);
}
.fw-pane-body { padding: 14px 16px; max-height: calc(100vh - 380px); overflow-y: auto; }

.fw-render {
  padding: 16px 18px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  min-height: 200px;
}

.fw-source {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
  overflow: hidden;
}
.fw-diff { max-height: calc(100vh - 360px); overflow: auto; font-family: Consolas, Monaco, monospace; font-size: 12.5px; }
.fw-diff-row { display: flex; align-items: flex-start; padding: 1px 0; }
.fw-diff-row.add { background: rgba(82, 196, 26, 0.12); }
.fw-diff-row.del { background: rgba(255, 77, 79, 0.12); }
.fw-diff-sign { width: 22px; text-align: center; color: var(--text-secondary); flex-shrink: 0; user-select: none; }
.fw-diff-text { margin: 0; padding: 0 8px 0 0; white-space: pre-wrap; word-break: break-word; color: var(--text-regular); }

.fw-edit-area :deep(.el-textarea__inner) { font-family: Consolas, Monaco, monospace; font-size: 13px; line-height: 1.7; }
.fw-edit-tip { margin-top: 8px; font-size: 12px; color: var(--text-secondary); }

.fw-number { display: flex; align-items: center; gap: 14px; }
.fw-number-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 14px;
  border: 1px dashed var(--border-base);
  border-radius: 8px;
  background: var(--bg-surface);
}
.fw-number-label { font-size: 12px; color: var(--text-secondary); }
.fw-number-input { width: 180px; }
.fw-number-unit { font-size: 13px; color: var(--text-secondary); }
.fw-limit-note { font-size: 12px; color: var(--el-color-warning-dark-2); }

.fw-samples-meta { font-size: 12px; color: var(--text-secondary); margin-bottom: 10px; }
.fw-samples { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.fw-sample {
  border: 1px solid var(--border-light);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--bg-surface);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fw-sample.changed { border-color: rgba(217, 119, 6, 0.45); }
.fw-sample.added { border-color: rgba(22, 163, 74, 0.45); }
.fw-sample.removed { border-color: rgba(220, 38, 38, 0.45); background: rgba(220, 38, 38, 0.06); }
.fw-sample-head { display: flex; justify-content: space-between; align-items: center; }
.fw-sample-index { font-size: 12px; color: var(--text-secondary); }
.fw-sample-readonly {
  margin: 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--bg-app);
  font-family: Consolas, Monaco, monospace;
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-secondary);
}
.fw-code :deep(.el-textarea__inner) { font-family: Consolas, Monaco, monospace; font-size: 12.5px; }

.fw-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 12px 24px;
  background: var(--bg-surface);
  border-top: 1px solid var(--border-light);
}
.fw-foot-info { display: flex; align-items: center; gap: 12px; font-size: 13px; color: var(--text-secondary); }
.fw-foot-strong { color: var(--text-primary); font-weight: 600; }
.fw-foot-warn { color: var(--el-color-warning-dark-2); }
.fw-foot-muted { color: var(--text-secondary); }
.fw-foot-actions { display: flex; gap: 10px; }

:deep(.fw-empty) { color: var(--text-secondary); font-style: italic; }

@media (max-width: 1100px) {
  .fw-nav { width: 190px; }
  .fw-compare { grid-template-columns: 1fr; }
}

/* 站点暗色主题只覆盖基础 token，浅蓝底（--accent-primary-light）不会跟着变深，
   直接拿来当背景会出现浅底浅字。这里补上暗色下真正需要的对比度。 */
.dark .fw-badge { color: #7ea6ff; border-color: #2f4a85; }
.dark .fw-nav-item.active { border-color: #2f4a85; background: #1c2129; }
.dark .fw-chip { background: #21262d; color: #8b949e; }
.dark .fw-issue { background: #161b22; }
.dark .fw-issue.error { background: rgba(248, 81, 73, 0.12); border-color: rgba(248, 81, 73, 0.35); }
.dark .fw-issue.warn { background: rgba(210, 153, 34, 0.12); border-color: rgba(210, 153, 34, 0.35); }
.dark .fw-foot-warn { color: #d29922; }
</style>

<style>
/* Drawer 自身不带内边距，工作台内部自己排版；scoped 无法命中 teleport 出去的容器 */
.format-workbench .el-drawer__body { padding: 0; display: flex; flex-direction: column; overflow: hidden; }
.format-workbench .el-drawer__header { display: none; }
</style>
