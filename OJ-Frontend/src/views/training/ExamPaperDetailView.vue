<template>
  <div class="exam-paper-detail">
    <NavbarContext>
      <div class="nav-header">
        <el-page-header @back="goBack" title="返回训练">
          <template #content>
            <span class="nav-title">{{ paper?.title || '试卷详情' }}</span>
          </template>
        </el-page-header>
      </div>
    </NavbarContext>

    <div class="main-content" v-loading="loading">
      <el-card shadow="never" class="paper-info" v-if="paper">
        <div class="info-header">
          <el-tag type="primary" effect="plain">{{ categoryLabel(paper.category) }}</el-tag>
          <el-tag v-if="paper.level" effect="plain">{{ paper.level }}</el-tag>
        </div>
        <div class="info-title">{{ paper.title }}</div>
        <div class="info-meta">
          <span>{{ paper.problemCount ?? 0 }} 题</span>
          <span>满分 {{ paper.totalScore ?? 100 }}</span>
          <span>建议时长 {{ paper.durationMinutes ?? 120 }} 分钟</span>
        </div>
        <div v-if="paper.description" class="info-desc">{{ paper.description }}</div>
      </el-card>

      <!-- 限时作答会话 -->
      <el-card v-if="paper" shadow="never" class="session-card">
        <template v-if="!attempt">
          <div class="session-row">
            <el-icon><Timer /></el-icon>
            <span class="session-tip">
              本卷限时 {{ paper.durationMinutes ?? 120 }} 分钟，开始后计时、到时自动交卷；
              客观题提交即时判分，编程题得分取会话时间内每题最好成绩。
            </span>
            <el-button type="primary" :loading="starting" @click="startSession">开始作答</el-button>
          </div>
        </template>

        <template v-else-if="attempt.status === 'RUNNING'">
          <div class="session-row">
            <el-icon><Timer /></el-icon>
            <span class="timer" :class="{ 'timer-warn': remaining <= 300 }">剩余 {{ formatTime(remaining) }}</span>
            <el-button :loading="finishing" @click="finishSession(false)">交卷</el-button>
          </div>
        </template>

        <template v-else>
          <div class="session-row session-result">
            <el-tag type="success">
              已交卷{{ attempt.status === 'TIMEOUT' ? '（到时自动交卷）' : '' }}
            </el-tag>
            <span>客观题 {{ attempt.objectiveScore ?? 0 }} 分</span>
            <span>编程题 {{ attempt.codingScore ?? 0 }} 分</span>
            <span class="total">总分 {{ attempt.totalScore ?? 0 }}</span>
            <el-button type="primary" plain :loading="starting" @click="startSession">再次作答</el-button>
          </div>
        </template>
      </el-card>

      <!-- 客观题：提交时即时判分 -->
      <el-card v-if="paper?.questions?.length && attempt" shadow="never" class="question-card">
        <template #header>
          <div class="card-header">
            <span>客观题（单选 / 多选 / 判断）</span>
            <div class="header-right">
              <el-tag v-if="objectiveScore !== null" type="success" effect="plain">
                客观题得分：{{ objectiveScore }} / {{ objectiveTotal }}
              </el-tag>
              <el-button type="primary" :loading="submitting" :disabled="!sessionActive" @click="submitAnswers">
                提交答案
              </el-button>
            </div>
          </div>
        </template>

        <div v-for="(q, idx) in paper.questions" :key="q.id" class="question-item">
          <div class="question-head">
            <span class="question-no">{{ idx + 1 }}.</span>
            <el-tag size="small" :type="typeTag(q.questionType)">{{ typeLabel(q.questionType) }}</el-tag>
            <el-tag size="small" effect="plain">{{ q.score }} 分</el-tag>
            <el-tag v-if="results[q.id]" size="small" :type="results[q.id].correct ? 'success' : 'danger'">
              {{ results[q.id].correct ? '✓ 正确' : '✗ 错误' }}
            </el-tag>
          </div>
          <div class="question-statement markdown-body" v-html="renderMarkdown(q.statement)"></div>

          <!-- 单选 -->
          <el-radio-group
            v-if="q.questionType === 'SINGLE'"
            v-model="answers[q.id]"
            class="option-group"
            :disabled="!sessionActive"
          >
            <el-radio v-for="opt in q.options" :key="opt.key" :value="opt.key" class="option-row">
              <span class="markdown-body option-markdown" v-html="renderMarkdown(`${opt.key}. ${opt.text}`)"></span>
            </el-radio>
          </el-radio-group>

          <!-- 多选 -->
          <el-checkbox-group
            v-else-if="q.questionType === 'MULTIPLE'"
            v-model="answers[q.id]"
            class="option-group"
            :disabled="!sessionActive"
          >
            <el-checkbox v-for="opt in q.options" :key="opt.key" :value="opt.key" class="option-row">
              <span class="markdown-body option-markdown" v-html="renderMarkdown(`${opt.key}. ${opt.text}`)"></span>
            </el-checkbox>
          </el-checkbox-group>

          <!-- 判断 -->
          <el-radio-group v-else v-model="answers[q.id]" class="option-group boolean-group" :disabled="!sessionActive">
            <el-radio value="T" class="option-row">对</el-radio>
            <el-radio value="F" class="option-row">错</el-radio>
          </el-radio-group>

          <div v-if="results[q.id]" class="question-result" :class="results[q.id].correct ? 'result-ok' : 'result-bad'">
            你的答案：{{ results[q.id].myAnswer || '未作答' }}
            <span v-if="!results[q.id].correct">，正确答案：{{ results[q.id].correctAnswer }}</span>
            ，得分 {{ results[q.id].score }}
            <div v-if="results[q.id].explanation" class="markdown-body explanation"
              v-html="renderMarkdown(results[q.id].explanation)"></div>
          </div>
        </div>
      </el-card>

      <!-- 编程题 -->
      <el-card shadow="never" class="problem-card">
        <template #header>
          <div class="card-header">
            <span>编程题</span>
            <el-tag size="small" type="info" effect="plain">按真实套卷顺序作答</el-tag>
          </div>
        </template>

        <el-table :data="paper?.problems ?? []" border stripe>
          <el-table-column label="#" width="70" align="center">
            <template #default="{ row }">{{ row.problemOrder }}</template>
          </el-table-column>
          <el-table-column label="题目标题" min-width="260">
            <template #default="{ row }">
              <span class="hover-link" @click="goProblem(row.problemId)">{{ row.title || `题目 #${row.problemId}` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="难度" width="120" align="center">
            <template #default="{ row }">
              <span :style="{ color: difficultyColor(row.difficulty) }">
                {{ difficultyLabel(row.difficulty) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="分值" width="100" align="center">
            <template #default="{ row }">{{ row.score ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <el-button type="primary" link @click="goProblem(row.problemId)">去作答</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 训练试卷作答页：客观题（单选/多选/判断）在本页限时作答并即时判分，
// 编程题按套卷顺序跳到题面页作答（成绩走正常的提交判题链路）。
//
// 限时会话由服务端裁定：前端倒计时只用于显示，归零时主动调交卷接口兜底，
// 刷新页面后靠 getExamAttempt 恢复剩余时间，不会因刷新而延长考试。
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Timer } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { getExamPaperDetail, submitExamPaperAnswers, startExamAttempt, getExamAttempt, finishExamAttempt } from '@/api/examPaper'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { useUserStore } from '@/stores/userStore'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'
import { createMarkdown } from '@/utils/markdown'
import 'github-markdown-css/github-markdown.css'

const route = useRoute()
const router = useRouter()
const paper = ref(null)
const loading = ref(false)
const submitting = ref(false)
const attempt = ref(null)
const remaining = ref(0)
const starting = ref(false)
const finishing = ref(false)
let timerId = null
const markdown = createMarkdown({ html: false, math: true, breaks: true })
const renderMarkdown = (text) => markdown.render(text || '')

// 作答：questionId -> SINGLE/JUDGE 存字符串，MULTIPLE 存数组
const answers = reactive({})
const results = reactive({})

const CATEGORY_LABELS = { GESP: 'GESP', CSP_J: 'CSP-J', CSP_S: 'CSP-S', NOIP: 'NOIP', MOCK: '模拟' }
const categoryLabel = (c) => CATEGORY_LABELS[c] || c || '套卷'
const typeLabel = (t) => ({ SINGLE: '单选', MULTIPLE: '多选', JUDGE: '判断' }[t] || t)
const typeTag = (t) => ({ SINGLE: 'primary', MULTIPLE: 'warning', JUDGE: 'info' }[t] || 'info')
const difficultyColor = (d) => getDifficultyColor(d)
const difficultyLabel = (d) => getDifficultyLabel(d)

const objectiveTotal = computed(() =>
  (paper.value?.questions ?? []).reduce((sum, q) => sum + (q.score ?? 0), 0)
)
// 客观题得分在 results 为空时显示 null（而非 0）：
// 未判分与判得 0 分是两回事，模板据此区分"还没提交"与"得了零分"。
const objectiveScore = computed(() => {
  const list = Object.values(results)
  return list.length ? list.reduce((sum, r) => sum + (r.score ?? 0), 0) : null
})

// 只有 RUNNING 才允许作答：未开始或已交卷的会话必须锁住输入框。
const sessionActive = computed(() => attempt.value?.status === 'RUNNING')

const goBack = () => router.push('/training')
const goProblem = (id) => router.push(`/problem/${id}`)

const submitAnswers = async () => {
  const questions = paper.value?.questions ?? []
  if (!questions.length) return
  if (!useUserStore().getAccessToken()) {
    ElMessage.warning('请先登录')
    return
  }
  const payload = {
    answers: questions.map((q) => {
      const value = answers[q.id]
      // 多选的答案在界面上是数组，提交时拼成字符串：
// 后端按字符串比对选项集合，没必要在协议里传结构化数组。
    const answer = Array.isArray(value) ? value.join('') : (value ?? '')
      return { questionId: q.id, answer }
    })
  }
  submitting.value = true
  try {
    const res = await submitExamPaperAnswers(route.params.id, payload)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '提交失败'))
      return
    }
    const list = unwrapResultData(res) || []
    list.forEach((r) => { results[r.questionId] = r })
    const score = list.reduce((sum, r) => sum + (r.score ?? 0), 0)
    ElMessage.success(`判分完成，客观题得分 ${score}`)
  } finally {
    submitting.value = false
  }
}

