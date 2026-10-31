<template>
  <div class="shared-table-container">
    <el-table 
      :header-cell-style="{ background: 'var(--bg-surface-hover)' }"
      :empty-text="emptyText"
      :data="problemList" 
      @row-click="handleRowClick"
      style="width: 100%" class="clickable-table"
    >
      <el-table-column label="题单名称" min-width="280">
        <template #default="{ row }">
          <div class="list-item-cell">
            <div class="list-icon" :style="{ background: getGradient(row.id) }">
              <el-icon><List /></el-icon>
            </div>
            <div class="list-info">
              <div class="list-title-link">{{ row.title }}</div>
              <div class="list-tags">
                <el-tag v-for="tag in parseTags(row.tags)" :key="tag" size="small" type="info" effect="plain" class="mr-1">
                  {{ tag }}
                </el-tag>
              </div>
            </div>
          </div>
        </template>
      </el-table-column>
      
      <el-table-column label="创建者" width="180">
        <template #default="{ row }">
          <div class="creator-box" @click.stop="goUser(row)">
            <el-avatar :size="24" :src="row.creatorAvatar" />
            <span class="creator-name">{{ row.creatorName }}</span>
          </div>
        </template>
      </el-table-column>
      
      <el-table-column prop="problemCount" label="题目数" width="100" align="center" />
      
      <el-table-column v-if="showFavorites" label="收藏数" width="120" align="center">
        <template #default="{ row }">
          <span style="color: var(--text-secondary);">
            {{ row.favoriteCount || 0 }}
          </span>
        </template>
      </el-table-column>
      
      <el-table-column label="更新时间" width="180" align="right">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.updateTime || row.createTime) }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
// 用户共享题单列表：紧凑表格形态，展示创建者以便跳转其主页。
import { List } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const emptyText = '暂无数据'

defineProps({
  problemList: {
    type: Array,
    default: () => []
  },
  showFavorites: {
    type: Boolean,
    default: true
  },
})

// 创建者区域用 @click.stop：否则点创建者会连带触发整行的"进入详情"，
// 用户想去别人主页却被扔进题单里。
const goUser = (row) => {
  const userId = row.creatorId
  if (userId) {
    router.push({ name: 'UserProfile', params: { id: userId } })
  }
}

// 处理整行点击（进入题单详情）
const handleRowClick = (row) => {
  emit('detail', row)
}

const emit = defineEmits(['detail'])

// 标签字段是 JSON 字符串，解析失败按"无标签"处理而不是抛错——
// 单条脏数据不该让整张表渲染失败。
const parseTags = (tags) => {
  if (!tags) return []
  if (Array.isArray(tags)) return tags
  try {
    return JSON.parse(tags)
  } catch {
    return [] 
  }
}

// 辅助：时间格式化
const formatTime = (t) => {
  if (!t) return '-'
  const str = String(t)
  return str.replace('T', ' ').substring(0, 16)
}

const getGradient = () => `linear-gradient(135deg, #a7f3d0 0%, #7dd3fc 100%)`
</script>

<style scoped>
.shared-table-container {
  background: var(--bg-surface);
  border-radius: 8px;
}
.clickable-table {
  cursor: pointer;
}
.list-item-cell { display: flex; align-items: center; gap: 12px; }
.list-icon { width: 36px; height: 36px; border-radius: 6px; display: flex; align-items: center; justify-content: center; color: white; flex-shrink: 0; }
.list-info { display: flex; flex-direction: column; gap: 4px; }
.list-title-link { font-weight: bold; color: var(--text-primary); }
.list-tags { display: flex; gap: 4px; }
.mr-1 { margin-right: 4px; }

/* 创建者样式优化 */
.creator-box { 
  display: flex; 
  align-items: center; 
  gap: 8px; 
  font-size: 14px; 
  transition: all 0.2s;
  padding: 4px 8px; /* 增加一点内边距让点击区域更大 */
  border-radius: 4px;
}
/* 鼠标悬停时变色，提示可点击 */
.creator-box:hover {
  color: var(--accent-primary);
  background-color: var(--accent-primary-light); /* 增加轻微背景色提示 */
  cursor: pointer;
}
.creator-box:hover .creator-name {
  text-decoration: underline;
}

.time-text {
  font-family: 'Menlo', monospace;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
