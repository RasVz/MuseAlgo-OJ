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
              :is-ide-mode="false"
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
            @feedback="handleFeedback"
          />
        </div>
      </div>
    </div>

    <div v-else class="ide-mode-container">
      <div class="ide-layout">
        <div class="ide-left">
          <el-scrollbar>
            <div class="ide-desc-wrapper">
                <ProblemDescription 
                  :problem="problem" 
                  :simple="true" 
                  :is-ide-mode="true"
                  @toggle-ide="exitIde"
                  @run-sample="onRunSample"
                  @open-ai="handleOpenAi"
                />
            </div>
          </el-scrollbar>
        </div>
        
        <div class="ide-right">
          <CodeEditor
            ref="editorRef"
            :problem-id="problem.id"
            :submitting="submitting"
            @submit="handleRealSubmit"
          />
        </div>
      </div>
    </div>

    <AiAssistant 
      v-model:visible="aiDrawerVisible"
      :problem="problem"
      :student-code="currentStudentCode"
    />

    <AddToProblemListDialog
      v-model:visible="addToListVisible"
      :problem-id="problem.id"
      :problem-title="problem.title"
    />

  </div>
</template>

<script setup>
// 题目详情页：OJ 的核心页面，把题面、IDE 判题与题解/AI 助手合到一屏。
//
// 三条交互主线：
// 1. 写代码 → CodeEditor 本地草稿（按题目隔离）或正式提交；
// 2. 点样例 → 切到 IDE 模式并把样例灌进输入框试跑（不出正式提交记录）；
// 3. 打开 AI 助手前先同步编辑器里的最新代码，保证 AI 看到的是用户当前的思路。
import { ref, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProblemDetail, submitCode } from '@/api/problem'
import { getRelatedArticles } from '@/api/post'
import { useUserStore } from '@/stores/userStore'
import { ElMessage } from 'element-plus'
import { Monitor, Plus, Back, Upload, EditPen } from '@element-plus/icons-vue'
import NavBarContext from '@/components/NavbarContext.vue'
import ProblemDescription from './components/ProblemDescription.vue'
import ProblemInfoCards from './components/ProblemInfoCards.vue'
import CodeEditor from './components/CodeEditor.vue'
import AiAssistant from './components/AiAssistant.vue'
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
const currentStudentCode = ref('')
const addToListVisible = ref(false)

// 点样例要把用户从"阅读模式"拉到"写代码模式"：
// 否则他看到样例被填进一个还没展开的编辑器里，会以为没生效。
const onRunSample = ({ input, output }) => {
  isIdeMode.value = true
  nextTick(() => {
    if (editorRef.value) {
      editorRef.value.fillSampleAndRun(input, output)
    }
  })
}

// 打开 AI 前主动取一次编辑器里的最新代码：
// 编辑器的 code 才是真源，父组件持有的这份是打开抽屉那一刻的快照，可能已经过时。
const handleOpenAi = () => {
  if (editorRef.value) {
    const data = editorRef.value.getSubmissionData()
    currentStudentCode.value = data.code || ''
  } else {
    currentStudentCode.value = ''
  }
  aiDrawerVisible.value = true
}

const fetchDetail = async () => {
  loading.value = true
  try {
    const res = await getProblemDetail(route.params.id)
    if (res.code === 200) {
      problem.value = res.data
      const articles = await getRelatedArticles(route.params.id, { currentPage: 1, pageSize: 4, sort: 'latest' })
      relatedArticles.value = articles.code === 200 ? (articles.data?.records || []) : []
    }
  } finally {
    loading.value = false
  }
}

const enterIde = () => isIdeMode.value = true
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
      if (res.data?.id) router.push({ name: 'SubmissionDetail', params: { id: res.data.id } })
    } else {
      ElMessage.error(res.message || '提交失败')
    }
  } catch {
    ElMessage.error('网络错误')
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
  const routeData = router.resolve({ name: 'Submission', query: { problemId: route.params.id } })
  window.open(routeData.href, '_blank')
}
const handleFeedback = () => ElMessage.info('题目反馈功能开发中')

onMounted(fetchDetail)
</script>

<style scoped>
/* 你的原有样式完全不变 */
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

.ide-mode-container { height: 100%; }
.ide-layout { display: flex; height: 100%; }
.ide-left {
  flex: 1;
  background: var(--bg-surface);
  border-right: 1px solid var(--border-hover);
  max-width: 45%; 
  display: flex;
  flex-direction: column;
}
.ide-desc-wrapper {
    padding: 20px;
    padding-bottom: 60px; 
}
.ide-right {
  flex: 1.2;
  height: 100%;
  overflow: hidden;
}
</style>
