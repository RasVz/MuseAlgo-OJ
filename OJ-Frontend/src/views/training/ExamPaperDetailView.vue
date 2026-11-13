<template>
  <div class="exam-paper-detail">
    <NavbarContext>
      <div class="nav-header">
        <el-page-header @back="goBack" title="返回试卷库">
          <template #content>
            <span class="nav-title">{{ paper?.title || '试卷详情' }}</span>
          </template>
        </el-page-header>
      </div>
    </NavbarContext>

    <main class="main-content" v-loading="loading">
      <template v-if="paper">
        <el-card shadow="never" class="paper-info">
          <div class="info-header">
            <el-tag type="primary" effect="plain">{{ categoryLabel(paper.category) }}</el-tag>
            <el-tag v-if="paper.level" effect="plain">{{ paper.level }}</el-tag>
          </div>
          <h1 class="info-title">{{ paper.title }}</h1>
          <div class="info-meta">
            <span>{{ paper.problemCount ?? 0 }} 题</span>
            <span>满分 {{ paper.totalScore ?? 100 }}</span>
            <span>建议时长 {{ paper.durationMinutes ?? 120 }} 分钟</span>
          </div>
          <div v-if="paper.description" class="info-desc">{{ paper.description }}</div>
        </el-card>

        <el-card v-if="!attempt" shadow="never" class="session-card">
          <div class="start-panel">
            <div class="start-copy">
              <div class="start-icon"><el-icon><Timer /></el-icon></div>
              <div>
                <div class="start-title">准备好开始了吗？</div>
                <p>开始后计时 {{ paper.durationMinutes ?? 120 }} 分钟，到时自动交卷。客观题可即时判分；编程题请在交卷前确认判题完成。</p>
              </div>
            </div>
            <el-button type="primary" size="large" :loading="starting" @click="startSession">开始作答</el-button>
          </div>
        </el-card>

        <el-card v-else-if="!sessionActive" shadow="never" class="session-card session-result">
          <div class="result-summary">
            <el-tag type="success" effect="light" size="large">
              {{ attempt.status === 'TIMEOUT' ? '时间到，已自动交卷' : '本次作答已交卷' }}
            </el-tag>
            <div class="score-item"><span>客观题</span><strong>{{ attempt.objectiveScore ?? 0 }}</strong></div>
            <div class="score-item"><span>编程题</span><strong>{{ attempt.codingScore ?? 0 }}</strong></div>
            <div class="score-total"><span>总分</span><strong>{{ attempt.totalScore ?? 0 }}</strong></div>
            <el-button type="primary" plain :loading="starting" @click="startSession">再次作答</el-button>
          </div>
          <el-alert
            v-if="attempt.status === 'TIMEOUT' && hasUnsubmittedChanges"
            class="timeout-result-alert"
            type="warning"
            show-icon
            :closable="false"
            title="时间已到，设备上暂存的未提交修改未计入本次成绩。"
            description="本次分数按超时前已提交并判分的答案统计；未提交修改仍显示在答题卡中，方便你核对。"
          />
        </el-card>

        <div v-if="attempt" class="answer-layout" :class="{ 'answer-layout-main-only': !sessionActive && !objectiveQuestions.length }">
          <section class="question-column" aria-label="试卷答题区">
            <el-card v-if="objectiveQuestions.length" shadow="never" class="question-card">
              <template #header>
                <div class="card-header">
                  <div>
                    <h2>客观题</h2>
                    <span>单选、多选与判断题</span>
                  </div>
                  <el-tag v-if="objectiveScore !== null" type="success" effect="plain">
                    最近判分 {{ objectiveScore }} / {{ objectiveTotal }} 分
                  </el-tag>
                </div>
              </template>

              <article
                v-for="(q, idx) in objectiveQuestions"
                :id="questionAnchor(q.id)"
                :key="q.id"
                class="question-item"
                :class="{ 'question-item-answered': isAnswered(q.id) }"
              >
                <div class="question-head">
                  <span class="question-no">{{ idx + 1 }}</span>
                  <div class="question-head-meta">
                    <el-tag size="small" :type="typeTag(q.questionType)">{{ typeLabel(q.questionType) }}</el-tag>
                    <el-tag size="small" effect="plain">{{ q.score }} 分</el-tag>
                    <el-tag v-if="currentResult(q)" size="small" :type="currentResult(q).correct ? 'success' : 'danger'">
                      {{ currentResult(q).correct ? '正确' : '错误' }}
                    </el-tag>
                  </div>
                  <span class="question-status">{{ isAnswerUnsubmitted(q) ? '未提交' : isAnswered(q.id) ? '已作答' : '未作答' }}</span>
                </div>
                <div class="question-statement markdown-body" v-html="renderMarkdown(q.statement)"></div>

                <el-radio-group
                  v-if="q.questionType === 'SINGLE'"
                  v-model="answers[q.id]"
                  class="option-group"
                  :disabled="!sessionActive || remaining === 0 || submitting || finishing"
                >
                  <el-radio
                    v-for="opt in q.options"
                    :key="opt.key"
                    :value="opt.key"
                    class="option-row"
                    @change="saveAnswersDraft"
                  >
                    <span class="markdown-body option-markdown" v-html="renderMarkdown(`${opt.key}. ${opt.text}`)"></span>
                  </el-radio>
                </el-radio-group>

                <el-checkbox-group
                  v-else-if="q.questionType === 'MULTIPLE'"
                  v-model="answers[q.id]"
                  class="option-group"
                  :disabled="!sessionActive || remaining === 0 || submitting || finishing"
                  @change="saveAnswersDraft"
                >
                  <el-checkbox v-for="opt in q.options" :key="opt.key" :value="opt.key" class="option-row">
                    <span class="markdown-body option-markdown" v-html="renderMarkdown(`${opt.key}. ${opt.text}`)"></span>
                  </el-checkbox>
                </el-checkbox-group>

                <el-radio-group
                  v-else
                  v-model="answers[q.id]"
                  class="option-group boolean-group"
                  :disabled="!sessionActive || remaining === 0 || submitting || finishing"
                  @change="saveAnswersDraft"
                >
                  <el-radio value="T" class="option-row">对</el-radio>
                  <el-radio value="F" class="option-row">错</el-radio>
                </el-radio-group>

                <div
                  v-if="currentResult(q)"
                  class="question-result"
                  :class="currentResult(q).correct ? 'result-ok' : 'result-bad'"
                >
                  你的答案：{{ currentResult(q).myAnswer || '未作答' }}
                  <span v-if="!currentResult(q).correct">，正确答案：{{ currentResult(q).correctAnswer }}</span>
                  ，得分 {{ currentResult(q).score }}
                  <div v-if="currentResult(q).explanation" class="markdown-body explanation" v-html="renderMarkdown(currentResult(q).explanation)"></div>
                </div>
              </article>

              <el-empty v-if="objectiveQuestions.length === 0" description="本卷没有客观题" />
            </el-card>

            <el-card shadow="never" class="problem-card">
              <template #header>
                <div class="card-header">
                  <div>
                    <h2>编程题</h2>
                <span>请在交卷前确认判题完成；按会话内同题普通练习的最高百分制得分折算，待判提交不会在交卷后回补</span>
                  </div>
                  <el-tag size="small" type="info" effect="plain">{{ paper.problems?.length ?? 0 }} 题</el-tag>
                </div>
              </template>
              <div v-if="paper.problems?.length" class="problem-list">
                <div v-for="row in paper.problems" :key="row.problemId" class="problem-row">
                  <span class="problem-order">{{ row.problemOrder }}</span>
                  <div class="problem-info">
                    <strong>{{ row.title || `题目 #${row.problemId}` }}</strong>
                    <span>{{ difficultyLabel(row.difficulty) }} · {{ row.score ?? '-' }} 分</span>
                  </div>
                  <RouterLink
                    :to="{ name: 'ProblemDetail', params: { id: row.problemId } }"
                    target="_blank"
                    rel="noopener"
                    class="problem-link"
                  >
                    去作答 <span aria-hidden="true">↗</span>
                  </RouterLink>
                </div>
              </div>
              <el-empty v-else description="本卷没有编程题" :image-size="72" />
            </el-card>
          </section>

          <aside v-if="sessionActive || objectiveQuestions.length" class="answer-sidebar" aria-label="作答工具">
            <el-card v-if="sessionActive" shadow="never" class="session-toolbar" aria-label="作答进度与提交">
              <div class="session-main">
                <div class="timer-block" aria-live="polite">
                  <span class="timer-label">剩余时间</span>
                  <strong class="timer" :class="{ 'timer-warn': remaining <= 300 }">{{ formatTime(remaining) }}</strong>
                </div>
                <div class="progress-block">
                  <div class="progress-copy">
                    <strong>{{ answeredCount }} / {{ objectiveQuestions.length }}</strong>
                    <span>道客观题已作答</span>
                  </div>
                  <el-progress :percentage="answerProgress" :show-text="false" :stroke-width="7" />
                </div>
                <div class="session-actions">
                  <span class="save-hint" :class="{ 'save-hint-error': !draftAvailable }">
                    {{ draftAvailable ? '本设备暂存不计分，请提交客观题；到时按已提交答案计分' : '本地暂存不可用，请及时提交答案并避免刷新' }}
                  </span>
                  <el-button :loading="submitting" :disabled="submitting || finishing" @click="submitAnswers()">提交客观题</el-button>
                  <el-button type="primary" :loading="finishing" :disabled="submitting" @click="finishSession(false)">
                    {{ remaining === 0 ? '重试交卷' : '交卷' }}
                  </el-button>
                </div>
              </div>
              <div v-if="attempt.gradingPending" class="timeout-hint" role="status">编程题仍在判题，正在等待判题完成后自动交卷。作答时间已结束，不能继续修改答案。</div>
              <div v-else-if="autoFinishFailed" class="timeout-hint" role="status">
                自动交卷暂未完成，答案仍保留。请重试交卷，系统会继续自动重试。
              </div>
            </el-card>

            <el-card v-if="objectiveQuestions.length" shadow="never" class="navigator-card">
              <template #header>
                <div class="navigator-heading">
                  <strong>答题卡</strong>
                  <span>{{ answeredCount }} / {{ objectiveQuestions.length }}</span>
                </div>
              </template>
              <div class="navigator-legend">
                <span><i class="legend-dot legend-answered"></i>已作答</span>
                <span><i class="legend-dot"></i>未作答</span>
                <span v-if="hasUnsubmittedChanges"><i class="legend-dot legend-unsubmitted"></i>未提交</span>
              </div>
              <div class="question-nav-grid">
                <button
                  v-for="(q, idx) in objectiveQuestions"
                  :key="q.id"
                  type="button"
                  class="question-nav-button"
                  :class="{
                    'nav-answered': isAnswered(q.id),
                    'nav-unsubmitted': isAnswerUnsubmitted(q),
                    'nav-correct': currentResult(q)?.correct,
                    'nav-incorrect': currentResult(q) && !currentResult(q).correct
                  }"
                  :aria-label="`第 ${idx + 1} 题，${isAnswered(q.id) ? '已作答' : '未作答'}`"
                  @click="scrollToQuestion(q.id)"
                >{{ idx + 1 }}</button>
              </div>
              <el-progress :percentage="answerProgress" :show-text="false" :stroke-width="6" />
              <div class="navigator-footnote">点击题号可跳转；答案会暂存在本设备。</div>
            </el-card>
          </aside>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Timer } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { getExamPaperDetail, submitExamPaperAnswers, startExamAttempt, getExamAttempt, finishExamAttempt } from '@/api/examPaper'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { useUserStore } from '@/stores/userStore'
