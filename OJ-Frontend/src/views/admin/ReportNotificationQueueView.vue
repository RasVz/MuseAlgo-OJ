<template>
  <div class="report-notification-queue admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">报告通知队列</div>
        <div class="page-desc">站内学习周报提醒的投递状态与管理员重投（仅管理员）</div>
      </div>
      <el-button @click="fetchList">刷新</el-button>
    </div>

    <el-alert
      title="本页只管理站内提醒投递，不代表报告生成结果。报告生成失败请在学习周报中心查看；生成失败与提醒投递失败是两个独立事实。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-card shadow="never">
      <div class="filters">
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 160px">
          <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="filters.eventKind" placeholder="全部事件" clearable style="width: 160px">
          <el-option label="报告已生成提醒" value="REPORT_READY" />
          <el-option label="报告生成失败提醒" value="REPORT_FAILED" />
        </el-select>
        <el-input v-model.trim="filters.jobId" placeholder="报告任务 ID" clearable style="width: 150px" />
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </div>

      <el-table :data="items" v-loading="loading" size="small" border stripe>
        <el-table-column prop="intentId" label="意图 ID" width="90" align="center" />
        <el-table-column label="事件类型" width="150">
          <template #default="{ row }">
            <el-tag :type="eventMeta(row.eventKind).type" size="small">{{ eventMeta(row.eventKind).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="jobId" label="报告任务" width="100" align="center" />
        <el-table-column prop="recipientId" label="收件人" width="100" align="center" />
        <el-table-column label="代次 / 修订" width="120" align="center">
          <template #default="{ row }">G{{ row.generationNo }} / R{{ row.terminalRevision }}</template>
        </el-table-column>
        <el-table-column label="投递状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" size="small">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="尝试 / 预算" width="140" align="center">
          <template #default="{ row }">
            <div>{{ row.attempts }} / {{ row.maxAttempts }}</div>
            <div class="subtle">第 {{ row.redriveRound + 1 }} 轮</div>
          </template>
        </el-table-column>
        <el-table-column label="下次尝试" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.nextAttemptAt) }}</template>
        </el-table-column>
        <el-table-column label="投递错误码" width="170">
          <template #default="{ row }">
            <span v-if="row.lastErrorCode" class="code notice-code">{{ row.lastErrorCode }}</span>
            <span v-else class="subtle">-</span>
          </template>
        </el-table-column>
        <el-table-column label="生成错误码" width="170">
          <template #default="{ row }">
            <span v-if="row.sourceErrorCode" class="code generation-code">{{ row.sourceErrorCode }}</span>
            <span v-else class="subtle">-</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="重投管理员" width="110" align="center">
          <template #default="{ row }">{{ row.redriveAdminId ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="重投时间" width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.redrivenAt) }}</template>
        </el-table-column>
        <el-table-column label="重投原因" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.redriveReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED' && row.redriveRound === 0 && !row.notificationId"
              link
              type="primary"
              @click="openRedrive(row)"
            >
              重投一次
            </el-button>
            <span v-else-if="row.redriveRound > 0" class="subtle">已用重投</span>
            <span v-else class="subtle">-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="fetchList"
      />
    </el-card>

    <el-dialog v-model="redriveVisible" title="重投报告通知" width="620px">
      <template v-if="selected">
        <el-descriptions :column="2" border class="mb-16">
          <el-descriptions-item label="意图 ID">{{ selected.intentId }}</el-descriptions-item>
          <el-descriptions-item label="事件类型">{{ eventMeta(selected.eventKind).label }}</el-descriptions-item>
          <el-descriptions-item label="报告任务">{{ selected.jobId }}</el-descriptions-item>
          <el-descriptions-item label="收件人">{{ selected.recipientId }}</el-descriptions-item>
          <el-descriptions-item label="尝试 / 预算">{{ selected.attempts }} / {{ selected.maxAttempts }}</el-descriptions-item>
          <el-descriptions-item label="投递错误码">{{ selected.lastErrorCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="生成错误码" :span="2">{{ selected.sourceErrorCode || '无（报告已生成）' }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          title="重投只对当前终态修订执行一次，累计尝试次数不清零，总预算从 5 次扩展到最多 10 次；偏好关闭、权限撤销或已过期的通知会被后端拒绝。"
          type="warning"
          :closable="false"
          show-icon
          class="mb-16"
        />

        <el-form label-width="90px">
          <el-form-item label="重投原因" required>
            <el-input
              v-model="redriveForm.reason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="必填 10-200 字，写入持久化重投审计；不要填写学生姓名或报告正文"
            />
            <div class="reason-hint" :class="{ invalid: !reasonValid }">
              当前 {{ reasonLength }} 字，需 10-200 字
            </div>
          </el-form-item>
        </el-form>

        <el-alert v-if="redriveError" :title="redriveError" type="error" :closable="false" show-icon />
      </template>
      <template #footer>
        <el-button @click="redriveVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="redriveLoading"
          :disabled="!reasonValid"
          @click="submitRedrive"
        >
          确认重投
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listReportNotificationIntents,
  redriveReportNotificationIntent
} from '@/api/reportNotification'
import { getResultMessage, unwrapResultData } from '@/utils/response'

const STATUS_META = {
  PENDING: { label: '待投递', type: 'warning' },
  DISPATCHING: { label: '投递中', type: 'primary' },
  DELIVERED: { label: '已投递', type: 'success' },
  SKIPPED_PREFERENCE: { label: '偏好关闭', type: 'info' },
  SKIPPED_PERMISSION: { label: '权限撤销', type: 'info' },
  SKIPPED_SUPERSEDED: { label: '已取代', type: 'info' },
  FAILED: { label: '投递失败', type: 'danger' }
}

const EVENT_META = {
  REPORT_READY: { label: '报告已生成', type: 'success' },
  REPORT_FAILED: { label: '报告生成失败', type: 'warning' }
}

const statusOptions = Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))

