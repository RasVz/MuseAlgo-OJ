<template>
  <div class="contest-layout">

    <NavbarContext>
      <div class="contest-navbar-info">
        <div class="nav-left">
          <BackButton 
            style="position: static; transform: none; box-shadow: none; border: none; background: transparent; padding-left: 0;" 
            default-path="/contest"
          />
          <el-divider direction="vertical" class="nav-divider" />

          <div class="info-text-box">
            <span class="c-title" :title="contestStore.info.title">
              {{ contestStore.info.title || '加载中...' }}
            </span>
            <el-tag :type="statusTagType" size="small" effect="dark" round class="status-tag">
              {{ statusText }}
            </el-tag>
          </div>
        </div>

        <div class="nav-right">
          <div class="timer-box" v-if="contestStore.status !== 'Ended'">
            <span class="timer-label">{{ timerLabel }}</span>
            <span class="timer-value">{{ countdownStr }}</span>
          </div>
        </div>
      </div>
    </NavbarContext>

    <div class="contest-tab-bar">
      <div class="width-limiter">
        <el-menu :default-active="activeRoute" mode="horizontal" class="contest-menu" :ellipsis="false" router>
          <el-menu-item :index="`/contest/${contestId}/problem`">题目列表</el-menu-item>
          <el-menu-item :index="`/contest/${contestId}/submission`">评测记录</el-menu-item>
          <el-menu-item :index="`/contest/${contestId}/ranking`">排行榜</el-menu-item>
          <!-- <el-menu-item :index="`/contest/${contestId}/editorial`">赛后题解</el-menu-item> -->
        </el-menu>
      </div>
    </div>

    <div class="contest-content">
      <div class="width-limiter content-wrapper">
        <router-view v-if="!contestStore.loading" v-slot="{ Component }">
          <transition name="el-fade-in-linear" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>

        <div v-else class="loading-box">
          <el-skeleton :rows="5" animated />
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
/**
 * 比赛页面布局：进入某场比赛后的公共外壳。
 *
 * 承担三件事：
 * 1. 拉取当前比赛详情并写入 contestContext Store，使题目列表 / 评测记录 / 排行榜
 *    三个子路由页面无需各自重复请求；
 * 2. 驱动每秒推进的「当前时间」，让导航栏倒计时与比赛状态（未开始/进行中/已结束）实时变化；
 * 3. 在详情加载完成前显示骨架屏，避免子页面先渲染出一屏空数据再被真实数据顶掉。
 *
 * 注意比赛状态不由后端下发：status 是 Store 里根据本地时间与起止时间现算的。
 * 考试期间必须让倒计时随本地时间实时走，若改用后端返回的状态字段，
 * 每次刷新页面都会看到状态跳变，也无法在跨过开考/交卷时刻时自动切换权限。
 */
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useContestStore } from '@/stores/contestContext'
import NavbarContext from '@/components/NavbarContext.vue'
import BackButton from '@/components/BackButton.vue'
import dayjs from 'dayjs'
import { getContestDetail } from '@/api/contest'

const route = useRoute()
const contestStore = useContestStore()
const contestId = computed(() => route.params.id) 
const activeRoute = computed(() => route.path)

// 秒级心跳句柄。挂在模块内变量而非 ref，是因为它不需要参与渲染，
// 存进 ref 只会让每次心跳都触发一次无意义的依赖更新。
let timer = null

// 心跳只做一件事：把 Store 里的 now 推到最新，从而重算 status 与倒计时。
// 把时间更新收敛在 Store 是为了让所有依赖 now 的计算属性共享同一时钟，
// 否则各页面各自 new Date() 会产生细微的时间漂移，导致状态判断不一致。
const updateTimer = () => {
  contestStore.updateNow()
}

onMounted(async () => {
  contestStore.loading = true
  try {
    const res = await getContestDetail(contestId.value)
    if (res.code === 200) {
      contestStore.setContest(res.data)
    }
  } catch (err) {
    // 请求失败只记日志不弹提示：布局层无法给出有意义的错误上下文，
    // 子页面会各自渲染自己的空态/错误态，此处弹窗只会重复打扰用户。
    console.error(err)
  } finally {
    contestStore.loading = false
  }
  // 定时器在 finally 之后无条件启动：即使详情请求失败，状态也要继续按时间推进，
  // 否则页面会永远停在 Loading 状态（status 拿不到起止时间时无法自愈）。
  timer = setInterval(updateTimer, 1000)
})

onUnmounted(() => {
  // 必须清理：在比赛页之间来回切换会反复挂载本布局，
  // 泄漏的 interval 会让已离开的页面继续每秒触发一次计算属性重算。
  if (timer) clearInterval(timer)
})

