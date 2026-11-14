<template>
  <div class="contest-answer" v-loading="loading">
    <el-result v-if="error" icon="error" title="无法进入比赛答题" :sub-title="error">
      <template #extra>
        <el-button type="primary" @click="backToContest">返回比赛</el-button>
      </template>
    </el-result>

    <ExamPaperWorkspace
      v-else-if="paper"
      :title="paper.title || '比赛答题'"
      :status-text="statusText"
      :remaining-seconds="remaining"
      :nav-items="navItems"
      :active-key="activeKey"
      :can-submit="canSubmit"
      :finishing="finishing"
      :show-save-objective="false"
      :show-finish="Boolean(paper.canFinish)"
      @select="selectProblem"
      @back="backToContest"
      @finish="finishVpPaper"
    >
      <CodingQuestionPane
        v-if="activeItem"
        :key="activeItem.key"
        :problem="codingProblem"
        :draft-adapter="draftAdapter"
        :submitting="submitting"
        :last-result="activeResult"
        :pending="Boolean(activeItem.pending)"
        :can-submit="canSubmit"
        :loading="codingLoading"
        :result-visibility="paper.resultVisibility || 'FULL'"
        @submit="submitCoding"
      />

      <div v-else-if="paper.status === 'NOT_STARTED'" class="contest-placeholder">
        <el-empty description="比赛尚未开始，开始后可进入答题">
          <p class="countdown-hint">距离开赛 {{ formattedCountdown }}</p>
        </el-empty>
      </div>

      <el-empty v-else description="本场比赛暂无题目" />
    </ExamPaperWorkspace>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ExamPaperWorkspace from '@/views/answer/components/ExamPaperWorkspace.vue'
import CodingQuestionPane from '@/views/answer/components/CodingQuestionPane.vue'
import { getContestAnswerPaper, getContestAnswerProblem, finishVp } from '@/api/contest'
import { submitCode } from '@/api/problem'
import { getSubmissionDetail } from '@/api/submission'
import { useServerClock } from '@/composables/useServerClock'
import { createScopedDraftAdapter } from '@/composables/useScopedCodeDraft'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const route = useRoute()
const router = useRouter()
const { remaining, sync } = useServerClock()

const contestId = computed(() => String(route.params.id))
const paper = ref(null)
const loading = ref(true)
const error = ref('')
const activeKey = ref('')
const codingProblem = ref(null)
const codingLoading = ref(false)
const submitting = ref(false)
const finishing = ref(false)
const resultsByProblem = reactive({})
const draftAdapter = ref(createScopedDraftAdapter(`contest:${contestId.value}`))

let paperTimer = null
let submissionPollTimer = null
let codingGeneration = 0
let disposed = false

const problems = computed(() => paper.value?.problems ?? [])
const canSubmit = computed(() => paper.value?.canSubmit === true && remaining.value > 0)

const statusText = computed(() => ({
  NOT_STARTED: '未开始',
  RUNNING: '作答中',
  ENDED: '已结束'
})[paper.value?.status] || '')

const navItems = computed(() => problems.value.map((problem, index) => ({
  key: `coding-${problem.problemId}`,
  label: `题目 ${letterOf(index)}`,
  shortLabel: letterOf(index),
  status: problem.myStatus || 'UNTOUCHED',
  pending: Boolean(problem.pending)
})))

const activeItem = computed(() => navItems.value.find(item => item.key === activeKey.value) || null)
const activeProblem = computed(() => problems.value.find(
  problem => `coding-${problem.problemId}` === activeKey.value
) || null)
const activeResult = computed(() => activeProblem.value
  ? resultsByProblem[activeProblem.value.problemId] || null
  : null)

const formattedCountdown = computed(() => {
  const seconds = Math.max(0, Number(remaining.value) || 0)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  const pad = value => String(value).padStart(2, '0')
  return hours > 0 ? `${pad(hours)}:${pad(minutes)}:${pad(rest)}` : `${pad(minutes)}:${pad(rest)}`
})

const letterOf = index => (index < 26 ? String.fromCharCode(65 + index) : `#${index + 1}`)

const bootstrap = async () => {
  loading.value = true
  error.value = ''
  try {
    await refreshPaper(true)
    startPaperLoop()
  } catch (caught) {
    error.value = caught.message || '比赛答题加载失败'
  } finally {
    loading.value = false
  }
}

