<template>
  <section class="canvas" aria-label="题面画布">
    <header class="canvas-head">
      <div class="head-main">
        <div class="eyebrow-row">
          <span class="eyebrow-badge">题面</span>
          <el-tag v-if="status" size="small" :type="statusInfo.type" effect="plain">{{ statusText }}</el-tag>
        </div>
        <h2 class="canvas-title">{{ displayTitle }}</h2>
        <p class="canvas-meta">
          <span>{{ charCount }} 字</span>
          <span>·</span>
          <span>样例 {{ samples.length }} 组</span>
          <span>·</span>
          <span>{{ difficultyText }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-button
          v-if="isFailed"
          size="small"
          type="primary"
          @click="$emit('retry')"
        >
          重试
        </el-button>
        <el-button
          v-if="isReady"
          size="small"
          :type="editingField ? 'primary' : 'default'"
          @click="$emit('toggle-edit', editingField ? null : 'content')"
        >
          {{ editingField ? '完成编辑' : '编辑' }}
        </el-button>
        <el-button size="small" :disabled="!isReady" @click="copyAll">复制全文</el-button>
        <el-button
          v-if="activeDraftId"
          size="small"
          text
          type="danger"
          title="删除本次生成"
          @click="$emit('delete')"
        >
          删除
        </el-button>
      </div>
    </header>

    <!-- 方案切换条：>1 才出现 -->
    <div v-if="variants.length > 1" class="variant-bar" role="group" aria-label="生成方案">
      <button
        v-for="item in variants"
        :key="item.id"
        type="button"
        :aria-pressed="item.id === activeId"
        class="variant-chip"
        :class="{ active: item.id === activeId }"
        @click="$emit('update:active-id', item.id)"
      >
        <span class="variant-no">{{ variantLabel(item) }}</span>
        <span class="variant-title">{{ item.title || '生成中…' }}</span>
        <span v-if="issueCount(item)" class="variant-issues" :title="`${issueCount(item)} 项问题`">
          {{ issueCount(item) }}
        </span>
      </button>
    </div>

    <div class="canvas-body">
      <!-- 非就绪态：只占位，不做假进度 -->
      <div v-if="!isReady" class="canvas-placeholder">
        <el-icon :class="['placeholder-icon', { 'is-danger': isFailed, 'is-loading': !isFailed && !isCanceled }]">
          <component :is="isFailed || isCanceled ? WarningFilled : Loading" />
        </el-icon>
        <p class="placeholder-title">{{ statusText }}</p>
        <p v-if="isFailed" class="placeholder-desc">
          {{ failureText }}
        </p>
        <p v-else-if="isCanceled" class="placeholder-desc">本次生成已取消，结果未保留。可以调整简报后重新生成。</p>
        <p v-else class="placeholder-desc">生成完成后会自动展示在这里；关闭页面不会中断服务端任务。</p>
      </div>

      <template v-else>
        <!-- 标题 -->
        <div class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">标题</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', 'title')">改</el-button>
          </div>
          <el-input
            v-if="editingField === 'title'"
            :model-value="content.title"
            maxlength="120"
            show-word-limit
            @update:model-value="setField('title', $event)"
          />
          <h1 v-else class="statement-title">{{ content.title || '（未生成标题）' }}</h1>
        </div>

        <!-- 正文 -->
        <div class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">题目描述</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', 'content')">改</el-button>
          </div>
          <el-input
            v-if="editingField === 'content'"
            :model-value="content.content"
            type="textarea"
            :rows="14"
            placeholder="支持 Markdown 与 LaTeX，公式用 $...$"
            class="code-area"
            @update:model-value="setField('content', $event)"
          />
          <div v-else class="markdown-body" v-html="render(content.content)" />
        </div>

        <!-- 输入 / 输出格式 -->
        <div v-for="block in FORMAT_BLOCKS" :key="block.key" class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">{{ block.label }}</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', block.key)">改</el-button>
          </div>
          <el-input
            v-if="editingField === block.key"
            :model-value="content[block.key]"
            type="textarea"
            :rows="3"
            @update:model-value="setField(block.key, $event)"
          />
          <div v-else-if="content[block.key]" class="markdown-body" v-html="render(content[block.key])" />
          <p v-else class="field-empty">（空）</p>
        </div>

        <!-- 提示 -->
        <div class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">提示</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', 'hint')">改</el-button>
          </div>
          <el-input
            v-if="editingField === 'hint'"
            :model-value="content.hint"
            type="textarea"
            :rows="3"
            @update:model-value="setField('hint', $event)"
          />
          <div v-else-if="content.hint" class="markdown-body" v-html="render(content.hint)" />
          <p v-else class="field-empty">（无提示）</p>
        </div>

        <!-- 标签 + 难度 -->
        <div class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">标签与难度</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', 'tags')">改</el-button>
          </div>
          <el-select
            v-if="editingField === 'tags'"
            :model-value="content.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :multiple-limit="TAG_LIMIT"
            placeholder="回车创建，最多 6 个"
            class="w-full"
            @update:model-value="setTags"
          >
            <el-option v-for="t in content.tags" :key="t" :label="t" :value="t" />
          </el-select>
          <div v-else class="tag-row">
            <el-tag v-for="t in content.tags" :key="t" size="small" effect="light" type="primary">{{ t }}</el-tag>
            <span v-if="!content.tags.length" class="field-empty">（无标签）</span>
          </div>
          <p class="field-help">单个标签 ≤20 字，最多 {{ TAG_LIMIT }} 个；输入 # 会被忽略</p>
        </div>

        <!-- 样例 -->
        <div class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">样例（{{ samples.length }} 组）</h3>
            <el-button v-if="!editingField" text size="small" @click="$emit('toggle-edit', 'samples')">改</el-button>
          </div>

          <div class="sample-grid">
            <article v-for="(sample, index) in samples" :key="index" class="sample-card">
              <div class="sample-head">
                <span class="sample-index">样例 {{ index + 1 }}</span>
                <el-button
                  v-if="editingField === 'samples'"
                  text
                  type="danger"
                  size="small"
                  :disabled="samples.length <= 1"
                  :title="samples.length <= 1 ? '至少保留 1 组样例' : '删除该组'"
                  @click="removeSample(index)"
                >
                  删除
                </el-button>
                <button
                  v-else
                  type="button"
                  class="sample-copy"
                  :aria-label="`复制样例 ${index + 1} 的输入`"
                  @click="copySample(sample.input, index, 'input')"
                >
                  <el-icon><component :is="copiedKey === `${index}-input` ? Check : CopyDocument" /></el-icon>
                  {{ copiedKey === `${index}-input` ? '已复制' : '复制' }}
                </button>
              </div>

              <template v-if="editingField === 'samples'">
                <el-input
                  :model-value="sample.input"
                  type="textarea"
                  :rows="4"
                  placeholder="样例输入"
                  class="code-area"
                  @update:model-value="updateSample(index, 'input', $event)"
                />
                <el-input
                  :model-value="sample.output"
                  type="textarea"
                  :rows="4"
                  placeholder="样例输出"
                  class="code-area"
                  @update:model-value="updateSample(index, 'output', $event)"
                />
              </template>
              <template v-else>
                <div class="sample-block">
                  <div class="sample-label">输入</div>
                  <pre :class="{ 'is-empty': !sample.input }">{{ sample.input || '（空）' }}</pre>
                </div>
                <div class="sample-block">
                  <div class="sample-label">输出</div>
                  <pre :class="{ 'is-empty': !sample.output }">{{ sample.output || '（空）' }}</pre>
                </div>
              </template>
            </article>
          </div>

          <div v-if="editingField === 'samples'" class="sample-actions">
            <el-button
              size="small"
              :disabled="samples.length >= SAMPLE_LIMIT"
              :title="samples.length >= SAMPLE_LIMIT ? `最多 ${SAMPLE_LIMIT} 组` : '添加一组'"
              @click="addSample"
            >
              + 添加样例
            </el-button>
            <span class="field-help">输入与输出同时为空的组会被忽略，重复样例会自动合并</span>
          </div>
          <el-empty v-else-if="!samples.length" description="本次没有提取到样例" :image-size="60" />
        </div>

        <!-- 相似题目 -->
        <div v-if="similar.length" class="field-block">
          <div class="field-row-head">
            <h3 class="field-label">题库中的相似题目</h3>
            <el-button text size="small" @click="$emit('refresh-similar')">刷新</el-button>
          </div>
          <ul class="similar-list">
            <li v-for="item in similar" :key="item.problemId">
              <router-link :to="`/problem/${item.problemId}`" class="similar-link">#{{ item.problemId }}</router-link>
              <span class="similar-title">{{ item.title }}</span>
              <span class="similar-meta">{{ getDifficultyLabel(item.difficulty ?? item.score) }}</span>
            </li>
          </ul>
        </div>
      </template>
    </div>
  </section>
</template>

<script setup>
// 中栏画布：渲染 / 源码切换、字段内联编辑、样例卡片、方案切换。
// 只负责呈现与局部编辑，所有写库动作由父级处理（emit update:content）。
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, CopyDocument, Loading, WarningFilled } from '@element-plus/icons-vue'
import { createMarkdown } from '@/utils/markdown'
import { sanitizeStatementMarkup } from '@/utils/statement-markup'
import { getDifficultyLabel } from '@/utils/difficulty'
import { normalizeTags, statusMeta, toStatementMarkdown } from './draftModel'

