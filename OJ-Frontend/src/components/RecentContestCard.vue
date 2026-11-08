<template>
  <el-card shadow="hover" class="home-card contest-card">
    <template #header>
      <div class="card-header">
        <span class="header-title"><el-icon><Trophy /></el-icon> 近期比赛</span>
        <el-link type="primary" underline="never" @click="$router.push({ name: 'Contest' })">更多 ></el-link>
      </div>
    </template>

    <div class="contest-list" v-loading="loading">
      <template v-if="!loading && list.length > 0">
        <div
          v-for="item in list"
          :key="item.id"
          class="contest-item"
          @click="$router.push({ name: 'ContestProblem', params: { id: item.id } })"
        >
          <div class="c-date">
            <div class="month">{{ getMonth(item.startTime) }}</div>
            <div class="day">{{ getDay(item.startTime) }}</div>
          </div>
          <div class="c-info">
            <div class="c-title">{{ item.title }}</div>
            <div class="c-tags">
              <el-tag size="small" :type="getStatusType(item)">{{ getStatusText(item) }}</el-tag>
              <span class="c-duration">{{ formatDuration(item.startTime, item.endTime, item.duration) }}</span>
            </div>
          </div>
        </div>
      </template>

      <div v-else-if="!loading" class="empty-wrap">
        <el-empty description="近期无赛事" :image-size="60" />
      </div>
    </div>
  </el-card>
</template>

<script setup>
/**
 * 首页「近期比赛」卡片：只展示最近 4 场比赛，点击直接进入该场比赛的题目列表。
 *
 * 依赖 getContestList 分页接口，取第 1 页 4 条。
 * 有两个刻意的设计：
 * 1. 卡片上的状态标签是前端按起止时间现算的，不依赖后端返回的 status 字段——
 *    这样即使后端缓存了几秒的列表数据，标签也不会与实际开考/交卷时刻脱节。
 * 2. 请求失败不弹错误、空数据展示「近期无赛事」空态。首页同时有多个卡片，
 *    一张卡片失败就弹一次全局提示会反复打断用户；空态占位则保证栅格不会塌陷。
 */
import { ref, onMounted } from 'vue'
import { getContestList } from '@/api/contest'
import { Trophy } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const list = ref([])
const loading = ref(false)

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getContestList({
      currentPage: 1,
      pageSize: 4 // 2行2列，展示4条
    })
    if (res.code === 200) {
      // 仍做一次 slice 截断：后端分页大小属实现细节，若被调大也不会撑破 2x2 栅格。
      // records 可能缺失（后端异常时返回空对象），故用 || [] 兜底，避免后续 .length 报错。
      list.value = (res.data.records || []).slice(0, 4)
    }
  } finally {
    // 只在 finally 收尾、不 catch：卡片内部不处理异常，
    // 让请求库统一的错误上报链路接手，模板靠 loading 复位渲染空态。
    loading.value = false
  }
}

const getMonth = (t) => {
  const d = dayjs(t)
  // isValid 判断不能省：非法时间 dayjs 会 format 出 'Invalid Date' 字符串直接显示给用户。
  return d.isValid() ? d.format('MMM') : '--'
}

const getDay = (t) => {
  const d = dayjs(t)
  return d.isValid() ? d.format('DD') : '--'
}

/**
 * 展示比赛时长，优先用「结束 - 开始」的时间差。
 * 只有两端时间缺失时才退化到后端给的 duration（分钟数）。
 * 注意 Number.isFinite 而非真值判断：duration 为 0 或 null 时都不该产出时长文案。
 */
const formatDuration = (startTime, endTime, duration) => {
  let diffMinutes = null

  const start = dayjs(startTime)
  const end = dayjs(endTime)

  if (start.isValid() && end.isValid()) {
    diffMinutes = end.diff(start, 'minute')
  } else if (Number.isFinite(duration)) {
    diffMinutes = duration
  }

  if (diffMinutes == null || diffMinutes <= 0) return '未知'

  const d = Math.floor(diffMinutes / 1440)
  const h = Math.floor((diffMinutes % 1440) / 60)
  const m = diffMinutes % 60

  const parts = []
  if (d > 0) parts.push(`${d}天`)
  if (h > 0) parts.push(`${h}小时`)
  // 分钟段：仅在天、小时都为 0 时强制显示，避免「0分」这种无意义尾巴，
  // 同时保证 45 分钟这类短比赛仍有一项可展示。
  if (m > 0 || (d === 0 && h === 0)) parts.push(`${m}分`)

  return parts.join(' ')
}

/**
 * 按当前时间推导某场比赛的状态。
 * 与比赛内布局用 Store 的 status 同源规则，但此处不读 Store：
 * Store 只承载「当前进入的那一场」，首页这张卡要判定的是列表里的多场比赛，
 * 复用 Store 会串状态。任何一处时间比较口径改动，这里都要同步。
 */
const getContestStatus = (c) => {
  const now = dayjs()
  const start = dayjs(c.startTime)

  if (!start.isValid()) return 'Ended'

  let end = dayjs(c.endTime)
  // 结束时间缺失时用「开始 + 时长」兜出结束时刻，
  // 否则会被判成 Ended，让正在进行却没填 endTime 的比赛显示为已结束。
  if (!end.isValid() && Number.isFinite(c.duration)) {
    end = start.add(c.duration, 'minute')
  }

  // 两端时间都拿不到时按已结束处理：宁可少吸引点击，也不要误导考生以为还能进考场。
  if (!end.isValid()) return 'Ended'

  if (now.isBefore(start)) return 'NotStarted'
  if (now.isBefore(end)) return 'Running'
  return 'Ended'
}

const getStatusType = (c) => {
  const status = getContestStatus(c)
  return {
    NotStarted: 'primary',
    Running: 'success',
    Ended: 'info'
  }[status]
}

const getStatusText = (c) => {
  const status = getContestStatus(c)
  return {
    NotStarted: '未开始',
    Running: '进行中',
    Ended: '已结束'
  }[status]
}

onMounted(fetchData)
</script>

<style scoped>
.home-card { border-radius: 12px; border: none; height: 100%; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-title { font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 6px; }

.contest-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.contest-item {
  display: flex;
  gap: 12px;
  padding: 10px;
  border: 1px solid var(--border-light);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 0;
}

.contest-item:hover {
  background: var(--bg-surface-hover);
  border-color: var(--border-base);
}

.c-date {
  background: var(--bg-input);
  border-radius: 8px;
  width: 46px;
  height: 46px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.c-date .month { font-size: 10px; color: var(--text-secondary); text-transform: uppercase; }
.c-date .day { font-size: 16px; font-weight: bold; color: var(--text-primary); line-height: 1; }

.c-info {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  overflow: hidden;
  min-width: 0;
}

.c-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.c-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  min-width: 0;
}

.c-duration {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-wrap {
  grid-column: 1 / -1;
}
</style>
