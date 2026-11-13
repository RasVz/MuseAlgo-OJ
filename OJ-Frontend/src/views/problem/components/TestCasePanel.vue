<template>
  <section class="test-case-panel" :class="`dock-${dock}`" aria-label="自测">
    <header class="panel-header">
      <div class="panel-title">
        <strong>自测</strong>
        <span class="panel-hint">仅本页保存</span>
      </div>
      <div class="panel-actions">
        <el-button
          v-if="selected && selected.source !== 'OFFICIAL'"
          text
          type="danger"
          size="small"
          :disabled="running"
          @click="removeCase"
        >
          删除样例
        </el-button>
        <el-button text size="small" @click="emit('toggle-dock')">
          <el-icon class="el-icon--left"><component :is="dock === 'right' ? Bottom : Right" /></el-icon>
          {{ dock === 'right' ? '停靠底部' : '停靠右侧' }}
        </el-button>
        <el-button
          text
          size="small"
          :icon="ArrowDown"
          title="收起自测面板"
          aria-label="收起自测面板"
          @click="emit('toggle-collapse')"
        />
      </div>
    </header>

    <div class="case-tabs" role="tablist" aria-label="样例列表">
      <button
        v-for="(item, index) in cases"
        :key="item.id"
        type="button"
        class="case-tab"
        :class="{ active: item.id === model?.selectedId }"
        role="tab"
        :aria-selected="item.id === model?.selectedId"
        @click="model?.select(item.id)"
      >
        <span>样例 {{ index + 1 }}</span>
        <span class="case-source" :class="sourceClass(item)">{{ sourceLabel(item) }}</span>
      </button>
      <button type="button" class="case-tab add-case" @click="addCase">＋ 新增样例</button>
    </div>

    <div v-if="selected" class="case-body">
      <p v-if="selected.note" class="case-note">{{ selected.note }}</p>

      <div class="case-columns">
        <div class="case-field" :class="{ 'locked-input': selected.source === 'OFFICIAL' }">
          <label :for="`case-input-${selected.id}`" class="case-label">输入</label>
          <el-input
            :id="`case-input-${selected.id}`"
            ref="inputRef"
            :model-value="selected.input"
            type="textarea"
            :rows="4"
            :maxlength="4096"
            :readonly="selected.source === 'OFFICIAL'"
            :disabled="running"
            :title="selected.source === 'OFFICIAL' ? '官方样例的输入不可修改' : undefined"
            :placeholder="selected.source === 'OFFICIAL' ? '官方样例输入' : '输入可以为空'"
            resize="none"
            @update:model-value="value => patch('input', value)"
          />
        </div>
        <div class="case-field">
          <label :for="`case-output-${selected.id}`" class="case-label">输出</label>
          <el-input
            :id="`case-output-${selected.id}`"
            :model-value="outputText"
            type="textarea"
            :rows="4"
            readonly
            placeholder="运行后在此显示输出"
            resize="none"
          />
        </div>
      </div>

      <div v-if="result" class="case-result" role="status">
        <strong>{{ resultLabel }}</strong>
        <small v-if="result.time != null">耗时 {{ result.time }} 秒</small>
        <pre v-if="diagnosticText">{{ diagnosticText }}</pre>
      </div>
    </div>
    <p v-else class="case-empty">还没有样例，点击「新增样例」开始自测。</p>
  </section>
</template>
<script setup>
// 自测面板：官方、自定义与 AI 反例共用一份页面级样例状态。
//
// 本组件只做展示和编辑，不做运行与持久化：
// - 样例直接原地编辑；官方样例的输入只读，自定义样例可改可删；
// - 输出栏只读，展示当前代码最近一次运行的真实输出，不做期望值比较；
// - 运行入口位于编辑器工具栏，由父级触发当前选中样例；
// - 运行结果由父级通过 snapshot + model 计算，内容修改后自动失效。
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, Bottom, Right } from '@element-plus/icons-vue'

const props = defineProps({
  cases: { type: Array, default: () => [] },
  model: { type: Object, default: null },
  snapshot: { type: Object, default: null },
  running: Boolean,
  dock: { type: String, default: 'bottom' }
})

const emit = defineEmits(['toggle-dock', 'toggle-collapse'])

const inputRef = ref(null)