const TAG_LIMIT = 6
const SAMPLE_LIMIT = 8
const FORMAT_BLOCKS = [
  { key: 'inputDesc', label: '输入格式' },
  { key: 'outputDesc', label: '输出格式' }
]

const props = defineProps({
  draft: { type: Object, default: () => ({}) },
  variants: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  editingField: { type: String, default: null },
  similar: { type: Array, default: () => [] }
})

const emit = defineEmits([
  'update:active-id',
  'toggle-edit',
  'update:content',
  'refresh-similar',
  'copy',
  'retry',
  'delete'
])

// html 与题目详情页一致：先消毒再渲染，避免 v-html 直出 AI 内容
const md = createMarkdown({ html: true, math: true })
const render = (text) => (text ? md.render(sanitizeStatementMarkup(String(text))) : '')

// detail 与 DraftVO 两种入参都兼容：`GET /{id}` 返回 {draft, brief, content, ...}，
// 列表项只是 DraftVO。父级传哪种都不会渲染空白。
const detail = computed(() => (props.draft?.draft ? props.draft : null))
const vo = computed(() => detail.value?.draft || props.draft || {})
const content = computed(() => detail.value?.content || props.draft?.content || {})
const samples = computed(() => content.value.samples || [])
// 兼容 CANCELED / CANCELLED 两种拼写，避免后端改名导致整页空白
const status = computed(() => String(vo.value.status || '').replace('CANCELLED', 'CANCELED'))
const isReady = computed(() => status.value === 'READY')
const isFailed = computed(() => status.value === 'FAILED')
const isCanceled = computed(() => status.value === 'CANCELED')
const activeDraftId = computed(() => vo.value.id || vo.value.draftId || null)
const displayTitle = computed(() => content.value.title || vo.value.title || '尚未生成题面')
const charCount = computed(() => (content.value.content || '').replace(/\s/g, '').length)
const difficultyText = computed(() => {
  const score = content.value.difficultyScore
  return score ? `${getDifficultyLabel(score)} · ${score} 分` : '难度未定'
})
// 失败原因按稳定错误码给事实描述，未知码回落到服务端 message
const failureText = computed(() => {
  const map = {
    AI_DRAFT_TIMEOUT: '服务端任务超时（20 分钟未完成），可删除后重新生成。',
    UPSTREAM_TIMEOUT: '上游模型网关超时，稍后重试通常可以恢复。',
    AI_OUTPUT_LIMIT: '模型输出被截断，建议缩短正文或减少样例组数后重试。',
    AI_RESPONSE_INVALID: '模型返回的内容无法解析，重试会重新生成一份。',
    AI_QUOTA_EXCEEDED: '本次生成超出配额，请联系管理员或稍后再试。'
  }
  return map[vo.value.errorCode] || vo.value.message || '生成没有完成，可以重试或调整简报后重新生成。'
})
const statusInfo = computed(() => statusMeta(status.value, vo.value.phase))
const statusText = computed(() => {
  if (status.value === 'RUNNING' && vo.value.phase === 'GENERATING') return '模型生成中'
  return statusInfo.value.label
})

