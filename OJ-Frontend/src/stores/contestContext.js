/**
 * 比赛上下文 Store。
 * <p>
 * 承载「当前进入的这场比赛」信息，并由它派生比赛状态与可见性判断。
 * 比赛状态是纯前端计算的结果——不依赖后端下发 status 字段，
 * 因为考试期间的倒计时必须随本地时间实时变化，
 * 若采用后端状态则前端每秒刷新页面都会看到状态跳变。
 * </p>
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import dayjs from 'dayjs'

export const useContestStore = defineStore('contest', () => {
  const info = ref({})
  const loading = ref(false)
  // 由外部定时器驱动 updateNow 更新，用于让倒计时与状态判断跟随时间推进
  const now = ref(dayjs())

  // 设置比赛信息
  const setContest = (data) => {
    info.value = data
  }

  // 实时更新当前时间
  const updateNow = () => {
    now.value = dayjs()
  }

  // 依据比赛起止时间推导状态机：Loading → NotStarted → Running → Ended。
  // 纯前端计算，不读后端 status，这样开考/交卷时刻的切换无需等接口。
  const status = computed(() => {
    // 防御性判断：未取到时间前先显示 Loading，避免误判为已结束
    if (!info.value.startTime || !info.value.endTime) return 'Loading'

    const start = dayjs(info.value.startTime)
    const end = dayjs(info.value.endTime)

    if (now.value.isBefore(start)) return 'NotStarted'
    if (now.value.isBefore(end)) return 'Running'
    return 'Ended'
  })

  // 可见性由状态派生，页面直接用这两个计算属性控制区块显隐，
  // 避免各页面重复写时间判断导致规则不一致。
  const canShowProblems = computed(() => status.value !== 'NotStarted') // 开始后可见
  const canShowPostMatch = computed(() => status.value === 'Ended')     // 结束后可见

  return {
    info,
    loading,
    now,
    status,
    canShowProblems,
    canShowPostMatch,
    setContest,
    updateNow
  }
})