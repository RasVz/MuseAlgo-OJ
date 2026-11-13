<template>
  <div class="exam-paper-answer" v-loading="loading">
    <el-result v-if="error" icon="error" title="无法进入整卷答题" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="backToPaper">返回试卷</el-button>
      </template>
    </el-result>

    <ExamPaperWorkspace
      v-else-if="paper"
      :title="paper.title || '成套试卷'"
      :status-text="statusText"
      :remaining-seconds="remaining"
      :nav-items="navItems"
      :active-key="activeKey"
      :can-submit="canSubmit"
      :finishing="finishing"
      :saving-objective="savingObjective"
      :show-save-objective="objectiveQuestions.length > 0 && canSubmit"
      @select="selectQuestion"
      @back="backToPaper"
      @finish="finishPaper"
      @save-objective="saveObjective"
    >
      <ObjectiveQuestionPane
        v-if="activeItem?.type === 'objective' && activeObjective"
        :key="activeObjective.id"
        :question="activeObjective"
        :model-value="answers[activeObjective.id]"
        :disabled="!canSubmit"
        :index="objectiveQuestions.indexOf(activeObjective)"
        @update:model-value="answers[activeObjective.id] = $event"
      />

      <CodingQuestionPane
        v-else-if="activeItem?.type === 'coding'"
        :key="activeItem.key"
        :problem="codingProblem"
        :draft-adapter="draftAdapter"
        :submitting="submitting"
        :last-result="activeCodingResult"
        :pending="Boolean(activeCoding?.pending)"
        :can-submit="canSubmit"
        :loading="codingLoading"
        @submit="submitCoding"
      />

      <el-empty v-else description="本卷暂无题目" />
    </ExamPaperWorkspace>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ExamPaperWorkspace from '@/views/answer/components/ExamPaperWorkspace.vue'
import ObjectiveQuestionPane from '@/views/answer/components/ObjectiveQuestionPane.vue'
import CodingQuestionPane from '@/views/answer/components/CodingQuestionPane.vue'
import { getAnswerPaper, getAnswerProblem } from '@/api/examPaperAnswer'
import { startExamAttempt, finishExamAttempt, submitExamPaperAnswers } from '@/api/examPaper'
import { submitCode } from '@/api/problem'
import { getSubmissionDetail } from '@/api/submission'
import { useUserStore } from '@/stores/userStore'
import { useServerClock } from '@/composables/useServerClock'
import { createScopedDraftAdapter } from '@/composables/useScopedCodeDraft'
import {
  buildExamAnswerState,
  buildExamAnswersPayload,
  createExamDraftStorage
} from '@/utils/examPaper'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { remaining, sync } = useServerClock()

const paperId = computed(() => String(route.params.id))
const attemptId = ref(null)
const paper = ref(null)
const loading = ref(true)
const error = ref('')
const answers = reactive({})
const activeKey = ref('')
const codingProblem = ref(null)
const codingLoading = ref(false)
const submitting = ref(false)
const savingObjective = ref(false)
const finishing = ref(false)
const resultsByProblem = reactive({})
const draftAdapter = ref(null)

let objectiveDraft = null
let refreshTimer = null
let submissionPollTimer = null
let codingGeneration = 0
let finished = false
let disposed = false

const objectiveQuestions = computed(() => paper.value?.objectiveQuestions ?? [])
const codingProblems = computed(() => paper.value?.codingProblems ?? [])
const canSubmit = computed(() => paper.value?.canSubmit === true && remaining.value > 0)
const statusText = computed(() => ({
  RUNNING: '作答中',
  TIMEOUT: '时间到',
  SUBMITTED: '已交卷'
})[paper.value?.status] || '')

const navItems = computed(() => {
  const objective = objectiveQuestions.value.map((question, index) => {
    const value = answers[question.id]
    const answered = Array.isArray(value) ? value.length > 0 : String(value ?? '').trim().length > 0
    const status = question.correct === true
      ? 'CORRECT'
      : question.correct === false
        ? 'INCORRECT'
        : answered ? 'ANSWERED' : 'UNTOUCHED'
    return {
      key: `objective-${question.id}`,
      label: `客观题 ${index + 1}`,
      shortLabel: String(index + 1),
      type: 'objective',
      status
    }
  })
  const coding = codingProblems.value.map((problem, index) => ({
    key: `coding-${problem.problemId}`,
    label: `编程题 ${index + 1}`,
    shortLabel: String(objectiveQuestions.value.length + index + 1),
    type: 'coding',
    status: problem.pending ? 'PENDING' : (problem.myStatus || 'UNTOUCHED'),
    pending: problem.pending
  }))
  return [...objective, ...coding]
})

