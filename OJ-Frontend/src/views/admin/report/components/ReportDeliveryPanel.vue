<template>
  <div class="delivery-panel">
    <el-empty v-if="!jobId" description="在「历史报告」中选择一份报告，或点击行内的「交付与提醒」" :image-size="90">
      <el-button type="primary" plain @click="emit('switch-tab', 'history')">前往历史报告</el-button>
    </el-empty>

    <template v-else>
      <div class="panel-head">
        <div>
          <div class="panel-title">报告 #{{ jobId }} 的交付与提醒</div>
          <div class="panel-sub">
            交付记录属于报告域，提醒与已读属于通知域，二者是独立事实，不合并为同一状态
          </div>
        </div>
        <div class="panel-actions">
          <el-button v-if="job?.status === 'GENERATED'" type="primary" plain @click="emit('open-report', job.id)">打开报告</el-button>
          <el-button :loading="loading || notificationLoading" @click="reloadAll">刷新</el-button>
        </div>
      </div>

      <el-alert v-if="forbidden" type="warning" title="无权查看该报告" :description="error" :closable="false" show-icon class="mb-12" />
      <el-alert v-else-if="error" type="error" title="报告详情加载失败" :description="error" :closable="false" show-icon class="mb-12">
        <template #default>
          <el-button link type="primary" @click="reloadAll">重新加载</el-button>
        </template>
      </el-alert>

      <el-card v-if="job" shadow="never" class="panel-card">
        <template #header>
          <div class="card-head">
            <span>报告交付记录（报告域）</span>
            <el-tag :type="metaOf(STATUS_META, job.status).type" size="small">
              {{ metaOf(STATUS_META, job.status).label }}
            </el-tag>
          </div>
        </template>
        <el-table v-if="deliveries.length" :data="deliveries" size="small" class="domain-table">
          <el-table-column label="通道" width="100">
            <template #default="{ row }">{{ CHANNEL_LABEL[row.channel] || row.channel }}</template>
          </el-table-column>
          <el-table-column prop="recipientId" label="接收人 ID" width="120" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="metaOf(DELIVERY_STATUS_META, row.status).type" size="small">
                {{ metaOf(DELIVERY_STATUS_META, row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="attempts" label="尝试次数" width="100" align="center" />
          <el-table-column label="送达时间" width="180">
            <template #default="{ row }">{{ formatTime(row.deliveredAt) }}</template>
          </el-table-column>
          <el-table-column label="错误" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无交付记录" :image-size="64" />
        <div class="panel-note">
          交付记录只表示报告中心可用（IN_APP）或外部通道 dry-run，不代表站内提醒已写入或已读。
        </div>
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="card-head">
            <span>提醒与已读（通知域）</span>
            <el-button link type="primary" :loading="notificationLoading" @click="loadNotificationStatus(jobId)">
              刷新提醒状态
            </el-button>
          </div>
        </template>
        <div class="availability">
          <el-tag :type="job?.status === 'GENERATED' ? 'success' : 'info'" size="small">
            报告中心{{ job?.status === 'GENERATED' ? '可用' : '不可用' }}
          </el-tag>
          <span class="panel-note-inline">报告中心可用性只由报告任务 status=GENERATED 决定</span>
        </div>
        <el-alert v-if="notificationError" type="warning" :title="notificationError" :closable="false" show-icon class="mb-12" />
        <template v-else>
          <el-table v-if="intents.length" :data="intents" size="small" class="domain-table">
            <el-table-column label="提醒事件" width="140">
              <template #default="{ row }">{{ EVENT_KIND_LABEL[row.eventKind] || row.eventKind || '-' }}</template>
            </el-table-column>
            <el-table-column label="提醒状态" width="170">
              <template #default="{ row }">
                <el-tag :type="metaOf(NOTIFICATION_STATUS_META, row.status).type" size="small">
                  {{ metaOf(NOTIFICATION_STATUS_META, row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="投递尝试" width="110" align="center">
              <template #default="{ row }">{{ row.attempts ?? 0 }} / {{ row.maxAttempts ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="下次尝试" width="180">
              <template #default="{ row }">{{ formatTime(row.nextAttemptAt) }}</template>
            </el-table-column>
            <el-table-column label="错误码" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.lastErrorCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="已读" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.notificationId" :type="row.isRead ? 'success' : 'info'" size="small">
                  {{ row.isRead ? '已读' : '未读' }}
                </el-tag>
                <span v-else class="panel-note-inline">无站内通知</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="panel-note">{{ notificationEmptyText }}</div>
        </template>
        <div class="panel-note">
          提醒投递失败不会把报告改成 FAILED，也不代表报告生成失败；用户已读是通知收件人的独立事实。
        </div>
      </el-card>

      <el-card v-if="isAdmin" shadow="never" class="panel-card">
        <template #header>
          <div class="card-head"><span>外部通道 dry-run（仅管理员）</span></div>
        </template>
        <el-alert
          type="info"
          title="dry-run 只记录交付演练，不会真实发送邮件 / 短信 / 微信。"
          :closable="false"
          show-icon
          class="mb-12"
        />
        <div class="dry-run-form">
          <el-select v-model="dryRunChannel" :disabled="dryRunning" style="width: 160px">
            <el-option v-for="item in EXTERNAL_CHANNELS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-input v-model.trim="dryRunRecipientId" :disabled="dryRunning" placeholder="接收人用户 ID" style="width: 200px" />
          <el-button type="warning" plain :loading="dryRunning" :disabled="!canDryRun" @click="handleDryRun">执行 dry-run</el-button>
        </div>
        <div class="panel-note">仅 GENERATED 状态可演练；接收人 ID 为平台用户 ID，不是邮箱或手机号。</div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { dryRunReportDelivery, getReportNotificationStatus } from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useUserStore } from '@/stores/userStore'
import { useReportJob } from '../composables/useReportJob'
import {
  CHANNEL_LABEL, DELIVERY_STATUS_META, EVENT_KIND_LABEL, EXTERNAL_CHANNELS,
  NOTIFICATION_STATUS_META, STATUS_META, isInProgress, metaOf
} from '../composables/reportMeta'

const props = defineProps({
  jobId: { type: [String, Number], default: null }
})
const emit = defineEmits(['open-report', 'switch-tab'])

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role >= 3)

const {
  job, loading, error, forbidden, reload, apply, load
} = useReportJob({ onTerminal: () => loadNotificationStatus(props.jobId) })

const notificationStatus = ref(null)
const notificationLoading = ref(false)
const notificationError = ref('')
let notificationRequestId = 0

const dryRunChannel = ref('EMAIL')
const dryRunRecipientId = ref('')
const dryRunning = ref(false)

const deliveries = computed(() => Array.isArray(job.value?.deliveries) ? job.value.deliveries : [])
const intents = computed(() => {
  const list = notificationStatus.value?.intents
  return Array.isArray(list) ? list : []
})
const notificationEmptyText = computed(() => {
  if (!props.jobId) return '选择报告后查询提醒状态。'
  if (job.value?.status === 'GENERATED') return '当前修订没有提醒记录；历史任务可能未记录提醒状态，不自动补发。'
  if (isInProgress(job.value?.status)) return '报告完成后才会产生提醒意图；进行中的任务暂无提醒事实。'
  return '未查询到当前修订的提醒记录；报告失败事实以生成错误为准。'
})
const canDryRun = computed(() => {
  if (!isAdmin.value || job.value?.status !== 'GENERATED' || dryRunning.value) return false
  return /^\d+$/.test(dryRunRecipientId.value) && Number(dryRunRecipientId.value) > 0
})

function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

async function loadNotificationStatus(id) {
  const jobId = Number(id)
  if (!Number.isInteger(jobId) || jobId <= 0) return
  const requestId = ++notificationRequestId
  notificationLoading.value = true
  notificationError.value = ''
  try {
    const data = unwrapResultData(await getReportNotificationStatus(jobId))
    if (requestId === notificationRequestId) notificationStatus.value = data || null
  } catch (e) {
    if (requestId === notificationRequestId) {
      notificationStatus.value = null
      notificationError.value = Number(e?.response?.status) === 403
        ? '当前账号无权查看该报告的通知状态。'
        : e?.response?.data?.message || e?.message || '提醒状态加载失败'
    }
  } finally {
    if (requestId === notificationRequestId) notificationLoading.value = false
  }
}

function reloadAll() {
  reload()
  loadNotificationStatus(props.jobId)
}

async function handleDryRun() {
  if (!canDryRun.value || !job.value) return
  dryRunning.value = true
  try {
    const updated = unwrapResultData(await dryRunReportDelivery(job.value.id, {
      channel: dryRunChannel.value,
      recipientId: Number(dryRunRecipientId.value)
    }))
    if (updated) apply(updated)
    loadNotificationStatus(job.value.id)
    ElMessage.success('dry-run 完成：已记录交付演练，未真实发送')
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    dryRunning.value = false
  }
}

watch(() => props.jobId, (id) => {
  notificationRequestId += 1
  notificationStatus.value = null
  notificationError.value = ''
  dryRunRecipientId.value = ''
  if (id) {
    load(id)
    loadNotificationStatus(id)
  }
}, { immediate: true })
</script>

<style scoped>
.delivery-panel { padding-top: 4px; }
.panel-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
.panel-title { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.panel-sub { margin-top: 4px; font-size: 12px; color: var(--text-secondary); }
.panel-actions { display: flex; gap: 8px; }
.panel-card { border: 1px solid var(--border-light); margin-bottom: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-weight: 600; color: var(--text-primary); }
.domain-table { width: 100%; }
.panel-note { margin-top: 10px; font-size: 12px; color: var(--text-secondary); line-height: 1.6; }
.panel-note-inline { font-size: 12px; color: var(--text-secondary); }
.mb-12 { margin-bottom: 12px; }
.availability { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.dry-run-form { display: flex; flex-wrap: wrap; gap: 10px; }
</style>
