<template>
  <div ref="viewRoot" class="problem-detail-view" v-loading="loading">
    <NavBarContext>
      <div class="navbar-actions-wrapper">
        <template v-if="!isIdeMode">
          <el-button type="primary" @click="enterIde">
            <el-icon class="el-icon--left"><Monitor /></el-icon> 提交答案
          </el-button>
          <el-button @click="addToProblemList">
            <el-icon class="el-icon--left"><Plus /></el-icon> 加入题单
          </el-button>
          <el-button @click="writeSolution">
            <el-icon class="el-icon--left"><EditPen /></el-icon> 写题解
          </el-button>
        </template>

        <template v-else>
          <el-button @click="exitIde" plain>
            <el-icon class="el-icon--left"><Back /></el-icon> 返回题目
          </el-button>
          <el-button type="success" @click="handleRealSubmit" :loading="submitting">
            <el-icon class="el-icon--left"><Upload /></el-icon> 确认提交
          </el-button>
        </template>
      </div>
    </NavBarContext>

    <div v-if="!isIdeMode" class="read-mode-container">
      <div class="read-layout-wrapper">
        <div class="left-column">
          <el-card shadow="never" class="desc-card">
            <ProblemDescription
              :problem="problem"
              :samples="officialSamples"
              :ai-enabled="!isContestScene"
              @toggle-ide="enterIde"
              @open-ai="handleOpenAi"
            />
          </el-card>
          <el-card v-if="relatedArticles.length" shadow="never" class="related-articles-card">
            <template #header>
              <div class="related-articles-header"><span>相关知识文章</span><span>延伸阅读</span></div>
            </template>
            <button v-for="article in relatedArticles" :key="article.id" class="related-article-row" @click="goPost(article.id)">
              <span class="related-article-main"><strong>{{ article.title }}</strong><small>{{ article.summary || '暂无摘要' }}</small></span>
              <span class="related-article-meta">♡ {{ article.likes || 0 }}</span>
            </button>
          </el-card>
        </div>

        <div class="right-column">
          <ProblemInfoCards
            :problem="problem"
            @view-records="handleViewRecords"
            @view-my-submission="handleViewMySubmission"
            @view-solutions="viewSolutions"
          />
        </div>
      </div>
    </div>

    <div v-else class="ide-mode-container">
      <div class="ide-layout">
        <section class="statement-pane" :style="statementStyle" aria-label="题面">
          <el-scrollbar>
            <div class="statement-body">
              <ProblemDescription
                :problem="problem"
                :samples="officialSamples"
                :is-ide-mode="true"
                :ai-enabled="!isContestScene"
                @toggle-ide="exitIde"
                @open-ai="handleOpenAi"
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
              :initial-snapshot="currentStudentContext"
              :problem-id="problem.id"
              :submitting="submitting"
              :running="runningCases"
              :test-collapsed="layout.collapsed"
              @snapshot="currentStudentContext = $event"
              @submit="handleRealSubmit"
              @run="runSelectedCase"
              @stop="stopCases"
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
              :snapshot="currentStudentContext"
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
    </div>

    <AiAssistant v-if="!isContestScene"
      v-model:visible="aiDrawerVisible"
      :problem="problem"
      :get-student-context="getStudentContext" @counterexample="appendCounterexample"
    />

    <AddToProblemListDialog
      v-model:visible="addToListVisible"
      :problem-id="problem.id"
      :problem-title="problem.title"
    />

  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 题目详情页：OJ 的核心页面，把题面、IDE 判题与题解/AI 助手合到一屏。
//
// 三条交互主线：
// 1. 写代码 → CodeEditor 本地草稿（按题目隔离）或正式提交；
// 2. 官方、自定义与 AI 反例共用本页样例状态；试跑不产生正式提交；
// 3. 每次发送 AI 提问读取当前代码；比赛场景不挂载 AI 助手。
//
// IDE 布局：题面 | 编辑器 | 自测 三区。题面与自测的宽度/高度都可拖动，
// 自测可停靠底部或右侧，也可收起；布局偏好只存浏览器本地，不涉及题目数据。
import { ref, computed, watch, onBeforeUnmount, onMounted, markRaw, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProblemDetail, submitCode, getLatestDraftCode, getDraftCode, runCodeApi } from '@/api/problem'
import { getRelatedArticles } from '@/api/post'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import { Monitor, Plus, Back, Upload, EditPen, ArrowUp, DArrowLeft } from '@element-plus/icons-vue'
import NavBarContext from '@/components/NavbarContext.vue'
import ProblemDescription from './components/ProblemDescription.vue'
import ProblemInfoCards from './components/ProblemInfoCards.vue'
import CodeEditor from './components/CodeEditor.vue'
import TestCasePanel from './components/TestCasePanel.vue'
import PaneSplitter from './components/PaneSplitter.vue'
import AiAssistant from './components/AiAssistant.vue'
import { createLocalTestCases } from '@/utils/localTestCases'
import { normalizeProblemSamples } from '@/utils/problemSamples'
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
import AddToProblemListDialog from './components/AddToProblemListDialog.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const problem = ref({})
const relatedArticles = ref([])
const isIdeMode = ref(false)
const editorRef = ref(null)

