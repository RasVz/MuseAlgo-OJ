<template>
  <div class="report-center admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">
          <el-icon class="title-icon"><Notebook /></el-icon>
          学习周报
        </div>
        <div class="page-desc">
          报告由后台按计划预生成，也支持按需受理；受理后在此轮询状态、查看历史与失败重试（仅本班班主任或管理员）
        </div>
      </div>
      <el-button :loading="classLoading" @click="refreshAll">刷新</el-button>
    </div>

    <el-alert v-if="classError" type="error" :title="classError" :closable="false" show-icon class="mb-16">
      <template #default>
        <el-button link type="primary" @click="loadClassOptions">重新加载班级</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="!classLoading && classes.length === 0"
      type="warning"
      title="当前账号没有可用班级"
      description="只有本班班主任或管理员可以生成 / 查看学习周报；若刚创建班级，请刷新后重试。"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-card shadow="never" class="mb-16">
      <template #header>
        <div class="card-header">
          <span>生成报告</span>
          <span class="card-sub">默认统计上周（周一～周日），可自定义起止日期</span>
        </div>
      </template>

      <div class="generate-form">
        <div class="form-item">
          <span class="form-label">班级</span>
          <el-select
            v-model="selectedClassId"
            placeholder="选择班级"
            filterable
            clearable
            :loading="classLoading"
            :disabled="generating || classLoading"
            style="width: 220px"
            @change="handleClassChange"
          >
            <el-option
              v-for="item in classes"
              :key="item.id"
              :label="`${item.name}（#${item.id}）`"
              :value="item.id"
            />
          </el-select>
        </div>

        <div class="form-item">
          <span class="form-label">学生</span>
          <el-select
            v-model="selectedStudentId"
            placeholder="选择学生"
            filterable
            :loading="membersLoading"
            :disabled="!selectedClassId || generating || membersLoading"
            no-data-text="该班级暂无学生"
            style="width: 220px"
          >
            <el-option
              v-for="member in members"
              :key="member.userId"
              :label="memberLabel(member)"
              :value="member.userId"
            />
          </el-select>
          <span v-if="selectedClassId && !membersLoading" class="field-hint">共 {{ members.length }} 名学生</span>
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
            :disabled="generating"
            style="width: 260px"
          />
          <el-button link type="primary" :disabled="generating" @click="resetPeriod">恢复上周</el-button>
        </div>

        <div class="form-item">
          <el-button type="primary" :loading="generating" :disabled="!canGenerate" @click="handleGenerate">
            生成学习周报
          </el-button>
        </div>
      </div>

      <div class="form-hint">
        提交只表示任务已受理，报告由后台 worker 生成；HTML 只保存在报告任务中，本页不生成公开链接。
        打印与下载均在前端本地完成。
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>历史报告</span>
          <div class="card-header-right">
            <span v-if="selectedClassId" class="card-sub">{{ className(selectedClassId) }}</span>
            <el-button link type="primary" :disabled="!selectedClassId" @click="loadHistory">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="historyError" type="error" :title="historyError" :closable="false" show-icon class="mb-12">
        <template #default>
          <el-button link type="primary" @click="loadHistory">重试</el-button>
        </template>
      </el-alert>

      <el-table v-loading="historyLoading" :data="historyItems" stripe class="history-table">
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMeta(row.status).type" size="small">{{ statusMeta(row.status).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="学生" min-width="150">
          <template #default="{ row }">
            <div class="student-cell">
              <span>{{ row.studentName || studentLabel(row.studentId) }}</span>
              <span class="student-id">#{{ row.studentId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="周期" min-width="190">
          <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column label="尝试次数" width="110" align="center">
          <template #default="{ row }">{{ row.attempts ?? 0 }} / {{ MAX_ATTEMPTS }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div v-if="row.status === 'FAILED'" class="failure-cell">
              <span v-if="failureCode(row)" class="failure-code">{{ failureCode(row) }}</span>
              <span>{{ failureMessage(row) }}</span>
            </div>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail(row.id)">详情</el-button>
            <el-button
              v-if="row.status === 'FAILED' && canRetry(row)"
              type="warning"
              link
              :loading="retryingId === row.id"
              @click="handleRetry(row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="emptyDescription" :image-size="80" />
        </template>
      </el-table>

      <div v-if="historyTotal > 0" class="pager">
        <el-pagination
          v-model:current-page="historyPage"
          v-model:page-size="historySize"
          :total="historyTotal"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadHistory"
          @size-change="handleHistorySizeChange"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="detailVisible"
      title="学习周报详情"
      width="90%"
      top="3vh"
      :close-on-click-modal="false"
      destroy-on-close
      @close="cancelDetailPolling"
      @closed="handleDetailClosed"
    >
      <div v-loading="detailLoading" class="detail-body">
        <el-result
          v-if="!detailLoading && detailForbidden"
          icon="warning"
          title="无权查看该报告"
          :sub-title="detailError"
        />

        <el-result
          v-else-if="!detailLoading && detailError"
          icon="error"
          title="报告详情加载失败"
          :sub-title="detailError"
        >
          <template #extra>
            <el-button type="primary" @click="reloadDetail">重新加载</el-button>
          </template>
        </el-result>

        <template v-else-if="detail">
          <div class="detail-summary">
            <div>
              <div class="detail-name">{{ studentLabel(detail.studentId) }} · 学习周报</div>
              <div class="detail-meta">
                {{ detail.studentName || `学生 #${detail.studentId}` }} · 班级 {{ className(detail.classId) || `#${detail.classId}` }} ·
                周期 {{ detail.periodStart }} ~ {{ detail.periodEnd }}
              </div>
              <div class="detail-meta">
                创建于 {{ formatTime(detail.createTime) }} · 尝试 {{ detail.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次
                <template v-if="detail.nextAttemptAt && isInProgress(detail)">
                  · 下次尝试 {{ formatTime(detail.nextAttemptAt) }}
                </template>
              </div>
            </div>
            <el-tag :type="statusMeta(detail.status).type">{{ statusMeta(detail.status).label }}</el-tag>
          </div>

          <el-alert
            v-if="detail.status === 'FAILED'"
            type="error"
            :title="failureMessage(detail)"
            :closable="false"
            show-icon
            class="mb-12"
          >
            <template #default>
              <div class="failure-detail">
                <span v-if="failureCode(detail)">错误码：{{ failureCode(detail) }}</span>
                <span>已消耗 {{ detail.attempts ?? 0 }} / {{ MAX_ATTEMPTS }} 次生成预算。</span>
                <span v-if="!canRetry(detail)">该错误类型或剩余预算不允许再次重试，请按错误码处理。</span>
              </div>
            </template>
          </el-alert>

          <el-alert
            v-else-if="isInProgress(detail)"
            type="info"
            :title="detail.status === 'PENDING' ? '报告已受理，正在排队' : '报告正在后台生成'"
            :closable="false"
            show-icon
            class="mb-12"
          >
            <template #default>
              <div class="progress-line">
                <span>
                  {{ pollActive ? '页面每 2 秒自动刷新当前任务状态。' : (pollPausedMessage || '等待自动刷新。') }}
                </span>
                <el-button v-if="pollPausedMessage" link type="primary" @click="resumeDetailPolling">
                  继续刷新
                </el-button>
              </div>
              <div>关闭弹窗、切到后台或离开页面只会停止刷新，不会取消已受理任务。</div>
            </template>
          </el-alert>

          <div v-if="hasHtml" class="report-frame-wrapper">
            <iframe
              ref="reportIframeRef"
              :srcdoc="reportDocument(detail.html)"
              class="report-iframe"
              title="学习周报预览"
              sandbox="allow-scripts allow-modals"
            />
          </div>
          <el-empty
            v-else-if="!isInProgress(detail) && detail.status !== 'FAILED'"
            description="报告内容未返回，请重新加载"
            :image-size="72"
          />

          <template v-if="detail.deliveries?.length">
            <el-divider content-position="left">报告交付记录（报告域）</el-divider>
            <el-table :data="detail.deliveries" size="small" class="delivery-table">
              <el-table-column label="通道" width="100">
                <template #default="{ row }">{{ CHANNEL_LABEL[row.channel] || row.channel }}</template>
              </el-table-column>
              <el-table-column prop="recipientId" label="接收人 ID" width="110" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="deliveryMeta(row.status).type" size="small">{{ deliveryMeta(row.status).label }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="attempts" label="尝试次数" width="90" align="center" />
              <el-table-column label="送达时间" width="170">
                <template #default="{ row }">{{ formatTime(row.deliveredAt) }}</template>
              </el-table-column>
              <el-table-column label="错误" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
              </el-table-column>
            </el-table>
            <div class="form-hint">
              交付记录只表示报告中心可用（IN_APP）或外部通道 dry-run，不代表站内提醒已写入或已读。
            </div>
          </template>

          <el-divider content-position="left">提醒与已读（独立事实）</el-divider>
          <div class="notification-head">
            <el-tag :type="detail.status === 'GENERATED' ? 'success' : 'info'" size="small">
              报告中心{{ detail.status === 'GENERATED' ? '可用' : '不可用' }}
            </el-tag>
            <span class="card-sub">报告中心可用性只由报告任务 status=GENERATED 决定</span>
            <el-button link type="primary" :loading="notificationLoading" @click="loadNotificationStatus(detail.id)">
              刷新提醒状态
            </el-button>
          </div>
          <el-alert v-if="notificationError" type="warning" :title="notificationError" :closable="false" show-icon class="mb-12" />
          <template v-else>
            <el-table v-if="notificationIntents.length" :data="notificationIntents" size="small" class="notification-table">
              <el-table-column label="提醒事件" width="140">
                <template #default="{ row }">{{ eventKindLabel(row.eventKind) }}</template>
              </el-table-column>
              <el-table-column label="提醒状态" width="170">
                <template #default="{ row }">
                  <el-tag :type="notificationStatusMeta(row.status).type" size="small">
                    {{ notificationStatusMeta(row.status).label }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="投递尝试" width="100" align="center">
                <template #default="{ row }">{{ row.attempts ?? 0 }} / {{ row.maxAttempts ?? '-' }}</template>
              </el-table-column>
              <el-table-column label="下次尝试" width="170">
                <template #default="{ row }">{{ formatTime(row.nextAttemptAt) }}</template>
              </el-table-column>
              <el-table-column label="通知域错误码" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.lastErrorCode || '-' }}</template>
              </el-table-column>
              <el-table-column label="已读（通知域）" width="170">
                <template #default="{ row }">
                  <template v-if="row.notificationId">
                    <el-tag :type="row.isRead ? 'success' : 'info'" size="small">
                      {{ row.isRead ? '已读' : '未读' }}
                    </el-tag>
                    <div v-if="row.readAt" class="read-time">{{ formatTime(row.readAt) }}</div>
                  </template>
                  <span v-else class="field-hint">无站内通知</span>
                </template>
              </el-table-column>
            </el-table>
            <div v-else class="form-hint">{{ notificationEmptyText }}</div>
            <div class="form-hint">
              提醒投递失败不会把报告改成 FAILED，也不代表报告生成失败；用户已读是通知收件人的独立事实，
              报告任务不会写入已读状态。
            </div>
          </template>

          <template v-if="isAdmin">
            <el-divider content-position="left">外部通道 dry-run（仅管理员）</el-divider>
            <el-alert
              type="info"
              title="dry-run 只记录交付演练，不会真实发送邮件 / 短信 / 微信。"
              :closable="false"
              show-icon
              class="mb-12"
            />
            <div class="dry-run-form">
              <el-select v-model="dryRunChannel" :disabled="dryRunning" style="width: 150px">
                <el-option
                  v-for="item in EXTERNAL_CHANNELS"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-input
                v-model.trim="dryRunRecipientId"
                :disabled="dryRunning"
                placeholder="接收人用户 ID"
                style="width: 180px"
              />
              <el-button type="warning" plain :loading="dryRunning" :disabled="!canDryRun" @click="handleDryRun">
                执行 dry-run
              </el-button>
            </div>
            <div class="form-hint">仅 GENERATED 状态可演练；接收人 ID 为平台用户 ID，不是邮箱或手机号。</div>
          </template>
        </template>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="detailVisible = false">关闭</el-button>
          <el-button
            v-if="detail?.status === 'FAILED' && canRetry(detail)"
            type="warning"
            :loading="retryingId === detail.id"
            @click="handleRetry(detail)"
          >
            重试生成
          </el-button>
          <el-button v-if="hasHtml" :loading="exporting" @click="handleExportPng">导出 PNG</el-button>
          <el-button v-if="hasHtml" @click="handleDownloadHtml">另存为 HTML</el-button>
          <el-button v-if="hasHtml" type="primary" @click="handlePrint">打印</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 教师 / 管理员的「学习周报」报告中心（T9-3）。
//
// 后端为 enqueue-only：POST 只受理/复用任务并立即返回 PENDING，报告由后台 worker 生成。
// 本页只在详情弹窗内轮询当前 job（2 秒，递归 setTimeout，天然不叠加相同 GET），
// 列表行不加载 HTML；GENERATED 后详情响应才带 html 并渲染 iframe。
// 关闭弹窗、路由离开、页面隐藏都会停止轮询；返回页面时重新 GET 最新服务端状态。
// 提醒投递与已读是通知域的独立事实，通过 /notification-status 单独查询并单独展示。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { Notebook } from '@element-plus/icons-vue'
import { getClassMembers, getMyClasses, getTeacherClassList } from '@/api/class'
import { getAdminClasses } from '@/api/adminClass'
import {
  dryRunReportDelivery,
  getReportJob,
  getReportNotificationStatus,
  listReportJobs,
  requestReportJob,
  retryReportJob
} from '@/api/report'
import { unwrapResultData } from '@/utils/response'
import { useUserStore } from '@/stores/userStore'
import { useReportExport } from '@/views/admin/class/composables/useReportExport'
import { reportDocument } from '@/views/admin/class/composables/reportIsolation'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.role >= 3)
// PNG 导出复用班级成员页的 composable（隐藏 iframe + html2canvas 截图）
const { exporting, exportImage } = useReportExport()

const STATUS_META = {
  PENDING: { label: '排队中', type: 'info' },
  GENERATING: { label: '生成中', type: 'warning' },
  GENERATED: { label: '已生成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}
const DELIVERY_STATUS_META = {
  PENDING: { label: '待交付', type: 'info' },
  DELIVERED: { label: '已送达', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  DRY_RUN: { label: 'dry-run', type: 'warning' }
}
const NOTIFICATION_STATUS_META = {
  PENDING: { label: '等待投递', type: 'info' },
  DISPATCHING: { label: '投递中', type: 'warning' },
  DELIVERED: { label: '已写入站内通知', type: 'success' },
  SKIPPED_PREFERENCE: { label: '偏好关闭，已跳过', type: 'info' },
  SKIPPED_PERMISSION: { label: '权限失效，已跳过', type: 'warning' },
  SKIPPED_SUPERSEDED: { label: '已被新版本取代', type: 'info' },
  FAILED: { label: '提醒投递失败', type: 'danger' }
}
const EVENT_KIND_LABEL = { REPORT_READY: '报告完成提醒', REPORT_FAILED: '报告失败提醒' }
const CHANNEL_LABEL = { IN_APP: '站内', EMAIL: '邮件', SMS: '短信', WECHAT: '微信' }
const EXTERNAL_CHANNELS = [
  { value: 'EMAIL', label: '邮件 EMAIL' },
  { value: 'SMS', label: '短信 SMS' },
  { value: 'WECHAT', label: '微信 WECHAT' }
]
// 与后端报告生成预算一致；仅用于展示 attempts/max 和服务端未返回 canRetry 时的文案。
// 重试入口只认服务端 canRetry，不在前端自行推算预算。
const MAX_ATTEMPTS = 3
const POLL_INTERVAL_MS = 2000

// --- 班级 / 学生 / 生成表单 ---
const classes = ref([])
const classLoading = ref(false)
const classError = ref('')
const selectedClassId = ref(null)
const members = ref([])
const membersLoading = ref(false)
const selectedStudentId = ref(null)
const periodRange = ref(lastWeekRange())
const generating = ref(false)

// --- 历史列表 ---
const historyItems = ref([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = ref(10)
const historyLoading = ref(false)
const historyError = ref('')
const retryingId = ref(null)
let historyRequestSerial = 0

// --- 详情弹窗 ---
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailForbidden = ref(false)
const detail = ref(null)
const lastDetailJobId = ref(null)
const reportIframeRef = ref(null)
const dryRunChannel = ref('EMAIL')
const dryRunRecipientId = ref('')
const dryRunning = ref(false)

// --- 当前 job 轮询（只服务详情弹窗，列表行不创建定时器）---
let pollTimer = null
let pollGeneration = 0
let detailDisposed = false
const detailControllers = new Map()
// 用集合记录在途 GET，保证同一 job 的轮询不会重叠（即使快速切换过详情）。
const pollInFlightJobs = new Set()
// 详情 GET 共用同一个在途 promise：轮询、重新加载、打开详情都不会并发请求同一 job。
const detailRequests = new Map()
const pollActive = ref(false)
const pollPausedMessage = ref('')

// --- 通知域事实（与报告状态分开）---
const notificationStatus = ref(null)
const notificationLoading = ref(false)
const notificationError = ref('')
let notificationRequestId = 0

const memberNameMap = computed(() => {
  const map = new Map()
  for (const member of members.value) map.set(Number(member.userId), memberLabel(member))
  return map
})

const canGenerate = computed(() => {
  const [start, end] = periodRange.value || []
  return Boolean(
    selectedClassId.value &&
      selectedStudentId.value &&
      start &&
      end &&
      !generating.value &&
      !membersLoading.value &&
      start <= end
  )
})

const hasHtml = computed(() => detail.value?.status === 'GENERATED' && Boolean(detail.value?.html))

const notificationIntents = computed(() =>
  Array.isArray(notificationStatus.value?.intents) ? notificationStatus.value.intents : []
)

const notificationEmptyText = computed(() => {
  if (detail.value?.status === 'GENERATED') {
    return '当前修订没有提醒记录；历史任务可能未记录提醒状态，不自动补发。'
  }
  if (isInProgress(detail.value)) {
    return '报告完成后才会产生提醒意图；进行中的任务暂无提醒事实。'
  }
  return '未查询到当前修订的提醒记录；报告失败事实以上方生成错误为准。'
})

const canDryRun = computed(() => {
  if (!isAdmin.value || detail.value?.status !== 'GENERATED' || dryRunning.value) return false
  return /^\d+$/.test(dryRunRecipientId.value) && Number(dryRunRecipientId.value) > 0
})

const emptyDescription = computed(() => {
  if (!selectedClassId.value) return '请先选择班级'
  return historyError.value ? '历史报告加载失败，请重试' : '该班级暂无学习周报，可在上方生成'
})

// 与后端 resolvePeriod 的默认值一致：上周一 00:00 ～ 上周日 23:59:59（按日期展示）
function lastWeekRange() {
  const today = dayjs().startOf('day')
  const daysSinceMonday = (today.day() + 6) % 7
  const thisMonday = today.subtract(daysSinceMonday, 'day')
  const lastMonday = thisMonday.subtract(7, 'day')
  return [lastMonday.format('YYYY-MM-DD'), lastMonday.add(6, 'day').format('YYYY-MM-DD')]
}

function resetPeriod() {
  periodRange.value = lastWeekRange()
}

function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

function errorMessage(error, fallback) {
  return error?.response?.data?.message || error?.response?.data?.msg || error?.message || fallback
}

function isForbidden(error) {
  return Number(error?.response?.status) === 403 || Number(error?.response?.data?.code) === 403
}

function isInProgress(job) {
  return job?.status === 'PENDING' || job?.status === 'GENERATING'
}

function statusMeta(status) {
  return STATUS_META[status] || { label: status || '未知', type: 'info' }
}

function deliveryMeta(status) {
  return DELIVERY_STATUS_META[status] || { label: status || '未知', type: 'info' }
}

function notificationStatusMeta(status) {
  return NOTIFICATION_STATUS_META[status] || { label: status || '未知', type: 'info' }
}

function eventKindLabel(eventKind) {
  return EVENT_KIND_LABEL[eventKind] || eventKind || '-'
}

function failureCode(job) {
  return job?.generationFailure?.code || job?.lastErrorCode || ''
}

function failureMessage(job) {
  return job?.generationFailure?.safeMessage || job?.errorMessage || '报告生成失败'
}

function memberLabel(member) {
  const remark = typeof member?.remark === 'string' ? member.remark.trim() : ''
  return remark || member?.username || `学生 #${member?.userId}`
}

function studentLabel(studentId) {
  return memberNameMap.value.get(Number(studentId)) || `学生 #${studentId}`
}

function className(classId) {
  const matched = classes.value.find((item) => Number(item.id) === Number(classId))
  return matched?.name || ''
}

// 是否展示重试入口：只看服务端预算与错误策略，前端 attempts 不参与判定。
function canRetry(job) {
  return job?.status === 'FAILED' && (job?.canRetry === true || job?.generationFailure?.canRetry === true)
}

/**
 * 加载班级下拉：
 * 1) 按任务约定复用 getMyClasses（加入的班级）；
 * 2) 教师账号通常不在 class_student 表里，而报告权限按「班主任」判定，
 *    因此补拉自己创建的班级，否则种子数据下教师下拉为空、功能不可用；
 * 3) 非管理员只保留自己担任班主任的班级（与后端 requireClassTeacher 同口径），
 *    避免选中「加入但非班主任」的班级后所有请求都 403；
 * 4) 管理员可跨班级，本人没有任何班级时回退到全部班级。
 */
async function loadClassOptions() {
  classLoading.value = true
  classError.value = ''
  try {
    const optionMap = new Map()
    const joinedData = unwrapResultData(await getMyClasses({ page: 1, size: 200 }))
    for (const item of joinedData?.records || []) {
      if (item?.id != null) optionMap.set(Number(item.id), item)
    }

    if (userStore.isTeacher) {
      const owned = unwrapResultData(await getTeacherClassList(userStore.userId)) || []
      for (const item of owned) {
        if (item?.id != null) optionMap.set(Number(item.id), item)
      }
    }

    let options = [...optionMap.values()]
    if (!isAdmin.value) {
      options = options.filter((item) => Number(item.teacherId) === Number(userStore.userId))
    } else if (options.length === 0) {
      const adminData = unwrapResultData(await getAdminClasses({ page: 1, size: 200 }))
      options = adminData?.records || []
    }

    options.sort((a, b) => Number(a.id) - Number(b.id))
    classes.value = options
    if (selectedClassId.value && !options.some((item) => Number(item.id) === Number(selectedClassId.value))) {
      historyRequestSerial += 1
      selectedClassId.value = null
      selectedStudentId.value = null
      members.value = []
      historyItems.value = []
      historyTotal.value = 0
      historyLoading.value = false
      historyError.value = ''
    }
  } catch (e) {
    classes.value = []
    classError.value = errorMessage(e, '班级列表加载失败')
  } finally {
    classLoading.value = false
  }
}

async function fetchAllMembers(classId) {
  const size = 100
  const list = []
  let page = 1
  let total = 0
  do {
    const data = unwrapResultData(await getClassMembers({ classId, page, size })) || {}
    const records = Array.isArray(data.records) ? data.records : []
    list.push(...records)
    total = Number(data.total) || list.length
    if (records.length === 0 || page >= 50) break
    page += 1
  } while (list.length < total)
  return list
}

async function loadMembers() {
  const classId = Number(selectedClassId.value)
  if (!classId) {
    members.value = []
    return
  }
  membersLoading.value = true
  try {
    const list = await fetchAllMembers(classId)
    if (Number(selectedClassId.value) === classId) {
      members.value = list
      // 切班后旧学生已不在新班级：清空选择，避免带着上一个人的 ID 生成报告
      if (selectedStudentId.value && !list.some((item) => Number(item.userId) === Number(selectedStudentId.value))) {
        selectedStudentId.value = null
      }
    }
  } catch (e) {
    if (Number(selectedClassId.value) === classId) {
      members.value = []
      showApiError(e, errorMessage(e, '班级成员加载失败'))
    }
  } finally {
    if (Number(selectedClassId.value) === classId) membersLoading.value = false
  }
}

async function loadHistory() {
  const requestSerial = ++historyRequestSerial
  if (!selectedClassId.value) {
    historyItems.value = []
    historyTotal.value = 0
    historyLoading.value = false
    historyError.value = ''
    return
  }
  const classId = Number(selectedClassId.value)
  const isCurrent = () => requestSerial === historyRequestSerial && Number(selectedClassId.value) === classId
  historyLoading.value = true
  historyError.value = ''
  try {
    const data =
      unwrapResultData(
        await listReportJobs({ classId, page: historyPage.value, size: historySize.value })
      ) || {}
    if (!isCurrent()) return
    historyItems.value = Array.isArray(data.records) ? data.records : []
    historyTotal.value = Number(data.total) || 0
  } catch (e) {
    if (isCurrent()) {
      historyItems.value = []
      historyTotal.value = 0
      historyError.value = errorMessage(e, '历史报告加载失败')
    }
  } finally {
    if (isCurrent()) historyLoading.value = false
  }
}

async function handleClassChange() {
  historyRequestSerial += 1
  selectedStudentId.value = null
  members.value = []
  historyItems.value = []
  historyTotal.value = 0
  historyPage.value = 1
  historyError.value = ''
  historyLoading.value = false
  if (!selectedClassId.value) return
  await Promise.all([loadMembers(), loadHistory()])
}

function handleHistorySizeChange() {
  historyPage.value = 1
  loadHistory()
}

// ---------------------------------------------------------------------------
// 详情轮询：单一定时器 + 世代号 + 同 job GET 去重。
// ---------------------------------------------------------------------------
function cancelDetailPolling() {
  pollGeneration += 1
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  for (const controller of detailControllers.values()) controller.abort()
  detailControllers.clear()
  detailRequests.clear()
  pollActive.value = false
}

function scheduleDetailPoll(jobId, generation) {
  if (detailDisposed || !detailVisible.value || generation !== pollGeneration) return
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = setTimeout(() => {
    pollTimer = null
    pollDetail(jobId, generation)
  }, POLL_INTERVAL_MS)
  pollActive.value = true
}

function fetchJobDetail(jobId) {
  const id = Number(jobId)
  const existing = detailRequests.get(id)
  if (existing) return existing
  const controller = new AbortController()
  detailControllers.set(id, controller)
  const tracked = getReportJob(id, { silent: true, signal: controller.signal }).finally(() => {
    if (detailControllers.get(id) === controller) detailControllers.delete(id)
    if (detailRequests.get(id) === tracked) detailRequests.delete(id)
  })
  detailRequests.set(id, tracked)
  return tracked
}

function beginDetailPolling(jobId, { immediate = false } = {}) {
  const id = Number(jobId)
  if (detailDisposed || !id || !detailVisible.value || lastDetailJobId.value !== id) return
  if (!isInProgress(detail.value)) return
  if (document.hidden) {
    pollPausedMessage.value = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'
    return
  }
  cancelDetailPolling()
  pollPausedMessage.value = ''
  pollActive.value = true
  const generation = pollGeneration
  if (immediate) pollDetail(id, generation)
  else scheduleDetailPoll(id, generation)
}

async function pollDetail(jobId, generation) {
  if (detailDisposed || generation !== pollGeneration) return
  if (!detailVisible.value || lastDetailJobId.value !== jobId) return
  if (document.hidden) {
    pollActive.value = false
    pollPausedMessage.value = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'
    return
  }
  if (pollInFlightJobs.has(jobId)) {
    // 同一 job 的 GET 已在途：不叠加，等它结束后再排下一轮。
    scheduleDetailPoll(jobId, generation)
    return
  }

  pollInFlightJobs.add(jobId)
  try {
    const job = unwrapResultData(await fetchJobDetail(jobId))
    if (detailDisposed || !detailVisible.value || generation !== pollGeneration || lastDetailJobId.value !== jobId) return
    if (!job) {
      cancelDetailPolling()
      detail.value = null
      detailError.value = '报告任务不存在'
      return
    }
    applyDetail(job)
    if (isInProgress(job)) {
      if (document.hidden) {
        pollActive.value = false
        pollPausedMessage.value = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'
        return
      }
      scheduleDetailPoll(jobId, generation)
      return
    }
    cancelDetailPolling()
    handleTerminalDetail(job)
  } catch (e) {
    if (detailDisposed || !detailVisible.value || generation !== pollGeneration || lastDetailJobId.value !== jobId) return
    if (isForbidden(e)) {
      handleDetailForbidden()
      return
    }
    cancelDetailPolling()
    pollPausedMessage.value = `自动刷新已暂停：${errorMessage(e, '网络异常')}`
  } finally {
    pollInFlightJobs.delete(jobId)
  }
}

function resumeDetailPolling() {
  const id = lastDetailJobId.value
  if (!id || !detailVisible.value) return
  if (document.hidden) {
    pollPausedMessage.value = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'
    return
  }
  cancelDetailPolling()
  pollPausedMessage.value = ''
  pollActive.value = true
  pollDetail(id, pollGeneration)
}

function handleVisibilityChange() {
  if (document.hidden) {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    if (pollActive.value || pollInFlightJobs.size > 0) {
      pollPausedMessage.value = '页面已隐藏，自动刷新已暂停；返回页面后会立即获取最新状态。'
    }
    pollActive.value = false
    return
  }
  if (!detailVisible.value || !lastDetailJobId.value || !isInProgress(detail.value)) return
  resumeDetailPolling()
}

function applyDetail(job) {
  detail.value = job
  patchHistoryRow(job)
}

function patchHistoryRow(job) {
  const index = historyItems.value.findIndex((row) => Number(row.id) === Number(job.id))
  if (index < 0) return
  // 列表行只同步报告域摘要，绝不把详情 html 写进列表数据。
  historyItems.value[index] = {
    ...historyItems.value[index],
    status: job.status,
    attempts: job.attempts,
    errorMessage: job.errorMessage,
    lastErrorCode: job.lastErrorCode,
    nextAttemptAt: job.nextAttemptAt,
    canRetry: job.canRetry,
    generationFailure: job.generationFailure,
    finishedAt: job.finishedAt
  }
}

function handleTerminalDetail(job) {
  if (job.status === 'GENERATED' && !job.html) {
    detailError.value = '报告已生成但正文未返回，请重新加载'
  }
  // 终态后刷新一次历史列表，让列表状态与详情一致；列表本身不轮询。
  if (selectedClassId.value && Number(selectedClassId.value) === Number(job.classId)) {
    loadHistory()
  }
  loadNotificationStatus(job.id)
}

function handleDetailForbidden() {
  cancelDetailPolling()
  detail.value = null
  detailLoading.value = false
  detailForbidden.value = true
  detailError.value = '当前账号无权查看该报告内容，已停止自动刷新。'
  notificationRequestId += 1
  notificationStatus.value = null
  notificationError.value = ''
  notificationLoading.value = false
}

async function loadNotificationStatus(jobId) {
  const id = Number(jobId)
  if (!id) return
  const requestId = ++notificationRequestId
  notificationLoading.value = true
  notificationError.value = ''
  try {
    const data = unwrapResultData(await getReportNotificationStatus(id))
    if (lastDetailJobId.value !== id || requestId !== notificationRequestId) return
    notificationStatus.value = data || null
  } catch (e) {
    if (lastDetailJobId.value !== id || requestId !== notificationRequestId) return
    notificationStatus.value = null
    notificationError.value = isForbidden(e)
      ? '当前账号无权查看该报告的通知状态。'
      : errorMessage(e, '提醒状态加载失败')
  } finally {
    if (lastDetailJobId.value === id && requestId === notificationRequestId) {
      notificationLoading.value = false
    }
  }
}

async function handleGenerate() {
  if (!canGenerate.value) return
  generating.value = true
  try {
    const job = unwrapResultData(
      await requestReportJob({
        studentId: Number(selectedStudentId.value),
        classId: Number(selectedClassId.value),
        periodStart: periodRange.value?.[0],
        periodEnd: periodRange.value?.[1]
      })
    )
    if (!job?.id) {
      ElMessage.error('报告任务未返回，请稍后重试')
      return
    }
    historyPage.value = 1
    await loadHistory()
    if (job.status === 'GENERATED') {
      ElMessage.success('学习周报已生成')
    } else if (isInProgress(job)) {
      // PENDING / GENERATING 都是正常进行态，不是失败。
      ElMessage.success('报告已受理，正在后台生成；可关闭弹窗稍后查看')
    } else if (job.status === 'FAILED') {
      ElMessage.error(failureMessage(job))
    }
    await openDetail(job.id)
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    generating.value = false
  }
}

async function handleRetry(row) {
  if (!row?.id || retryingId.value) return
  retryingId.value = row.id
  try {
    const job = unwrapResultData(await retryReportJob(row.id))
    if (!job?.id) return
    if (isInProgress(job)) {
      ElMessage.success('已重新排队，报告将由后台生成')
    } else if (job.status === 'GENERATED') {
      ElMessage.success('报告已生成')
    } else if (job.status === 'FAILED') {
      ElMessage.error(failureMessage(job))
    }
    if (detailVisible.value && Number(detail.value?.id) === Number(job.id)) {
      applyDetail(job)
      loadNotificationStatus(job.id)
      if (isInProgress(job)) beginDetailPolling(job.id)
    } else {
      await openDetail(job.id)
    }
    await loadHistory()
  } catch {
    /* 全局请求拦截器已统一提示错误（409 预算耗尽/不可重试）；刷新列表以同步服务端 canRetry */
    await loadHistory()
  } finally {
    retryingId.value = null
  }
}

async function openDetail(jobId) {
  const id = Number(jobId)
  if (!Number.isInteger(id) || id <= 0) return
  cancelDetailPolling()
  lastDetailJobId.value = id
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = ''
  detailForbidden.value = false
  detail.value = null
  notificationStatus.value = null
  notificationError.value = ''
  notificationLoading.value = false
  pollPausedMessage.value = ''
  dryRunChannel.value = 'EMAIL'
  dryRunRecipientId.value = ''
  try {
    const job = unwrapResultData(await fetchJobDetail(id))
    if (detailDisposed || !detailVisible.value || lastDetailJobId.value !== id) return
    if (!job) {
      detailError.value = '报告任务不存在'
      return
    }
    detail.value = job
    pollPausedMessage.value = ''
    if (job.status === 'GENERATED' && !job.html) {
      detailError.value = '报告已生成但正文未返回，请重新加载'
      return
    }
    // 提醒状态是独立端点；失败只影响该区块，不影响报告详情。
    loadNotificationStatus(id)
    // 打开历史中已预生成的任务只走 GET，不重新 POST；进行态才启动轮询。
    beginDetailPolling(id)
  } catch (e) {
    if (detailDisposed || !detailVisible.value || lastDetailJobId.value !== id) return
    if (isForbidden(e)) {
      handleDetailForbidden()
      return
    }
    detailError.value = errorMessage(e, '报告详情加载失败')
  } finally {
    if (lastDetailJobId.value === id) detailLoading.value = false
  }
}

function reloadDetail() {
  if (lastDetailJobId.value) openDetail(lastDetailJobId.value)
}

function handleDetailClosed() {
  cancelDetailPolling()
  pollPausedMessage.value = ''
  detail.value = null
  detailError.value = ''
  detailForbidden.value = false
  notificationStatus.value = null
  notificationError.value = ''
  notificationLoading.value = false
  dryRunRecipientId.value = ''
  dryRunning.value = false
  lastDetailJobId.value = null
  // 从通知中心带 ?jobId= 打开时，关闭弹窗后清掉 query，避免刷新又弹一次
  if (route.query.jobId !== undefined) {
    const query = { ...route.query }
    delete query.jobId
    router.replace({ path: route.path, query })
  }
}

function parseQueryJobId() {
  const raw = Array.isArray(route.query.jobId) ? route.query.jobId[0] : route.query.jobId
  const jobId = Number(raw)
  return Number.isInteger(jobId) && jobId > 0 ? jobId : null
}

async function selectFirstClass() {
  if (selectedClassId.value || classes.value.length === 0) return
  selectedClassId.value = classes.value[0].id
  await Promise.all([loadMembers(), loadHistory()])
}

// 从通知中心带 ?jobId= 进入时，详情加载完再把对应班级选中，让历史列表与详情保持同一上下文
async function focusJobClass() {
  const job = detail.value
  if (!job?.classId) return
  const matched = classes.value.find((item) => Number(item.id) === Number(job.classId))
  if (!matched) return
  selectedClassId.value = matched.id
  await Promise.all([loadMembers(), loadHistory()])
}

async function refreshAll() {
  await loadClassOptions()
  if (selectedClassId.value) {
    await Promise.all([loadMembers(), loadHistory()])
    return
  }
  await selectFirstClass()
}

function handlePrint() {
  const printWindow = reportIframeRef.value?.contentWindow
  if (!printWindow) {
    ElMessage.warning('报告尚未加载完成，请稍后再试')
    return
  }
  try {
    printWindow.postMessage({ kind: 'muse-report-request', action: 'print' }, '*')
  } catch (caughtError) {
    showApiError(caughtError, '打印失败，请改用「另存为 HTML」')
  }
}

function safeFileSegment(value) {
  return String(value || '学生').replace(/[\\/:*?"<>|\s]+/g, '_').slice(0, 40) || '学生'
}

function handleDownloadHtml() {
  if (!hasHtml.value) return
  const fileName = `${safeFileSegment(studentLabel(detail.value.studentId))}_学习周报_${detail.value.periodStart}_${detail.value.periodEnd}.html`
  const blob = new Blob([detail.value.html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  ElMessage.success('HTML 已开始下载')
}

function handleExportPng() {
  if (!hasHtml.value) return
  exportImage(detail.value.html, studentLabel(detail.value.studentId))
}

async function handleDryRun() {
  if (!canDryRun.value || !detail.value) return
  dryRunning.value = true
  try {
    const job = unwrapResultData(
      await dryRunReportDelivery(detail.value.id, {
        channel: dryRunChannel.value,
        recipientId: Number(dryRunRecipientId.value)
      })
    )
    if (job) {
      applyDetail(job)
      loadNotificationStatus(job.id)
    }
    ElMessage.success('dry-run 完成：已记录交付演练，未真实发送')
    await loadHistory()
  } catch {
    /* 全局请求拦截器已统一提示错误 */
  } finally {
    dryRunning.value = false
  }
}

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  await loadClassOptions()
  const queryJobId = parseQueryJobId()
  if (queryJobId) {
    await openDetail(queryJobId)
    await focusJobClass()
    return
  }
  await selectFirstClass()
})

onBeforeUnmount(() => {
  historyRequestSerial += 1
  detailDisposed = true
  cancelDetailPolling()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeRouteLeave(() => {
  cancelDetailPolling()
  return true
})
</script>

<style scoped>
.report-center { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.page-title { display: flex; align-items: center; gap: 6px; font-size: 22px; font-weight: 700; color: var(--text-primary); }
.title-icon { color: #6366f1; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.mb-16 { margin-bottom: 16px; }
.mb-12 { margin-bottom: 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-weight: 600; color: var(--text-primary); }
.card-header-right { display: flex; align-items: center; gap: 10px; }
.card-sub { color: var(--text-secondary); font-size: 12px; font-weight: 400; }
.generate-form { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; }
.form-item { display: flex; align-items: center; gap: 8px; }
.form-label { color: var(--text-regular); font-size: 13px; white-space: nowrap; }
.field-hint { color: var(--text-secondary); font-size: 12px; }
.form-hint { margin-top: 12px; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.history-table { width: 100%; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.student-cell { display: flex; align-items: baseline; gap: 6px; }
.student-id { color: var(--text-secondary); font-size: 12px; }
.failure-cell { display: flex; flex-direction: column; gap: 2px; }
.failure-code { color: var(--danger-base, #f56c6c); font-family: 'Menlo', monospace; font-size: 12px; }
.failure-detail { display: flex; flex-wrap: wrap; gap: 6px 16px; }
.progress-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.detail-body { min-height: 120px; max-height: 64vh; overflow-y: auto; padding-right: 4px; box-sizing: border-box; }
.detail-summary { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 14px; }
.detail-name { font-size: 17px; font-weight: 600; color: var(--text-primary); }
.detail-meta { margin-top: 5px; color: var(--text-secondary); font-size: 12px; }
.report-frame-wrapper { width: 100%; height: 58vh; margin-top: 12px; border: 1px solid var(--border-base); border-radius: 6px; overflow: hidden; }
.report-iframe { width: 100%; height: 100%; border: none; background: #fff; }
.delivery-table { width: 100%; margin-top: 8px; }
.notification-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.notification-table { width: 100%; }
.read-time { margin-top: 2px; color: var(--text-secondary); font-size: 11px; }
.dry-run-form { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.dialog-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