const activeItem = computed(() => navItems.value.find(item => item.key === activeKey.value) || null)
const activeObjective = computed(() => {
  if (activeItem.value?.type !== 'objective') return null
  return objectiveQuestions.value.find(question => `objective-${question.id}` === activeKey.value) || null
})
const activeCoding = computed(() => {
  if (activeItem.value?.type !== 'coding') return null
  return codingProblems.value.find(problem => `coding-${problem.problemId}` === activeKey.value) || null
})
const activeCodingResult = computed(() => activeCoding.value
  ? resultsByProblem[activeCoding.value.problemId] || null
  : null)

const bootstrap = async () => {
  loading.value = true
  error.value = ''
  try {
    let id = route.query.attemptId ? Number(route.query.attemptId) : null
    if (!id) {
      const res = await startExamAttempt(paperId.value)
      if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '无法开始作答'))
      const attempt = unwrapResultData(res)
      if (!attempt?.id) throw new Error('未能创建作答会话')
      id = Number(attempt.id)
      await router.replace({
        name: 'ExamPaperAnswer',
        params: { id: paperId.value },
        query: { attemptId: id }
      })
    }
    attemptId.value = id
    draftAdapter.value = createScopedDraftAdapter(`paper:${id}`)
    const userId = userStore.userId ?? userStore.userInfo?.id
    objectiveDraft = userId
      ? createExamDraftStorage({ userId, paperId: paperId.value, attemptId: id })
      : null
    await refreshPaper(true)
    if (!paper.value) throw new Error('试卷加载失败')
    if (paper.value.status !== 'RUNNING') {
      if (paper.value.status === 'TIMEOUT') {
        try { await finishExamAttempt(paperId.value) } catch { /* 结果页会展示最终状态 */ }
      }
      await router.replace({ name: 'TrainingDetail', params: { id: paperId.value } })
      return
    }
    startRefreshLoop()
  } catch (caught) {
    error.value = caught.message || '试卷加载失败'
  } finally {
    loading.value = false
  }
}

const refreshPaper = async (initial = false) => {
  if (!attemptId.value) return
  const res = await getAnswerPaper(paperId.value, attemptId.value)
  if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '试卷加载失败'))
  paper.value = unwrapResultData(res)
  sync({
    remainingSeconds: paper.value?.remainingSeconds,
    deadline: paper.value?.deadline
  })
  if (initial) restoreAnswers()
  syncActiveKey()
  await loadActiveCoding()
}

const restoreAnswers = () => {
  const questions = objectiveQuestions.value
  const draft = objectiveDraft?.load() ?? null
  const serverAnswers = questions.map(question => ({
    questionId: question.id,
    answer: question.myAnswer ?? ''
  }))
  const state = buildExamAnswerState({
    questions,
    attempt: { answers: serverAnswers },
    draft
  })
  Object.assign(answers, state)
}

const syncActiveKey = () => {
  const requested = typeof route.query.q === 'string' ? route.query.q : ''
  if (requested && navItems.value.some(item => item.key === requested)) {
    activeKey.value = requested
  } else if (!activeKey.value || !navItems.value.some(item => item.key === activeKey.value)) {
    activeKey.value = navItems.value[0]?.key || ''
  }
}

const loadActiveCoding = async () => {
  const item = activeItem.value
  if (item?.type !== 'coding') {
    codingProblem.value = null
    codingLoading.value = false
    return
  }
  const problemId = Number(item.key.slice('coding-'.length))
  if (codingProblem.value?.problemId === problemId) return
  const generation = ++codingGeneration
  codingLoading.value = true
  try {
    const res = await getAnswerProblem(paperId.value, attemptId.value, problemId)
    if (generation !== codingGeneration) return
    if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '题面加载失败'))
    codingProblem.value = unwrapResultData(res)
  } catch (caught) {
    if (generation === codingGeneration) showApiError(caught, '题面加载失败')
  } finally {
    if (generation === codingGeneration) codingLoading.value = false
  }
}

const selectQuestion = key => {
  activeKey.value = key
  router.replace({ query: { ...route.query, q: key } })
  loadActiveCoding()
}

const saveObjective = async () => {
  if (!objectiveQuestions.value.length || savingObjective.value) return
  savingObjective.value = true
  try {
    const payload = buildExamAnswersPayload(objectiveQuestions.value, answers)
    const res = await submitExamPaperAnswers(paperId.value, payload)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '客观题提交失败'))
      return
    }
    const list = unwrapResultData(res) || []
    for (const result of list) {
      const question = objectiveQuestions.value.find(item => String(item.id) === String(result.questionId))
      if (!question) continue
      question.correct = result.correct
      question.gainedScore = result.score
      question.myAnswer = result.myAnswer
    }
    objectiveDraft?.save(answers)
    const score = list.reduce((sum, item) => sum + (item.score ?? 0), 0)
    ElMessage.success(`客观题已判分，本次得分 ${score}`)
    await refreshPaper(false)
  } catch (caught) {
    showApiError(caught, '客观题提交失败')
  } finally {
    savingObjective.value = false
  }
}

