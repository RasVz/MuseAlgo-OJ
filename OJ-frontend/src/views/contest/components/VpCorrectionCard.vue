<template>
  <el-card v-if="isContestFinished" shadow="never" class="table-card vp-card">
    <template #header>
      <div class="card-header">
        <span>虚拟参赛 & 赛后订正</span>
        <el-button v-if="vpAttempt && vpAttempt.status === 'RUNNING'" size="small" type="primary" :loading="vpBusy" @click="doFinishVp">交卷</el-button>
        <el-button v-else size="small" type="primary" :loading="vpBusy" @click="doStartVp">开始虚拟参赛</el-button>
      </div>
    </template>

    <div v-if="vpAttempt && vpAttempt.status === 'RUNNING'" class="vp-running">
      <span>倒计时 <b>{{ formatVpTime(vpRemaining) }}</b>，按比赛真实时长计时，提交计入 VP 成绩</span>
    </div>
    <div v-else-if="vpAttempt && vpAttempt.status === 'FINISHED'" class="vp-result">
      <el-tag type="success">
        VP 成绩：{{ isOiContest ? `${vpAttempt.score ?? 0} 分` : `${vpAttempt.solvedCount ?? 0} 题 · ${vpAttempt.penaltyTime ?? 0} 分钟罚时` }}
        · 排名 {{ vpAttempt.rankNum ?? '-' }}/{{ vpAttempt.totalParticipants ?? '-' }}
      </el-tag>
    </div>
    <div v-else class="vp-empty">本场比赛已结束，可以虚拟参赛（限时补打、按真实规则计分与排名）。</div>

    <el-divider content-position="left">我的赛后订正（赛后 7 天内重新 AC）</el-divider>
    <el-table v-if="corrections.length" :data="corrections" size="small" border>
      <el-table-column prop="problemTitle" label="题目" min-width="200" />
      <el-table-column prop="contestStatus" label="赛中结果" width="100" align="center" />
      <el-table-column label="已订正" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.corrected ? 'success' : 'danger'" size="small">{{ row.corrected ? '✓ 是' : '未订正' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <div v-else class="vp-empty">没有可追踪的订正记录（未参加过本场或没有题目数据）。</div>
  </el-card>
</template>

<script setup>
// VP（Virtual Participation，补赛）卡：OI 赛制比赛结束后才可使用。
// 逻辑分两段：比赛进行中隐藏；比赛结束（isContestFinished 变 true）后拉取 VP 记录与罚时公示。
// 倒计时在前端本地递减，真正的时间边界由服务端裁定——
// 前端只负责显示，超时也会主动调 finishVp 兜底交卷。
import { ref, watch } from 'vue'
import { getVp, startVp, finishVp, getCorrection } from '@/api/contest'
import { unwrapResultData } from '@/api/contestRank'

const props = defineProps({
  contestId: { type: [Number, String], default: null },
  isContestFinished: { type: Boolean, default: false },
  isOiContest: { type: Boolean, default: false }
})

const vpAttempt = ref(null)
const vpRemaining = ref(0)
const vpBusy = ref(false)
const corrections = ref([])
let vpTimer = null

// 剩余秒数由服务端下发，前端不自行推算起始时间：
// 刷新页面后重新拉一次就能准确续上，无需保存本地时间戳。
const loadVp = async () => {
  try {
    const res = await getVp(props.contestId)
    vpAttempt.value = unwrapResultData(res) || null
    if (vpAttempt.value?.status === 'RUNNING') {
      vpRemaining.value = vpAttempt.value.remainingSeconds ?? 0
      startVpTimer()
    }
  } catch (e) { console.error(e) }
}

const doStartVp = async () => {
  vpBusy.value = true
  try {
    const res = await startVp(props.contestId)
    vpAttempt.value = unwrapResultData(res) || null
    if (vpAttempt.value?.status === 'RUNNING') {
      vpRemaining.value = vpAttempt.value.remainingSeconds ?? 0
      startVpTimer()
    }
  } finally { vpBusy.value = false }
}

// 交卷不可撤销（VP 记录会写入并影响排名），用原生 confirm 二次确认
const doFinishVp = async () => {
  if (!window.confirm('确定交卷？交卷后本次 VP 结束。')) return
  vpBusy.value = true
  try {
    const res = await finishVp(props.contestId)
    vpAttempt.value = unwrapResultData(res) || null
    stopVpTimer()
  } finally { vpBusy.value = false }
}

// 每次启动前先 stop：重复调用 startVpTimer 会叠加多个 interval，倒计时会快一倍。
// 归零时主动交卷，避免用户忘点导致 VP 超时挂起。
const startVpTimer = () => {
  stopVpTimer()
  vpTimer = setInterval(() => {
    vpRemaining.value -= 1
    if (vpRemaining.value <= 0) doFinishVp()
  }, 1000)
}

const stopVpTimer = () => {
  if (vpTimer) { clearInterval(vpTimer); vpTimer = null }
}

const formatVpTime = (seconds) => {
  const s = Math.max(0, seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${pad(h)}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`
}

const loadCorrection = async () => {
  try {
    const res = await getCorrection(props.contestId)
    corrections.value = unwrapResultData(res) || []
  } catch (e) { console.error(e) }
}

// 比赛一结束就同时拉 VP 记录与罚时公示：这两份数据只在赛后存在，
// 比赛进行中请求必然返回空，白费两个往返。immediate 保证直接打开赛后页面也能加载。
watch(
  () => props.isContestFinished,
  (finished) => {
    if (finished) {
      loadVp()
      loadCorrection()
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.vp-card { margin-top: 16px; }
.vp-running { font-size: 14px; color: #409eff; }
.vp-running b { font-size: 18px; }
.vp-result { font-size: 14px; }
.vp-empty { color: #909399; font-size: 13px; }
</style>
