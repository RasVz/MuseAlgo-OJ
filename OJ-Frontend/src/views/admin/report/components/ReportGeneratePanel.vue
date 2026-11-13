<template>
  <div class="generate-panel">
    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-head">
          <span>受理新的周报任务</span>
          <span class="card-sub">默认统计上周（周一～周日），可一次选择多名学生批量受理</span>
        </div>
      </template>

      <div class="generate-form">
        <div class="form-item">
          <span class="form-label">学生</span>
          <ReportStudentPicker
            v-model="selectedStudentIds"
            :members="members"
            :loading="membersLoading"
            :disabled="formDisabled"
          />
          <span v-if="classId && !membersLoading" class="field-hint">
            已选 {{ selectedStudentIds.length }} / 共 {{ members.length }} 名学生
          </span>
        </div>
        <div class="form-item">
          <span class="form-label">统计周期</span>
          <el-date-picker
            v-model="periodRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            unlink-panels
            :disabled="formDisabled"
            style="width: 270px"
          />
          <el-button link type="primary" :disabled="formDisabled" @click="resetPeriod">恢复上周</el-button>
        </div>
        <el-button type="primary" :loading="generating" :disabled="!canGenerate" @click="handleGenerate">
          生成学习周报
        </el-button>
      </div>
      <div class="form-hint">
        提交只表示任务已受理，报告由后台 worker 生成；同一学生 + 班级 + 周期的已存在任务会被复用，不会重复渲染。
        单次最多受理 {{ BATCH_LIMIT }} 份，超出会自动分批提交；受理后无需停留本页，关闭或切换标签页只会停止状态刷新。
      </div>
    </el-card>

    <el-card v-if="job && batchItems.length === 0" shadow="never" class="panel-card task-card">
      <template #header>
        <div class="card-head">
          <span>当前受理任务</span>
          <el-tag :type="metaOf(STATUS_META, job.status).type" size="small">
            {{ metaOf(STATUS_META, job.status).label }}
          </el-tag>
        </div>
      </template>

      <div class="task-summary">
        <div>
          <div class="task-title">{{ job.studentName || `学生 #${job.studentId}` }} · 学习周报</div>
          <div class="task-meta">
            周期 {{ job.periodStart }} ~ {{ job.periodEnd }} · 尝试 {{ job.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次
            <template v-if="job.nextAttemptAt && inProgress"> · 下次尝试 {{ formatTime(job.nextAttemptAt) }}</template>
          </div>
        </div>
        <div class="task-actions">
          <el-button v-if="job.status === 'GENERATED'" type="primary" @click="emit('open-report', job.id)">
            打开报告
          </el-button>
          <el-button v-if="job.status === 'FAILED' && canRetry(job)" type="warning" :loading="retrying" @click="handleRetry">
            重试生成
          </el-button>
          <el-button :loading="loading" @click="reload">刷新状态</el-button>
        </div>
      </div>

      <el-alert v-if="inProgress" type="info" :closable="false" show-icon class="mt-12">
        <template #title>{{ job.status === 'PENDING' ? '报告已受理，正在排队' : '报告正在后台生成' }}</template>
        <template #default>
          <div class="progress-line">
            <span>{{ pollActive ? '页面每 2 秒自动刷新当前任务状态。' : (pollPausedMessage || '等待自动刷新。') }}</span>
            <el-button v-if="pollPausedMessage" link type="primary" @click="resume">继续刷新</el-button>
          </div>
        </template>
      </el-alert>
      <el-alert v-else-if="job.status === 'FAILED'" type="error" :title="failureMessage(job)" :closable="false" show-icon class="mt-12">
        <template #default>
          <div class="failure-detail">
            <span v-if="failureCode(job)">错误码：{{ failureCode(job) }}</span>
            <span>已消耗 {{ job.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次生成预算。</span>
            <span v-if="!canRetry(job)">该错误类型或剩余预算不允许再次重试。</span>
          </div>
        </template>
      </el-alert>
      <el-alert v-else-if="job.status === 'GENERATED'" type="success" title="报告已生成，可在阅读页查看完整正文或导出" :closable="false" show-icon class="mt-12" />
    </el-card>

    <el-card v-if="batchItems.length > 0" shadow="never" class="panel-card batch-task-card">
      <template #header>
        <div class="card-head">
          <span>本批受理任务（{{ batchSummary.total }}）</span>
          <div class="batch-tags">
            <el-tag v-if="batchSummary.queued" type="info" size="small">排队/生成中 {{ batchSummary.queued }}</el-tag>
            <el-tag v-if="batchSummary.generated" type="success" size="small">已生成 {{ batchSummary.generated }}</el-tag>
            <el-tag v-if="batchSummary.failed" type="danger" size="small">失败 {{ batchSummary.failed }}</el-tag>
            <el-tag v-if="batchSummary.rejected" type="danger" size="small">未受理 {{ batchSummary.rejected }}</el-tag>
          </div>
        </div>
      </template>

      <div class="batch-period">周期 {{ batchPeriod.start }} ~ {{ batchPeriod.end }}</div>

      <div class="batch-list">
        <div v-for="item in batchItems" :key="item.studentId" class="batch-item">
          <div class="batch-row">
            <div class="batch-row-main">
              <span class="batch-name">{{ item.name }}</span>
              <span class="batch-id">#{{ item.studentId }}</span>
              <el-tag :type="batchStatusMeta(item).type" size="small">{{ batchStatusMeta(item).label }}</el-tag>
              <span v-if="item.jobId" class="batch-meta">尝试 {{ item.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次</span>
            </div>
            <div class="batch-row-actions">
              <el-button v-if="item.status === 'GENERATED'" link type="primary" @click="emit('open-report', item.jobId)">
                打开报告
              </el-button>
              <el-button
                v-else-if="item.status === 'FAILED' && item.canRetry"
                link
                type="warning"
                :loading="item.retrying"
                @click="handleBatchRetry(item)"
              >
                重试
              </el-button>
              <span v-else-if="isInProgress(item.status)" class="batch-meta">等待后台生成</span>
              <span v-else class="batch-meta">—</span>
            </div>
          </div>
          <div v-if="batchErrorText(item)" class="batch-error">{{ batchErrorText(item) }}</div>
        </div>
      </div>

      <el-alert v-if="batchPausedMessage" type="info" :closable="false" show-icon class="mt-12">
        <template #default>
          <div class="progress-line">
            <span>{{ batchPausedMessage }}</span>
            <el-button v-if="batchSummary.queued" link type="primary" @click="resumeBatch">继续刷新</el-button>
          </div>
        </template>
      </el-alert>

      <div class="batch-footer">
        <span class="form-hint">
          列表只跟踪本次提交；关闭页面或清空列表不会取消已受理任务，完整状态可在「历史报告」查看。
        </span>
        <div class="task-actions">
          <el-button :loading="batchRefreshing" @click="refreshBatch">刷新状态</el-button>
          <el-button link @click="clearBatch">清空列表</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="card-head">
          <span>最近受理记录</span>
          <el-button link type="primary" @click="emit('switch-tab', 'history')">历史报告</el-button>
        </div>
      </template>
      <div v-if="recent.length" class="recent-list">
        <div v-for="row in recent" :key="row.id" class="recent-row">
          <el-tag :type="metaOf(STATUS_META, row.status).type" size="small">{{ metaOf(STATUS_META, row.status).label }}</el-tag>
          <span class="recent-name">{{ row.studentName || `学生 #${row.studentId}` }}</span>
          <span class="recent-period">{{ row.periodStart }} ~ {{ row.periodEnd }}</span>
          <el-button v-if="row.status === 'GENERATED'" link type="primary" @click="emit('open-report', row.id)">打开报告</el-button>
          <span v-else class="recent-placeholder">—</span>
        </div>
      </div>
      <el-empty v-else description="暂无受理记录" :image-size="72" />
    </el-card>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { requestReportJob, retryReportJob } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useReportJob } from '../composables/useReportJob'
import { useReportBatch } from '../composables/useReportBatch'
import { BATCH_LIMIT } from '../composables/reportBatch'
import ReportStudentPicker from './ReportStudentPicker.vue'
import {
  MAX_ATTEMPTS, STATUS_META, canRetry, failureCode, failureMessage, isInProgress, metaOf
} from '../composables/reportMeta'

const props = defineProps({
  classId: { type: [String, Number], default: null },
  members: { type: Array, default: () => [] },
  membersLoading: { type: Boolean, default: false },
  history: { type: Array, default: () => [] }
})
const emit = defineEmits(['generated', 'open-report', 'switch-tab'])

// 批量状态与单人卡片共用一套状态文案；REJECTED 只出现在批量结果里。
const BATCH_STATUS_META = {
  ...STATUS_META,
  REJECTED: { label: '未受理', type: 'danger' }
}

const selectedStudentIds = ref([])
const periodRange = ref(lastWeekRange())
const generating = ref(false)
const retrying = ref(false)
// 本批学生的姓名/周期用于展示；受理结果只按 studentId 关联。
const batchPeriod = ref({ start: '', end: '' })

const {
  job, loading, pollActive, pollPausedMessage, inProgress,
  reload, adopt, apply, resume, stop
} = useReportJob({ onTerminal: () => emit('generated', job?.value || null) })

const {
  items: batchItems,
  submitting: batchSubmitting,
  refreshing: batchRefreshing,
  pausedMessage: batchPausedMessage,
  summary: batchSummary,
  submit: submitBatch,
  refresh: refreshBatch,
  retry: retryBatchItem,
  clear: clearBatch,
  resume: resumeBatch
} = useReportBatch()

const formDisabled = computed(
  () => !props.classId || props.membersLoading || generating.value || batchSubmitting.value
)

const selectedStudents = computed(() => {
  const nameById = new Map(props.members.map((member) => [Number(member.userId), memberLabel(member)]))
  return selectedStudentIds.value.map((id) => ({
    studentId: Number(id),
    name: nameById.get(Number(id)) || `学生 #${id}`
  }))
})

const canGenerate = computed(() => {
  const [start, end] = periodRange.value || []
  return Boolean(
    props.classId && selectedStudentIds.value.length > 0 && start && end &&
    !generating.value && !batchSubmitting.value && !props.membersLoading && start <= end
  )
})
const recent = computed(() => props.history.slice(0, 5))

function lastWeekRange() {
  const today = dayjs().startOf('day')
  const thisMonday = today.subtract((today.day() + 6) % 7, 'day')
  const lastMonday = thisMonday.subtract(7, 'day')
  return [lastMonday.format('YYYY-MM-DD'), lastMonday.add(6, 'day').format('YYYY-MM-DD')]
}

function resetPeriod() {
  periodRange.value = lastWeekRange()
}

function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

function memberLabel(member) {
  const remark = typeof member?.remark === 'string' ? member.remark.trim() : ''
  return remark || member?.username || `学生 #${member?.userId}`
}

function batchStatusMeta(item) {
  if (!item) return BATCH_STATUS_META.REJECTED
  if (item.rejected) return BATCH_STATUS_META.REJECTED
  if (!item.status) return { label: '受理中', type: 'info' }
  return metaOf(BATCH_STATUS_META, item.status)
}

function batchErrorText(item) {
  if (!item) return ''
  if (item.status === 'FAILED') {
    const code = failureCode(item)
    const message = item.failure?.safeMessage ||
      (item.errorMessage && item.errorMessage !== code ? item.errorMessage : '')
    return [code ? `错误码：${code}` : '', message].filter(Boolean).join(' · ') || '报告生成失败'
  }
  if (item.rejected) return item.errorMessage || '未受理'
  return ''
}

async function handleBatchRetry(item) {
  try {
    await retryBatchItem(item)
    if (item.status === 'GENERATED') ElMessage.success('报告已生成')
    else if (isInProgress(item.status)) ElMessage.success('已重新排队，报告将由后台生成')
    else if (item.status === 'FAILED') ElMessage.error(failureMessage(item))
  } catch {
    /* 全局请求拦截器已统一提示错误；服务端 canRetry 状态已同步 */
  }
}

async function handleGenerate() {
  if (!canGenerate.value) return
  if (selectedStudents.value.length === 0) {
    ElMessage.warning('请先选择学生')
    return
  }
  generating.value = true
  try {
    const [start, end] = periodRange.value
    if (selectedStudents.value.length === 1) {
      clearBatch()
      const created = unwrapResultData(await requestReportJob({
        studentId: Number(selectedStudents.value[0].studentId),
        classId: Number(props.classId),
        periodStart: start,
        periodEnd: end
      }))
      if (!created?.id) {
        ElMessage.error('报告任务未返回，请稍后重试')
        return
      }
      adopt(created)
      emit('generated', created)
      if (created.status === 'GENERATED') ElMessage.success('学习周报已生成')
      else if (isInProgress(created.status)) ElMessage.success('报告已受理，正在后台生成')
      else if (created.status === 'FAILED') ElMessage.error(failureMessage(created))
      return
    }

    stop()
    batchPeriod.value = { start, end }
    const result = await submitBatch({
      classId: Number(props.classId),
      students: selectedStudents.value,
      periodStart: start,
      periodEnd: end
    })
    if (result.total === 0) {
      ElMessage.warning('所选学生无有效 ID，未提交')
      return
    }
    const problems = result.failed + result.rejected
    if (result.accepted > 0) {
      const parts = [`已受理 ${result.accepted} 份`]
      if (result.queued > 0) parts.push(`${result.queued} 份排队/生成中`)
      if (result.generated > 0) parts.push(`${result.generated} 份已生成`)
      if (problems > 0) parts.push(`${problems} 份未受理或生成失败`)
      ElMessage.success(parts.join('，'))
    } else {
      ElMessage.error(`本次 ${result.total} 份报告均未受理或已失败`)
    }
    emit('generated', null)
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    generating.value = false
  }
}

async function handleRetry() {
  if (!job.value?.id || retrying.value) return
  retrying.value = true
  try {
    const updated = unwrapResultData(await retryReportJob(job.value.id))
    if (updated) {
      apply(updated)
      emit('generated', updated)
      if (isInProgress(updated.status)) ElMessage.success('已重新排队，报告将由后台生成')
      else if (updated.status === 'GENERATED') ElMessage.success('报告已生成')
    }
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    retrying.value = false
  }
}

// 换班后旧选择与旧批次不再成立：清空选择并停止批量轮询，避免把 A 班状态带到 B 班。
watch(
  () => props.classId,
  () => {
    selectedStudentIds.value = []
    clearBatch()
  }
)

// 成员重载后剔除已不在本班的选择，防止提交无效学生。
watch(
  () => props.members,
  (list) => {
    if (selectedStudentIds.value.length === 0) return
    const ids = new Set((list || []).map((member) => Number(member.userId)))
    selectedStudentIds.value = selectedStudentIds.value.filter((id) => ids.has(Number(id)))
  }
)

// 整批离开进行态后刷新一次历史，避免「最近受理记录」停留在排队中而批量卡片已生成。
watch(
  () => batchSummary.value.queued,
  (now, before) => {
    if (before > 0 && now === 0 && batchItems.value.length > 0 && !batchSubmitting.value) {
      emit('generated', null)
    }
  }
)
</script>

<style scoped>
.generate-panel { display: flex; flex-direction: column; gap: 16px; padding-top: 4px; }
.panel-card { border: 1px solid var(--border-light); }
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-weight: 600; color: var(--text-primary); }
.card-sub { font-size: 12px; font-weight: 400; color: var(--text-secondary); }
.generate-form { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; }
.form-item { display: flex; align-items: center; gap: 8px; }
.form-label { font-size: 13px; color: var(--text-regular); white-space: nowrap; }
.field-hint { font-size: 12px; color: var(--text-secondary); }
.form-hint { margin-top: 12px; font-size: 12px; color: var(--text-secondary); line-height: 1.6; }
.task-summary { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.task-title { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.task-meta { margin-top: 6px; font-size: 12px; color: var(--text-secondary); }
.task-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.mt-12 { margin-top: 12px; }
.progress-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.failure-detail { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 13px; }
.recent-list { display: flex; flex-direction: column; }
.recent-row { display: flex; align-items: center; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border-light); font-size: 13px; }
.recent-row:last-child { border-bottom: none; }
.recent-name { color: var(--text-primary); min-width: 90px; }
.recent-period { color: var(--text-secondary); font-size: 12px; flex: 1; }
.recent-placeholder { color: var(--text-secondary); min-width: 62px; text-align: center; }

.batch-task-card { border: 1px solid var(--border-light); }
.batch-tags { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.batch-period { font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; }
.batch-list { display: flex; flex-direction: column; }
.batch-item { padding: 8px 0; border-bottom: 1px solid var(--border-light); }
.batch-item:last-child { border-bottom: none; }
.batch-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.batch-row-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-width: 0; }
.batch-row-actions { display: flex; align-items: center; gap: 6px; }
.batch-name { font-size: 13px; font-weight: 500; color: var(--text-primary); }
.batch-id { font-size: 12px; color: var(--text-secondary); }
.batch-meta { font-size: 12px; color: var(--text-secondary); }
.batch-error { margin-top: 4px; font-size: 12px; color: var(--danger-base, #f56c6c); line-height: 1.5; }
.batch-footer { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-top: 8px; flex-wrap: wrap; }
.batch-footer .form-hint { margin-top: 0; flex: 1; min-width: 220px; }
</style>