const aiDrawerVisible = ref(false)
const currentStudentContext = ref(null)
const addToListVisible = ref(false)

const testCases = ref([])
const caseModel = ref(markRaw(createLocalTestCases()))
const officialSamples = ref([])
const runningCases = ref(false)
let runGeneration = 0
let runController = null
const stopCases = () => { runGeneration++; runController?.abort(); runningCases.value = false }
const initializeCases = data => {
  stopCases()
  const official = normalizeProblemSamples(data)
  officialSamples.value = official
  caseModel.value = markRaw(createLocalTestCases(official, items => { testCases.value = items }))
  testCases.value = [...caseModel.value.items]
}
const runSelectedCases = async items => {
  if (runningCases.value) return
  const model = caseModel.value, problemId = problem.value.id
  const generation = ++runGeneration
  runController = new AbortController()
  const signal = runController.signal
  runningCases.value = true
  try {
    const snapshot = await readStudentSnapshot()
    if (generation !== runGeneration) return
    if (!snapshot.code?.trim()) return ElMessage.info('请先进入编辑器编写代码')
    for (const item of items) {
      if (generation !== runGeneration) break
      // 代码/语言在执行中改变时，停止后续试跑；已经产生的旧回执只匹配旧版本。
      if (model.result(item.id, snapshot)) continue
      const result = await runCodeApi({ ...snapshot, input: item.input, problemId }, signal)
      if (generation !== runGeneration) break
      if (result.code !== 200) throw new Error(result.message || '试跑失败')
      if (model.items.some(existing => existing.id === item.id && existing.input === item.input)) model.record(item.id, snapshot, result.data)
    }
  } catch (error) { if (generation === runGeneration && !signal.aborted) showApiError(error, '样例运行失败') }
  finally { if (generation === runGeneration) runningCases.value = false }
}
const runCase = item => runSelectedCases([{ ...item }])
const runSelectedCase = () => {
  const item = caseModel.value.items.find(candidate => candidate.id === caseModel.value.selectedId)
  if (!item) return ElMessage.info('请先选择或新增样例')
  return runCase(item)
}
const appendCounterexample = event => {
  try {
    const id = caseModel.value.addCounterexample(event)
    if (id) ElMessage.success('已验证反例已同步到本页样例区')
  } catch (error) { ElMessage.info(error.message) }
}
watch(() => [currentStudentContext.value?.code, currentStudentContext.value?.language], (value, previous) => {
  if (previous && previous[0] !== undefined && (value[0] !== previous[0] || value[1] !== previous[1])) stopCases()
})

// 编辑器是当前代码真源；普通练习每次发送重新读取，比赛上下文不挂载 AI。
const isContestScene = computed(() => route.query.contestId !== undefined)
watch(isContestScene, value => { if (value) aiDrawerVisible.value = false })
const readStudentSnapshot = async () => {
  if (editorRef.value) return editorRef.value.getSubmissionData()
  if (currentStudentContext.value && currentStudentContext.value.ready !== false) return currentStudentContext.value
  const problemId = problem.value.id
  const pending = currentStudentContext.value
  const result = pending?.ready === false ? await getDraftCode(problemId, pending.language) : await getLatestDraftCode(problemId)
  const snapshot = { code: result.data?.code || '', language: pending?.language || result.data?.language || 'cpp', ready: true }
  if (problemId === problem.value.id && currentStudentContext.value === pending) currentStudentContext.value = snapshot
  return snapshot
}
const getStudentContext = async () => {
  const snapshot = await readStudentSnapshot()
  return { ...snapshot, runIds: caseModel.value.receipts(snapshot), selectedSample: caseModel.value.context() }
}
const handleOpenAi = () => {
  if (!isContestScene.value) aiDrawerVisible.value = true
}

// === IDE 布局：容器测量 + 本地偏好 ===
const viewRoot = ref(null)
const containerWidth = ref(0)
const containerHeight = ref(0)
const browserStorage = () => {
  try {
    return window.localStorage
  } catch {
    return null
  }
}
const layout = ref(loadIdeLayout(browserStorage()))
let persistTimer = null
let resizeObserver = null

const measureContainer = () => {
  const element = viewRoot.value
  if (!element) return
  containerWidth.value = element.clientWidth
  containerHeight.value = element.clientHeight
}

