<template>
  <div class="profile-stats-grid">
    <div v-for="item in items" :key="item.label" class="profile-stat">
      <span class="stat-label">{{ item.label }}</span
      ><strong>{{ item.value }}</strong
      ><span class="stat-note">{{ item.note }}</span>
    </div>
  </div>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ stats: { type: Object, default: () => ({}) } })
const items = computed(() => [
  { label: '提交总数', value: props.stats.submissions ?? '—', note: '累计提交次数' },
  { label: '通过题目', value: props.stats.accepted ?? '—', note: '按题目去重' },
  { label: '当前 Rating', value: props.stats.rating ?? '—', note: '竞赛积分' },
])
</script>
<style scoped>
.profile-stats-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}
.profile-stat {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
  min-width: 0;
}
.stat-label {
  font-size: 13px;
  color: var(--text-regular);
}
.profile-stat strong {
  color: var(--text-primary);
  font-size: 28px;
  line-height: 1.2;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.stat-note {
  color: var(--text-secondary);
  font-size: 12px;
}
@media (max-width: 680px) {
  .profile-stats-grid {
    gap: 8px;
  }
  .profile-stat {
    padding: 16px 12px;
  }
  .profile-stat strong {
    font-size: 24px;
  }
  .stat-note {
    font-size: 11px;
  }
}
</style>
