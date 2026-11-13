<template>
  <div ref="workspaceRoot" class="ide-layout">
    <section class="statement-pane" :style="statementStyle" aria-label="题面">
      <el-scrollbar>
        <div class="statement-body">
          <ProblemDescription
            :problem="problem"
            :samples="samples"
            :is-ide-mode="true"
            :ai-enabled="aiEnabled"
            :show-ide-toggle="showIdeToggle"
            @toggle-ide="$emit('toggle-ide')"
            @open-ai="$emit('open-ai')"
          />
        </div>
      </el-scrollbar>
    </section>

    <PaneSplitter
      orientation="vertical"
      :value="statementWidth"
      :min="statementBounds.min"
      :max="statementBounds.max"
      label="题面宽度"
      @update:value="onStatementResize"
      @drag-end="persistLayout"
      @reset="resetStatementWidth"
    />

    <section class="workbench" :class="`dock-${effectiveDock}`">
      <div class="editor-pane">
        <CodeEditor
          ref="editorRef"
          :key="problem.id"
          :initial-snapshot="initialSnapshot"
          :problem-id="problem.id"
          :submitting="submitting"
          :running="runningCases"
          :test-collapsed="layout.collapsed"
          :draft-adapter="draftAdapter"
          :draft-save-delay="draftSaveDelay"
          @snapshot="$emit('snapshot', $event)"
          @submit="$emit('submit')"
          @run="$emit('run')"
          @stop="$emit('stop')"
          @toggle-test="expandTestPanel"
        />
      </div>

      <PaneSplitter
        v-if="!layout.collapsed"
        :orientation="effectiveDock === 'right' ? 'vertical' : 'horizontal'"
        :value="testSize"
        :min="testBounds.min"
        :max="testBounds.max"
        :label="effectiveDock === 'right' ? '自测宽度' : '自测高度'"
        invert
        @update:value="onTestResize"
        @drag-end="persistLayout"
        @reset="resetTestSize"
      />

      <div class="test-pane" :class="[`dock-${effectiveDock}`, { collapsed: layout.collapsed }]" :style="testPaneStyle">
        <TestCasePanel
          v-if="!layout.collapsed"
          :cases="testCases"
          :model="caseModel"
          :snapshot="currentSnapshot"
          :running="runningCases"
          :dock="effectiveDock"
          @toggle-dock="toggleDock"
          @toggle-collapse="collapseTestPanel"
        />
        <button
          v-else
          type="button"
          class="collapsed-toggle"
          :class="`dock-${effectiveDock}`"
          aria-label="展开自测面板"
          @click="expandTestPanel"
        >
          <el-icon><component :is="effectiveDock === 'right' ? DArrowLeft : ArrowUp" /></el-icon>
          <span>自测</span>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
// 共享 IDE 内核：题面 | 编辑器 | 自测三区。
//
// 只负责布局、编辑器和自测面板，不判断比赛/试卷规则，也不发起提交。
// 场景壳通过 props 提供题面白名单、草稿适配器和能力开关，通过事件接走提交/试跑。
// 这样普通训练、成套试卷和比赛参赛卷共用同一套编辑器行为，避免三份实现漂移。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowUp, DArrowLeft } from '@element-plus/icons-vue'
import ProblemDescription from './ProblemDescription.vue'
import CodeEditor from './CodeEditor.vue'
import TestCasePanel from './TestCasePanel.vue'
import PaneSplitter from './PaneSplitter.vue'
import {
  DEFAULT_IDE_LAYOUT,
  IDE_LAYOUT_LIMITS,
  canDockRight,
  loadIdeLayout,
  resolveStatementWidth,
  resolveTestSize,
  saveIdeLayout,
  statementWidthBounds,
  testSizeBounds
} from '@/utils/ideLayout'

const props = defineProps({
  problem: { type: Object, default: () => ({}) },
  samples: { type: Array, default: () => [] },
  initialSnapshot: { type: Object, default: null },
  currentSnapshot: { type: Object, default: null },
  submitting: { type: Boolean, default: false },
  runningCases: { type: Boolean, default: false },
  testCases: { type: Array, default: () => [] },
  caseModel: { type: Object, default: null },
  draftAdapter: { type: Object, default: null },
  draftSaveDelay: { type: Number, default: 10000 },
  aiEnabled: { type: Boolean, default: false },
  showIdeToggle: { type: Boolean, default: true },
  layoutStorageKey: { type: String, default: '' }
})

defineEmits(['submit', 'run', 'stop', 'snapshot', 'toggle-ide', 'open-ai'])

const editorRef = ref(null)
const workspaceRoot = ref(null)
const containerWidth = ref(0)
const containerHeight = ref(0)

const browserStorage = () => {
  try {
    return window.localStorage
  } catch {
    return null
  }
}

const storageKey = props.layoutStorageKey || undefined
const layout = ref(loadIdeLayout(browserStorage(), storageKey))
let persistTimer = null
let resizeObserver = null

const measureContainer = () => {
  const element = workspaceRoot.value
  if (!element) return
  containerWidth.value = element.clientWidth
  containerHeight.value = element.clientHeight
}

