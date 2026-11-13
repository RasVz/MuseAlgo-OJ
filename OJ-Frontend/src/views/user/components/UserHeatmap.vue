<template>
  <section class="heatmap-card" v-loading="loading">
    <div class="heatmap-header">
      <h2>练习足迹</h2>
      <span v-if="!error && !loading"
        >过去一年共提交 <strong>{{ total }}</strong> 次</span
      >
    </div>
    <div v-if="error" class="heatmap-error" role="status">
      {{ error }}<el-button text type="primary" @click="load">重试</el-button>
    </div>
    <template v-else-if="!loading">
      <div class="heatmap-scroll" aria-label="最近365天提交日历">
        <div class="calendar-months">
          <span v-for="month in months" :key="month.column" :style="{ gridColumn: month.column }">{{
            month.label
          }}</span>
        </div>
        <div class="calendar-body">
          <div class="week-labels">
            <span>日</span><span>一</span><span>二</span><span>三</span><span>四</span
            ><span>五</span><span>六</span>
          </div>
          <div class="heatmap-grid">
            <div
              v-for="(day, index) in calendar"
              :key="day?.date || index"
              class="heatmap-cell"
              :class="day ? `heat-level-${level(day.count)}` : 'heat-padding'"
              :title="day ? `${day.date} · ${day.count} 次提交` : undefined"
              :aria-label="day ? `${day.date}，${day.count}次提交` : undefined"
              :tabindex="day?.count > 0 ? 0 : undefined"
            ></div>
          </div>
        </div>
      </div>
      <div class="heatmap-footer">
        <span>最近 365 天</span>
        <div class="heatmap-legend">
          <span>少</span><i v-for="n in 5" :key="n" :class="`heat-level-${n - 1}`"></i
          ><span>多</span>
        </div>
      </div>
    </template>
  </section>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getUserHeatmap } from '@/api/user'
const props = defineProps({ userId: { type: [Number, String], default: null } })
const route = useRoute(),
  total = ref(0),
  days = ref([]),
  loading = ref(true),
  error = ref('')
const userId = computed(() => props.userId ?? route.params.id)
const formatDate = (d) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const calendar = computed(() => {
  const counts = new Map(days.value.map((day) => [day.date, Number(day.count) || 0]))
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  start.setDate(start.getDate() - 364)
  const result = Array(start.getDay()).fill(null)
  for (let i = 0; i < 365; i++) {
    const day = new Date(start)
    day.setDate(start.getDate() + i)
    const date = formatDate(day)
    result.push({ date, count: counts.get(date) || 0 })
  }
  return result
})
const months = computed(() => {
  const result = []
  let last = ''
  for (let i = 0; i < calendar.value.length; i += 7) {
    const day = calendar.value.slice(i, i + 7).find(Boolean)
    const month = day?.date.slice(5, 7)
    if (month && month !== last) {
      result.push({ column: Math.floor(i / 7) + 1, label: `${Number(month)}月` })
      last = month
    }
  }
  return result
})
const level = (n) => (n === 0 ? 0 : n <= 2 ? 1 : n <= 4 ? 2 : n <= 6 ? 3 : 4)
let sequence = 0
async function load() {
  const seq = ++sequence
  loading.value = true
  error.value = ''
  try {
    const result = await getUserHeatmap(userId.value)
    if (seq !== sequence) return
    if (result.code !== 200 || !result.data) throw new Error('活跃日历加载失败')
    days.value = Array.isArray(result.data.days) ? result.data.days : []
    total.value = Number(result.data.total) || 0
  } catch (cause) {
    if (seq === sequence)
      error.value = Number(cause.code) === 401 ? '登录后可查看练习足迹' : '活跃日历加载失败，请重试'
  } finally {
    if (seq === sequence) loading.value = false
  }
}
watch(userId, load, { immediate: true })
</script>
<style scoped>
.heatmap-card {
  padding: 22px 24px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
  min-width: 0;
}
.heatmap-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
}
.heatmap-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.heatmap-header > span {
  font-size: 12px;
  color: var(--text-secondary);
}
.heatmap-header strong {
  color: var(--text-regular);
  font-weight: 600;
}
.heatmap-scroll {
  overflow-x: auto;
  padding-bottom: 6px;
}
.calendar-months {
  display: grid;
  grid-auto-columns: 13px;
  grid-auto-flow: column;
  margin-left: 24px;
  margin-bottom: 8px;
  height: 16px;
  font-size: 10px;
  color: var(--text-secondary);
  width: max-content;
}
.calendar-months span {
  white-space: nowrap;
}
.calendar-body {
  display: flex;
  gap: 10px;
  width: max-content;
}
.week-labels {
  display: grid;
  grid-template-rows: repeat(7, 10px);
  gap: 3px;
  font-size: 9px;
  color: var(--text-secondary);
  width: 14px;
  line-height: 10px;
}
.heatmap-grid {
  display: grid;
  grid-template-rows: repeat(7, 10px);
  grid-auto-flow: column;
  grid-auto-columns: 10px;
  gap: 3px;
}
.heatmap-cell,
.heatmap-legend i {
  border-radius: 2px;
}
.heatmap-cell:focus {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
.heat-level-0 {
  background: var(--bg-app);
  box-shadow: inset 0 0 0 1px var(--border-light);
}
.heat-level-1 {
  background: #b5dfca;
}
.heat-level-2 {
  background: #71bd99;
}
.heat-level-3 {
  background: #3f946d;
}
.heat-level-4 {
  background: #216442;
}
.heat-padding {
  background: transparent;
}
.heatmap-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  color: var(--text-secondary);
  font-size: 11px;
}
.heatmap-legend {
  display: flex;
  align-items: center;
  gap: 4px;
}
.heatmap-legend i {
  width: 10px;
  height: 10px;
}
.heatmap-error {
  font-size: 13px;
  color: var(--text-secondary);
  padding: 12px 0;
}
:global(.dark) .heat-level-0 {
  background: #202932;
}
:global(.dark) .heat-level-1 {
  background: #244d3d;
}
:global(.dark) .heat-level-2 {
  background: #3b7958;
}
:global(.dark) .heat-level-3 {
  background: #61aa80;
}
:global(.dark) .heat-level-4 {
  background: #99d1ad;
}
</style>