const items = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filters = reactive({ status: '', eventKind: '', jobId: '' })

const redriveVisible = ref(false)
const redriveLoading = ref(false)
const redriveError = ref('')
const selected = ref(null)
const redriveForm = reactive({ reason: '' })

const reasonLength = computed(() => [...redriveForm.reason.trim()].length)
const reasonValid = computed(() => reasonLength.value >= 10 && reasonLength.value <= 200)

const statusMeta = (status) => STATUS_META[status] ?? { label: status || '-', type: 'info' }
const eventMeta = (eventKind) => EVENT_META[eventKind] ?? { label: eventKind || '-', type: 'info' }

const formatTime = (value) => {
  if (!value) return '-'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString('zh-CN', { hour12: false })
}

const fetchList = async () => {
  loading.value = true
  try {
    const res = await listReportNotificationIntents({
      status: filters.status || undefined,
      eventKind: filters.eventKind || undefined,
      jobId: filters.jobId || undefined,
      page: page.value,
      size: size.value
    })
    const data = unwrapResultData(res)
    items.value = data?.items ?? []
    total.value = data?.total ?? 0
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  page.value = 1
  fetchList()
}

const handleReset = () => {
  filters.status = ''
  filters.eventKind = ''
  filters.jobId = ''
  page.value = 1
  fetchList()
}

const openRedrive = (row) => {
  selected.value = row
  redriveForm.reason = ''
  redriveError.value = ''
  redriveVisible.value = true
}

const submitRedrive = async () => {
  if (!selected.value || !reasonValid.value) return
  redriveLoading.value = true
  redriveError.value = ''
  try {
    await redriveReportNotificationIntent(selected.value.intentId, redriveForm.reason.trim())
    ElMessage.success('已重新排队，将在下一轮投递中处理')
    redriveVisible.value = false
    await fetchList()
  } catch (error) {
    redriveError.value = getResultMessage(error?.response?.data, error?.message || '重投失败')
  } finally {
    redriveLoading.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped>
.report-notification-queue { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.mb-16 { margin-bottom: 16px; }
.filters { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.pager { margin-top: 12px; justify-content: flex-end; }
.subtle { color: var(--text-secondary); font-size: 12px; }
.code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; }
.notice-code { color: var(--el-color-danger); }
.generation-code { color: var(--el-color-warning-dark-2); }
.reason-hint { margin-top: 4px; font-size: 12px; color: var(--text-secondary); }
.reason-hint.invalid { color: var(--el-color-warning-dark-2); }
</style>