const startSession = async () => {
  if (!useUserStore().getAccessToken()) {
    ElMessage.warning('请先登录')
    return
  }
  starting.value = true
  try {
    // 再次作答时清空上一次的本地作答与判分结果
    ;(paper.value?.questions ?? []).forEach((q) => {
      if (q.questionType === 'MULTIPLE') answers[q.id] = []
      else answers[q.id] = ''
    })
    Object.keys(results).forEach((k) => delete results[k])
    const res = await startExamAttempt(route.params.id)
    attempt.value = unwrapResultData(res) || null
    if (attempt.value?.status === 'RUNNING') {
      remaining.value = attempt.value.remainingSeconds ?? 0
      startTimer()
    }
  } finally {
    starting.value = false
  }
}

// finishing 同时作为互斥标志：时间到自动交卷与手动点击交卷可能同时发生，
// 不加锁会重复提交交卷请求。
const finishSession = async (auto) => {
  if (finishing.value) return
  if (!auto && !window.confirm('确定交卷吗？交卷后不能继续作答。')) return
  finishing.value = true
  try {
    const res = await finishExamAttempt(route.params.id)
    attempt.value = unwrapResultData(res) || null
    stopTimer()
    if (auto) ElMessage.info('时间到，已自动交卷')
  } finally {
    finishing.value = false
  }
}