const persistLayout = () => {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    saveIdeLayout(browserStorage(), layout.value, storageKey)
  }, 120)
}
const saveLayoutNow = () => {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = null
  saveIdeLayout(browserStorage(), layout.value, storageKey)
}

// 右侧停靠需要同时容纳题面、编辑器与自测的最小宽度；窗口变窄时自动回退底部。
const effectiveDock = computed(() =>
  layout.value.dock === 'right' && canDockRight(containerWidth.value) ? 'right' : 'bottom'
)
const requestedTestSize = computed(() =>
  effectiveDock.value === 'right' ? layout.value.rightSize : layout.value.bottomSize
)
const statementBounds = computed(() =>
  statementWidthBounds({
    containerWidth: containerWidth.value,
    dock: effectiveDock.value,
    requestedTestSize: requestedTestSize.value,
    collapsed: layout.value.collapsed
  })
)
const statementWidth = computed(() => {
  if (!containerWidth.value) return 0
  return resolveStatementWidth({
    containerWidth: containerWidth.value,
    ratio: layout.value.statementRatio,
    dock: effectiveDock.value,
    requestedTestSize: requestedTestSize.value,
    collapsed: layout.value.collapsed
  })
})
const testBounds = computed(() =>
  testSizeBounds({
    dock: effectiveDock.value,
    containerWidth: containerWidth.value,
    containerHeight: containerHeight.value,
    statementWidth: statementWidth.value
  })
)
const testSize = computed(() =>
  resolveTestSize({
    requestedSize: requestedTestSize.value,
    dock: effectiveDock.value,
    containerWidth: containerWidth.value,
    containerHeight: containerHeight.value,
    statementWidth: statementWidth.value
  })
)

const statementStyle = computed(() =>
  containerWidth.value ? { width: `${statementWidth.value}px` } : { width: '42%' }
)
const testPaneStyle = computed(() => {
  if (layout.value.collapsed) return effectiveDock.value === 'right' ? { width: '36px' } : { height: '36px' }
  return effectiveDock.value === 'right' ? { width: `${testSize.value}px` } : { height: `${testSize.value}px` }
})

const onStatementResize = value => {
  if (!containerWidth.value) return
  layout.value.statementRatio = value / containerWidth.value
  persistLayout()
}
const onTestResize = value => {
  if (effectiveDock.value === 'right') layout.value.rightSize = value
  else layout.value.bottomSize = value
  persistLayout()
}
const resetStatementWidth = () => {
  layout.value.statementRatio = DEFAULT_IDE_LAYOUT.statementRatio
  saveLayoutNow()
}
const resetTestSize = () => {
  if (effectiveDock.value === 'right') layout.value.rightSize = DEFAULT_IDE_LAYOUT.rightSize
  else layout.value.bottomSize = DEFAULT_IDE_LAYOUT.bottomSize
  saveLayoutNow()
}
const toggleDock = () => {
  const nextDock = effectiveDock.value === 'right' ? 'bottom' : 'right'
  if (nextDock === 'right' && !canDockRight(containerWidth.value)) {
    ElMessage.info(`窗口宽度不足，右侧停靠至少需要 ${IDE_LAYOUT_LIMITS.minRightDockWidth}px`)
    return
  }
  layout.value.dock = nextDock
  persistLayout()
}
const collapseTestPanel = () => {
  layout.value.collapsed = true
  saveLayoutNow()
}
const expandTestPanel = () => {
  layout.value.collapsed = false
  saveLayoutNow()
}

onMounted(() => {
  measureContainer()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => measureContainer())
    if (workspaceRoot.value) resizeObserver.observe(workspaceRoot.value)
  } else {
    window.addEventListener('resize', measureContainer)
  }
})

onBeforeUnmount(() => {
  saveLayoutNow()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', measureContainer)
})

defineExpose({
  getSubmissionData: () => editorRef.value?.getSubmissionData()
})
</script>

<style scoped>
.ide-layout { display: flex; height: 100%; min-height: 0; align-items: stretch; }
.statement-pane {
  flex: 0 0 auto;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: var(--bg-surface);
}
.statement-pane :deep(.el-scrollbar) { height: 100%; }
.statement-body { padding: 20px 20px 60px; }

.workbench { flex: 1; min-width: 0; min-height: 0; display: flex; }
.workbench.dock-bottom { flex-direction: column; }
.workbench.dock-right { flex-direction: row; }

.editor-pane {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-surface);
}

.test-pane {
  flex: 0 0 auto;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-surface);
}
.test-pane.dock-bottom { border-top: 1px solid var(--border-hover); }
.test-pane.dock-right { border-left: 1px solid var(--border-hover); }
.test-pane.collapsed { display: flex; }

.collapsed-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: var(--bg-input);
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
}
.collapsed-toggle:hover { color: var(--accent-primary); background: var(--bg-surface-hover); }
.collapsed-toggle.dock-right { flex-direction: column; }
.collapsed-toggle.dock-right span { writing-mode: vertical-rl; letter-spacing: 2px; }
</style>