import { getDifficultyLabel } from '@/utils/difficulty'
import { createMarkdown } from '@/utils/markdown'
import {
  buildExamAnswerState,
  buildExamAnswersPayload,
  createExamDraftStorage,
  examAttemptDeadline,
  getAnsweredQuestionIds,
  isExamAnswerUnsubmitted,
  remainingExamSeconds,
  serializeQuestionAnswer
} from '@/utils/examPaper'
import 'github-markdown-css/github-markdown.css'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const paper = ref(null)
const loading = ref(false)
const submitting = ref(false)
const attempt = ref(null)
const remaining = ref(0)
const starting = ref(false)
const finishing = ref(false)
const draftAvailable = ref(true)
const autoFinishFailed = ref(false)
let timerId = null
let deadlineAt = 0
let autoRetryAt = 0
let loadSequence = 0
let draftStorage = null

const markdown = createMarkdown({ html: false, math: true, breaks: true })
const renderMarkdown = (text) => markdown.render(text || '')
const answers = reactive({})
const results = reactive({})

const CATEGORY_LABELS = { GESP: 'GESP', CSP_J: 'CSP-J', CSP_S: 'CSP-S', NOIP: 'NOIP', MOCK: '模拟' }
const categoryLabel = (value) => CATEGORY_LABELS[value] || value || '套卷'
const typeLabel = (type) => ({ SINGLE: '单选', MULTIPLE: '多选', JUDGE: '判断' })[type] || type
const typeTag = (type) => ({ SINGLE: 'primary', MULTIPLE: 'warning', JUDGE: 'info' })[type] || 'info'
const difficultyLabel = (difficulty) => getDifficultyLabel(difficulty)

