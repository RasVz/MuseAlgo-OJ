<template>
  <div class="status-banner mb-4">
    <div class="status-card" :class="getStatusClass(detail.status)">
      <div class="status-left">
        <el-icon class="status-icon" :size="24">
          <component :is="getStatusIcon(detail.status)" />
        </el-icon>
        <span class="status-text">{{ getStatusText(detail.status) }}</span>
      </div>

      <div class="status-metrics">
        <div class="metric-item">
          <span class="label">分数</span>
          <span class="value score">{{ detail.score }}</span>
        </div>
        <div class="divider"></div>
        <div class="metric-item">
          <span class="label">耗时</span>
          <span class="value">{{ detail.runTime }} ms</span>
        </div>
        <div class="divider"></div>
        <div class="metric-item">
          <span class="label">内存</span>
          <span class="value">{{ (detail.runMemory / 1024).toFixed(2) }} MB</span>
        </div>
        <div class="divider"></div>
        <div class="metric-item">
          <span class="label">语言</span>
          <span class="value">{{ detail.language }}</span>
        </div>
      </div>
      <div v-if="canReview" class="review-action">
        <el-button type="primary" :loading="reviewing" @click="$emit('review')">
          🤖 AI 复盘
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
// 判题状态横幅：把结果码翻译成一句话 + 图标，并按状态给出排查建议入口。
// 受控展示组件，所有状态解释集中在这里，避免各页面各写一份文案。
import {
  CircleCheckFilled, CircleCloseFilled, Clock, RemoveFilled, Warning, InfoFilled
} from '@element-plus/icons-vue'

defineProps({
  detail: { type: Object, required: true },
  canReview: { type: Boolean, default: false },
  reviewing: { type: Boolean, default: false }
})
defineEmits(['review'])

// 状态码与后端 JudgeStatus 枚举一一对应。这里带上英文原名（Accepted / Wrong Answer…），
// 是为了让学生对照国际通用的术语，方便查资料。
const getStatusText = (s) => ({
  0: '等待判题 (Pending)',
  1: '通过 (Accepted)',
  2: '答案错误 (Wrong Answer)',
  3: '编译错误 (Compile Error)',
  4: '运行错误 (Runtime Error)',
  5: '时间超限 (Time Limit Exceeded)'
}[s] || '未知状态')

const getStatusClass = (s) => ({
  1: 'status-ac',
  2: 'status-wa',
  3: 'status-ce',
  4: 'status-re',
  5: 'status-tle'
}[s] || 'status-pending')

const getStatusIcon = (s) => ({
  1: CircleCheckFilled,
  2: CircleCloseFilled,
  3: RemoveFilled,
  4: Warning,
  5: Clock
}[s] || InfoFilled)
</script>

<style scoped>
.mb-4 { margin-bottom: 20px; }

.status-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 30px;
  border-radius: 8px;
  background: var(--bg-surface);
  box-shadow: var(--shadow-md);
  border-left: 6px solid var(--text-secondary);
}

.status-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-text {
  font-size: 24px;
  font-weight: bold;
  color: var(--text-primary);
}

.status-icon {
  margin-top: 2px;
}

.status-metrics {
  display: flex;
  align-items: center;
  gap: 20px;
  background: var(--bg-surface-soft);
  padding: 8px 20px;
  border-radius: 20px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.metric-item .label {
  font-size: 12px;
  color: var(--text-regular);
  margin-bottom: 2px;
}

.metric-item .value {
  font-family: Consolas, monospace;
  font-weight: bold;
  font-size: 16px;
  color: var(--text-primary);
}

.divider {
  width: 1px;
  height: 24px;
  background-color: var(--border-light);
}

.status-ac { border-left-color: var(--accent-success); background: var(--accent-success-light); }
.status-ac .status-text, .status-ac .status-icon { color: var(--accent-success); }
.status-ac .value.score { color: var(--accent-success); }
.status-wa { border-left-color: var(--accent-danger); background: var(--accent-danger-light); }
.status-wa .status-text, .status-wa .status-icon { color: var(--accent-danger); }
.status-wa .value.score { color: var(--accent-danger); }
.status-ce { border-left-color: var(--accent-warning); background: var(--accent-warning-light); }
.status-ce .status-text, .status-ce .status-icon { color: var(--accent-warning); }
.status-tle { border-left-color: var(--accent-warning); background: var(--accent-warning-light); }
.status-tle .status-text { color: var(--accent-warning); }
.status-re { border-left-color: var(--accent-info); background: var(--accent-info-light); }
.status-re .status-text { color: var(--accent-info); }

.review-action {
  margin-left: auto;
  align-self: center;
}
</style>
