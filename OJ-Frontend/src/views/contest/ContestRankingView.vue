<template>
  <div class="contest-ranking-page">
    <el-card shadow="never" class="page-card">
      <div class="page-header">
        <div>
          <div class="page-title">比赛排行榜</div>
          <div class="page-desc">
            正式榜只展示正式选手；全局榜包含正式、超限（打星号）与虚拟赛（VP）选手
          </div>
        </div>
        <div class="page-actions">
          <el-button v-if="activeTab === 'global' && isAdmin" :loading="exporting" @click="handleExportGlobal">
            导出全局榜
          </el-button>
          <el-button :loading="activeLoading" @click="handleRefresh">刷新</el-button>
        </div>
      </div>

      <div class="rank-switcher">
        <el-radio-group v-model="activeTab">
          <el-radio-button value="official">正式榜</el-radio-button>
          <el-radio-button value="global">全局榜</el-radio-button>
        </el-radio-group>

        <div v-if="activeTab === 'global'" class="board-legend">
          <el-tag type="warning" effect="plain" size="small">* 超限</el-tag>
          <span>超限选手打星号，不计 Rating</span>
          <el-tag type="info" effect="plain" size="small">VP</el-tag>
          <span>虚拟赛</span>
          <span>无标记为正式选手</span>
        </div>
      </div>
      <div v-if="streamMessage" role="status" aria-live="polite">
        {{ streamMessage }}
        <el-button v-if="streamExhausted" link type="primary" @click="retryStream">重新连接</el-button>
      </div>
    </el-card>

    <!-- 虚拟赛 + 赛后订正（B4） -->
    <VpCorrectionCard :contest-id="contestId" :is-contest-finished="isContestFinished" :is-oi-contest="isOiContest" />

    <!-- 娱乐赛不计 Rating，正式榜恒为空；引导用户去全局榜看完整参赛情况 -->
    <el-alert
      v-if="activeTab === 'official' && isEntertainment"
      title="娱乐赛（ENTERTAINMENT）不计 Rating，正式榜为空；请切换到全局榜查看参赛选手。"
      type="info"
      :closable="false"
      show-icon
      class="mb-16"
    />

    <ContestRankTable
      :rank-list="activeRankList"
      :loading="activeLoading"
      :error-text="activeErrorText"
      :oi-ranking-hidden="oiRankingHidden"
      :is-oi-contest="isOiContest"
      :show-participant-type="activeTab === 'global'"
      :empty-text="activeTab === 'global' ? '暂无全局榜单数据' : '暂无排行榜数据'"
    />
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 比赛排行榜页：正式榜 / 全局榜双榜切换（T10-3）。
//
// 正式榜：未结束时从权威提交快照读取，结束后读取最终榜；只包含正式选手。
// 全局榜：GET /contest/{id}/rank/global/list，合并正式 OFFICIAL、超限打星号 STARRED
//         与虚拟赛 VP 三类选手，名次统一连续编号，每行带 participantType。
// 两类 SSE 都只发分榜 invalidation；实际行始终来自匹配的权威 GET。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import dayjs from 'dayjs'
import {
  getContestRankList,
  getContestFinalRankList,
  getContestGlobalRankList,
  getContestRankStreamUrl,
  exportContestGlobalRank,
  getResultMessage,
  isResultSuccess,
  unwrapResultData
} from '@/api/contestRank'
import { getContestDetail } from '@/api/contest'
import { useUserStore } from '@/stores/userStore'
import { connectSSE } from '@/utils/sse'
import VpCorrectionCard from './components/VpCorrectionCard.vue'
import ContestRankTable from './components/ContestRankTable.vue'

const route = useRoute()
const userStore = useUserStore()
let sseConn = null
let viewEpoch = 0
let streamGeneration = 0
let rankRequestSerial = { official: 0, global: 0 }
let clockTimer = null
let lastVisibilityKey = null
let mounted = false
let rankInFlight = null
let rankRefreshPending = false
let rankRefreshTimer = null
const streamMessage = ref('')
const streamExhausted = ref(false)

