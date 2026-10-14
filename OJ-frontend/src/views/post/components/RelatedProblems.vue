<template>
  <section v-if="problems?.length" class="related-problems-section">
    <div class="related-section-heading">
      <span>关联练习</span>
      <span>读完动手做一做</span>
    </div>
    <div class="related-problem-list">
      <button v-for="problem in problems" :key="problem.id" class="related-problem-item" @click="$emit('open', problem.id)">
        <span class="related-problem-id">#{{ problem.id }}</span>
        <span class="related-problem-title">{{ problem.title || '题目已不可用' }}</span>
        <span v-if="problem.difficulty" class="related-problem-difficulty">{{ problem.difficulty }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
// 文章关联题目列表：展示题解/文章引用的题目，点击上抛 open 事件由父组件跳转。
defineProps({
  problems: { type: Array, default: () => [] }
})
defineEmits(['open'])
</script>

<style scoped>
.related-problems-section { margin: 26px 0; padding: 18px 20px; border: 1px solid var(--el-color-primary-light-8); border-radius: var(--radius-md); background: var(--bg-surface); }

.related-section-heading { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; color: var(--accent-primary); font-size: 15px; font-weight: 650; }
.related-section-heading span:last-child { color: var(--text-secondary); font-size: 12px; font-weight: 400; }

.related-problem-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }

.related-problem-item { display: flex; min-width: 0; align-items: center; gap: 8px; padding: 10px; border: 1px solid var(--border-light); border-radius: var(--radius-sm); background: var(--bg-surface); color: var(--text-primary); cursor: pointer; text-align: left; transition: border-color .15s, transform .15s; }
.related-problem-item:hover { border-color: var(--accent-primary); transform: translateY(-1px); }

.related-problem-id { flex: 0 0 auto; color: var(--accent-primary); font-size: 12px; font-weight: 600; }
.related-problem-title { overflow: hidden; flex: 1; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.related-problem-difficulty { color: var(--text-secondary); font-size: 12px; }

@media (max-width: 640px) { .related-problem-list { grid-template-columns: 1fr; } }
</style>