// 启动前先 stopTimer：重复调用会叠加 interval，倒计时会以两倍速度归零。
const startTimer = () => {
  stopTimer()
  timerId = setInterval(() => {
    remaining.value -= 1
    if (remaining.value <= 0) {
      finishSession(true)
    }
  }, 1000)
}

const stopTimer = () => {
  if (timerId) {
    clearInterval(timerId)
    timerId = null
  }
}

const formatTime = (seconds) => {
  const s = Math.max(0, seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await getExamPaperDetail(route.params.id)
    paper.value = unwrapResultData(res) || null
    ;(paper.value?.questions ?? []).forEach((q) => {
      if (q.questionType === 'MULTIPLE') answers[q.id] = []
      else answers[q.id] = ''
    })
    // 恢复进行中的会话：刷新或切走再回来，倒计时按服务端剩余时间续上，
// 而不是从试卷总时长重新开始。
    if (useUserStore().getAccessToken()) {
      const res = await getExamAttempt(route.params.id)
      attempt.value = unwrapResultData(res) || null
      if (attempt.value?.status === 'RUNNING') {
        remaining.value = attempt.value.remainingSeconds ?? 0
        startTimer()
      }
    }
  } finally {
    loading.value = false
  }
})

// 必须停表：setInterval 不随组件销毁停止，会持续触发已失效的交卷请求。
onUnmounted(stopTimer)
</script>

