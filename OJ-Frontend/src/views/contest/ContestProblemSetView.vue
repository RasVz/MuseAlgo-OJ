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
          <li>系统会进行代码查重与行为检测，违规将取消成绩。</li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<script setup>
// 比赛题目页：赛题列表 + 比赛信息侧栏，并实时反映比赛进行状态。
// 比赛基本信息来自 contestStore（比赛布局统一注入），本页只额外拉一次赛题列表。
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getContestProblemList } from '@/api/contest'
import { useContestStore } from '@/stores/contestContext'
import { Check, Minus, Calendar, Tickets } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const contestStore = useContestStore()

const contestId = computed(() => route.params.id || route.params.contestId)
const contest = computed(() => contestStore.info || {})

const loading = ref(false)
const problems = ref([])

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

// 必须带 contestId：题面页据此把提交识别为比赛提交，
// 漏传会让用户在比赛里提交的成绩不进榜单。
const openProblemInNewTab = (row) => {
  const target = router.resolve({
    name: 'ProblemDetail',
    params: { id: row.problemId },
    query: { contestId: contestId.value }
  })
  window.open(target.href, '_blank')
}

const handleRowClick = (row) => {
  openProblemInNewTab(row)
}

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

// 比赛中途打开新标签页写代码很常见，回到本页时状态要能自动更新，
// 所以比赛状态由 now 每秒推算，而非首次加载时定格。
onMounted(() => {
  fetchData()
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
</style>
