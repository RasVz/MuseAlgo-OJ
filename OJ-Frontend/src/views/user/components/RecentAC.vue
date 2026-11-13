<template>
  <section class="recent-ac-card" v-loading="loading">
    <div class="recent-header">
      <h2>最近通过</h2>
      <span>最近的 AC 提交记录</span>
    </div>
    <div v-if="recentAC.length" class="recent-list">
      <div v-for="(row, index) in recentAC" :key="row.id || index" class="recent-row">
        <div class="recent-main">
          <RouterLink
            v-if="row.problemId"
            :to="{ name: 'ProblemDetail', params: { id: row.problemId } }"
            >{{ row.title }}</RouterLink
          ><span v-else>{{ row.title }}</span
          ><span class="recent-meta"
            >{{ row.language }} <span aria-hidden="true">·</span> {{ row.runTime ?? '—' }} ms</span
          >
        </div>
        <time :datetime="row.createTime">{{ formatTime(row.createTime) }}</time>
      </div>
    </div>
    <el-empty v-else-if="!loading" :image-size="64" description="暂无通过记录" />
  </section>
</template>
<script setup>
import dayjs from 'dayjs'
defineProps({ recentAC: { type: Array, default: () => [] }, loading: Boolean })
const formatTime = (value) => (value ? dayjs(value).format('MM-DD HH:mm') : '—')
</script>
<style scoped>
.recent-ac-card {
  padding: 22px 24px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
}
.recent-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.recent-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.recent-header > span {
  font-size: 12px;
  color: var(--text-secondary);
}
.recent-row {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-light);
}
.recent-row:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}
.recent-main {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.recent-main a,
.recent-main > span:first-child {
  color: var(--text-primary);
  font-size: 14px;
  text-decoration: none;
  overflow-wrap: anywhere;
}
.recent-main a:hover {
  color: var(--el-color-primary);
}
.recent-meta,
time {
  font-size: 12px;
  color: var(--text-secondary);
}
time {
  white-space: nowrap;
}
</style>