const objectiveQuestions = computed(() => paper.value?.questions ?? [])
const sessionActive = computed(() => attempt.value?.status === 'RUNNING')
const answeredIds = computed(() => new Set(getAnsweredQuestionIds(objectiveQuestions.value, answers)))
const answeredCount = computed(() => answeredIds.value.size)
const answerProgress = computed(() => objectiveQuestions.value.length
  ? Math.round((answeredCount.value / objectiveQuestions.value.length) * 100)
  : 0)
const objectiveTotal = computed(() => objectiveQuestions.value.reduce((sum, question) => sum + (question.score ?? 0), 0))
const objectiveScore = computed(() => {
  const questions = objectiveQuestions.value
  if (!questions.length || questions.some((question) => !currentResult(question))) return null
  return questions.reduce((sum, question) => sum + (currentResult(question).score ?? 0), 0)
})
const hasUnsubmittedChanges = computed(() => objectiveQuestions.value.some(isAnswerUnsubmitted))

const goBack = () => router.push({ name: 'Training', query: { ...route.query } })
const questionAnchor = (id) => `objective-question-${id}`
const isAnswered = (id) => answeredIds.value.has(id)
const currentResult = (question) => {
  const result = results[question.id]
  if (!result) return null
  return serializeQuestionAnswer(question, result.myAnswer) === serializeQuestionAnswer(question, answers[question.id])
    ? result
    : null
}
const isAnswerUnsubmitted = (question) => {
  if (attempt.value?.status !== 'TIMEOUT') return false
  const savedAnswer = attempt.value?.answers?.find((item) => String(item.questionId) === String(question.id))?.answer
    ?? attempt.value?.results?.find((item) => String(item.questionId) === String(question.id))?.myAnswer
    ?? ''
  return isExamAnswerUnsubmitted(question, answers[question.id], savedAnswer)
}
const scrollToQuestion = (id) => document.getElementById(questionAnchor(id))?.scrollIntoView({ behavior: 'smooth', block: 'start' })
const saveAnswersDraft = () => {
  if (!draftStorage || !sessionActive.value) return
  draftAvailable.value = draftStorage.save(answers)
}

