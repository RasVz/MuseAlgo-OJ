<template>
  <div class="problem-detail-view" v-loading="loading">
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
      <ProblemIdeWorkspace
        ref="workspaceRef"
        :problem="problem"
        :samples="officialSamples"
        :initial-snapshot="currentStudentContext"
        :current-snapshot="currentStudentContext"
        :submitting="submitting"
        :running-cases="runningCases"
        :test-cases="testCases"
        :case-model="caseModel"
        :ai-enabled="!isContestScene"
        :show-ide-toggle="true"
        @submit="handleRealSubmit"
        @run="runSelectedCase"
        @stop="stopCases"
        @snapshot="currentStudentContext = $event"
        @toggle-ide="exitIde"
        @open-ai="handleOpenAi"
      />
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
// IDE 布局由共享的 ProblemIdeWorkspace 负责：题面 | 编辑器 | 自测 三区，
// 可拖拽、可停靠、可收起；本页只保留阅读模式、题目加载与提交/AI 入口。
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProblemDetail, submitCode, getLatestDraftCode, getDraftCode } from '@/api/problem'
import { getRelatedArticles } from '@/api/post'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import { Monitor, Plus, Back, Upload, EditPen } from '@element-plus/icons-vue'
import NavBarContext from '@/components/NavbarContext.vue'
import ProblemDescription from './components/ProblemDescription.vue'
import ProblemInfoCards from './components/ProblemInfoCards.vue'
import ProblemIdeWorkspace from './components/ProblemIdeWorkspace.vue'
import AiAssistant from './components/AiAssistant.vue'
import { useProblemWorkbench } from './composables/useProblemWorkbench'
import AddToProblemListDialog from './components/AddToProblemListDialog.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const problem = ref({})
const relatedArticles = ref([])
const isIdeMode = ref(false)
const workspaceRef = ref(null)

const aiDrawerVisible = ref(false)
const currentStudentContext = ref(null)
const addToListVisible = ref(false)

const {
  testCases,
  caseModel,
  officialSamples,
  runningCases,
  initializeCases,
  runSelectedCase,
  stopCases,
  appendCounterexample
} = useProblemWorkbench({
  getProblemId: () => problem.value.id,
  getSnapshot: () => readStudentSnapshot()
})

watch(() => [currentStudentContext.value?.code, currentStudentContext.value?.language], (value, previous) => {
  if (previous && previous[0] !== undefined && (value[0] !== previous[0] || value[1] !== previous[1])) stopCases()
})

// 编辑器是当前代码真源；普通练习每次发送重新读取，比赛上下文不挂载 AI。
const isContestScene = computed(() => route.query.contestId !== undefined)
watch(isContestScene, value => { if (value) aiDrawerVisible.value = false })
const readStudentSnapshot = async () => {
  if (workspaceRef.value) return workspaceRef.value.getSubmissionData()
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
}
const exitIde = () => isIdeMode.value = false

// 提交后直接跳判题进度页而不是原地轮询：
// 判题是异步任务，URL 里带上提交 ID 后刷新页面也能继续看进度。
const handleRealSubmit = async () => {
  if (submitting.value) return
  if (!workspaceRef.value) return
  if (!problem.value?.id) return ElMessage.error('题目信息未加载完成')

  const { language, code } = workspaceRef.value.getSubmissionData()
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
const writeSolution = () => {
  const target = router.resolve({ path: '/editor', query: { channel: 'solution', problemId: route.params.id } })
  window.open(target.href, '_blank')
}
const viewSolutions = () => router.push({ name: 'ProblemSolutions', params: { id: route.params.id } })
const goPost = (id) => router.push({ name: 'PostDetail', params: { id } })
const handleViewRecords = () => {
  const routeData = router.resolve({ name: 'Submission', query: { problemId: route.params.id, ...(isContestScene.value ? { contestId: route.query.contestId } : {}) } })
  window.open(routeData.href, '_blank')
}

onBeforeUnmount(() => {
  stopCases()
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
</style>
