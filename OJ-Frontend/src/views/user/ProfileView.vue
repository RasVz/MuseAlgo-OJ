<template>
  <div class="profile-dashboard">
    <ProfileIntroduction :user="user" :is-self="isSelf" @updated="applyPatch" />
    <section class="learning-overview">
      <div class="overview-header">
        <h2>学习概况</h2>
        <RouterLink v-if="isSelf" to="/user/practice"
          >查看练习分析 <span aria-hidden="true">↗</span></RouterLink
        >
      </div>
      <ProfileStatsGrid :stats="stats" />
    </section>
    <section v-if="ratingError" class="section-failure" role="status">
      积分趋势加载失败 <el-button text type="primary" @click="loadRating">重试</el-button>
    </section>
    <RatingChart
      v-else-if="!ratingLoading"
      :data="ratingHistory"
      :registered-at="user.createTime || ''"
    />
    <UserHeatmap />
  </div>
</template>
<script setup>
import { ref, onMounted, inject, computed } from 'vue'
import { getUserRatingHistory } from '@/api/user'
import ProfileIntroduction from './components/ProfileIntroduction.vue'
import ProfileStatsGrid from './components/ProfileStatsGrid.vue'
import UserHeatmap from './components/UserHeatmap.vue'
import RatingChart from './components/RatingChart.vue'
const { user, isSelf, applyPatch } = inject('profileContext')
const stats = computed(() => ({
  submissions: user.value.submitCount,
  accepted: user.value.acceptedCount,
  rating: user.value.rating,
}))
const ratingHistory = ref([]),
  ratingError = ref(false),
  ratingLoading = ref(true)
async function loadRating() {
  ratingLoading.value = true
  ratingError.value = false
  try {
    const result = await getUserRatingHistory(user.value.id)
    if (result.code !== 200) throw new Error()
    ratingHistory.value = result.data || []
  } catch {
    ratingError.value = true
  } finally {
    ratingLoading.value = false
  }
}
onMounted(loadRating)
</script>
<style scoped>
.profile-dashboard {
  display: flex;
  flex-direction: column;
  gap: 22px;
  min-width: 0;
}
.overview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.overview-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.overview-header a {
  color: var(--el-color-primary);
  text-decoration: none;
  font-size: 13px;
}
.section-failure {
  padding: 24px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  color: var(--text-secondary);
  background: var(--bg-surface);
  font-size: 14px;
}
</style>