const clearReactiveRecord = (record) => Object.keys(record).forEach((key) => delete record[key])

const setupAttemptState = (nextAttempt, paperId, options = {}) => {
  attempt.value = nextAttempt
  // 进行中的作答统一进入整卷答题页；本页只保留介绍、成绩单和历史成绩。
  if (nextAttempt?.status === 'RUNNING') {
    router.replace({ name: 'ExamPaperAnswer', params: { id: paperId }, query: { attemptId: nextAttempt.id } })
    return
  }
  clearReactiveRecord(answers)
  clearReactiveRecord(results)
  draftAvailable.value = true

  if (nextAttempt?.id != null && nextAttempt.status === 'TIMEOUT') {
    const userId = userStore.userId ?? userStore.userInfo?.id
    draftStorage = userId && nextAttempt.id != null
      ? createExamDraftStorage({ userId, paperId, attemptId: nextAttempt.id })
      : null
    draftAvailable.value = Boolean(draftStorage)
  } else {
    draftStorage = null
  }

  const draft = Object.hasOwn(options, 'draft') ? options.draft : draftStorage?.load() ?? null
  Object.assign(answers, buildExamAnswerState({ questions: objectiveQuestions.value, attempt: nextAttempt, draft }))
  for (const result of nextAttempt?.results ?? []) {
    if (result?.questionId != null) results[result.questionId] = result
  }

  if (nextAttempt?.status === 'RUNNING') {
    remaining.value = Number(nextAttempt.remainingSeconds ?? 0)
    deadlineAt = examAttemptDeadline(remaining.value)
    autoRetryAt = 0
    autoFinishFailed.value = false
    saveAnswersDraft()
    startTimer()
  } else {
    stopTimer()
    remaining.value = 0
  }
}

