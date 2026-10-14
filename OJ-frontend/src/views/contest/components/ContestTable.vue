<template>
  <el-table
    :data="list"
    style="width: 100%"
    height="100%"
    stripe
    v-loading="loading"
    :header-cell-style="{ background: 'var(--el-fill-color-light)', color: 'var(--text-regular)', fontWeight: 'bold' }"
  >
    <el-table-column label="赛事名称" min-width="260">
      <template #default="{ row }">
        <div class="contest-title-wrapper">
          <el-link
            type="primary"
            :underline="false"
            class="contest-link"
            @click="toContestDetail(row.id)"
          >
            {{ row.title }}
          </el-link>
          <el-tag v-if="row.type === 'Official'" size="small" type="danger" effect="dark" class="ml-2">官方</el-tag>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="主办方" min-width="120">
      <template #default="{ row }">
        <div class="organizer-info">
          <el-avatar :size="24" :src="row.authorAvatar" class="mr-2">{{ (row.authorName || 'U').charAt(0) }}</el-avatar>
          <span class="author-name">{{ row.authorName }}</span>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="开始时间" width="160">
      <template #default="{ row }">
        <span class="mono-font">{{ formatTime(row.startTime) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="结束时间" width="160">
      <template #default="{ row }">
        <span class="mono-font">{{ formatTime(row.endTime) }}</span>
      </template>
    </el-table-column>

    <el-table-column label="时长" width="180" align="center">
      <template #default="{ row }">
        {{ calculateDuration(row.startTime, row.endTime) }}
      </template>
    </el-table-column>

    <el-table-column label="状态" width="160" align="center">
      <template #default="{ row }">
        <div class="status-box">
          <template v-if="getContestStatus(row) === 'NotStarted'">
            <span class="status-label text-gray">距离开始</span>
            <span class="countdown-text">{{ getCountdown(row.startTime) }}</span>
          </template>

          <template v-else-if="getContestStatus(row) === 'Running'">
            <el-tag effect="dark" type="success" class="mb-1">进行中</el-tag>
            <el-link type="primary" :underline="false" @click="toContestRank(row.id)">查看实时榜单</el-link>
          </template>

          <template v-else>
            <span class="status-label text-gray" style="margin-bottom: 4px;">已结束</span>
            <el-link type="info" :underline="false" @click="toContestRank(row.id)">查看最终榜单</el-link>
          </template>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="报名/热度" width="120" align="center" fixed="right">
      <template #default="{ row }">
        <div class="action-column">
          <div class="participant-count">
            <el-icon><User /></el-icon>
            <span>x{{ row.participantCount }}</span>
          </div>

          <div class="action-btn-wrapper">
            <el-button
              v-if="getContestStatus(row) === 'NotStarted' && !row.registered"
              type="primary"
              size="small"
              round
              @click="$emit('register', row)"
            >
              立即报名
            </el-button>
            <el-tag v-else-if="row.registered" type="success" size="small" effect="plain">已报名</el-tag>
          </div>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup>
// 比赛列表卡片：显示报名状态与倒计时。
// 倒计时在本地每秒递减，不轮询接口——报名截止时间是一次性数据，
// 靠本地走秒即可，省掉整页每分钟一次的请求。
import { useRouter } from 'vue-router'
import { User } from '@element-plus/icons-vue'
import { onMounted, onUnmounted, ref } from 'vue'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

defineProps({
  list: Array,
  loading: Boolean
})

defineEmits(['register'])
const router = useRouter()
// 共享的"当前时间"：每秒只更新一次 ref，所有倒计时与状态判定都读它。
// 若每张卡片各自 setInterval，9 场比赛就是 9 个定时器。
const now = ref(dayjs())
let timer = null

onMounted(() => {
  timer = setInterval(() => {
    now.value = dayjs()
  }, 1000)
})

// 必须清理定时器：组件卸载后它仍会每秒触发，累积成内存泄漏。
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// 比赛状态由起止时间实时推算，不存 status 字段：
// 这样页面开着不动，状态也会在跨过起点/终点的那一刻自动翻转。
const getContestStatus = (contest) => {
  if (!contest.startTime || !contest.endTime) return 'Ended'

  const start = dayjs(contest.startTime)
  const end = dayjs(contest.endTime)

  if (now.value.isBefore(start)) return 'NotStarted'
  if (now.value.isBefore(end)) return 'Running'
  return 'Ended'
}

const calculateDuration = (startTime, endTime) => {
  if (!startTime || !endTime) return '未知'

  const diffMinutes = dayjs(endTime).diff(dayjs(startTime), 'minute')
  if (diffMinutes <= 0) return '0分钟'

  const d = Math.floor(diffMinutes / 1440)
  const h = Math.floor((diffMinutes % 1440) / 60)
  const m = diffMinutes % 60

  let parts = []
  if (d > 0) parts.push(`${d}天`)
  if (h > 0) parts.push(`${h}小时`)
  if (m > 0 || (d === 0 && h === 0)) parts.push(`${m}分`)

  return parts.join(' ')
}

// 超过 24 小时改用"还有几天"而不是 hh:mm:ss：
// 三位数的倒计时时分秒没人愿意盯着看，也挤坏了卡片布局。
const getCountdown = (startTime) => {
  const start = dayjs(startTime)
  const diff = start.diff(now.value)

  if (diff <= 0) return '00:00:00'

  if (diff > 24 * 60 * 60 * 1000) {
    return start.fromNow()
  }

  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const formatTime = (t) => dayjs(t).format('YYYY-MM-DD HH:mm')
const toContestDetail = (id) => router.push({ name: 'ContestProblem', params: { id } })
const toContestRank = (id) => router.push({ name: 'ContestRanking', params: { id } })
</script>

<style scoped>
.contest-title-wrapper { display: flex; align-items: center; }
.contest-link { font-weight: 600; font-size: 15px; }
.ml-2 { margin-left: 8px; }
.mr-2 { margin-right: 8px; }
.mb-1 { margin-bottom: 4px; }

.organizer-info { display: flex; align-items: center; }
.author-name { color: var(--text-regular); font-size: 14px; }

.mono-font { font-family: 'Menlo', monospace; color: var(--text-regular); font-size: 13px; }

.status-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 53.7px;
  line-height: 1.4;
}

.status-label { font-size: 12px; color: var(--text-secondary); }
.countdown-text { font-weight: bold; color: var(--accent-primary); font-family: monospace; font-size: 14px; }

.action-column {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 53.7px;
  gap: 4px;
}

.action-btn-wrapper {
  height: 20px;
  display: flex;
  align-items: center;
}

.participant-count { display: flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-secondary); }

.text-gray { color: var(--text-secondary); }
</style>
