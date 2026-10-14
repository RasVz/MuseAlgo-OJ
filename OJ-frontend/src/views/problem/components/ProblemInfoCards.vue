<template>
  <div class="info-cards-container">
    
    <el-card shadow="never" class="info-card">
      <template #header>
        <div class="card-header">
          <span><el-icon><DataLine /></el-icon> 统计信息</span>
        </div>
      </template>
      <div class="stats-grid">
        <div class="stat-item">
          <div class="label">提交</div>
          <div class="value">{{ problem.totalCount || 0 }}</div>
        </div>
        <div class="stat-item">
          <div class="label">通过</div>
          <div class="value">{{ problem.passCount || 0 }}</div>
        </div>
        <div class="stat-item">
          <div class="label">时间限制</div>
          <div class="value">{{ problem.timeLimit }}ms</div>
        </div>
        <div class="stat-item">
          <div class="label">内存限制</div>
          <div class="value">{{ problem.memoryLimit }}MB</div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="info-card">
      <template #header>
        <div class="card-header">
          <span><el-icon><InfoFilled /></el-icon> 题目信息</span>
        </div>
      </template>
      
      <div class="info-list">
        <div class="info-row">
          <span class="label">ID:</span>
          <span class="value">{{ problem.id }}</span>
        </div>
        <div class="info-row">
          <span class="label">作者:</span>
          <span class="value">{{ problem.authorName || 'System' }}</span>
        </div>
        <div class="info-row">
          <span class="label">难度:</span>
          <span class="badge" :style="{ color: getDiffColor(problem.difficulty) }">
            {{ getDifficultyLabel(problem.difficulty) }} {{ problem.difficulty ?? '-' }}
          </span>
        </div>
        <div class="info-row">
          <span class="label">分数:</span>
          <span class="value">100</span>
        </div>
      </div>

      <el-divider class="card-divider" />

      <div class="action-buttons">
        <el-button class="full-btn" @click="$emit('view-records')">
          <el-icon><List /></el-icon> 提交记录
        </el-button>
        <el-button class="full-btn" @click="$emit('view-solutions')">
          <el-icon><Document /></el-icon> 题解
        </el-button>
        <el-tooltip content="功能即将上线" placement="top">
          <span class="full-btn-wrap">
            <el-button class="full-btn" disabled>
              <el-icon><ChatLineSquare /></el-icon> 题目反馈
            </el-button>
          </span>
        </el-tooltip>
      </div>
    </el-card>

    <el-card shadow="never" class="info-card">
      <template #header>
        <div class="card-header">
          <span><el-icon><CollectionTag /></el-icon> 算法标签</span>
          <el-switch 
            v-model="showTags" 
            size="small"
            style="margin-left: auto"
          />
        </div>
      </template>
      
      <div v-if="showTags" class="tags-wrapper">
        <template v-if="parsedTags.length">
          <el-tag 
            v-for="tag in parsedTags" 
            :key="tag" 
            class="tag-item"
            effect="plain"
          >
            {{ tag }}
          </el-tag>
        </template>
        <span v-else class="no-tags">暂无标签</span>
      </div>
      <div v-else class="tags-placeholder">
        <span class="blur-text">点击右上角显示标签</span>
      </div>
    </el-card>

  </div>
</template>

<script setup>
// 题目信息侧栏：难度、标签、通过率与统计，以及提交记录/我的提交/题解的跳转入口。
// 侧栏是「看完题面之后决定下一步做什么」的地方，因此入口按用户意图分组而非按数据表分组。
import { ref, computed } from 'vue'
import { DataLine, InfoFilled, CollectionTag, List, Document, ChatLineSquare } from '@element-plus/icons-vue'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const props = defineProps({
  problem: { type: Object, default: () => ({}) }
})
defineEmits(['view-records', 'view-my-submission', 'view-solutions', 'feedback'])

const showTags = ref(false)

// 标签默认折叠（showTags=false）：标签是辅助信息，
// 默认展示会挤掉难度、通过率这些更该被看到的统计。
// tags 字段历史上有 JSON 字符串与数组两种形态，解析失败按单个标签处理。
const parsedTags = computed(() => {
  const t = props.problem.tags
  if (!t) return []
  if (Array.isArray(t)) return t
  try {
    return JSON.parse(t)
  } catch {
    return [t] // fallback
  }
})

// 辅助函数
const getDiffColor = getDifficultyColor
</script>

<style scoped>
.info-cards-container {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.info-card {
  border: 1px solid var(--border-light);
  border-radius: 4px;
}
/* 调整卡片头部更紧凑 */
.info-card :deep(.el-card__header) {
  padding: 10px 15px;
  background-color: #fafafa;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.card-header {
  display: flex;
  align-items: center;
  font-weight: bold;
  font-size: 14px;
  color: var(--text-primary);
  gap: 6px;
}

/* 统计表格样式 */
.stats-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
}
.stat-item {
  text-align: center;
}
.stat-item .label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.stat-item .value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 信息列表样式 */
.info-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
}
.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.info-row .label { color: var(--text-regular); }
.info-row .value { color: var(--text-primary); font-weight: 500; }
.badge {
  border: 1px solid currentColor;
  border-radius: 4px;
  background: var(--bg-input);
  padding: 2px 6px;
  font-size: 12px;
  font-weight: 600;
}

.card-divider { margin: 15px 0; }

/* 按钮组 */
.action-buttons {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.full-btn {
  width: 100%;
  justify-content: flex-start; /* 图标文字左对齐 */
  margin-left: 0 !important;
}
.full-btn-wrap {
  display: block;
  width: 100%;
}

/* 标签区域 */
.tags-wrapper {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tags-placeholder {
  text-align: center;
  padding: 10px 0;
  color: var(--text-secondary);
  font-size: 12px;
  background: #fdfdfd;
  border-radius: 4px;
}
</style>
