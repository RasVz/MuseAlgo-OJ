<template>
  <div class="profile-dashboard" v-loading="loading">
    <el-alert
      v-if="profileHidden"
      type="warning"
      :closable="false"
      title="该用户未公开主页"
      description="对方关闭了主页公开，登录后可查看完整主页。"
      style="margin-bottom: 20px;"
    />

    <ProfileStatsGrid :stats="stats" />

    <RatingHistogram v-if="ratingBuckets.length" :data="ratingBuckets" style="margin-bottom: 20px;" />
    
    <RatingChart :data="ratingHistory" style="margin: 20px 0;" />

    <UserHeatmap :total="stats.submissions" />

    <RecentAC :recentAC="recentAC" />

    <SkillRadar :data="radarData" style="margin: 20px 0;" />

  </div>
</template>

<script setup>
// 用户资料页：统计概览 + 能力雷达 + 提交热力图 + Rating 趋势 + 最近 AC + Rating 分布。
// 页面要展示别人的资料，所以所有接口都以路由里的 userId 为准，不读本地 store。
import { ref, onMounted, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import { getUserInfo, getUserRadar, getRecentAC, getUserRatingHistory } from '@/api/user'
import ProfileStatsGrid from './components/ProfileStatsGrid.vue'
import UserHeatmap from './components/UserHeatmap.vue'
import SkillRadar from '@/components/SkillRadar.vue'
import RatingChart from './components/RatingChart.vue'
import RecentAC from './components/RecentAC.vue'
import RatingHistogram from '@/components/RatingHistogram.vue'

const route = useRoute()

// 定义响应式数据 (给个初始默认值，防止页面加载时报错)
const stats = ref({
  submissions: 0,
  accepted: 0,
  rating: null,
  likes: 0
})

const radarData = ref([])
const recentAC = ref([])
const ratingHistory = ref([])
const loading = ref(true)
const profileHidden = ref(false)

// Rating 直方图按 100 分一档分桶：原始 Rating 是逐次提交的快照，
// 逐条画会得到几千根柱子，完全看不出分布。
const ratingBuckets = computed(() => {
  if (!ratingHistory.value.length) return []
  const buckets = {}
  ratingHistory.value.forEach(r => {
    const bucket = Math.floor((r.rating || 800) / 100) * 100
    buckets[bucket] = (buckets[bucket] || 0) + 1
  })
  return Object.entries(buckets).map(([rating, count]) => ({ rating: Number(rating), count }))
          .sort((a, b) => a.rating - b.rating)
})

// 获取数据的核心逻辑
const fetchData = async () => {
  const targetId = route.params.id 
  if (!targetId) return
  profileHidden.value = false
  
  try {
    const res = await getUserInfo(targetId)
    if (res.code === 200) {
      const backendData = res.data
      
      // 把后端的字段映射给前端组件
      stats.value = {
        submissions: backendData.submitCount || 0,
        accepted: backendData.acceptedCount || 0,
        rating: backendData.rating ?? null,
        likes: backendData.likeCount || 0
      }
    }
  } catch (error) {
    // 用户选择「不公开资料」时接口返回 403：切到隐藏态而不是报错页，
// 否则会让人误以为账号不存在。
    if (error?.response?.data?.code === 403) {
      profileHidden.value = true
      return
    }
    console.error("获取用户统计失败", error)
  }

  try {
    const res = await getUserRadar(targetId)
    if (res.code === 200 && res.data) {
      // 雷达图把后端的原始 Rating 线性映射到 0~100：
// 雷达图各维度共用一套刻度，不归一的话不同维度间无法比较。
      radarData.value = res.data.map(item => ({
        name: item.label,
        value: Math.round(Math.max(0, Math.min(100, (item.rating - 800) * 0.1)))
      }))
    }
  } catch (err) {
    console.error("获取雷达图失败", err)
  }

  try {
    const res = await getRecentAC(targetId)
    if (res.code === 200) recentAC.value = res.data || []
  } catch (err) { console.error("获取最近AC失败", err) }

  try {
    const res = await getUserRatingHistory(targetId)
    if (res.code === 200) ratingHistory.value = res.data || []
  } catch (err) { console.error("获取Rating历史失败", err) }

  loading.value = false
}

// 四组数据分四个 try/catch 串行拉取：任一失败只降级对应的那块图表，
// 不影响其余指标展示（这里没有用 Promise.all 就是为了这个）。
// 页面一加载就调用
onMounted(() => {
  fetchData()
})

// 从用户 A 的页面跳到用户 B 时 ID 变了必须重新拉数据：
// 组件可能被复用（同一路由不同参数），不会重新执行 onMounted。
watch(() => route.params.id, (newId) => {
  if (newId) fetchData()
})
</script>