<style scoped>
.exam-paper-detail { min-height: 100vh; background: var(--bg-surface-hover); }
.nav-header { padding: 8px 0; }
.main-content { max-width: 1280px; margin: 0 auto; padding: 16px 24px; display: flex; flex-direction: column; gap: 16px; }
.info-header { display: flex; gap: 8px; }
.info-title { font-size: 22px; font-weight: 700; margin-top: 10px; }
.info-meta { display: flex; gap: 18px; color: var(--text-secondary); font-size: 13px; margin-top: 10px; }
.info-desc { color: var(--text-regular); font-size: 14px; margin-top: 10px; white-space: pre-wrap; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-right { display: flex; align-items: center; gap: 10px; }
.question-item { border: 1px solid var(--border-light); border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.question-head { display: flex; align-items: center; gap: 8px; }
.question-no { font-weight: 700; font-size: 15px; }
.question-statement { font-size: 15px; margin: 8px 0 12px; white-space: pre-wrap; line-height: 1.6; }
.option-group { display: flex; flex-direction: column; gap: 6px; }
.option-row { display: flex; margin-right: 0; height: auto; white-space: normal; }
.boolean-group { flex-direction: row; align-items: center; gap: 16px; }
.boolean-group .option-row { align-items: center; }
.question-result { margin-top: 10px; font-size: 13px; padding: 8px 10px; border-radius: 6px; }
.option-markdown :deep(p), .question-statement :deep(p), .explanation :deep(p) { margin: 0; }
.explanation { margin-top: 8px; padding-top: 8px; border-top: 1px solid currentColor; }
.result-ok { background: var(--accent-success-light); color: var(--accent-success); }
.result-bad { background: var(--accent-danger-light); color: var(--accent-danger); }
.hover-link { color: var(--accent-primary); cursor: pointer; }
.session-row { display: flex; align-items: center; gap: 12px; }
.session-tip { flex: 1; color: var(--text-regular); font-size: 13px; }
.timer { font-size: 22px; font-weight: 700; color: var(--accent-primary); font-variant-numeric: tabular-nums; }
.timer-warn { color: var(--accent-danger); }
.session-result { gap: 18px; font-size: 14px; }
.session-result .total { font-size: 18px; font-weight: 700; color: var(--accent-primary); }
</style>
