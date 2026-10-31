<template>
  <div class="article-item" @click="$emit('view', post.id)">
    <div class="item-main">
      <div class="item-title-row">
        <el-tag v-if="post.status === 0 || post.status === 'Draft'" type="info" size="small" class="draft-tag" effect="dark">草稿</el-tag>
        <el-tag :type="contentMeta.tagType" size="small" effect="light" class="content-type-tag">
          <el-icon><component :is="contentMeta.icon" /></el-icon>
          {{ contentMeta.label }}
        </el-tag>
        <h2 class="title">{{ post.title }}</h2>
      </div>
      <p class="summary">{{ post.summary || '暂无摘要...' }}</p>

      <div class="item-footer">
        <div class="meta-info">
          <span class="time">{{ formatTime(post.createTime) }}</span>
          <span class="divider"></span>
          <span class="stat" title="浏览量"><el-icon><View /></el-icon> {{ post.views || post.viewCount || 0 }}</span>
          <span class="stat" title="点赞数"><svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>{{ post.likes || post.likeCount || 0 }}</span>
          <span class="stat" title="评论数"><el-icon><ChatDotRound /></el-icon> {{ post.commentCount || post.comments || 0 }}</span>
        </div>

        <div class="actions" @click.stop>
          <el-button link type="primary" @click="$emit('edit', post)">编辑</el-button>
          <el-divider direction="vertical" class="action-divider" />
          <el-button link type="danger" @click="$emit('delete', post)">删除</el-button>
        </div>
      </div>
    </div>

    <div class="item-cover" v-if="post.cover">
      <el-image :src="post.cover" fit="cover" class="cover-img">
        <template #error>
          <div class="image-slot"><el-icon><Picture /></el-icon></div>
        </template>
      </el-image>
    </div>
  </div>
</template>

<script setup>
// 我的文章列表项：卡片 + 操作菜单（查看/编辑/删除）。
import { computed } from 'vue'
import { ChatDotRound, Document, Picture, Reading, View } from '@element-plus/icons-vue'

const props = defineProps({
  post: { type: Object, required: true }
})
defineEmits(['view', 'edit', 'delete'])

// 文章类型判定要同时看 type 与 category：type 是后端权威分类（0讨论/1题解/2文章），
// 但历史数据里有些文章 category 已经标成 solution 却 type 还是 0，
// 只看 type 会把它们误显示成讨论贴。
const contentMeta = computed(() => {
  const type = Number(props.post.type)
  if (props.post.problemId || props.post.category === 'solution' || type === 1) {
    return { label: '题解', tagType: 'primary', icon: Document }
  }
  if (type === 2 || ['algorithm', 'life', 'leisure', 'contest'].includes(props.post.category)) {
    return { label: '知识文章', tagType: 'success', icon: Reading }
  }
  return { label: '讨论贴', tagType: 'warning', icon: ChatDotRound }
})

const formatTime = (t) => {
  if (!t) return ''
  return String(t).replace('T', ' ').substring(0, 16)
}
</script>

<style scoped>
.article-item {
  display: flex;
  padding: 24px;
  border-bottom: 1px solid var(--border-light);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.article-item:hover {
  background-color: var(--bg-surface-hover);
}

.article-item:last-child {
  border-bottom: none;
}

.item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.item-title-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.draft-tag {
  margin-right: 10px;
  flex-shrink: 0;
}

.content-type-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 10px;
  flex-shrink: 0;
}

.title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  overflow: hidden;
  transition: color 0.2s;
}

.article-item:hover .title {
  color: var(--accent-primary);
}

.summary {
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin: 0 0 16px 0;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.item-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.meta-info {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: var(--text-secondary);
}

.time {
  margin-right: 16px;
}

.divider {
  width: 1px;
  height: 12px;
  background-color: var(--border-light);
  margin-right: 16px;
}

.stat {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-right: 20px;
}
.stat .el-icon {
  font-size: 15px;
}
.heart-icon { width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }

.actions {
  display: flex;
  align-items: center;
  opacity: 0.6;
  transition: opacity 0.2s;
}
.article-item:hover .actions {
  opacity: 1;
}

.action-divider {
  margin: 0 12px;
}

.item-cover {
  flex-shrink: 0;
  width: 140px;
  height: 94px;
  margin-left: 24px;
  border-radius: 6px;
  overflow: hidden;
  background-color: var(--bg-app);
  border: 1px solid var(--border-light);
}

.cover-img {
  width: 100%;
  height: 100%;
  transition: transform 0.3s ease;
}

.article-item:hover .cover-img {
  transform: scale(1.05);
}

.image-slot {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  color: var(--text-placeholder);
  font-size: 24px;
}
</style>
