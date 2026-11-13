<template>
  <aside class="review-panel" aria-label="审阅与保存">
    <header class="panel-head">
      <div class="panel-head-main">
        <span class="eyebrow-badge">审阅</span>
        <h2 class="panel-title">质量与落库</h2>
      </div>
      <el-tag v-if="statusInfo.label" size="small" :type="statusInfo.type" effect="plain">
        {{ statusInfo.label }}
      </el-tag>
    </header>

    <div class="panel-body">
      <!-- 一句话结论 -->
      <p class="summary" :class="`is-${summary.tone}`" role="status">{{ summary.text }}</p>

      <div v-if="counts.total" class="count-row">
        <span v-if="counts.ERROR" class="count is-error">阻断 {{ counts.ERROR }}</span>
        <span v-if="counts.WARN" class="count is-warn">提醒 {{ counts.WARN }}</span>
        <span v-if="counts.INFO" class="count is-info">说明 {{ counts.INFO }}</span>
      </div>

      <!-- 问题清单 -->
      <section class="section">
        <h3 class="section-title">问题清单</h3>
        <el-empty v-if="!grouped.length" description="没有发现质量问题" :image-size="52" />
        <div v-for="group in grouped" :key="group.field" class="issue-group">
          <button type="button" class="group-head" @click="$emit('locate-issue', group.field)">
            <span class="group-label">{{ group.label }}</span>
            <span class="group-count">{{ group.issues.length }}</span>
          </button>
          <ul class="issue-list">
            <li
              v-for="issue in group.issues"
              :key="`${issue.code}-${issue.message}`"
              class="issue-item"
              :class="issue.severity?.toLowerCase()"
            >
              <span class="issue-dot" />
              <div class="issue-body">
                <span class="issue-text">{{ issue.message }}</span>
                <code class="issue-code">{{ issue.code }}</code>
              </div>
            </li>
          </ul>
        </div>
      </section>

      <!-- 字段勾选 -->
      <section class="section">
        <h3 class="section-title">保存哪些字段</h3>
        <p class="section-hint">只写入勾选的字段；题名与题目描述为必填，始终写入。</p>
        <div class="field-list">
          <label
            v-for="field in FIELD_DEFS"
            :key="field.key"
            class="field-item"
            :class="{ disabled: !isSelectable(field.key) }"
          >
            <el-checkbox
              :model-value="Boolean(selection[field.key])"
              :disabled="!isSelectable(field.key) || saving"
              :aria-label="`选择字段：${field.label}`"
              @update:model-value="toggle(field.key, $event)"
            />
            <span class="field-name">{{ field.label }}</span>
            <span v-if="field.required" class="field-badge">必填</span>
            <span v-else-if="!isSelectable(field.key)" class="field-badge is-muted">无内容</span>
          </label>
        </div>
      </section>

      <!-- 时限与内存 -->
      <section class="section">
        <h3 class="section-title">评测限制</h3>
        <div class="limit-row">
          <label class="limit-item" for="review-time-limit">
            <span class="limit-label">时间 (ms)</span>
            <el-input-number
              id="review-time-limit"
              v-model="timeLimit"
              :min="100"
              :step="100"
              :disabled="saving"
              controls-position="right"
            />
          </label>
          <label class="limit-item" for="review-memory-limit">
            <span class="limit-label">内存 (MB)</span>
            <el-input-number
              id="review-memory-limit"
              v-model="memoryLimit"
              :min="16"
              :step="16"
              :disabled="saving"
              controls-position="right"
            />
          </label>
        </div>
      </section>

      <!-- 局部重写 -->
      <section class="section">
        <h3 class="section-title">局部重写</h3>
        <p class="section-hint">只重写一个字段，会生成一个新方案，原方案保留。</p>
        <el-select v-model="rewriteField" size="small" placeholder="选择要重写的字段" class="w-full">
          <el-option v-for="field in rewriteOptions" :key="field.key" :label="field.label" :value="field.key" />
        </el-select>
        <el-input
          v-model="rewriteInstruction"
          size="small"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="如：把背景换成食堂排队，数据范围放大到 10^6"
          class="mt8"
        />
        <el-button
          size="small"
          class="mt8"
          :disabled="saving || !rewriteField || !rewriteInstruction.trim()"
          @click="submitRewrite"
        >
          重写该字段
        </el-button>
      </section>

      <!-- 已落库结果 -->
      <el-alert
        v-if="problemId"
        type="success"
        :closable="false"
        class="saved-block"
        :title="`题目 #${problemId} 已创建（私有）`"
      >
        <div class="saved-actions">
          <el-button size="small" @click="openEditor">打开题目编辑器</el-button>
          <el-button size="small" type="primary" @click="gotoStd">去生成标准答案</el-button>
        </div>
      </el-alert>

      <el-alert
        v-if="errorCount && !problemId"
        type="warning"
        :closable="false"
        class="allow-block"
        title="存在阻断问题，确认后仍可保存"
      >
        <el-checkbox v-model="allowIssues" :disabled="saving" aria-label="确认后仍然保存">
          我已核对上述问题，仍然保存
        </el-checkbox>
      </el-alert>
    </div>

    <footer class="panel-foot">
      <span class="foot-info">已选 {{ selectedCount }} 项</span>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!canSubmit"
        @click="submitSave"
      >
        {{ submitLabel }}
      </el-button>
    </footer>
  </aside>