const loadPaper = async (paperId) => {
  const sequence = ++loadSequence
  stopTimer()
  draftStorage = null
  paper.value = null
  attempt.value = null
  submitting.value = false
  finishing.value = false
  starting.value = false
  draftAvailable.value = true
  clearReactiveRecord(answers)
  clearReactiveRecord(results)
  loading.value = true
  try {
    const paperResponse = await getExamPaperDetail(paperId)
    if (sequence !== loadSequence) return
    paper.value = unwrapResultData(paperResponse) || null
    if (!paper.value) return

    const userStore = useUserStore()
    if (!userStore.getAccessToken()) return
    const attemptResponse = await getExamAttempt(paperId)
    if (sequence !== loadSequence) return
    setupAttemptState(unwrapResultData(attemptResponse) || null, paperId)
  } catch {
    if (sequence === loadSequence) ElMessage.error('试卷加载失败，请稍后重试')
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const submitAnswers = async ({ quiet = false, paperId: requestedPaperId, sequence: requestedSequence } = {}) => {
  const paperId = String(requestedPaperId ?? route.params.id)
  const sequence = requestedSequence ?? loadSequence
  if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
  const questions = objectiveQuestions.value
  if (!questions.length) return true
  if (!useUserStore().getAccessToken()) {
    if (!quiet) ElMessage.warning('请先登录')
    return false
  }
  if (!sessionActive.value || submitting.value || (finishing.value && !quiet)) return false

  submitting.value = true
  try {
    const payload = buildExamAnswersPayload(questions, answers)
    const res = await submitExamPaperAnswers(paperId, payload)
    if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
    if (!isResultSuccess(res)) {
      if (!quiet) ElMessage.error(getResultMessage(res, '答案提交失败'))
      return false
    }
    const list = unwrapResultData(res) || []
    clearReactiveRecord(results)
    const submittedAnswerById = new Map(payload.answers.map((item) => [String(item.questionId), item.answer]))
    list.forEach((result) => {
      const question = questions.find((item) => String(item.id) === String(result.questionId))
      const capturedAnswer = submittedAnswerById.get(String(result.questionId))
      if (
        question && capturedAnswer != null &&
        serializeQuestionAnswer(question, answers[question.id]) === capturedAnswer &&
        serializeQuestionAnswer(question, result.myAnswer) === capturedAnswer
      ) results[result.questionId] = result
    })
    draftAvailable.value = draftStorage?.save(answers) ?? true
    if (!quiet) {
      const score = list.reduce((sum, result) => sum + (result.score ?? 0), 0)
      ElMessage.success(`判分完成，客观题得分 ${score}`)
    }
    return true
  } catch {
    if (sequence === loadSequence && paperId === String(route.params.id) && !quiet) {
      ElMessage.error('答案提交失败，请检查网络后重试')
    }
    return false
  } finally {
    submitting.value = false
  }
}

const startSession = async () => {
  if (!useUserStore().getAccessToken()) {
    ElMessage.warning('请先登录')
    return
  }
  const paperId = String(route.params.id)
  const sequence = loadSequence
  starting.value = true
  try {
    const res = await startExamAttempt(paperId)
    if (sequence !== loadSequence || paperId !== String(route.params.id)) return
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '无法开始作答'))
      return
    }
    const nextAttempt = unwrapResultData(res)
    if (!nextAttempt) {
      ElMessage.error('未能创建作答会话，请重试')
      return
    }
    setupAttemptState(nextAttempt, paperId)
    if (nextAttempt.status === 'RUNNING') ElMessage.success('作答已开始，祝你发挥顺利')
  } catch {
    if (sequence === loadSequence && paperId === String(route.params.id)) {
      ElMessage.error('开始作答失败，请检查网络后重试')
    }
  } finally {
    starting.value = false
  }
}

const syncAttemptAfterDeadline = async (paperId, sequence) => {
  draftStorage?.save(answers)
  const localDraft = draftStorage?.load() ?? null
  const res = await getExamAttempt(paperId)
  if (sequence !== loadSequence || paperId !== String(route.params.id)) return 'stale'
  if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '无法读取交卷状态'))
  const latest = unwrapResultData(res)
  if (!latest) {
    autoRetryAt = Date.now() + 5000
    return 'running'
  }

  setupAttemptState(latest, paperId, { draft: localDraft })
  if (latest.status && latest.status !== 'RUNNING') {
    autoFinishFailed.value = false
    if (hasUnsubmittedChanges.value) {
      ElMessage.warning('时间已到，未提交修改未计入成绩；本次分数按已提交答案统计')
    } else {
      ElMessage.info('时间到，已自动交卷')
    }
    return 'finished'
  }
  autoRetryAt = Date.now() + 5000
  return latest.gradingPending ? 'grading' : 'running'
}

