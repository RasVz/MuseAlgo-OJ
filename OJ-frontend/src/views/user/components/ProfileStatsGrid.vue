<template>
  <div class="stats-overview-grid">
    <div class="overview-card blue-card">
      <div class="icon-box"><el-icon><Cpu /></el-icon></div>
      <div class="info-box">
        <div class="ov-num">{{ stats.submissions || 0 }}</div>
        <div class="ov-label">提交总数</div>
      </div>
    </div>
    <div class="overview-card green-card">
      <div class="icon-box"><el-icon><CircleCheckFilled /></el-icon></div>
      <div class="info-box">
        <div class="ov-num">{{ stats.accepted || 0 }}</div>
        <div class="ov-label">通过题目</div>
      </div>
    </div>
    <div class="overview-card orange-card">
      <div class="icon-box"><el-icon><Trophy /></el-icon></div>
      <div class="info-box">
        <div class="ov-num">{{ stats.rating != null ? stats.rating : '—' }}</div>
        <div class="ov-label">竞赛积分</div>
      </div>
    </div>
    <div class="overview-card purple-card">
      <div class="icon-box"><svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg></div>
      <div class="info-box">
        <div class="ov-num">{{ stats.likes || 0 }}</div>
        <div class="ov-label">获赞数</div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 资料页统计概览：提交数、通过数、Rating 与获赞。纯展示，数据由父组件算好传入。
import { Cpu, CircleCheckFilled, Trophy } from '@element-plus/icons-vue'

defineProps({
  stats: {
    type: Object,
    default: () => ({})
  }
})
</script>

<style scoped>
.stats-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 25px;
}
.overview-card {
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 15px;
  background: var(--bg-surface);
  box-shadow: 0 2px 12px rgba(0,0,0,0.03);
  transition: transform 0.2s;
}
.overview-card:hover { transform: translateY(-3px); }

.blue-card .icon-box { color: #409eff; background: #ecf5ff; }
.green-card .icon-box { color: #67c23a; background: #f0f9eb; }
.orange-card .icon-box { color: #e6a23c; background: #fdf6ec; }
.purple-card .icon-box { color: #a0cfff; background: #f0f5ff; }

.icon-box { width: 48px; height: 48px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 24px; }
.heart-icon { width: 24px; height: 24px; fill: currentColor; stroke: currentColor; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.ov-num { font-size: 22px; font-weight: bold; color: var(--text-primary); line-height: 1.2; }
.ov-label { font-size: 12px; color: var(--text-secondary); }
</style>
