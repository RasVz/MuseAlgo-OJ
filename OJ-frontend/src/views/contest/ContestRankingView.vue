<template>
  <div class="contest-ranking-page">
    <el-card shadow="never" class="page-card">
      <div class="page-header">
        <div>
          <div class="page-title">比赛排行榜</div>
          <div class="page-desc">
            比赛未结束时展示实时榜，比赛结束后展示最终榜
          </div>
        </div>
        <div class="page-actions">
          <el-button :loading="loading" @click="loadRankList">刷新</el-button>
        </div>
      </div>
    </el-card>

    <!-- 虚拟赛 + 赛后订正（B4） -->
    <VpCorrectionCard :contest-id="contestId" :is-contest-finished="isContestFinished" :is-oi-contest="isOiContest" />

    <ContestRankTable
      :rank-list="rankList"
      :loading="loading"
      :error-text="errorText"
      :oi-ranking-hidden="oiRankingHidden"
      :is-oi-contest="isOiContest"
    />
  </div>
</template>

<script setup>
// 比赛排行榜页：比赛中走 Redis 实时榜（SSE 推送），比赛结束后切换到 MySQL 最终榜。
//
// 两套数据源的切换由 isContestFinished 决定：
// 实时榜只反映当前 Redis 中的状态（重启会丢，且看不到中途被封榜的用户），
// 最终榜是判题结束后的权威快照。切换时还要断开 SSE——
// 比赛结束后继续挂着连接只会白耗服务端资源，且推送会覆盖用户正在看的最终榜。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import {
  getContestRankList,
  getContestFinalRankList,
  getResultMessage,
  isResultSuccess,
  unwrapResultData
} from '@/api/contestRank'
import { getContestDetail } from '@/api/contest'
import { connectSSE } from '@/utils/sse'
import VpCorrectionCard from './components/VpCorrectionCard.vue'
import ContestRankTable from './components/ContestRankTable.vue'

const route = useRoute()
// SSE 连接是长连接，必须持有引用：组件卸载时要显式 close，
// EventSource 不关闭会让服务端保留连接直到超时。
let sseConn = null

const loading = ref(false)
const rankList = ref([])
const errorText = ref('')
const contestDetail = ref(null)

const contestId = computed(() => {
  const id = route.params.id ?? route.params.contestId
  const num = Number(id)
  return Number.isFinite(num) && num > 0 ? num : null
})

const isContestFinished = computed(() => {
  const endTime = contestDetail.value?.endTime ?? contestDetail.value?.finishTime
  if (!endTime) return false
  return dayjs().isAfter(dayjs(endTime))
})

const isOiContest = computed(() => contestDetail.value?.mode === 'OI')

// OI 赛制：比赛中隐藏排名，赛后公布
const oiRankingHidden = computed(() => isOiContest.value && !isContestFinished.value)

// 切换比赛（路由参数变化）→ 先重新拉页面数据，再把 SSE 重连到新比赛。
// 顺序不能反：先连 SSE 会收到旧榜的推送并覆盖刚拉到的数据。
watch(
  () => contestId.value,
  async (newCid) => {
    await initPage()
    reconnectSSE(newCid)
  }
)

// 重连前必须先 close 旧连接：EventSource 不会自动随路由断开，
// 留着旧连接会持续收到上一个比赛的推送。
function reconnectSSE(cid) {
  if (sseConn) sseConn.close()
  // 比赛已结束就不再连实时榜——此时数据源是最终榜，推送只会造成覆盖
  if (!cid || isContestFinished.value) return
  sseConn = connectSSE(`/api/contest/${cid}/ranking/stream`, (eventName, data) => {
    if (eventName === 'ranking' && Array.isArray(data) && !isContestFinished.value) {
      rankList.value = normalizeListData(data)
    }
  })
}

onMounted(async () => {
  await initPage()
  reconnectSSE(contestId.value)
})

// 断线重连由 connectSSE 内部负责（带退避），本页只管连接与关闭的生命周期。
onUnmounted(() => {
  if (sseConn) sseConn.close()
})

function normalizeListData(data) {
  if (Array.isArray(data)) return data
  if (data && Array.isArray(data.records)) return data.records
  if (data && Array.isArray(data.list)) return data.list
  if (data && Array.isArray(data.rows)) return data.rows
  if (data && Array.isArray(data.items)) return data.items
  return []
}

