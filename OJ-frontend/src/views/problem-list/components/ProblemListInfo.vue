<template>
  <div class="info-view animate__animated animate__fadeIn">
    <el-row :gutter="24">
      <el-col :span="8" :xs="24">
        <el-card shadow="hover" class="metric-card mb-4">
          <template #header>
            <div class="card-header">
              <span>基础信息</span>
            </div>
          </template>
          <div class="info-list">
            <div class="info-item">
              <span class="label">题单 ID</span>
              <span class="value mono">{{ detail.id }}</span>
            </div>
            
            <div class="info-item">
                <span class="label">创建者</span>
                <div class="value user-value clickable" @click="handleUserClick(detail.creatorId)">
                    <el-avatar :size="20" :src="detail.creatorAvatar" class="mr-2"></el-avatar>
                    <span class="username-text">{{ detail.creatorName }}</span>
                </div>
            </div>

            <div class="info-item">
              <span class="label">类型</span>
              <span class="value">个人精选</span>
            </div>
            <div class="info-item">
              <span class="label">总热度</span>
              <span class="value">{{ detail.favoriteCount || 0 }} 收藏</span>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" class="metric-card">
          <template #header>
            <div class="card-header">
              <span>完成进度</span>
            </div>
          </template>
          <div class="progress-container">
            <el-progress 
              type="dashboard" 
              :percentage="calculateProgress" 
              :color="customColors"
              :width="150"
            >
              <template #default>
                <div class="progress-content">
                  <span class="percentage-value">{{ detail.solvedCount || 0 }}</span>
                  <span class="percentage-label">/ {{ detail.problemCount || 0 }} 题</span>
                </div>
              </template>
            </el-progress>
            <div class="progress-footer">
              <div class="footer-item">
                <span class="dot success"></span> 已通过 {{ detail.solvedCount || 0 }}
              </div>
              <div class="footer-item">
                <span class="dot total"></span> 总题目 {{ detail.problemCount || 0 }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="16" :xs="24">
        <el-card shadow="hover" class="desc-card h-100">
          <template #header>
            <div class="card-header">
              <span>题单简介</span>
            </div>
          </template>
          <div class="markdown-body desc-content" v-if="detail.description">
            {{ detail.description }}
          </div>
          <div class="empty-state" v-else>
            <el-empty description="作者很懒，什么都没写~" :image-size="120"></el-empty>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
// 题单概览卡：展示简介、创建者与完成进度环形图。
// 完成数来自父组件遍历题目时算好的 solvedCount，本组件不重复统计。
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter() // 获取 router 实例

const handleUserClick = (userId) => {
  if (userId) {
    router.push(`/user/${userId}`)
  }
}

const props = defineProps({
  detail: {
    type: Object,
    default: () => ({})
  }
})

// 进度条颜色配置
const customColors = [
  { color: 'var(--accent-danger)', percentage: 20 },
  { color: 'var(--accent-warning)', percentage: 60 },
  { color: 'var(--accent-success)', percentage: 100 },
]

// 分母用 `|| 1` 兜底：空题单直接算会得到 NaN，el-progress 拿到 NaN 会渲染成空白环。
// Math.min(...,100) 兜住"完成数大于题目数"的数据不一致情况（后端统计口径不同时会发生）。
const calculateProgress = computed(() => {
  const total = props.detail.problemCount || 1
  const solved = props.detail.solvedCount || 0
  return Math.min(Math.round((solved / total) * 100), 100)
})
</script>

<style scoped>
/* 工具类 */
.mb-4 { margin-bottom: 20px; }
.h-100 { height: 100%; }

/* 卡片通用 */
.metric-card, .desc-card {
  border-radius: 8px;
  border: none;
}

.card-header {
  font-weight: 600;
  font-size: 16px;
  color: var(--text-primary);
  position: relative;
  padding-left: 10px;
}

.card-header::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 16px;
  background-color: var(--accent-primary);
  border-radius: 2px;
}

/* 信息列表样式 */
.info-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--border-light);
}

.info-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.info-item .label { color: var(--text-secondary); font-size: 14px; }
.info-item .value { color: var(--text-primary); font-weight: 500; font-size: 14px; }

.mono {
  font-family: monospace;
  background: var(--bg-surface-hover);
  padding: 2px 6px;
  border-radius: 4px;
}

.user-value { display: flex; align-items: center; }

/* 进度条样式 */
.progress-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 0;
}

.progress-content {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.percentage-value { font-size: 24px; font-weight: bold; color: var(--text-primary); }
.percentage-label { font-size: 12px; color: var(--text-secondary); }

.progress-footer {
  margin-top: 20px;
  width: 100%;
  display: flex;
  justify-content: space-around;
}

.footer-item {
  font-size: 13px;
  color: var(--text-regular);
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot { width: 8px; height: 8px; border-radius: 50%; }
.dot.success { background-color: var(--accent-success); }
.dot.total { background-color: var(--border-base); }

/* 简介样式 */
.desc-content {
  font-size: 15px;
  line-height: 1.8;
  color: var(--text-regular);
  white-space: pre-wrap;
  padding: 10px 0;
}

.user-value.clickable {
  cursor: pointer; /* 鼠标放上去变成小手 */
  transition: all 0.3s; /* 添加平滑过渡 */
}

.user-value.clickable:hover .username-text {
  color: var(--accent-primary); /* 鼠标悬停时文字变蓝 (Element Plus 主题色) */
  text-decoration: underline; /* 可选：加下划线增强链接感 */
}

/* 防止头像被拉伸 */
.mr-2 { 
  margin-right: 8px; 
  flex-shrink: 0; 
}
</style>