const refreshPaper = async (initial = false) => {
  const res = await getContestAnswerPaper(contestId.value)
  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, '比赛答题加载失败'))
  }
  paper.value = unwrapResultData(res)
  sync({
    remainingSeconds: paper.value?.remainingSeconds,
    deadline: paper.value?.deadline
  })
  if (initial) {
    draftAdapter.value = createScopedDraftAdapter(`contest:${contestId.value}`)
  }
  syncActiveKey()
  await loadActiveProblem()
}

const syncActiveKey = () => {
  const requested = typeof route.query.q === 'string' ? route.query.q : ''
  if (requested && navItems.value.some(item => item.key === requested)) {
    activeKey.value = requested
  } else if (!activeKey.value || !navItems.value.some(item => item.key === activeKey.value)) {
    activeKey.value = navItems.value[0]?.key || ''
  }
}

const loadActiveProblem = async () => {
  const item = activeItem.value
  if (!item) {
    codingProblem.value = null
    codingLoading.value = false
    return
  }
  const problemId = Number(item.key.slice('coding-'.length))
  if (codingProblem.value?.problemId === problemId) return
  const generation = ++codingGeneration
  codingLoading.value = true
  try {
    const res = await getContestAnswerProblem(contestId.value, problemId)
    if (generation !== codingGeneration) return
    if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '题面加载失败'))
    codingProblem.value = unwrapResultData(res)
  } catch (caught) {
    if (generation === codingGeneration) showApiError(caught, '题面加载失败')
  } finally {
    if (generation === codingGeneration) codingLoading.value = false
  }
}

const selectProblem = key => {
  activeKey.value = key
  router.replace({ query: { ...route.query, q: key } })
  loadActiveProblem()
}

const submitCoding = async ({ language, code }) => {
  const problem = activeProblem.value
  if (!problem || submitting.value) return
  if (!canSubmit.value) {
    ElMessage.warning('当前不可提交')
    return
  }
  submitting.value = true
  const requestId = globalThis.crypto?.randomUUID?.()
    || `contest-${contestId.value}-${problem.problemId}-${Date.now()}`
  try {
    const res = await submitCode({
      problemId: problem.problemId,
      contestId: Number(contestId.value),
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
      // 轮询失败不打断答题，下一次刷新继续同步。
    }
    if (tries >= 30 && submissionPollTimer) {
      clearInterval(submissionPollTimer)
      submissionPollTimer = null
    }
  }, 2000)
}

const startPaperLoop = () => {
  if (paperTimer) clearInterval(paperTimer)
  paperTimer = setInterval(async () => {
    if (disposed) return
    try {
      await refreshPaper(false)
    } catch {
      // 定时刷新失败不打断作答。
    }
  }, 30000)
}

const finishVpPaper = async () => {
  if (finishing.value) return
  try {
    await ElMessageBox.confirm('交卷后不能继续提交，确定交卷？', '虚拟参赛交卷', {
      type: 'warning',
      confirmButtonText: '确定交卷',
      cancelButtonText: '继续作答'
    })
  } catch {
    return
  }
  finishing.value = true
  try {
    const res = await finishVp(contestId.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '交卷失败'))
      return
    }
    ElMessage.success('交卷成功')
    await router.replace({ name: 'ContestRanking', params: { id: contestId.value } })
  } catch (caught) {
    showApiError(caught, '交卷失败')
  } finally {
    finishing.value = false
  }
}

const backToContest = () => {
  router.push({ name: 'ContestProblem', params: { id: contestId.value } })
}

const handleVisibility = () => {
  if (document.visibilityState === 'visible') refreshPaper(false).catch(() => {})
}

watch(() => route.query.q, () => {
  syncActiveKey()
  loadActiveProblem()
})

onMounted(() => {
  bootstrap()
  document.addEventListener('visibilitychange', handleVisibility)
})

onBeforeUnmount(() => {
  disposed = true
  if (paperTimer) clearInterval(paperTimer)
  if (submissionPollTimer) clearInterval(submissionPollTimer)
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<style scoped>
.contest-answer {
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.contest-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.countdown-hint {
  margin: 0;
  color: var(--text-secondary);
}
</style>