const variantLabel = (item) => String.fromCharCode(64 + (item.variantNo || 1))
const issueCount = (item) => Number(item.errorCount || 0) + Number(item.warnCount || 0)

// 所有字段写入统一回传整份 content，父级只有一个写入点
const patch = (next) => emit('update:content', { ...content.value, ...next })
const setField = (key, value) => patch({ [key]: value })

const setTags = (tags) => patch({ tags: normalizeTags(tags, TAG_LIMIT) })

const updateSample = (index, key, value) => {
  const next = samples.value.map((s, i) => (i === index ? { ...s, [key]: value } : s))
  patch({ samples: next })
}
const addSample = () => patch({ samples: [...samples.value, { input: '', output: '', explanation: '' }] })
const removeSample = (index) => patch({ samples: samples.value.filter((_, i) => i !== index) })

// 复制：优先 clipboard API，非安全上下文回退 execCommand（与题面样例组件同策略）
const copiedKey = ref('')
let copiedTimer = null
const writeClipboard = async (text) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(area)
  if (!ok) throw new Error('copy failed')
}

const copySample = async (text, index, part) => {
  try {
    await writeClipboard(text || '')
    copiedKey.value = `${index}-${part}`
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copiedKey.value = '' }, 1500)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

const copyAll = async () => {
  const text = toStatementMarkdown(content.value)
  try {
    await writeClipboard(text)
    ElMessage.success('题面已复制')
    emit('copy', text)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

onBeforeUnmount(() => {
  if (copiedTimer) clearTimeout(copiedTimer)
})
</script>

<style scoped>
.canvas {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.canvas-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px 10px;
  border-bottom: 1px solid var(--border-light);
}
.head-main { min-width: 0; }
.eyebrow-row { display: flex; align-items: center; gap: 6px; }
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
.canvas-title {
  margin: 6px 0 0;
  font-size: 18px;
  line-height: 1.35;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.canvas-meta {
  margin: 4px 0 0;
  display: flex;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

.variant-bar {
  display: flex;
  gap: 8px;
  padding: 8px 18px;
  overflow-x: auto;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-app);
}
.variant-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 4px 10px;
  border: 1px solid var(--border-base);
  border-radius: 999px;
  background: var(--bg-surface);
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
}
.variant-chip.active {
  border-color: var(--accent-primary);
  color: var(--accent-primary);
  font-weight: 600;
}
.variant-no { font-weight: 700; }
.variant-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.variant-issues {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--accent-danger);
  color: var(--text-on-accent);
  font-size: 11px;
  line-height: 16px;
  text-align: center;
}

.canvas-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 16px 18px 24px;
  overscroll-behavior: contain;
}

