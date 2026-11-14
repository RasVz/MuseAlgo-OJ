<template>
  <div class="status-banner" :class="getStatusClass(detail.status)">
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
  </div>
</template>

<script setup>
// 判题状态横幅：把结果码翻译成一句话 + 图标，并按状态给出排查建议入口。
// 受控展示组件，所有状态解释集中在这里，避免各页面各写一份文案。
import {
  CircleCheckFilled, CircleCloseFilled, Clock, RemoveFilled, Warning, InfoFilled
} from '@element-plus/icons-vue'

defineProps({
  detail: { type: Object, required: true }
})

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
.status-banner {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 12px 32px;
  padding: 14px 24px;
  border-radius: 0;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-app);
}

.status-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-text {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}

.status-icon {
  margin-top: 2px;
}

.status-metrics {
  display: flex;
  align-items: center;
  gap: 20px;
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
  font-family: var(--font-mono);
  font-weight: bold;
  font-size: 16px;
  color: var(--text-primary);
}

.divider {
  width: 1px;
  height: 24px;
  background-color: var(--border-light);
}

.status-ac { background: var(--accent-success-light); }
.status-ac .status-text, .status-ac .status-icon { color: var(--accent-success); }
.status-ac .value.score { color: var(--accent-success); }
.status-wa { background: var(--accent-danger-light); }
.status-wa .status-text, .status-wa .status-icon { color: var(--accent-danger); }
.status-wa .value.score { color: var(--accent-danger); }
.status-ce { background: var(--accent-warning-light); }
.status-ce .status-text, .status-ce .status-icon { color: var(--accent-warning); }
.status-tle { background: var(--accent-warning-light); }
.status-tle .status-text { color: var(--accent-warning); }
.status-re { background: var(--accent-info-light); }
.status-re .status-text { color: var(--accent-info); }

/* 深色主题：上面几组浅色强调底在深色页面上会把 --text-primary 的数值压成浅底浅字，
   这里换成同色系低透明度深底；状态文字颜色（accent）保持不变。 */
.dark .status-ac { background: rgba(22,163,74,0.14); }
.dark .status-wa { background: rgba(220,38,38,0.14); }
.dark .status-ce,
.dark .status-tle { background: rgba(217,119,6,0.16); }
.dark .status-re { background: rgba(14,165,233,0.14); }
</style>
