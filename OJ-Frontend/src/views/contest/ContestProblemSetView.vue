<template>
  <div class="contest-workspace">
    <div class="left-panel">
      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="panel-header">
            <div class="header-title">
              <el-icon><Tickets /></el-icon>
              <span>比赛题目</span>
            </div>
            <el-tag type="info" effect="plain">共 {{ problems.length }} 题</el-tag>
          </div>
        </template>

        <el-table
          v-loading="loading"
          :data="problems"
          style="width: 100%"
          :header-cell-style="{ background: 'var(--bg-input)', color: 'var(--text-regular)', fontWeight: '600' }"
          :row-class-name="tableRowClassName"
          @row-click="handleRowClick"
        >
          <el-table-column width="60" align="center">
            <template #default="{ row }">
              <el-icon v-if="row.userStatus === 1" color="var(--accent-success)"><Check /></el-icon>
              <el-icon v-else-if="row.userStatus === 2" color="var(--accent-warning)"><Minus /></el-icon>
              <span v-else class="status-dot"></span>
            </template>
          </el-table-column>

          <el-table-column label="题号" width="80" align="center">
            <template #default="{ $index }">
              <span class="problem-index">{{ getProblemIndex($index) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="题目名称" min-width="200">
            <template #default="{ row }">
              <span class="problem-title">{{ row.title }}</span>
            </template>
          </el-table-column>

          <!-- <el-table-column label="限制" width="200" align="center">
            <template #default="{ row }">
              <div class="limits-tags">
                <el-tag size="small" type="info" effect="plain">{{ row.timeLimit }}ms</el-tag>
                <el-tag size="small" type="info" effect="plain">{{ row.memoryLimit }}MB</el-tag>
              </div>
            </template>
          </el-table-column> -->

          <!-- <el-table-column label="通过率" width="220">
            <template #default="{ row }">
              <div class="rate-box">
                <el-progress
                  :percentage="calcRate(row)"
                  :stroke-width="6"
                  :color="customColorMethod"
                  :show-text="false"
                />
                <span class="rate-text">
                  {{ row.passCount || 0 }} / {{ row.totalCount || 0 }}
                  <span class="rate-percent">({{ calcRate(row) }}%)</span>
                </span>
              </div>
            </template>
          </el-table-column> -->

          <el-table-column label="操作" width="100">
            <template #default>
              <el-button type="primary" link>去挑战</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <div class="right-panel">
      <el-card shadow="never" class="info-card">
        <div class="contest-title">{{ contest.title || '比赛信息' }}</div>
        <div class="contest-time">
          <el-icon><Calendar /></el-icon>
          <span>{{ formatTime(contest.startTime) }} ~ {{ formatTime(contest.endTime) }}</span>
        </div>
        <div class="contest-duration">时长：{{ realDuration }}</div>

        <el-divider />

        <div class="meta-grid">
          <div class="meta-item">
            <span class="meta-label">赛制</span>
            <span class="meta-value">{{ contest.type || 'ACM / ICPC' }}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">罚时</span>
            <span class="meta-value">20 分钟</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">状态</span>
            <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag>
          </div>
          <div class="meta-item">
            <span class="meta-label">参赛人数</span>
            <span class="meta-value">{{ contest.participantCount || 0 }}</span>
          </div>
        </div>

        <el-divider />

        <div class="rules-title">参赛提醒</div>
        <ul class="rules-list">
          <li>严禁使用 AI 辅助生成比赛代码。</li>
          <li>严禁共享代码、讨论题解、代打等作弊行为。</li>
          <li>系统会进行代码查重，违规将取消成绩。</li>
        </ul>

        <el-divider />

        <el-button
          type="primary"
          class="entry-button"
          :loading="registering"
          :disabled="entryDisabled"
          @click="handleEntry"
        >
          {{ entryLabel }}
        </el-button>
        <p v-if="entryHint" class="entry-hint">{{ entryHint }}</p>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 比赛题目页：赛题列表 + 比赛信息侧栏，并实时反映比赛进行状态。
// 比赛基本信息来自 contestStore（比赛布局统一注入），本页只额外拉一次赛题列表。
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getContestProblemList, getMyContestRegistration, registerContestApi } from '@/api/contest'
import { useContestStore } from '@/stores/contestContext'
import { useUserStore } from '@/stores/userStore'
import { unwrapResultData } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'
import { Check, Minus, Calendar, Tickets } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const contestStore = useContestStore()
const userStore = useUserStore()

const contestId = computed(() => route.params.id || route.params.contestId)
const contest = computed(() => contestStore.info || {})

const loading = ref(false)
const problems = ref([])
const registration = ref(null)
const registering = ref(false)

// 每秒推进一次"当前时间"，让状态与倒计时在页面开着不动时也能自动翻转。
// 定时器必须在卸载时清理，否则每次进出这个页面都会多留一个。
const now = ref(dayjs())
let timer = null
onMounted(() => {
  timer = setInterval(() => {
    now.value = dayjs()
  }, 1000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const realStatus = computed(() => {
  if (!contest.value.startTime || !contest.value.endTime) return 'Ended'
  const start = dayjs(contest.value.startTime)
  const end = dayjs(contest.value.endTime)
  if (now.value.isBefore(start)) return 'NotStarted'
  if (now.value.isBefore(end)) return 'Running'
  return 'Ended'
})

const statusText = computed(() => {
  if (realStatus.value === 'NotStarted') return '未开始'
  if (realStatus.value === 'Running') return '进行中'
  return '已结束'
})

const statusTagType = computed(() => {
  if (realStatus.value === 'NotStarted') return 'info'
  if (realStatus.value === 'Running') return 'success'
  return ''
})

const realDuration = computed(() => {
  if (!contest.value.startTime || !contest.value.endTime) return '未知'
  const diffMinutes = dayjs(contest.value.endTime).diff(dayjs(contest.value.startTime), 'minute')
  if (diffMinutes <= 0) return '0分钟'
  const d = Math.floor(diffMinutes / 1440)
  const h = Math.floor((diffMinutes % 1440) / 60)
  const m = diffMinutes % 60
  const parts = []
  if (d > 0) parts.push(`${d}天`)
  if (h > 0) parts.push(`${h}小时`)
  if (m > 0 || (d === 0 && h === 0)) parts.push(`${m}分`)
  return parts.join(' ')
})

const formatTime = (t) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '未知')

// 赛题编号用 A、B、C... 顺序生成，不取后端字段：
// 比赛内题目顺序由管理员的排序决定，字母号必须与展示顺序严格一致。
const getProblemIndex = (index) => String.fromCharCode(65 + index)



const tableRowClassName = () => 'clickable-row'

const isApprovedRegistration = computed(() =>
  ['APPROVED', 'AUTO_APPROVED'].includes(registration.value?.status))
const registrationPending = computed(() => registration.value?.status === 'PENDING')
const registrationRejected = computed(() => registration.value?.status === 'REJECTED')

const entryLabel = computed(() => {
  if (realStatus.value === 'NotStarted') return '比赛未开始'
  if (realStatus.value === 'Ended') return isApprovedRegistration.value ? '进入复盘' : '比赛已结束'
  if (registrationRejected.value) return '报名未通过'
  if (registrationPending.value) return '审核中'
  if (isApprovedRegistration.value) return '进入答题'
  if (registration.value?.registrationReviewRequired) return '报名参赛（需审核）'
  return '参赛'
})

const entryDisabled = computed(() => {
  if (realStatus.value === 'NotStarted') return true
  if (realStatus.value === 'Ended') return !isApprovedRegistration.value
  return registrationPending.value || registrationRejected.value
})

const entryHint = computed(() => {
  if (realStatus.value === 'NotStarted') return '比赛开始后可进入答题'
  if (realStatus.value === 'Ended' && !isApprovedRegistration.value) return '比赛已结束，未参赛不能查看题目'
  if (registrationPending.value) return '报名审核中，通过后可进入答题'
  if (registrationRejected.value) return '报名未通过审核，不能查看题目'
  if (entryDisabled.value) return '当前不可进入答题'
  return ''
})

const fetchRegistration = async () => {
  if (!userStore.getAccessToken() || !contestId.value) return
  try {
    const res = await getMyContestRegistration(contestId.value)
    if (res.code === 200) registration.value = unwrapResultData(res) || null
  } catch {
    registration.value = null
  }
}

/**
 * 进入比赛答题页前的准入：未开始/已结束/审核中/未通过都挡下；
 * 无需审核且未报名的用户在点击时自动报名，与后端提交入口口径一致。
 */
const ensureEntry = async () => {
  if (!userStore.getAccessToken()) {
    ElMessage.warning('请先登录后参赛')
    router.push({ name: 'Login', query: { redirect: route.fullPath } })
    return false
  }
  if (realStatus.value === 'NotStarted') {
    ElMessage.info('比赛尚未开始')
    return false
  }
  if (realStatus.value === 'Ended') {
    if (isApprovedRegistration.value) return true
    ElMessage.warning('比赛已结束，未参赛不能查看题目')
    return false
  }
  if (registrationRejected.value) {
    ElMessage.warning('报名未通过审核，不能查看题目')
    return false
  }
  if (registrationPending.value) {
    ElMessage.info('报名审核中，通过后可进入答题')
    return false
  }
  if (isApprovedRegistration.value) return true

  // 进入报名前再拉一次：上次加载可能因瞬时失败把已报名用户误判成未报名。
  await fetchRegistration()
  if (isApprovedRegistration.value) return true
  if (registrationRejected.value) {
    ElMessage.warning('报名未通过审核，不能查看题目')
    return false
  }
  if (registrationPending.value) {
    ElMessage.info('报名审核中，通过后可进入答题')
    return false
  }

  registering.value = true
  try {
    const res = await registerContestApi(contestId.value)
    if (res.code !== 200) {
      // 并发或重复报名：重新读取一次状态，已通过则直接进入。
      await fetchRegistration()
      if (isApprovedRegistration.value) return true
      if (registrationPending.value) {
        ElMessage.info('报名已提交，等待审核')
        return false
      }
      ElMessage.error(res.message || '报名失败')
      return false
    }
    await fetchRegistration()
    if (registrationPending.value) {
      ElMessage.info('报名已提交，等待审核')
      return false
    }
    return isApprovedRegistration.value
  } catch (error) {
    showApiError(error, '报名失败，请稍后重试')
    return false
  } finally {
    registering.value = false
  }
}

const enterAnswer = async (problemId) => {
  if (!(await ensureEntry())) return
  const target = problemId || problems.value[0]?.problemId
  router.push({
    name: 'ContestAnswer',
    params: { id: contestId.value },
    query: target ? { q: `coding-${target}` } : {}
  })
}

const handleEntry = () => enterAnswer()
const handleRowClick = (row) => enterAnswer(row.problemId)

const fetchData = async () => {
  if (!contestId.value) return
  loading.value = true
  try {
    const res = await getContestProblemList(contestId.value)
    if (res.code === 200) {
      problems.value = res.data || []
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

// 比赛状态由 now 每秒推算，回到本页时能自动更新。
onMounted(() => {
  fetchData()
  fetchRegistration()
})
</script>

<style scoped>
.contest-workspace {
  display: grid;
  grid-template-columns: 3fr 1fr;
  gap: 16px;
  max-width: 1400px;
  margin: 0 auto;
  padding: 16px 8px 0;
}

.left-panel,
.right-panel {
  min-width: 0;
}

.table-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  color: var(--text-primary);
}

.problem-index {
  font-weight: bold;
  color: var(--el-color-primary);
  font-family: 'Lato', sans-serif;
}

.problem-title {
  font-weight: 500;
  color: var(--text-primary);
  cursor: pointer;
  transition: color 0.2s;
}
.problem-title:hover {
  color: var(--el-color-primary);
}

.limits-tags {
  display: flex;
  gap: 8px;
  justify-content: center;
}

.rate-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.rate-text {
  font-size: 12px;
  color: var(--text-secondary);
}
.rate-percent {
  margin-left: 4px;
  color: var(--text-regular);
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: var(--el-border-color);
}

:deep(.el-table__row) {
  cursor: pointer;
  height: 64px;
}
:deep(.el-table__cell) {
  padding-top: 16px !important;
  padding-bottom: 16px !important;
}

.info-card {
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
  position: sticky;
  top: 14px;
}

.contest-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.4;
  margin-bottom: 10px;
}
.contest-time {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-regular);
  font-size: 13px;
}
.contest-duration {
  margin-top: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}

.meta-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}
.meta-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.meta-label {
  color: var(--text-secondary);
  font-size: 13px;
}
.meta-value {
  color: var(--text-primary);
  font-weight: 600;
}

.rules-title {
  font-weight: 700;
  margin-bottom: 8px;
  color: var(--text-primary);
}
.rules-list {
  margin: 0;
  padding-left: 18px;
  color: var(--text-regular);
  line-height: 1.8;
  font-size: 13px;
}

.entry-button {
  width: 100%;
}
.entry-hint {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
}
</style>
