<template>
  <div class="admin-contest-registrations admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">报名审核</div>
        <div class="page-desc">
          {{ pageDesc }}
        </div>
      </div>
      <div class="page-actions">
        <el-button @click="goBack">返回比赛管理</el-button>
        <el-button :loading="loading" @click="fetchList">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="contestDetail && Number(contestDetail.registrationReview ?? 0) !== 1"
      title="本场比赛未开启报名审核：报名会自动通过，仍可在此查看报名记录。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-alert
      v-if="errorText"
      :title="errorText"
      type="warning"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="registrations"
        border
        stripe
        height="100%"
        empty-text="暂无报名记录"
      >
        <el-table-column label="用户" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="user-meta">
                <div class="username">{{ getUserDisplayName(row) }}</div>
                <div class="userid">UID: {{ row.userId ?? '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusMeta(row.status).type" size="small">
              {{ getStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="报名时 Rating" width="130" align="center">
          <template #default="{ row }">
            {{ row.ratingAtRegistration ?? '-' }}
          </template>
        </el-table-column>

        <el-table-column label="是否超限" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="isOverLimit(row) ? 'warning' : 'success'" size="small" effect="plain">
              {{ isOverLimit(row) ? '超限打星号' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="报名时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>

        <el-table-column label="审核信息" min-width="200">
          <template #default="{ row }">
            <span v-if="row.status === 'REJECTED'" class="reject-reason">
              拒绝原因：{{ row.rejectReason || '-' }}
            </span>
            <span v-else-if="row.reviewTime" class="review-time">
              {{ formatTime(row.reviewTime) }}
            </span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button
                type="success"
                link
                :loading="reviewingId === row.userId"
                @click="handleApprove(row)"
              >
                通过
              </el-button>
              <el-button
                type="danger"
                link
                :loading="reviewingId === row.userId"
                @click="handleReject(row)"
              >
                拒绝
              </el-button>
            </template>
            <span v-else class="muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-box">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 比赛报名审核页（比赛作者/管理员，T10-1）：
// 分页查看看某场比赛的全部报名记录，展示报名时 Rating 与是否超限（eligible=0 → 打星号），
// 并对待审核（PENDING）记录执行通过/拒绝；拒绝必须填写原因。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { getContestDetail, getContestRegistrations, reviewContestRegistration } from '@/api/contest'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

const route = useRoute()
const router = useRouter()

const contestId = computed(() => {
  const num = Number(route.params.id)
  return Number.isFinite(num) && num > 0 ? num : null
})

const loading = ref(false)
const errorText = ref('')
const registrations = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const contestDetail = ref(null)
const reviewingId = ref(null)

const pageDesc = computed(() => {
  const title = contestDetail.value?.title
  const idPart = contestId.value ? `比赛 #${contestId.value}` : '比赛'
  return title
    ? `${idPart} · ${title}：报名列表与审核`
    : `${idPart}：报名列表与审核`
})

// 状态语义与后端 ContestUserServiceImpl 常量保持一致。
function getStatusMeta(status) {
  switch (status) {
    case 'AUTO_APPROVED':
      return { label: '自动通过', type: 'success' }
    case 'PENDING':
      return { label: '待审核', type: 'warning' }
    case 'APPROVED':
      return { label: '已通过', type: 'success' }
    case 'REJECTED':
      return { label: '已拒绝', type: 'danger' }
    default:
      return { label: status || '未知', type: 'info' }
  }
}

// eligible=0 表示超出该场 Rating 上限（超限打星号：仍可参赛但不进正式榜、不计 Rating）。
function isOverLimit(row) {
  return Number(row?.eligible) === 0
}

function getUserDisplayName(row) {
  return row?.nickname || row?.username || (row?.userId ? `用户${row.userId}` : '未知用户')
}

function formatTime(value) {
  if (!value) return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : '-'
}

async function loadContestDetail() {
  if (!contestId.value) return
  try {
    const res = await getContestDetail(contestId.value)
    if (isResultSuccess(res)) {
      contestDetail.value = unwrapResultData(res) || null
    }
  } catch (e) {
    // 详情只用于标题展示，失败不阻断报名列表加载（列表接口的权限校验才是关键）。
    console.error('获取比赛详情失败', e)
  }
}

async function fetchList() {
  if (!contestId.value) {
    registrations.value = []
    total.value = 0
    errorText.value = '未获取到有效的比赛 ID'
    return
  }

  loading.value = true
  errorText.value = ''
  try {
    const res = await getContestRegistrations(contestId.value, {
      page: currentPage.value,
      size: pageSize.value
    })

    if (!isResultSuccess(res)) {
      errorText.value = getResultMessage(res, '获取报名列表失败')
      registrations.value = []
      total.value = 0
      return
    }

    const data = unwrapResultData(res) || {}
    registrations.value = Array.isArray(data.records) ? data.records : []
    total.value = Number(data.total ?? 0)
  } catch (e) {
    errorText.value = e?.message || '获取报名列表失败'
    registrations.value = []
    total.value = 0
    ElMessage.error(errorText.value)
  } finally {
    loading.value = false
  }
}

function handlePageChange(page) {
  currentPage.value = page
  fetchList()
}

async function submitReview(userId, approve, reason = '') {
  reviewingId.value = userId
  try {
    const res = await reviewContestRegistration(contestId.value, userId, { approve, reason })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '审核失败'))
      return false
    }
    ElMessage.success(getResultMessage(res, approve ? '已通过报名' : '已拒绝报名'))
    await fetchList()
    return true
  } catch (e) {
    ElMessage.error(e?.message || '审核失败')
    return false
  } finally {
    reviewingId.value = null
  }
}

async function handleApprove(row) {
  if (!row?.userId) return
  try {
    await ElMessageBox.confirm(
      `确定通过「${getUserDisplayName(row)}」的报名吗？通过后该用户可参加正式赛程。`,
      '通过报名',
      { type: 'info', confirmButtonText: '通过', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await submitReview(row.userId, true)
}

async function handleReject(row) {
  if (!row?.userId) return
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `请填写拒绝「${getUserDisplayName(row)}」报名的原因（将展示给用户/审核记录）：`,
      '拒绝报名',
      {
        type: 'warning',
        confirmButtonText: '拒绝',
        cancelButtonText: '取消',
        inputPlaceholder: '例如：未满足本场 Rating 资格要求',
        inputValidator: (val) => (val && val.trim() ? true : '拒绝原因不能为空')
      }
    )
    reason = (value || '').trim()
  } catch {
    return
  }
  await submitReview(row.userId, false, reason)
}

function goBack() {
  router.push({ name: 'AdminContest' })
}

// 路由参数变化（切换比赛）时重新初始化：先清空旧数据再拉详情与列表，
// 否则上一个比赛的报名会残留在页面上。
watch(
  () => contestId.value,
  async () => {
    currentPage.value = 1
    registrations.value = []
    total.value = 0
    contestDetail.value = null
    await Promise.all([loadContestDetail(), fetchList()])
  }
)

onMounted(() => {
  loadContestDetail()
  fetchList()
})
</script>

<style scoped>
.admin-contest-registrations {
  padding: 16px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.page-desc {
  color: var(--text-secondary);
  font-size: 13px;
  margin-top: 4px;
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mb-16 {
  margin-bottom: 16px;
}

.admin-contest-registrations :deep(.el-card) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.admin-contest-registrations :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.admin-contest-registrations :deep(.el-table) {
  flex: 1;
  min-height: 0;
}

.user-cell {
  display: flex;
  align-items: center;
}

.user-meta {
  min-width: 0;
}

.username {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-all;
}

.userid {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}

.reject-reason {
  font-size: 13px;
  color: var(--text-regular);
}

.review-time {
  font-size: 13px;
  color: var(--text-secondary);
}

.muted {
  font-size: 12px;
  color: var(--text-secondary);
}

.pagination-box {
  margin-top: auto;
  padding-top: 14px;
  display: flex;
  justify-content: center;
}
</style>