</template>

<script setup>
// 右栏审阅：问题清单 → 字段勾选 → 评测限制 → 保存/重写。
// 所有判断走 draftModel，组件只负责呈现与收集输入。
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  FIELD_DEFS,
  buildSavePayload,
  countIssues,
  defaultSelection,
  groupIssues,
  reviewSummary,
  selectableFields,
  statusMeta
} from './draftModel'

const props = defineProps({
  draft: { type: Object, default: () => ({}) },
  saving: { type: Boolean, default: false },
  canSave: { type: Boolean, default: true }
})

const emit = defineEmits(['save', 'rewrite', 'locate-issue'])

const router = useRouter()

// 与画布一致：detail 与 DraftVO 两种入参都兼容
const detail = computed(() => (props.draft?.draft ? props.draft : null))
const vo = computed(() => detail.value?.draft || props.draft || {})
const content = computed(() => detail.value?.content || props.draft?.content || {})
const issues = computed(() => detail.value?.issues || props.draft?.issues || [])
const statusInfo = computed(() => statusMeta(vo.value.status, vo.value.phase))
const problemId = computed(() => vo.value.problemId || null)

const grouped = computed(() => groupIssues(issues.value))
const counts = computed(() => countIssues(issues.value))
const errorCount = computed(() => counts.value.ERROR)
const summary = computed(() => reviewSummary(content.value, issues.value))
const selectable = computed(() => selectableFields(content.value))

// 服务端只接受这 5 个重写目标；inputDesc/outputDesc 不支持局部重写
const REWRITE_KEYS = ['title', 'content', 'hint', 'tags', 'samples']
const rewriteOptions = computed(() => FIELD_DEFS.filter((field) => REWRITE_KEYS.includes(field.key)))

const selection = ref({})
const allowIssues = ref(false)
const timeLimit = ref(1000)
const memoryLimit = ref(512)
const rewriteField = ref('')
const rewriteInstruction = ref('')

const isSelectable = (key) => Boolean(selectable.value[key])
const toggle = (key, value) => { selection.value = { ...selection.value, [key]: value } }

const effectiveFields = computed(() =>
  FIELD_DEFS.map((f) => f.key).filter((key) => selection.value[key] && isSelectable(key))
)
const selectedCount = computed(() => effectiveFields.value.length)

// 阻断问题必须先确认才能保存；其余情况由父级的 canSave 决定（例如保存中/未就绪）
const canSubmit = computed(() => {
  if (problemId.value) return false
  if (!props.canSave || props.saving || !selectedCount.value) return false
  if (errorCount.value > 0 && !allowIssues.value) return false
  return true
})
const submitLabel = computed(() => (problemId.value ? '已保存' : '保存为私有题目'))

// 只补「用户还没动过」的字段：内容后到时会自动勾上，用户手动取消的不会被弹回
const applyDefaults = (value) => {
  const base = defaultSelection(value)
  const next = { ...selection.value }
  for (const key of Object.keys(base)) {
    if (next[key] === undefined) next[key] = base[key]
  }
  selection.value = next
}

// 切换草稿时重置勾选与确认态
watch(
  () => vo.value.id,
  () => {
    selection.value = {}
    applyDefaults(content.value)
    allowIssues.value = false
    timeLimit.value = Number(content.value.timeLimit) || 1000
    memoryLimit.value = Number(content.value.memoryLimit) || 512
    rewriteField.value = ''
    rewriteInstruction.value = ''
  },
  { immediate: true }
)

// 同一条草稿内容更新时只补缺失勾选
watch(() => content.value, (value) => applyDefaults(value))