.canvas-placeholder {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 48px 8px;
  color: var(--text-regular);
}
.placeholder-icon { font-size: 24px; color: var(--accent-primary); }
.placeholder-icon.is-danger { color: var(--accent-danger); }
.placeholder-title { margin: 0; font-size: 15px; font-weight: 600; color: var(--text-primary); }
.placeholder-desc { margin: 0; font-size: 13px; line-height: 1.7; max-width: 560px; }

.field-block { margin-bottom: 20px; }
.field-block:last-child { margin-bottom: 0; }
.field-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.field-label {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.statement-title {
  margin: 0;
  font-size: 17px;
  line-height: 1.4;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}
.field-empty { margin: 0; font-size: 13px; color: var(--text-placeholder); }
.field-help { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--text-secondary); }
.w-full { width: 100%; }
.code-area :deep(.el-textarea__inner) {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.7;
}
.tag-row { display: flex; flex-wrap: wrap; gap: 6px; }

.sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.sample-card {
  min-width: 0;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  padding: 10px;
  background: var(--bg-surface);
}
.sample-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.sample-index { font-size: 12px; font-weight: 600; color: var(--text-secondary); }
.sample-copy,
.sample-copy:focus-visible {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 4px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--accent-primary);
  font-size: 12px;
  cursor: pointer;
}
.sample-copy:hover { text-decoration: underline; }
.sample-copy:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 1px; }
.sample-block + .sample-block { margin-top: 8px; }
.sample-label {
  padding: 4px 8px;
  border: 1px solid var(--border-light);
  border-bottom: 0;
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  background: var(--bg-input);
  font-size: 11px;
  color: var(--text-regular);
}
.sample-block pre {
  margin: 0;
  padding: 10px;
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--border-light);
  border-radius: 0 0 var(--radius-sm) var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre;
  tab-size: 4;
}
.sample-block pre.is-empty { color: var(--text-placeholder); }
.sample-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 8px; }

.similar-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.similar-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  font-size: 13px;
  border-bottom: 1px solid var(--border-light);
}
.similar-list li:last-child { border-bottom: 0; }
.similar-link { color: var(--accent-primary); text-decoration: none; flex-shrink: 0; }
.similar-link:hover { text-decoration: underline; }
.similar-title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}
.similar-meta { font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
</style>
