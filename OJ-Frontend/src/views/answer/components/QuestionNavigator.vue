<template>
  <div class="question-navigator">
    <div class="navigator-legend">
      <span><i class="dot dot-answered"></i>已答</span>
      <span><i class="dot dot-correct"></i>正确</span>
      <span><i class="dot dot-wrong"></i>错误</span>
      <span><i class="dot dot-pending"></i>判题中</span>
    </div>

    <div class="navigator-grid">
      <button
        v-for="item in items"
        :key="item.key"
        type="button"
        class="nav-button"
        :class="[`status-${item.status.toLowerCase()}`, { active: item.key === activeKey }]"
        :aria-label="`${item.label}，${statusLabel(item)}`"
        :aria-current="item.key === activeKey ? 'true' : undefined"
        @click="$emit('select', item.key)"
      >
        {{ item.shortLabel || item.label }}
        <i v-if="item.pending" class="pending-dot" aria-hidden="true"></i>
      </button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  items: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' }
})

defineEmits(['select'])

const STATUS_LABELS = {
  UNTOUCHED: '未作答',
  ANSWERED: '已作答',
  CORRECT: '正确',
  INCORRECT: '错误',
  ATTEMPTED: '已尝试',
  SOLVED: '已通过',
  PENDING: '判题中',
  SUBMITTED: '已提交'
}

const statusLabel = item => STATUS_LABELS[item.status] || STATUS_LABELS.UNTOUCHED
</script>

<style scoped>
.question-navigator { display: flex; flex-direction: column; gap: 10px; }
.navigator-legend {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 11px;
}
.navigator-legend span { display: flex; align-items: center; gap: 4px; }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; background: var(--border-hover); }
.dot-answered { background: var(--accent-primary); }
.dot-correct { background: var(--accent-success, #67c23a); }
.dot-wrong { background: var(--accent-danger, #f56c6c); }
.dot-pending { background: var(--accent-warning, #e6a23c); }

.navigator-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(42px, 1fr));
  gap: 8px;
}
.nav-button {
  position: relative;
  height: 38px;
  border: 1px solid var(--border-base);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--text-regular);
  font-size: 13px;
  cursor: pointer;
}
.nav-button:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.nav-button.active {
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 1px var(--accent-primary) inset;
  font-weight: 700;
}
.nav-button.status-answered { background: color-mix(in srgb, var(--accent-primary) 12%, var(--bg-surface)); }
.nav-button.status-correct { background: color-mix(in srgb, var(--accent-success, #67c23a) 16%, var(--bg-surface)); }
.nav-button.status-incorrect { background: color-mix(in srgb, var(--accent-danger, #f56c6c) 14%, var(--bg-surface)); }
.nav-button.status-attempted { background: color-mix(in srgb, var(--accent-warning, #e6a23c) 14%, var(--bg-surface)); }
.nav-button.status-solved { background: color-mix(in srgb, var(--accent-success, #67c23a) 22%, var(--bg-surface)); font-weight: 700; }
.nav-button.status-pending { background: color-mix(in srgb, var(--accent-warning, #e6a23c) 18%, var(--bg-surface)); }
.pending-dot {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent-warning, #e6a23c);
}
</style>
