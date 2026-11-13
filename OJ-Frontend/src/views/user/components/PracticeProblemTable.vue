<template>
  <div class="practice-problem-list">
    <div class="problem-row problem-head" aria-hidden="true">
      <span>题目</span><span>难度</span
      ><span>{{ type === 'passed' ? '通过时间' : '最近尝试' }}</span
      ><span></span>
    </div>
    <div v-for="row in rows" :key="row.problemId" class="problem-row">
      <RouterLink class="problem-name" :to="problemLink(row)"
        ><span class="problem-id">#{{ row.problemId }}</span
        >{{ row.title || '未命名题目' }}</RouterLink
      >
      <span class="problem-difficulty"
        ><i :style="{ background: getDifficultyColor(row.difficulty) }"></i
        >{{ getDifficultyLabel(row.difficulty) }} <small>{{ row.difficulty }}</small></span
      >
      <time :datetime="row[timeField]">{{ formatTime(row[timeField]) }}</time>
      <RouterLink class="problem-action" :to="problemLink(row)"
        >{{ type === 'passed' ? '查看' : '继续练习' }}<span aria-hidden="true"> ↗</span></RouterLink
      >
    </div>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import dayjs from 'dayjs'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'
const props = defineProps({
  rows: { type: Array, default: () => [] },
  type: { type: String, default: 'passed' },
})
const timeField = computed(() => (props.type === 'passed' ? 'solvedAt' : 'lastAttemptAt'))
const formatTime = (value) =>
  value && dayjs(value).isValid() ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'
const problemLink = (row) => ({ name: 'ProblemDetail', params: { id: row.problemId } })
</script>
<style scoped>
.problem-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px 150px 80px;
  align-items: center;
  gap: 20px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border-light);
  font-size: 13px;
}
.problem-row:last-child {
  border: 0;
}
.problem-head {
  color: var(--text-secondary);
  font-size: 12px;
  padding-top: 4px;
  padding-bottom: 12px;
}
.problem-name {
  color: var(--text-primary);
  text-decoration: none;
  overflow-wrap: anywhere;
  line-height: 1.65;
  font-size: 14px;
}
.problem-name:hover {
  color: var(--el-color-primary);
}
.problem-id {
  color: var(--text-secondary);
  margin-right: 10px;
  font-size: 12px;
}
.problem-difficulty {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-regular);
}
.problem-difficulty i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.problem-difficulty small {
  color: var(--text-secondary);
}
time {
  color: var(--text-secondary);
  font-size: 12px;
}
.problem-action {
  text-decoration: none;
  color: var(--el-color-primary);
  text-align: right;
  white-space: nowrap;
}
@media (max-width: 1000px) {
  .problem-row {
    grid-template-columns: minmax(0, 1fr) 125px 125px 68px;
    gap: 12px;
  }
}
@media (max-width: 680px) {
  .problem-head {
    display: none;
  }
  .problem-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 10px;
  }
  .problem-name {
    grid-column: 1 / -1;
  }
  .problem-difficulty {
    grid-column: 1;
  }
  .problem-action {
    grid-column: 2;
    grid-row: 2 / 4;
  }
  time {
    grid-column: 1;
  }
}
</style>