// 榜单字段名在不同数据源里不一致（实时榜用 rankNum，最终榜用 rank/ranking），
// 归一到统一结构后模板只认一套字段名。
function normalizeRankRow(raw, index) {
  const rankNum = Number(raw?.rankNum ?? raw?.rank ?? raw?.ranking ?? index + 1)
  const solvedCount = Number(raw?.solvedCount ?? raw?.solved ?? raw?.acCount ?? 0)
  const penaltyTime = Number(raw?.penaltyTime ?? raw?.penalty ?? raw?.timePenalty ?? 0)
  const totalScore = Number(raw?.totalScore ?? raw?.score ?? 0)
  const ratingBefore = raw?.ratingBefore ?? raw?.oldRating ?? raw?.beforeRating ?? null
  const ratingAfter = raw?.ratingAfter ?? raw?.newRating ?? raw?.afterRating ?? null
  const isVisible = Number(raw?.isVisible ?? raw?.visible ?? 1) === 0 ? 0 : 1

  return {
    id: raw?.id ?? `rank-${index}`,
    contestId: raw?.contestId ?? contestId.value ?? null,
    userId: raw?.userId ?? raw?.uid ?? null,
    username: raw?.username ?? raw?.userName ?? raw?.nickname ?? '',
    nickname: raw?.nickname ?? raw?.username ?? raw?.userName ?? '',
    userAvatar: raw?.userAvatar ?? raw?.avatar ?? raw?.userPic ?? '',
    rankNum: Number.isFinite(rankNum) && rankNum > 0 ? rankNum : index + 1,
    solvedCount: Number.isFinite(solvedCount) ? solvedCount : 0,
    penaltyTime: Number.isFinite(penaltyTime) ? penaltyTime : 0,
    totalScore: Number.isFinite(totalScore) ? totalScore : 0,
    ratingBefore,
    ratingAfter,
    award: raw?.award ?? '',
    isVisible
  }
}

async function loadContestDetail() {
  if (!contestId.value) {
    contestDetail.value = null
    return
  }

  const res = await getContestDetail(contestId.value)
  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, '获取比赛详情失败'))
  }

  contestDetail.value = unwrapResultData(res) || null
}

async function requestRankWith(apiFn, sourceLabel) {
  const res = await apiFn(contestId.value)

  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, `获取${sourceLabel}失败`))
  }

  const data = unwrapResultData(res)
  const list = normalizeListData(data)
  return list.map((item, index) => normalizeRankRow(item, index))
}

async function initPage() {
  if (!contestId.value) {
    rankList.value = []
    errorText.value = '未获取到有效的比赛 ID'
    contestDetail.value = null
    return
  }

  loading.value = true
  errorText.value = ''

  try {
    await loadContestDetail()
    await loadRankList()
  } catch (err) {
    const message = err?.message || '初始化排行榜失败'
    errorText.value = message
    rankList.value = []
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}

// 未结束时读 Redis 实时榜，结束后读 MySQL 最终榜——这是本页最关键的一处分叉。
async function loadRankList() {
  if (!contestId.value) {
    rankList.value = []
    errorText.value = '未获取到有效的比赛 ID'
    return
  }

  loading.value = true
  errorText.value = ''

  try {
    const apiFn = isContestFinished.value ? getContestFinalRankList : getContestRankList
    const sourceLabel = isContestFinished.value ? 'MySQL最终榜' : 'Redis实时榜'

    rankList.value = await requestRankWith(apiFn, sourceLabel)
  // 榜单接口尚未联调时后端返回 404，这里给出明确文案而不是笼统的"获取失败"，
// 便于区分"功能没做完"与"真的出错了"。
  } catch (err) {
    const message =
      err?.response?.status === 404
        ? '排行榜接口暂未实现，页面已准备完成，等待后端联调'
        : err?.message || '获取排行榜失败'
    errorText.value = message
    rankList.value = []
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.contest-ranking-page {
  padding: 20px;
}

.page-card,
.table-card {
  margin-bottom: 16px;
}

.page-header,
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
}

.page-desc {
  font-size: 13px;
  color: var(--text-secondary);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.header-extra {
  font-size: 13px;
  color: var(--text-secondary);
}

.source-label {
  color: var(--text-regular);
}
</style>