const persistLayout = () => {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    saveIdeLayout(browserStorage(), layout.value)
  }, 120)
}
const saveLayoutNow = () => {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = null
  saveIdeLayout(browserStorage(), layout.value)
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

let detailGeneration = 0
const fetchDetail = async () => {
  const id = route.params.id
  const generation = ++detailGeneration
  loading.value = true
  try {
    const res = await getProblemDetail(id)
    if (generation !== detailGeneration) return
    if (res.code === 200) {
      problem.value = res.data
      initializeCases(res.data)
      const articles = await getRelatedArticles(id, { currentPage: 1, pageSize: 4, sort: 'latest' })
      if (generation !== detailGeneration) return
      relatedArticles.value = articles.code === 200 ? (articles.data?.records || []) : []
    }
  } finally {
    if (generation === detailGeneration) loading.value = false
  }
}

const enterIde = async () => {
  isIdeMode.value = true
  await nextTick()
  measureContainer()
}
const exitIde = () => isIdeMode.value = false

// 提交后直接跳判题进度页而不是原地轮询：
// 判题是异步任务，URL 里带上提交 ID 后刷新页面也能继续看进度。
const handleRealSubmit = async () => {
  if (submitting.value) return
  if (!editorRef.value) return
  if (!problem.value?.id) return ElMessage.error('题目信息未加载完成')

  const { language, code } = editorRef.value.getSubmissionData()
  if (!code.trim()) { return ElMessage.warning('代码不能为空') }

  submitting.value = true
  try {
    // 从比赛内进入时 URL 上带着 contestId，必须原样传给提交接口：
    // 后端据此把提交判定为"比赛提交"（计入榜单、受比赛时间限制），漏传会被当成普通练习提交。
    const contestId = route.query.contestId ? Number(route.query.contestId) : null
    const res = await submitCode({ problemId: problem.value.id, contestId, language, code })
    if (res.code === 200) {
      ElMessage.success('提交成功，正在进入判题进度页')
      if (res.data?.id) router.push({ name: 'SubmissionDetail', params: { id: res.data.id }, query: contestId ? { contestId } : {} })
    } else {
      ElMessage.error(res.message || '提交失败')
    }
  } catch (caughtError) {
    showApiError(caughtError, '网络错误')
  } finally {
    submitting.value = false
  }
}

const addToProblemList = () => {
  if (!useUserStore().isLoggedIn) return ElMessage.warning('请先登录后再加入题单')
  if (!problem.value?.id) return ElMessage.error('题目信息未加载完成')
  addToListVisible.value = true
}
const writeSolution = () => router.push({ name: 'PostCreate', query: { problemId: route.params.id, type: '2' } })
const viewSolutions = () => router.push({ name: 'ProblemSolutions', params: { id: route.params.id } })
const goPost = (id) => router.push({ name: 'PostDetail', params: { id } })
const handleViewRecords = () => {
  const routeData = router.resolve({ name: 'Submission', query: { problemId: route.params.id, ...(isContestScene.value ? { contestId: route.query.contestId } : {}) } })
  window.open(routeData.href, '_blank')
}

onMounted(() => {
  measureContainer()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => measureContainer())
    if (viewRoot.value) resizeObserver.observe(viewRoot.value)
  } else {
    window.addEventListener('resize', measureContainer)
  }
})

onBeforeUnmount(() => {
  stopCases()
  saveLayoutNow()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', measureContainer)
})

watch(() => route.params.id, () => {
  aiDrawerVisible.value = false
  isIdeMode.value = false
  currentStudentContext.value = null
  initializeCases({})
  problem.value = {}
  relatedArticles.value = []
  fetchDetail().catch(error => showApiError(error, '题目加载失败'))
}, { immediate: true })
</script>

<style scoped>
.problem-detail-view {
  height: 100%; 
  background-color: var(--bg-app);
  overflow: hidden; 
  display: flex;
  flex-direction: column;
}

.navbar-actions-wrapper {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 20px;
}

.read-mode-container {
  height: 100%;
  overflow-y: auto; 
}

.read-layout-wrapper {
  max-width: 1560px;
  margin: 0 auto;
  display: flex;
  gap: 20px;
  align-items: flex-start; 
}

.left-column { flex: 1; min-width: 0; }
.desc-card {
  min-height: 706px;
  border-radius: 8px;
  padding: 10px;
}

.desc-card :deep(.el-card__body) {
  height: auto;
}
.related-articles-card { margin-top: 20px; border-radius: 8px; }
.related-articles-header { display: flex; justify-content: space-between; color: var(--text-primary); font-weight: 650; }
.related-articles-header span:last-child { color: var(--text-secondary); font-size: 12px; font-weight: 400; }
.related-article-row { display: flex; width: 100%; align-items: center; gap: 16px; padding: 13px 4px; border: 0; border-bottom: 1px solid var(--border-light); background: transparent; color: inherit; cursor: pointer; text-align: left; }
.related-article-row:last-child { border-bottom: 0; }
.related-article-row:hover strong { color: var(--accent-primary); }
.related-article-main { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 4px; }
.related-article-main strong, .related-article-main small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.related-article-main strong { font-size: 14px; }
.related-article-main small, .related-article-meta { color: var(--text-secondary); font-size: 12px; }

.right-column { width: 300px; flex-shrink: 0; }

/* === IDE 工作台 === */
.ide-mode-container { height: 100%; min-height: 0; }
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