// official | global —— 两个榜的数据、loading、error 完全独立，
// 切换时互不覆盖，也方便分别保留各自的空态/错误态。
const activeTab = ref('official')
const officialRankList = ref([])
const globalRankList = ref([])
const loadingOfficial = ref(false)
const loadingGlobal = ref(false)
const errorOfficial = ref('')
const errorGlobal = ref('')
const exporting = ref(false)
const contestDetail = ref(null)
const currentTimeMs = ref(Date.now())

const contestId = computed(() => {
  const id = route.params.id ?? route.params.contestId
  const num = Number(id)
  return Number.isFinite(num) && num > 0 ? num : null
})

const isAdmin = computed(() => userStore.role >= 3)

const isContestFinished = computed(() => {
  const endTime = contestDetail.value?.endTime ?? contestDetail.value?.finishTime
  if (!endTime) return false
  return dayjs(currentTimeMs.value).isAfter(dayjs(endTime))
})

const isOiContest = computed(() => String(contestDetail.value?.mode || '').toUpperCase() === 'OI')

// 娱乐赛不计 Rating：正式榜恒为空，页面上给出说明引导看全局榜。
const isEntertainment = computed(
  () => String(contestDetail.value?.category || '').toUpperCase() === 'ENTERTAINMENT'
)

const hasContestStarted = computed(() => {
  const startTime = contestDetail.value?.startTime
  return !!startTime && !dayjs(currentTimeMs.value).isBefore(dayjs(startTime))
})

// OI 赛中对学生隐藏；教师/管理员沿用 userStore 与服务端一致的既有权限。
// 结束边界会触发权威 GET，服务端仍对每次 GET 和 SSE 建连单独鉴权。
const oiRankingHidden = computed(() =>
  isOiContest.value && hasContestStarted.value && !isContestFinished.value && !userStore.isTeacher
)

const activeRankList = computed(() =>
  activeTab.value === 'global' ? globalRankList.value : officialRankList.value
)
const activeLoading = computed(() =>
  activeTab.value === 'global' ? loadingGlobal.value : loadingOfficial.value
)
const activeErrorText = computed(() =>
  activeTab.value === 'global' ? errorGlobal.value : errorOfficial.value
)

watch([contestId, activeTab, () => userStore.accessToken, () => userStore.isTeacher], () => {
  if (mounted) void reloadCurrentView()
})

onMounted(() => {
  mounted = true
  document.addEventListener('visibilitychange', handlePageVisibility)
  clockTimer = setInterval(handleClockTick, 100)
  void reloadCurrentView()
})

