<template>
  <div class="list-card">
    <div class="card-cover" :style="{ background: gradient }">
      <el-icon class="cover-icon"><List /></el-icon>
      <span class="cover-type">OFFICIAL</span>
    </div>
    <div class="card-body">
      <h4 class="card-title">{{ data.title }}</h4>
      <div class="card-meta">
        <span><el-icon><Document /></el-icon> {{ data.problemCount }} 题</span>
        <span><el-icon><User /></el-icon> {{ data.favoriteCount ?? data.views ?? 0 }} 收藏</span>
      </div>
      <div class="card-footer">
        <el-tag size="small" effect="plain" type="info">算法入门</el-tag>
      </div>
    </div>
  </div>
</template>

<script setup>
// 官方题单卡片：纯展示。渐变色由父级按 ID 传入，本组件不生成颜色，
// 保证同一题单在列表中的配色稳定。
defineProps(['data', 'gradient'])
</script>

<style scoped>
.list-card { border: 1px solid var(--border-light); border-radius: 12px; overflow: hidden; cursor: pointer; transition: 0.3s; margin-bottom: 20px; background: var(--bg-surface); }
.list-card:hover { transform: translateY(-5px); box-shadow: var(--shadow-lg); }
.card-cover { height: 110px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--text-on-accent); }
.cover-icon { font-size: 32px; margin-bottom: 5px; }
.cover-type { font-size: 13px; font-weight: bold; letter-spacing: 2px; }
.card-body { padding-left: 15px; }
.card-title { font-size: 18px; font-weight: bold; height: 30px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; color: var(--text-primary); }
.card-meta { display: flex; gap: 10px; color: var(--text-secondary); font-size: 14px; margin: 10px 0; }
.card-footer { border-top: 1px solid var(--border-light); padding-top: 8px; padding-bottom: 8px; }
</style>