const submitCoding = async ({ language, code }) => {
  const problem = activeCoding.value
  if (!problem || submitting.value) return
  submitting.value = true
  const requestId = globalThis.crypto?.randomUUID?.()
    || `attempt-${attemptId.value}-${problem.problemId}-${Date.now()}`
  try {
    const res = await submitCode({
      problemId: problem.problemId,
      attemptId: attemptId.value,
      language,
      code,
      clientRequestId: requestId
    })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '提交失败'))
      return
    }
    const result = unwrapResultData(res)
    resultsByProblem[problem.problemId] = result
    ElMessage.success('提交成功，正在判题')
    pollSubmission(result?.id, problem.problemId)
  } catch (caught) {
    showApiError(caught, '提交失败')
  } finally {
    submitting.value = false
  }
}

const pollSubmission = (submissionId, problemId) => {
  if (!submissionId) return
  if (submissionPollTimer) clearInterval(submissionPollTimer)
  let tries = 0
  submissionPollTimer = setInterval(async () => {
    tries += 1
    try {
      const res = await getSubmissionDetail(submissionId)
      const detail = unwrapResultData(res)
      if (detail) {
        resultsByProblem[problemId] = {
          id: detail.id,
          status: detail.status,
          judgePhase: detail.judgePhase,
          score: detail.score,
          runTime: detail.runTime,
          runMemory: detail.runMemory,
          errorMessage: detail.errorMessage
        }
        const terminal = Number(detail.status) > 0
          || ['FINISHED', 'FAILED'].includes(detail.judgePhase)
        if (terminal) {
          clearInterval(submissionPollTimer)
          submissionPollTimer = null
          await refreshPaper(false)
        }
      }
    } catch {
      // 轮询失败不打断答题，下一次定时刷新会继续同步。
    }
    if (tries >= 30 && submissionPollTimer) {
      clearInterval(submissionPollTimer)
      submissionPollTimer = null
    }
  }, 2000)
}

const refreshLoopTick = async () => {
  if (disposed || finished) return
  try {
    if (paper.value?.status === 'RUNNING' && remaining.value <= 0) {
      // 判题未完成时先继续刷新，避免每 15 秒重复弹「仍在判题」提示。
      if (!paper.value?.gradingPending) await finishPaper(true)
      return
    }
    await refreshPaper(false)
  } catch {
    // 定时刷新失败不打断作答；用户可以继续写代码或手动刷新。
  }
}

const startRefreshLoop = () => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = setInterval(refreshLoopTick, 15000)
}

const finishPaper = async (auto = false) => {
  if (finishing.value) return
  if (!auto) {
    try {
      await ElMessageBox.confirm('交卷后不能继续修改，确定交卷？', '交卷确认', {
        type: 'warning',
        confirmButtonText: '确定交卷',
        cancelButtonText: '继续作答'
      })
    } catch {
      return
    }
  }
  if (paper.value?.gradingPending) {
    ElMessage.warning('编程题仍在判题，请等待判题完成后再交卷')
    return
  }
  finishing.value = true
  try {
    const res = await finishExamAttempt(paperId.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '交卷失败'))
      return
    }
    finished = true
    ElMessage.success(auto ? '时间到，已自动交卷' : '交卷成功')
    await router.replace({ name: 'TrainingDetail', params: { id: paperId.value } })
  } catch (caught) {
    showApiError(caught, '交卷失败')
  } finally {
    finishing.value = false
  }
}

const backToPaper = () => {
  router.push({ name: 'TrainingDetail', params: { id: paperId.value } })
}

const handleBeforeUnload = event => {
  if (finished || paper.value?.status !== 'RUNNING') return
  event.preventDefault()
  event.returnValue = ''
}

const handleVisibility = () => {
  if (document.visibilityState === 'visible') refreshLoopTick()
}

watch(remaining, value => {
  if (value === 0 && paper.value?.status === 'RUNNING' && !finishing.value) {
    finishPaper(true)
  }
})

watch(() => route.query.q, () => {
  syncActiveKey()
  loadActiveCoding()
})

watch(answers, () => {
  if (paper.value?.status === 'RUNNING') objectiveDraft?.save(answers)
}, { deep: true })

onMounted(() => {
  bootstrap()
  window.addEventListener('beforeunload', handleBeforeUnload)
  document.addEventListener('visibilitychange', handleVisibility)
})

onBeforeUnmount(() => {
  disposed = true
  if (refreshTimer) clearInterval(refreshTimer)
  if (submissionPollTimer) clearInterval(submissionPollTimer)
  window.removeEventListener('beforeunload', handleBeforeUnload)
  document.removeEventListener('visibilitychange', handleVisibility)
})

onBeforeRouteLeave(async () => {
  if (finished || paper.value?.status !== 'RUNNING') return true
  try {
    await ElMessageBox.confirm('离开后计时继续，确定返回答题卡？', '离开答题页', {
      type: 'warning',
      confirmButtonText: '确定离开',
      cancelButtonText: '继续作答'
    })
    return true
  } catch {
    return false
  }
})
</script>

<style scoped>
.exam-paper-answer {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
</style>