onUnmounted(() => {
  mounted = false
  viewEpoch += 1
  if (clockTimer !== null) clearInterval(clockTimer)
  document.removeEventListener('visibilitychange', handlePageVisibility)
  if (rankRefreshTimer !== null) clearTimeout(rankRefreshTimer)
  closeSSE()
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
function normalizeRankRow(raw, index, fallbackContestId = contestId.value) {
  const rankNum = Number(raw?.rankNum ?? raw?.rank ?? raw?.ranking ?? index + 1)
  const solvedCount = Number(raw?.solvedCount ?? raw?.solved ?? raw?.acCount ?? 0)
  const penaltyTime = Number(raw?.penaltyTime ?? raw?.penalty ?? raw?.timePenalty ?? 0)
  const totalScore = Number(raw?.totalScore ?? raw?.score ?? 0)
  const ratingBefore = raw?.ratingBefore ?? raw?.oldRating ?? raw?.beforeRating ?? null
  const ratingAfter = raw?.ratingAfter ?? raw?.newRating ?? raw?.afterRating ?? null
  const isVisible = Number(raw?.isVisible ?? raw?.visible ?? 1) === 0 ? 0 : 1
  // 全局榜额外下发 participantType（OFFICIAL / STARRED / VP）；
  // 正式榜接口不返回时按正式选手处理，保证表格行为一致。
  const participantType = String(raw?.participantType ?? 'OFFICIAL').toUpperCase()

  return {
    id: raw?.id ?? `rank-${index}`,
    contestId: raw?.contestId ?? fallbackContestId ?? null,
    userId: raw?.userId ?? raw?.uid ?? null,
    username: raw?.username ?? raw?.userName ?? raw?.nickname ?? '',
    nickname: raw?.nickname ?? raw?.username ?? raw?.userName ?? '',
    userAvatar: raw?.userAvatar ?? raw?.avatar ?? raw?.userPic ?? '',
    rankNum: Number.isFinite(rankNum) && rankNum > 0 ? rankNum : index + 1,
    solvedCount: Number.isFinite(solvedCount) ? solvedCount : 0,
    penaltyTime: Number.isFinite(penaltyTime) ? penaltyTime : 0,
    totalScore: Number.isFinite(totalScore) ? totalScore : 0,
    participantType: ['OFFICIAL', 'STARRED', 'VP'].includes(participantType) ? participantType : 'OFFICIAL',
    ratingBefore,
    ratingAfter,
    award: raw?.award ?? '',
    isVisible
  }
}

function isCurrentView(cid, boardType, epoch) {
  return mounted && epoch === viewEpoch && cid === contestId.value && boardType === activeTab.value
}

async function loadContestDetail(cid, boardType, epoch) {
  const res = await getContestDetail(cid)
  if (!isCurrentView(cid, boardType, epoch)) return false
  if (!isResultSuccess(res)) throw new Error(getResultMessage(res, '获取比赛详情失败'))
  currentTimeMs.value = Date.now()
  contestDetail.value = unwrapResultData(res) || null
  return true
}

async function requestRankWith(apiFn, sourceLabel, cid) {
  const res = await apiFn(cid)

  if (!isResultSuccess(res)) {
    throw new Error(getResultMessage(res, `获取${sourceLabel}失败`))
  }

  const data = unwrapResultData(res)
  const list = normalizeListData(data)
  return list.map((item, index) => normalizeRankRow(item, index, cid))
}

async function reloadCurrentView() {
  const cid = contestId.value
  const boardType = activeTab.value
  const epoch = ++viewEpoch
  closeSSE()
  lastVisibilityKey = null

  contestDetail.value = null
  clearRankLists()
  loadingOfficial.value = false
  loadingGlobal.value = false
  errorOfficial.value = ''
  errorGlobal.value = ''

  if (document.hidden) return

  if (!cid) {
    contestDetail.value = null
    errorOfficial.value = '未获取到有效的比赛 ID'
    return
  }

  try {
    const detailLoaded = await loadContestDetail(cid, boardType, epoch)
    if (!detailLoaded || !isCurrentView(cid, boardType, epoch)) return
  } catch (err) {
    if (!isCurrentView(cid, boardType, epoch)) return
    const message = err?.message || '获取比赛详情失败'
    if (boardType === 'global') errorGlobal.value = message
    else errorOfficial.value = message
    showApiError(err, message)
    return
  }

  lastVisibilityKey = visibilityKey()
  if (oiRankingHidden.value) {
    clearRankLists()
    return
  }

  await loadActiveRankList(cid, boardType, epoch)
  if (isCurrentView(cid, boardType, epoch)) reconnectSSE(cid, boardType, epoch)
}

function loadActiveRankList(cid = contestId.value, boardType = activeTab.value, epoch = viewEpoch) {
  if (document.hidden || !mounted) return Promise.resolve()
  if (rankInFlight) {
    rankRefreshPending = true
    return rankInFlight
  }
  const work = boardType === 'global'
    ? loadGlobalRankList(cid, boardType, epoch)
    : loadOfficialRankList(cid, boardType, epoch)
  rankInFlight = Promise.resolve(work).finally(() => {
    rankInFlight = null
    if (rankRefreshPending) {
      rankRefreshPending = false
      scheduleRankRefresh()
    }
  })
  return rankInFlight
}

function scheduleRankRefresh() {
  if (!mounted || document.hidden) return
  if (rankRefreshTimer !== null) clearTimeout(rankRefreshTimer)
  rankRefreshTimer = setTimeout(() => {
    rankRefreshTimer = null
    void loadActiveRankList()
  }, 150)
}

function handlePageVisibility() {
  if (document.hidden) {
    viewEpoch += 1
    closeSSE()
    if (rankRefreshTimer !== null) clearTimeout(rankRefreshTimer)
    rankRefreshTimer = null
    rankRefreshPending = false
    streamMessage.value = '页面已隐藏，实时更新已暂停。'
    return
  }
  if (!contestDetail.value) { void reloadCurrentView(); return }
  currentTimeMs.value = Date.now()
  const cid = contestId.value
  const boardType = activeTab.value
  const epoch = ++viewEpoch
  lastVisibilityKey = visibilityKey()
  if (oiRankingHidden.value) { hideRankIfRequired(); return }
  streamMessage.value = ''
  void loadActiveRankList(cid, boardType, epoch).then(() => {
    if (isCurrentView(cid, boardType, epoch)) reconnectSSE(cid, boardType, epoch, true)
  })
}

function retryStream() {
  if (!document.hidden) reconnectSSE(contestId.value, activeTab.value, viewEpoch)
}

function handleRefresh() {
  void reloadCurrentView()
}

function isCurrentRequest(cid, boardType, epoch, serial) {
  return isCurrentView(cid, boardType, epoch) && serial === rankRequestSerial[boardType]
}

async function loadOfficialRankList(cid, boardType, epoch) {
  const serial = ++rankRequestSerial[boardType]
  if (!cid) {
    if (!isCurrentView(cid, boardType, epoch)) return
    officialRankList.value = []
    errorOfficial.value = '未获取到有效的比赛 ID'
    return
  }
  if (!isCurrentRequest(cid, boardType, epoch, serial)) return

  if (oiRankingHidden.value) {
    officialRankList.value = []
    errorOfficial.value = ''
    return
  }

  loadingOfficial.value = true
  errorOfficial.value = ''

  try {
    const apiFn = isContestFinished.value ? getContestFinalRankList : getContestRankList
    const sourceLabel = isContestFinished.value ? '最终榜' : '实时榜'
    const rows = await requestRankWith(apiFn, sourceLabel, cid)
    currentTimeMs.value = Date.now()
    if (!isCurrentRequest(cid, boardType, epoch, serial)) return
    if (hideRankIfRequired()) return
    officialRankList.value = rows
  } catch (err) {
    currentTimeMs.value = Date.now()
    if (!isCurrentRequest(cid, boardType, epoch, serial)) return
    if (hideRankIfRequired()) return
    const message =
      err?.response?.status === 404
        ? '比赛不存在或未公开，无法加载榜单'
        : err?.message || '获取正式榜失败'
    errorOfficial.value = message
    officialRankList.value = []
    showApiError(err, message)
  } finally {
    if (isCurrentRequest(cid, boardType, epoch, serial)) loadingOfficial.value = false
  }
}

async function loadGlobalRankList(cid, boardType, epoch) {
  const serial = ++rankRequestSerial[boardType]
  if (!cid) {
    if (!isCurrentView(cid, boardType, epoch)) return
    globalRankList.value = []
    errorGlobal.value = '未获取到有效的比赛 ID'
    return
  }
  if (!isCurrentRequest(cid, boardType, epoch, serial)) return

  if (oiRankingHidden.value) {
    globalRankList.value = []
    errorGlobal.value = ''
    return
  }

  loadingGlobal.value = true
  errorGlobal.value = ''

  try {
    const rows = await requestRankWith(getContestGlobalRankList, '全局榜', cid)
    currentTimeMs.value = Date.now()
    if (!isCurrentRequest(cid, boardType, epoch, serial)) return
    if (hideRankIfRequired()) return
    globalRankList.value = rows
  } catch (err) {
    currentTimeMs.value = Date.now()
    if (!isCurrentRequest(cid, boardType, epoch, serial)) return
    if (hideRankIfRequired()) return
    const message =
      err?.response?.status === 404
        ? '比赛不存在或未公开，无法加载全局榜'
        : err?.message || '获取全局榜失败'
    errorGlobal.value = message
    globalRankList.value = []
    showApiError(err, message)
  } finally {
    if (isCurrentRequest(cid, boardType, epoch, serial)) loadingGlobal.value = false
  }
}

function closeSSE() {
  streamGeneration += 1
  if (sseConn) sseConn.close()
  sseConn = null
}

function clearRankLists() {
  officialRankList.value = []
  globalRankList.value = []
}

function setBoardError(boardType, message) {
  if (boardType === 'global') errorGlobal.value = message
  else errorOfficial.value = message
}

function hideRankIfRequired() {
  if (!oiRankingHidden.value) return false
  closeSSE()
  clearRankLists()
  loadingOfficial.value = false
  loadingGlobal.value = false
  errorOfficial.value = ''
  errorGlobal.value = ''
  return true
}

function reconnectSSE(cid, boardType, epoch, skipConnectedRefresh = false) {
  closeSSE()
  streamExhausted.value = false
  if (document.hidden || !cid || isContestFinished.value || oiRankingHidden.value || !userStore.getAccessToken()) return
  const generation = streamGeneration
  const apiFn = boardType === 'global' ? getContestGlobalRankList : getContestRankList
  sseConn = connectSSE(getContestRankStreamUrl(cid, boardType), (eventName, data) => {
    if (generation !== streamGeneration || !isCurrentView(cid, boardType, epoch)) return
    currentTimeMs.value = Date.now()
    if (oiRankingHidden.value) {
      closeSSE()
      clearRankLists()
      return
    }
    if (eventName === 'connected') {
      if (skipConnectedRefresh) skipConnectedRefresh = false
      else scheduleRankRefresh()
      if (isContestFinished.value) closeSSE()
      return
    }
    if (eventName !== 'ranking-invalidated' || data !== boardType) return
    scheduleRankRefresh()
  }, {
    getAccessToken: () => userStore.getAccessToken(),
    onStatus: ({ state, attempt }) => {
      if (generation !== streamGeneration || !isCurrentView(cid, boardType, epoch)) return
      if (state === 'connected') { streamMessage.value = ''; streamExhausted.value = false }
      else if (state === 'reconnecting') streamMessage.value = `实时连接中断，正在重连（第 ${attempt} 次）。`
      else if (state === 'exhausted') { streamMessage.value = '实时连接多次失败，已暂停。'; streamExhausted.value = true }
    },
    onUnauthorized: async () => {
      if (!isCurrentView(cid, boardType, epoch)) return
      const response = await apiFn(cid)
      if (!isResultSuccess(response)) throw new Error(getResultMessage(response, '排行榜授权已失效'))
    },
    onError: error => {
      if (generation !== streamGeneration || !isCurrentView(cid, boardType, epoch)) return
      if (error?.status === 403) {
        if (boardType === 'global') globalRankList.value = []
        else officialRankList.value = []
        setBoardError(boardType, '当前比赛排行榜暂不可见')
      } else if (error?.status === 401) {
        setBoardError(boardType, '登录状态已失效，请重新登录后刷新排行榜')
      }
    }
  })
}

function visibilityKey() {
  return `${isContestFinished.value}:${oiRankingHidden.value}`
}

function handleClockTick() {
  currentTimeMs.value = Date.now()
  if (document.hidden) return
  const nextKey = visibilityKey()
  if (lastVisibilityKey === null || nextKey === lastVisibilityKey) {
    lastVisibilityKey = nextKey
    return
  }

  lastVisibilityKey = nextKey
  const cid = contestId.value
  const boardType = activeTab.value
  const epoch = ++viewEpoch
  closeSSE()
  if (oiRankingHidden.value) {
    clearRankLists()
    errorOfficial.value = ''
    errorGlobal.value = ''
    loadingOfficial.value = false
    loadingGlobal.value = false
    return
  }

  void loadActiveRankList(cid, boardType, epoch).then(() => {
    if (isCurrentView(cid, boardType, epoch)) reconnectSSE(cid, boardType, epoch)
  })
}

// 全局榜 CSV 导出（仅管理员）：后端直接返回带 BOM 的 text/csv。
async function handleExportGlobal() {
  if (!contestId.value || exporting.value) return

  exporting.value = true
  try {
    await exportContestGlobalRank(contestId.value)
  } catch (err) {
    showApiError(err, err?.message || '导出全局榜失败')
  } finally {
    exporting.value = false
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

.rank-switcher {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 14px;
}

.board-legend {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-secondary);
}

.mb-16 {
  margin-bottom: 16px;
}

.header-extra {
  font-size: 13px;
  color: var(--text-secondary);
}

.source-label {
  color: var(--text-regular);
}
</style>