const finishSession = async (auto) => {
  if (finishing.value || !sessionActive.value) return false
  if (remaining.value === 0 && !auto) return finishSession(true)
  if (submitting.value) {
    if (auto) autoRetryAt = Date.now() + 1000
    return false
  }
  const paperId = String(route.params.id)
  const sequence = loadSequence
  if (!auto) {
    try {
      await ElMessageBox.confirm('交卷后将无法继续修改答案，确定现在交卷吗？', '确认交卷', {
        confirmButtonText: '确认交卷',
        cancelButtonText: '继续作答',
        type: 'warning'
      })
    } catch {
      return false
    }
    if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
  }

  finishing.value = true
  autoFinishFailed.value = false
  try {
    if (auto || remainingExamSeconds(deadlineAt) === 0) {
      const status = await syncAttemptAfterDeadline(paperId, sequence)
      if (status === 'finished') return true
      if (status === 'stale' || status === 'grading') return false
      autoFinishFailed.value = true
      ElMessage.warning('正在确认服务端交卷状态，系统会继续重试')
      return false
    }
    // Persist every visible choice before the server closes the attempt. If grading
    // fails, leave the attempt running and keep both local and server state intact.
    const answersSaved = await submitAnswers({ quiet: true, paperId, sequence })
    if (!answersSaved) {
      if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
      if (remainingExamSeconds(deadlineAt) === 0) {
        try {
          const status = await syncAttemptAfterDeadline(paperId, sequence)
          if (status === 'finished') return true
          if (status === 'stale') return false
        } catch {
          // Keep the attempt open locally and retry after the backoff below.
        }
      }
      autoFinishFailed.value = true
      ElMessage.error('答案提交失败，试卷仍未交卷。请检查网络后重试')
      autoRetryAt = Date.now() + 5000
      return false
    }

    const res = await finishExamAttempt(paperId)
    if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
    if (!isResultSuccess(res)) {
      autoFinishFailed.value = true
      ElMessage.error(getResultMessage(res, '交卷失败，答案已保留，请重试'))
      autoRetryAt = Date.now() + 5000
      return false
    }
    const finishedAttempt = unwrapResultData(res)
    if (!finishedAttempt) {
      autoFinishFailed.value = true
      ElMessage.error('未收到交卷结果，答案已保留，请重试')
      autoRetryAt = Date.now() + 5000
      return false
    }
    if (finishedAttempt.status === 'RUNNING') {
      attempt.value = finishedAttempt
      ElMessage.info('编程题仍在判题，请等待完成后再交卷')
      return false
    }
    attempt.value = finishedAttempt
    stopTimer()
    draftStorage?.clear()
    draftStorage = null
    if (auto) ElMessage.info('时间到，已自动交卷')
    return true
  } catch (error) {
    if (sequence !== loadSequence || paperId !== String(route.params.id)) return false
    if (error?.errorCode === 'EXAM_ATTEMPT_GRADING_PENDING' || error?.response?.data?.errorCode === 'EXAM_ATTEMPT_GRADING_PENDING') {
      autoFinishFailed.value = false
      ElMessage.info('编程题仍在判题，客观题已保存。请等待判题完成后再次交卷')
      return false
    }
    autoFinishFailed.value = true
    ElMessage.error(auto ? '自动交卷暂未完成，系统会继续重试' : '交卷失败，答案已保留，请检查网络后重试')
    autoRetryAt = Date.now() + 5000
    return false
  } finally {
    finishing.value = false
  }
}

const tickTimer = () => {
  if (!sessionActive.value) return
  remaining.value = remainingExamSeconds(deadlineAt)
  if (remaining.value === 0 && Date.now() >= autoRetryAt) void finishSession(true)
}

const startTimer = () => {
  stopTimer()
  tickTimer()
  timerId = window.setInterval(tickTimer, 1000)
}

const stopTimer = () => {
  if (timerId != null) {
    window.clearInterval(timerId)
    timerId = null
  }
}

const onVisibilityChange = () => {
  if (document.visibilityState === 'visible') tickTimer()
}

