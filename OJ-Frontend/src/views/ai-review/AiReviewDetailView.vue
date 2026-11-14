<template>
  <div class="review-detail-page">
    <header class="review-header">
      <div>
        <el-button link type="primary" @click="backToRecords">‹ 复盘记录</el-button>
        <div class="title-line">
          <h1>AI 代码复盘 <span v-if="review">#{{ review.id }}</span></h1>
          <el-tag v-if="review" :type="aiReviewStatus(review.status).type">{{ aiReviewStatus(review.status).label }}</el-tag>
        </div>
        <p v-if="review">P{{ review.problemId }} {{ review.problemTitle || '题目' }} · 提交 #{{ review.submissionId }} · {{ formatTime(review.createTime) }}</p>
      </div>
      <div class="header-actions">
        <el-button v-if="review" @click="router.push({ name: 'SubmissionDetail', params: { id: review.submissionId } })">查看原提交</el-button>
        <el-button v-if="active" type="danger" plain :loading="canceling" :disabled="review?.status === 'CANCELLING' || canceling" @click="cancelGeneration">
          {{ review?.status === 'CANCELLING' ? '停止中' : '停止生成' }}
        </el-button>
        <el-button v-if="review?.status === 'FAILED' || review?.status === 'CANCELLED'" type="primary" @click="openConfirm('RETRY')">重试</el-button>
        <el-button v-if="review?.status === 'SUCCEEDED'" type="primary" plain @click="openConfirm('REANALYZE')">重新分析</el-button>
      </div>
    </header>

    <el-alert v-if="loadError" type="error" :closable="false" show-icon class="load-error">
      <template #title>{{ loadError }}</template>
      <el-button size="small" @click="loadDetail">重试读取</el-button>
    </el-alert>

    <main v-if="review" class="review-layout">
      <section v-if="active" class="state-card">
        <div class="spinner" aria-hidden="true"></div>
        <div>
          <h2>{{ review.status === 'QUEUED' ? '正在等待 AI 服务' : review.status === 'CANCELLING' ? '正在停止复盘生成' : 'AI 正在分析这次提交' }}</h2>
          <p>{{ review.status === 'CANCELLING' ? '正在等待当前生成任务结束，完成后会更新为已停止。' : '复盘在后台继续生成。你可以离开此页，之后从「复盘记录」回来查看。' }}</p>
          <small v-if="statusError">状态暂时无法更新：{{ statusError }} · 正在继续尝试读取</small>
        </div>
      </section>

      <section v-else-if="review.status === 'FAILED' || review.status === 'CANCELLED'" class="state-card ended-card">
        <div class="state-mark">{{ review.status === 'FAILED' ? '!' : 'Ⅱ' }}</div>
        <div>
          <h2>{{ review.status === 'FAILED' ? '本次复盘未完成' : '本次复盘已停止' }}</h2>
          <p>{{ review.message || '你可以查看已收到的内容，或发起一条新的复盘记录。' }}</p>
        </div>
      </section>

      <section class="report-card">
        <div class="report-toolbar">
          <div><h2>复盘内容</h2><span v-if="review.relationType">{{ aiReviewRelation(review.relationType) }}</span></div>
          <el-button v-if="review.status === 'SUCCEEDED'" link type="primary" @click="openFollowup = !openFollowup">
            {{ openFollowup ? '收起追问' : '继续追问' }}
          </el-button>
        </div>
        <div v-if="review.answer" class="markdown-body custom-md review-answer" v-html="renderMarkdown(review.answer)"></div>
        <el-empty v-else-if="active" :image-size="80" description="AI 完成后，复盘内容会显示在这里" />
        <el-empty v-else :image-size="80" description="本次没有可显示的复盘内容" />
        <el-alert v-if="review.status !== 'SUCCEEDED' && review.answer" type="warning" :closable="false"
          title="以下内容未完整生成，请结合原提交自行核对。" class="partial-alert" />
        <div v-if="openFollowup && review.status === 'SUCCEEDED'" class="followup-form">
          <label for="review-followup">继续追问</label>
          <el-input id="review-followup" v-model="followupQuestion" type="textarea" :rows="3" :maxlength="2000"
            placeholder="针对这份复盘继续提问" />
          <div class="followup-actions">
            <el-select v-model="followupMode" aria-label="追问讲解方式">
              <el-option label="自由分析" value="AUTO" />
              <el-option label="检查思路" value="CHECK_APPROACH" />
              <el-option label="排查代码" value="DEBUG_CODE" />
              <el-option label="完整讲解" value="FULL_SOLUTION" />
            </el-select>
            <el-button type="primary" :disabled="!followupQuestion.trim()" :loading="creating" @click="createFollowup">发送追问</el-button>
          </div>
        </div>
      </section>

      <el-collapse class="context-card">
        <el-collapse-item title="查看本次分析依据" name="context">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="题目">P{{ review.problemId }} {{ review.problemTitle || '' }}</el-descriptions-item>
            <el-descriptions-item label="语言">{{ review.language || '—' }}</el-descriptions-item>
            <el-descriptions-item label="评测结果">{{ resultText(review.submissionStatus) }}</el-descriptions-item>
            <el-descriptions-item label="得分">{{ review.score ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="耗时">{{ review.runTime == null ? '—' : `${review.runTime} ms` }}</el-descriptions-item>
            <el-descriptions-item label="内存">{{ review.runMemory == null ? '—' : `${review.runMemory} KB` }}</el-descriptions-item>
            <el-descriptions-item label="AI 服务" :span="3">{{ review.serviceName || review.model || '服务来源未记录' }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="review.code" class="code-context">
            <div class="code-title"><strong>本次提交代码</strong><span v-if="review.codeHash">快照校验 {{ review.codeHash.slice(0, 12) }}</span></div>
            <pre><code>{{ review.code }}</code></pre>
          </div>
          <p v-else class="muted">原提交源码当前不可读取，复盘正文仍可回看。</p>
        </el-collapse-item>
      </el-collapse>

      <section v-if="review.parentReviewId" class="parent-link">
        此记录来自
        <el-link type="primary" @click="router.push({ name: 'AiReviewDetail', params: { id: review.parentReviewId }, query: route.query.from ? { from: route.query.from } : {} })">
          {{ aiReviewRelation(review.relationType) }} #{{ review.parentReviewId }}
        </el-link>
      </section>
    </main>
    <el-skeleton v-else-if="loading" :rows="7" animated class="loading-skeleton" />
    <el-empty v-else-if="!loadError" description="没有找到这条复盘记录" />

    <AiReviewConfirmDialog v-model:visible="confirmVisible" :detail="confirmSubmission" :relation-type="nextRelation"
      :initial-options="nextRelation === 'RETRY' ? retryOptions : undefined"
      :loading="creating" @confirm="createRelatedReview" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAiReviewDetail, getAiReviewStatus, createAiReview, cancelAiReview } from '@/api/aiReview'
import AiReviewConfirmDialog from './AiReviewConfirmDialog.vue'
import { createMarkdown } from '@/utils/markdown'
import { aiReviewListLocation, aiReviewRelation, aiReviewRequestKey, aiReviewStatus, isAiReviewActive } from '@/utils/aiReview'
import { showApiError } from '@/utils/errorFeedback'

const route = useRoute()
const router = useRouter()
const renderer = createMarkdown({ html: false, math: true, breaks: true })
const review = ref(null)
const loading = ref(false)
const creating = ref(false)
const canceling = ref(false)
const loadError = ref('')
const statusError = ref('')
const openFollowup = ref(false)
const followupQuestion = ref('')
const followupMode = ref('AUTO')
const confirmVisible = ref(false)
const nextRelation = ref('REANALYZE')
let generation = 0
let pollTimer
let pendingCreate = null

const active = computed(() => isAiReviewActive(review.value?.status))
const retryOptions = computed(() => ({
  helpMode: review.value?.helpMode,
  studentQuestion: review.value?.studentQuestion
}))
const confirmSubmission = computed(() => review.value ? {
  ...review.value,
  id: review.value.submissionId,
  status: review.value.submissionStatus
} : null)
const renderMarkdown = text => renderer.render(text || '')
const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
const resultText = status => ({ 1: '通过', 2: '答案错误', 3: '编译错误', 4: '运行错误', 5: '时间超限' }[Number(status)] || '—')

const loadDetail = async () => {
  const id = route.params.id
  const currentGeneration = ++generation
  clearTimeout(pollTimer)
  loading.value = true
  loadError.value = ''
  statusError.value = ''
  try {
    const response = await getAiReviewDetail(id)
    if (currentGeneration !== generation || id !== route.params.id) return
    if (response.code !== 200 || !response.data) throw new Error(response.message || '复盘记录读取失败')
    review.value = response.data
    if (isAiReviewActive(review.value.status)) schedulePoll()
  } catch (error) {
    if (currentGeneration !== generation) return
    loadError.value = error.message || '暂时无法读取复盘记录'
  } finally {
    if (currentGeneration === generation) loading.value = false
  }
}

const pollStatus = async () => {
  if (!review.value || !isAiReviewActive(review.value.status) || document.hidden) return
  const id = route.params.id
  const currentGeneration = generation
  try {
    const response = await getAiReviewStatus(id)
    if (currentGeneration !== generation || id !== route.params.id) return
    if (response.code !== 200) throw new Error(response.message || '状态读取失败')
    review.value = { ...review.value, ...response.data }
    statusError.value = ''
    if (isAiReviewActive(review.value.status)) schedulePoll()
    else loadDetail()
  } catch (error) {
    if (currentGeneration !== generation) return
    statusError.value = error.message || '状态读取失败'
    schedulePoll()
  }
}
const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (active.value && !document.hidden) pollTimer = setTimeout(pollStatus, 2500)
}
const onVisibilityChange = () => {
  if (document.hidden) clearTimeout(pollTimer)
  else if (active.value) pollStatus()
}
const backToRecords = () => {
  const submissionId = review.value?.submissionId || route.query.submissionId
  router.push(aiReviewListLocation(route.query.from, submissionId))
}
const openConfirm = relation => {
  nextRelation.value = relation
  confirmVisible.value = true
}

const createReview = async payload => {
  const signature = JSON.stringify(payload)
  if (!pendingCreate || pendingCreate.signature !== signature) {
    pendingCreate = { signature, requestId: aiReviewRequestKey(), payload }
  }
  creating.value = true
  try {
    const response = await createAiReview({ ...pendingCreate.payload, requestId: pendingCreate.requestId })
    if (response.code !== 200 || !response.data?.id) throw new Error(response.message || '复盘任务创建失败')
    const id = response.data.id
    pendingCreate = null
    confirmVisible.value = false
    followupQuestion.value = ''
    await router.push({ name: 'AiReviewDetail', params: { id }, query: route.query.from ? { from: route.query.from } : {} })
  } catch (error) {
    showApiError(error, error.message || '复盘任务创建失败')
    if (error?.response?.status === 409 || error?.response?.data?.code === 409) {
      ElMessage.info('该提交已有复盘任务，请先查看复盘记录。')
      router.push({ name: 'AiReviewRecords', query: { submissionId: String(review.value.submissionId) } })
    }
  } finally {
    creating.value = false
  }
}

const createRelatedReview = options => createReview({
  submissionId: review.value.submissionId,
  helpMode: options.helpMode,
  studentQuestion: options.studentQuestion || undefined,
  ...(nextRelation.value === 'RETRY' ? { parentReviewId: review.value.id } : {}),
  relationType: nextRelation.value
})
const createFollowup = () => {
  if (!followupQuestion.value.trim()) return
  createReview({
    submissionId: review.value.submissionId,
    helpMode: followupMode.value,
    studentQuestion: followupQuestion.value.trim(),
    parentReviewId: review.value.id,
    relationType: 'FOLLOW_UP'
  })
}

const cancelGeneration = async () => {
  try {
    await ElMessageBox.confirm('停止后会保留当前记录和已收到的部分内容。运行中的模型调用可能仍产生费用。', '停止复盘生成？', {
      confirmButtonText: '停止生成', cancelButtonText: '继续等待', type: 'warning'
    })
    canceling.value = true
    const response = await cancelAiReview(review.value.id)
    if (response.code !== 200) throw new Error(response.message || '停止请求失败')
    review.value = { ...review.value, ...response.data }
    ElMessage.success('已发送停止请求')
    if (isAiReviewActive(review.value.status)) schedulePoll()
    else loadDetail()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showApiError(error, error.message || '停止请求失败')
  } finally {
    canceling.value = false
  }
}

watch(() => route.params.id, () => {
  review.value = null
  loadDetail()
})
onMounted(() => {
  loadDetail()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  generation++
  clearTimeout(pollTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.review-detail-page { display: grid; gap: 18px; padding: 22px clamp(18px, 3vw, 42px) 36px; }
.review-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; padding-bottom: 8px; border-bottom: 1px solid var(--border-light); }
.review-header h1 { margin: 8px 0 6px; color: var(--text-primary); font-size: 25px; }
.review-header h1 span { color: var(--text-secondary); font-size: 18px; }
.review-header p { margin: 0; color: var(--text-secondary); }
.title-line, .header-actions, .report-toolbar, .followup-actions { display: flex; align-items: center; gap: 12px; }
.header-actions { justify-content: flex-end; flex-wrap: wrap; }
.review-layout { display: grid; gap: 16px; max-width: 1260px; width: 100%; margin: 0 auto; }
.state-card, .report-card, .context-card, .parent-link { border: 1px solid var(--border-light); border-radius: var(--radius-card); background: var(--bg-surface); }
.state-card { display: flex; align-items: center; gap: 18px; padding: 22px 26px; background: var(--accent-primary-light); }
.state-card h2 { margin: 0 0 5px; color: var(--text-primary); font-size: 18px; }
.state-card p { margin: 0; color: var(--text-secondary); }
.state-card small { display: block; margin-top: 8px; color: var(--text-secondary); }
.spinner { width: 32px; height: 32px; flex: 0 0 auto; border: 3px solid var(--el-color-primary-light-7); border-top-color: var(--el-color-primary); border-radius: 50%; animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.ended-card { background: var(--bg-surface); }
.state-mark { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 50%; background: var(--accent-warning-light); color: var(--accent-warning); font-weight: 700; }
.report-card { overflow: hidden; }
.report-toolbar { min-height: 58px; justify-content: space-between; padding: 0 22px; border-bottom: 1px solid var(--border-light); }
.report-toolbar h2 { display: inline; margin: 0 10px 0 0; color: var(--text-primary); font-size: 17px; }
.report-toolbar span { color: var(--text-secondary); font-size: 13px; }
.review-answer { padding: 24px clamp(18px, 3vw, 38px); color: var(--text-primary); line-height: 1.8; }
.partial-alert { margin: 0 20px 18px; width: auto; }
.followup-form { padding: 20px 22px; border-top: 1px solid var(--border-light); }
.followup-form label { display: block; margin-bottom: 8px; color: var(--text-regular); }
.followup-actions { justify-content: flex-end; margin-top: 10px; }
.context-card { padding: 0 18px; }
.code-context { margin-top: 18px; border: 1px solid var(--border-light); border-radius: var(--radius-control); overflow: hidden; }
.code-title { display: flex; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid var(--border-light); color: var(--text-primary); }
.code-title span, .muted { color: var(--text-secondary); }
.code-context pre { max-height: 480px; overflow: auto; margin: 0; padding: 16px; background: var(--bg-app); color: var(--text-primary); font-family: var(--font-mono); line-height: 1.55; white-space: pre; }
.parent-link { padding: 12px 18px; color: var(--text-secondary); }
.loading-skeleton { padding: 20px; }
.load-error { margin-bottom: 6px; }
@media (max-width: 760px) { .review-header { align-items: flex-start; flex-direction: column; } .header-actions { justify-content: flex-start; } .title-line { flex-wrap: wrap; } }
</style>