// 状态 → 标签配色的映射。与 ContestLayout 内部的状态机取值一一对应，
// 缺省值取 info 是为了覆盖 Store 的 Loading 态（尚未拿到起止时间）。
const statusTagType = computed(() => {
  const map = { NotStarted: 'primary', Running: 'success', Ended: 'info' }
  return map[contestStore.status] || 'info'
})

const statusText = computed(() => {
  const map = { NotStarted: '未开始', Running: '进行中', Ended: '已结束' }
  return map[contestStore.status] || '加载中'
})

// 同一个倒计时数字在不同状态下含义不同，标签必须跟着状态走，
// 否则「距离开考 10 分钟」会被误读成「比赛还剩 10 分钟」。
const timerLabel = computed(() => {
  if (contestStore.status === 'NotStarted') return '距离开始'
  if (contestStore.status === 'Running') return '距离结束'
  return '比赛时长'
})

/**
 * 倒计时文案。
 *
 * 目标时刻按状态选取：未开始时倒计时到开考，进行中倒计时到交卷，
 * 已结束返回破折号占位——此时继续显示 00:00:00 会让人误以为还有提交机会。
 * 注意这里读的是 Store 的 now（由上方定时器推进）而不是直接 new Date()，
 * 这样倒计时与 status 用的是同一时钟，不会出现「显示已结束但还有 30 秒」这种割裂。
 */
const countdownStr = computed(() => {
  const info = contestStore.info
  if (!info.startTime || !info.endTime) return '--:--:--'

  const now = contestStore.now
  const start = dayjs(info.startTime)
  const end = dayjs(info.endTime)

  let targetTime = null

  if (now.isBefore(start)) {
    targetTime = start
  } else if (now.isBefore(end)) {
    targetTime = end
  } else {
    return '\u2014\u2014:\u2014\u2014:\u2014\u2014'
  }

  const diffSeconds = targetTime.diff(now, 'second')
  // 兜底归零：diff 在毫秒级临界点上可能算出负数或 -1，直接格式化会显示 "-1秒"。
  if (diffSeconds <= 0) return '00:00:00'

  const d = Math.floor(diffSeconds / 86400)
  const h = Math.floor((diffSeconds % 86400) / 3600)
  const m = Math.floor((diffSeconds % 3600) / 60)
  const s = diffSeconds % 60

  // 天数单独前置：跨天的比赛只显示时分秒会看不出还剩多久，
  // 但把天数混进时分秒里又会破坏 HH:MM:SS 的等宽排版。
  const timeStr = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return d > 0 ? `${d}天 ${timeStr}` : timeStr
})
</script>

<style scoped>
* {
  box-sizing: border-box;
}

.contest-layout {
  height: 100%; 
  width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden; 
  background-color: var(--bg-surface);
}

.width-limiter {
  width: 100%;
  max-width: 1600px; 
  margin: 0 auto;
  padding: 0 20px;
}

.contest-navbar-info {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 100%;
  padding-right: 20px;
}
.nav-left { display: flex; align-items: center; height: 100%; }
.nav-divider { height: 20px; border-color: var(--border-hover); margin: 0 15px; }
.info-text-box { display: flex; align-items: center; }
.c-title { 
  font-weight: bold; font-size: 16px; color: var(--text-primary); 
  margin-right: 10px; max-width: 400px; 
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; 
}
.nav-right { display: flex; align-items: center; }
.timer-box { text-align: right; line-height: 1.2; }
.timer-label { display: block; font-size: 12px; color: var(--text-secondary); }
.timer-value { display: block; font-size: 20px; font-weight: bold; font-family: monospace; color: var(--text-primary); }

.contest-tab-bar {
  height: 48px; 
  width: 100%;
  background-color: var(--bg-surface);
  border-bottom: 1px solid var(--border-base);
  display: flex;
  justify-content: center; 
  align-items: center;     
  flex-shrink: 0;
}

.contest-menu {
  border-bottom: none !important;
  width: auto;
  height: 48px;
  background: transparent !important;
  display: flex;
  justify-content: center
}

.contest-menu :deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
  font-size: 15px;
  padding: 0 20px; 
  color: var(--text-regular);
  margin-right: 10px;
  border-bottom: 3px solid transparent; 
  transition: color 0.2s;
}

.contest-menu :deep(.el-menu-item:hover) {
  color: var(--accent-primary);
  background-color: transparent !important;
}

.contest-menu :deep(.el-menu-item.is-active) {
  color: var(--accent-primary);
  border-bottom-color: var(--accent-primary); 
  font-weight: 600;
  background-color: rgb(245, 245, 245);
}

.contest-content {
  flex: 1; 
  overflow-y: overlay; 
  overflow-x: hidden; 
  background-color: var(--bg-surface);
  padding-top: 20px; 
}

.content-wrapper {
  padding-bottom: 40px; 
}

.loading-box {
  padding: 40px;
}
</style>