const selected = computed(() => props.cases.find(item => item.id === props.model?.selectedId) ?? null)
// 直接依赖 props.cases，而不是只依赖 selected：
// 运行结果写回后数组会被替换、样例对象引用不变，只依赖 selected 时 computed 不会重算。
const result = computed(() => {
  const item = props.cases.find(candidate => candidate.id === props.model?.selectedId)
  if (!item || !props.snapshot || !props.model) return null
  return props.model.result(item.id, props.snapshot)
})

const STATUS_LABELS = {
  5: '运行超时',
  6: '编译错误',
  7: '运行错误',
  8: '运行错误',
  9: '运行错误',
  10: '运行错误',
  11: '运行错误',
  12: '运行错误'
}

const resultLabel = computed(() => {
  const value = result.value
  if (!value) return ''
  if (value.truncated) return '输出已截断，无法完整比较'
  if ([13, 14].includes(Number(value.status))) return '运行服务异常，请稍后重试'
  if (Number(value.status) !== 3) return STATUS_LABELS[Number(value.status)] || '运行未完成'
  return '运行完成'
})

const outputText = computed(() => result.value?.actual ?? '')
const diagnosticText = computed(() => {
  const value = result.value
  if (!value) return ''
  return value.stderr || value.compile_output || value.message || ''
})

const sourceLabel = item => {
  if (item.source === 'OFFICIAL') return '官方'
  if (item.source === 'AI_VERIFIED') return 'AI 实测'
  return '自定义'
}

const sourceClass = item => ({ OFFICIAL: 'official', CUSTOM: 'custom', AI_VERIFIED: 'ai' })[item.source] || 'custom'

const patch = (field, value) => {
  if (!selected.value || props.running || !props.model) return
  try {
    props.model.patch(selected.value.id, { [field]: value })
  } catch (error) {
    ElMessage.error(error.message)
  }
}

const addCase = () => {
  if (!props.model) return
  try {
    props.model.add()
    nextTick(() => inputRef.value?.focus())
  } catch (error) {
    ElMessage.info(error.message)
  }
}

const removeCase = () => {
  if (!selected.value || !props.model) return
  const index = props.cases.findIndex(item => item.id === selected.value.id)
  try {
    props.model.remove(selected.value.id)
    ElMessage.success(`已删除样例 ${index + 1}`)
  } catch (error) {
    ElMessage.error(error.message)
  }
}
</script>
<style scoped>
.test-case-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
}
.panel-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  background: var(--bg-input);
  border-bottom: 1px solid var(--border-light);
}
.panel-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.panel-title strong {
  font-size: 14px;
  color: var(--text-primary);
}
.panel-hint {
  font-size: 11px;
  color: var(--text-secondary);
}
.panel-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.panel-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.dock-right .panel-hint {
  display: none;
}
.case-tabs {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px 7px;
  overflow-x: auto;
  border-bottom: 1px solid var(--border-light);
  scrollbar-width: thin;
}
.case-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  padding: 3px 10px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-input);
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}
.case-tab:hover {
  border-color: var(--border-hover);
}
.case-tab.active {
  border-color: var(--accent-primary);
  background: var(--accent-primary-light);
  color: var(--accent-primary-dark);
}
/* 深色主题下浅蓝底会变成一块亮色，改用深色输入底 + 主色文字保持可读性。 */
.dark .case-tab.active {
  background: var(--bg-input);
  color: var(--accent-primary);
}
.case-tab.add-case {
  border-style: dashed;
  background: transparent;
  color: var(--accent-primary);
}
.case-source {
  font-size: 11px;
  opacity: 0.72;
}
.case-source.official {
  color: var(--accent-success);
}
.case-source.ai {
  color: var(--accent-info);
}
.case-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 12px 14px;
}
.case-note {
  margin: 0 0 8px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.case-columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  align-items: start;
}
.case-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.case-label {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-secondary);
}
.case-field :deep(.el-textarea__inner) {
  min-height: 104px;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
}
.case-field.locked-input :deep(.el-textarea__inner) {
  background: var(--bg-input);
  color: var(--text-regular);
  cursor: default;
}
.case-result {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-light);
}
.case-result strong {
  font-size: 13px;
  color: var(--text-primary);
}
.case-result pre {
  margin: 8px 0 4px;
  max-height: 180px;
  overflow: auto;
  padding: 10px;
  background: var(--bg-app);
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-size: 13px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.case-result small {
  margin-left: 8px;
  color: var(--text-secondary);
  font-size: 12px;
}
.case-empty {
  padding: 16px 12px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