const submitSave = () => {
  if (!canSubmit.value) return
  emit('save', buildSavePayload({
    fields: effectiveFields.value,
    timeLimit: timeLimit.value,
    memoryLimit: memoryLimit.value,
    allowIssues: allowIssues.value
  }))
}

const submitRewrite = () => {
  const instruction = rewriteInstruction.value.trim()
  if (!rewriteField.value || !instruction) return
  emit('rewrite', { targetField: rewriteField.value, instruction })
  rewriteInstruction.value = ''
}

const openEditor = () => {
  if (problemId.value) router.push(`/admin/problem/edit/${problemId.value}`)
}
const gotoStd = () => {
  if (problemId.value) router.push(`/admin/problem/edit/${problemId.value}?tab=std`)
}
</script>

<style scoped>
.review-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px 10px;
  border-bottom: 1px solid var(--border-light);
}
.panel-head-main { min-width: 0; }
.eyebrow-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent-primary);
  background: var(--bg-surface-hover);
  border: 1px solid var(--border-light);
  border-radius: 999px;
  padding: 1px 8px;
}
.panel-title { margin: 6px 0 0; font-size: 15px; line-height: 1.3; color: var(--text-primary); }

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 14px 16px;
  overscroll-behavior: contain;
}

.summary {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.6;
  border: 1px solid var(--border-light);
  background: var(--bg-app);
  color: var(--text-regular);
}
.summary.is-ok { color: var(--accent-success); background: var(--accent-success-light); border-color: transparent; }
.summary.is-warn { color: var(--accent-warning); background: var(--accent-warning-light); border-color: transparent; }
.summary.is-error { color: var(--accent-danger); background: var(--accent-danger-light); border-color: transparent; }

.count-row { display: flex; gap: 8px; margin-bottom: 10px; }
.count { font-size: 12px; padding: 1px 8px; border-radius: 999px; background: var(--bg-app); color: var(--text-regular); }
.count.is-error { color: var(--accent-danger); }
.count.is-warn { color: var(--accent-warning); }
.count.is-info { color: var(--accent-info); }

.section { margin-bottom: 18px; }
.section:last-of-type { margin-bottom: 12px; }
.section-title { margin: 0 0 6px; font-size: 13px; font-weight: 600; color: var(--text-primary); }
.section-hint { margin: 0 0 8px; font-size: 12px; line-height: 1.6; color: var(--text-secondary); }

.issue-group { margin-bottom: 10px; }
.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 4px 6px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-regular);
  font-size: 12.5px;
  cursor: pointer;
}
.group-head:hover { background: var(--bg-surface-hover); color: var(--text-primary); }
.group-label { font-weight: 600; }
.group-count { font-size: 11px; color: var(--text-secondary); }
.issue-list { margin: 2px 0 0; padding: 0 0 0 6px; list-style: none; }
.issue-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  margin-bottom: 4px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  line-height: 1.55;
  background: var(--bg-app);
}
.issue-item.error { background: var(--accent-danger-light); }
.issue-item.warn { background: var(--accent-warning-light); }
.issue-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex-shrink: 0;
  background: var(--accent-info);
}
.issue-item.error .issue-dot { background: var(--accent-danger); }
.issue-item.warn .issue-dot { background: var(--accent-warning); }
.issue-body { min-width: 0; }
.issue-text { display: block; color: var(--text-regular); overflow-wrap: anywhere; }
.issue-code { font-size: 11px; color: var(--text-placeholder); }

.field-list { display: flex; flex-direction: column; gap: 2px; }
.field-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 4px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-regular);
  cursor: pointer;
}
.field-item:hover { background: var(--bg-surface-hover); }
.field-item.disabled { cursor: default; opacity: 0.75; }
.field-name { min-width: 0; }
.field-badge {
  font-size: 11px;
  color: var(--accent-primary);
  background: var(--accent-primary-light);
  border-radius: 999px;
  padding: 0 6px;
}
.field-badge.is-muted { color: var(--text-placeholder); background: var(--bg-app); }

.limit-row { display: flex; gap: 10px; }
.limit-item { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 0; }
.limit-label { font-size: 12px; color: var(--text-secondary); }
.limit-item :deep(.el-input-number) { width: 100%; }

.mt8 { margin-top: 8px; }
.w-full { width: 100%; }

.saved-block { margin-top: 8px; }
.saved-actions { display: flex; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
.allow-block { margin-top: 8px; }

.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid var(--border-light);
}
.foot-info { font-size: 12px; color: var(--text-secondary); }
</style>
