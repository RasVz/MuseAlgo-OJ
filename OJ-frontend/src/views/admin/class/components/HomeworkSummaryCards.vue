<template>
  <div class="summary-grid">
    <div class="summary-card">
      <div class="summary-label">学生数</div>
      <div class="summary-value">{{ summary.studentCount }}</div>
    </div>
    <div class="summary-card">
      <div class="summary-label">题目数</div>
      <div class="summary-value">{{ summary.problemCount }}</div>
    </div>
    <div class="summary-card">
      <div class="summary-label">总提交数</div>
      <div class="summary-value">{{ summary.totalSubmissions }}</div>
    </div>
    <div class="summary-card">
      <div class="summary-label">最终 AC 格子数</div>
      <div class="summary-value success">{{ summary.finalAcCount }}</div>
    </div>
    <div class="summary-card">
      <div class="summary-label">活跃学生数</div>
      <div class="summary-value">{{ summary.activeStudentCount }}</div>
    </div>
    <div class="summary-card">
      <div class="summary-label">总单元格</div>
      <div class="summary-value">{{ summary.totalCellCount }}</div>
    </div>
  </div>
</template>

<script setup>
// 作业概览卡片：纯展示组件，所有统计数字由父组件算好后经 summary 传入。
// 不自行发请求——同一份 summary 会被作业详情页与学情页复用，各自取数会造成口径不一致。
defineProps({
  summary: {
    type: Object,
    required: true
  }
})
</script>

<style scoped>
.summary-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  padding: 18px 16px;
  border: 1px solid var(--border-light);
  border-radius: 12px;
  background: linear-gradient(180deg, var(--bg-surface) 0%, var(--accent-primary-light) 100%);
}

.summary-label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 10px;
}

.summary-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1;
}

.summary-value.success {
  color: var(--accent-success);
}

@media (max-width: 1400px) {
  .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 768px) {
  .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