const formatTime = (seconds) => {
  const safeSeconds = Math.max(0, seconds)
  const hours = Math.floor(safeSeconds / 3600)
  const minutes = Math.floor((safeSeconds % 3600) / 60)
  const secs = safeSeconds % 60
  const pad = (value) => String(value).padStart(2, '0')
  return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(secs)}` : `${pad(minutes)}:${pad(secs)}`
}

watch(() => answers, saveAnswersDraft, { deep: true })
watch(() => [route.params.id, userStore.userId], ([paperId]) => {
  if (paperId != null) loadPaper(paperId)
}, { immediate: true })

document.addEventListener('visibilitychange', onVisibilityChange)
onBeforeUnmount(() => {
  ++loadSequence
  stopTimer()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.exam-paper-detail { min-height: 100vh; background: var(--bg-surface-hover); }
.nav-header { padding: 8px 0; }
.nav-title { max-width: min(62vw, 680px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.main-content { max-width: 1320px; margin: 0 auto; padding: 18px 24px 48px; display: flex; flex-direction: column; gap: 16px; }
.paper-info { border-radius: 12px; }
.info-header { display: flex; gap: 8px; }
.info-title { font-size: 24px; line-height: 1.35; font-weight: 700; margin: 12px 0 0; color: var(--text-primary); }
.info-meta { display: flex; flex-wrap: wrap; gap: 18px; color: var(--text-secondary); font-size: 13px; margin-top: 11px; }
.info-desc { color: var(--text-regular); font-size: 14px; line-height: 1.6; margin-top: 12px; white-space: pre-wrap; }
.session-card { border-radius: 12px; }
.start-panel { display: flex; justify-content: space-between; align-items: center; gap: 24px; }
.start-copy { display: flex; align-items: flex-start; gap: 14px; }
.start-icon { width: 42px; height: 42px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 12px; background: var(--bg-surface-hover); color: var(--accent-primary); font-size: 20px; }
.start-title { font-size: 16px; font-weight: 650; color: var(--text-primary); }
.start-copy p { margin: 5px 0 0; color: var(--text-secondary); font-size: 13px; line-height: 1.55; }
.session-toolbar { border-radius: 12px; flex-shrink: 0; }
.session-main { display: flex; flex-direction: column; align-items: stretch; gap: 16px; }
.timer-block { display: flex; flex-direction: column; min-width: 112px; }
.timer-label { color: var(--text-secondary); font-size: 11px; }
.timer { font-size: 25px; line-height: 1.25; color: var(--accent-primary); font-variant-numeric: tabular-nums; }
.timer-warn { color: var(--accent-danger); }
.progress-block { width: 100%; min-width: 0; }
.progress-copy { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; margin-bottom: 6px; font-size: 12px; color: var(--text-secondary); }
.progress-copy strong { font-size: 14px; color: var(--text-primary); }
.session-actions { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px; }
.session-actions .el-button { margin-left: 0; min-width: 0; padding-inline: 8px; }
.save-hint { grid-column: 1 / -1; font-size: 12px; line-height: 1.6; color: var(--text-secondary); }
.save-hint-error, .timeout-hint { color: var(--accent-danger); }
.timeout-hint { margin-top: 10px; font-size: 12px; }
.result-summary { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; }
.score-item, .score-total { display: flex; flex-direction: column; gap: 2px; color: var(--text-secondary); font-size: 12px; }
.score-item strong { color: var(--text-primary); font-size: 18px; }
.score-total strong { color: var(--accent-primary); font-size: 22px; }
.timeout-result-alert { margin-top: 16px; }
.result-summary .el-button { margin-left: auto; }
.answer-layout { display: grid; grid-template-columns: minmax(0, 1fr) 270px; align-items: start; gap: 16px; }
.question-column { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.question-card, .problem-card, .navigator-card { border-radius: 12px; }
.answer-layout-main-only { grid-template-columns: minmax(0, 1fr); }
.answer-sidebar { position: sticky; top: 8px; display: flex; flex-direction: column; gap: 16px; min-width: 0; max-height: calc(100dvh - var(--app-header-height, 72px) - 48px); overflow-y: auto; scrollbar-width: thin; }
.navigator-card { flex-shrink: 0; }
.navigator-heading { display: flex; align-items: center; justify-content: space-between; }
.navigator-heading strong { font-size: 15px; }
.navigator-heading span { font-size: 12px; color: var(--text-secondary); }
.navigator-legend { display: flex; gap: 14px; margin: 0 0 14px; color: var(--text-secondary); font-size: 11px; }
.navigator-legend span { display: inline-flex; align-items: center; gap: 5px; }
.legend-dot { width: 9px; height: 9px; border: 1px solid var(--border-light); border-radius: 50%; background: var(--bg-surface); }
.legend-answered { border-color: var(--accent-primary); background: var(--accent-primary); }
.legend-unsubmitted { border-color: var(--accent-warning); border-style: dashed; background: var(--accent-warning-light); }
.question-nav-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 7px; margin-bottom: 16px; }
.question-nav-button { aspect-ratio: 1; border: 1px solid var(--border-light); border-radius: 8px; background: var(--bg-surface); color: var(--text-regular); cursor: pointer; font: inherit; font-size: 12px; transition: border-color .15s ease, background-color .15s ease; }
.question-nav-button:hover, .question-nav-button:focus-visible { border-color: var(--accent-primary); outline: none; }
.question-nav-button.nav-answered { border-color: var(--accent-primary); background: var(--accent-primary); color: var(--text-on-accent, #fff); }
.question-nav-button.nav-correct { border-color: var(--accent-success); background: var(--accent-success); color: #fff; }
.question-nav-button.nav-incorrect { border-color: var(--accent-danger); background: var(--accent-danger); color: #fff; }
.question-nav-button.nav-unsubmitted { border-color: var(--accent-warning); border-style: dashed; background: var(--accent-warning-light); color: var(--text-primary); }
.navigator-footnote { margin-top: 12px; color: var(--text-secondary); font-size: 11px; line-height: 1.5; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.card-header h2 { margin: 0; font-size: 16px; color: var(--text-primary); }
.card-header > div > span, .card-header > span { color: var(--text-secondary); font-size: 12px; }
.card-header > div > span { display: block; margin-top: 3px; }
.question-item { scroll-margin-top: 16px; border: 1px solid var(--border-light); border-radius: 10px; padding: 16px; margin-bottom: 12px; background: var(--bg-surface); }
.question-item:last-child { margin-bottom: 0; }
.question-item-answered { border-color: color-mix(in srgb, var(--accent-primary) 32%, var(--border-light)); }
.question-head { display: flex; align-items: center; gap: 10px; }
.question-no { width: 28px; height: 28px; flex: 0 0 auto; border-radius: 8px; display: grid; place-items: center; background: var(--bg-surface-hover); color: var(--text-primary); font-weight: 700; font-size: 13px; }
.question-head-meta { display: flex; flex-wrap: wrap; gap: 6px; }
.question-status { margin-left: auto; color: var(--text-secondary); font-size: 11px; }
.question-statement { font-size: 15px; margin: 12px 0 14px; white-space: pre-wrap; line-height: 1.7; color: var(--text-primary); }
.option-group { display: flex; flex-direction: column; gap: 5px; }
.option-row { width: 100%; display: flex; margin-right: 0; height: auto; min-height: 38px; white-space: normal; border: 1px solid transparent; border-radius: 8px; padding: 8px 10px; }
.option-row:hover { background: var(--bg-surface-hover); }
.option-markdown { color: var(--text-primary); line-height: 1.55; }
.boolean-group { flex-direction: row; gap: 10px; }
.boolean-group .option-row { width: auto; min-width: 96px; }
.question-result { margin-top: 12px; font-size: 13px; padding: 10px 12px; border-radius: 8px; line-height: 1.5; }
.option-markdown :deep(p), .question-statement :deep(p), .explanation :deep(p) { margin: 0; }
.explanation { margin-top: 8px; padding-top: 8px; border-top: 1px solid currentColor; }
.result-ok { background: var(--accent-success-light); color: var(--accent-success); }
.result-bad { background: var(--accent-danger-light); color: var(--accent-danger); }
.problem-list { display: flex; flex-direction: column; }
.problem-row { display: flex; align-items: center; gap: 12px; padding: 13px 2px; }
.problem-row + .problem-row { border-top: 1px solid var(--border-light); }
.problem-order { width: 30px; height: 30px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 8px; color: var(--text-secondary); background: var(--bg-surface-hover); font-size: 12px; font-weight: 650; }
.problem-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.problem-info strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-primary); font-size: 14px; }
.problem-info span { color: var(--text-secondary); font-size: 12px; }
.problem-link { flex: 0 0 auto; margin-left: auto; text-decoration: none; color: var(--accent-primary); font-size: 13px; }
@media (max-width: 900px) {
  .answer-layout { grid-template-columns: minmax(0, 1fr) 220px; gap: 12px; }
  .session-main { gap: 16px; }
}
@media (max-width: 720px) {
  .nav-header { min-width: 0; width: 100%; overflow: hidden; }
  .nav-header :deep(.el-page-header__title), .nav-header :deep(.el-divider--vertical) { display: none; }
  .nav-header :deep(.el-page-header__left) { margin-right: 8px; flex-shrink: 0; }
  .nav-header :deep(.el-page-header__content) { min-width: 0; overflow: hidden; display: block; }
  .nav-title { display: block; max-width: 100%; font-size: 14px; }
  .main-content { padding: 12px 12px 36px; gap: 12px; }
  .info-title { font-size: 20px; }
  .start-panel { align-items: stretch; flex-direction: column; gap: 16px; }
  .start-panel > .el-button { width: 100%; }
  .session-main { gap: 14px; }
  .timer-block { min-width: 88px; }
  .timer { font-size: 21px; }
  .progress-copy { flex-wrap: wrap; }
  .progress-copy strong { white-space: nowrap; }
  .answer-layout { display: flex; flex-direction: column; }
  .answer-sidebar { position: static; width: 100%; order: -1; max-height: none; overflow: visible; }
  .navigator-card :deep(.el-card__body) { padding: 12px; }
  .question-nav-grid { grid-template-columns: repeat(8, minmax(0, 1fr)); margin-bottom: 12px; }
  .question-item { padding: 13px; scroll-margin-top: 16px; }
  .question-status { display: none; }
  .problem-row { gap: 9px; }
  .problem-info strong { max-width: 44vw; }
  .result-summary { gap: 14px; }
  .result-summary .el-button { width: 100%; margin-left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .question-nav-button { transition: none; }
}
</style>
