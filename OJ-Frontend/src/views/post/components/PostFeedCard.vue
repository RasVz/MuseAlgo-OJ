<template>
  <el-card
    shadow="never"
    class="post-feed-card"
    :body-style="{ padding: '20px' }"
    @click="$emit('view', post.id)"
  >
    <div class="feed-inner">
      <h2 class="feed-title">{{ post.title }}</h2>

      <p class="feed-preview">{{ post.summary || '暂无摘要' }}</p>

      <div v-if="thumbnails.length" class="feed-thumbs">
        <div v-for="(img, index) in thumbnails" :key="`${img}-${index}`" class="thumb-cell" @click.stop>
          <el-image
            :src="img"
            fit="cover"
            class="thumb-image"
            :preview-src-list="imageSources"
            :initial-index="index"
            preview-teleported
            hide-on-click-modal
          />
          <span v-if="index === thumbnails.length - 1 && hiddenImages > 0" class="thumb-more">
            +{{ hiddenImages }}
          </span>
        </div>
      </div>

      <div class="feed-footer">
        <div class="footer-left">
          <div class="author-box" @click.stop="$emit('user', post.authorId)">
            <el-avatar :size="24" :src="post.authorAvatar" class="author-avatar">
              {{ (post.authorName || 'U').charAt(0) }}
            </el-avatar>
            <span class="author-name">{{ post.authorName }}</span>
          </div>

          <div class="tags-box">
            <el-tag
              v-for="tag in tags"
              :key="tag"
              size="small"
              type="info"
              effect="plain"
              class="feed-tag"
              @click.stop="$emit('tag', tag)"
            ># {{ tag }}</el-tag>
          </div>
        </div>

        <div class="footer-right">
          <span class="stat-item">{{ formatTime(post.createTime) }}</span>
          <span class="stat-item">
            <el-icon><View /></el-icon> {{ post.views ?? 0 }}
          </span>
          <span class="stat-item">
            <el-icon><ChatDotRound /></el-icon> {{ post.comments ?? 0 }}
          </span>
          <span class="stat-item like-item">
            <svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>{{ post.likes ?? 0 }}
          </span>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
// 社区与班级共用的动态卡片：标题 → 三行摘要 → 最多 3 张缩略图 → 作者/标签 → 时间与统计。
// 数据由调用方规范化；这里兼容 images/tags 的字符串形式，避免沿用旧接口时白屏。
import { computed } from 'vue'
import dayjs from 'dayjs'
import { ChatDotRound, View } from '@element-plus/icons-vue'
import { parsePostImages } from '@/utils/post-images'

const props = defineProps({
  post: { type: Object, required: true }
})
defineEmits(['view', 'user', 'tag'])

const tags = computed(() => {
  const value = props.post.tags
  if (Array.isArray(value)) return value.filter(Boolean)
  return String(value || '').split(',').map((tag) => tag.trim()).filter(Boolean)
})

const imageSources = computed(() => parsePostImages(props.post.images))
const thumbnails = computed(() => imageSources.value.slice(0, 3))
const hiddenImages = computed(() => Math.max(imageSources.value.length - 3, 0))

const formatTime = (time) => (time ? dayjs(time).format('YYYY-MM-DD') : '')
</script>

<style scoped>
.post-feed-card {
  margin-bottom: 14px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-card);
  cursor: pointer;
  transition: border-color 0.2s;
  background: var(--bg-surface);
}
.post-feed-card:hover { border-color: var(--border-base); }
.feed-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 10px 0;
  line-height: 1.45;
}
.post-feed-card:hover .feed-title { color: var(--accent-primary); }
.feed-preview {
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.7;
  margin: 0 0 16px 0;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}
.feed-thumbs {
  display: flex;
  gap: 8px;
  max-width: 520px;
  margin: -4px 0 16px;
}
.thumb-cell {
  position: relative;
  flex: 1 1 0;
  min-width: 0;
  max-width: 166px;
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-control);
  overflow: hidden;
  background: var(--bg-app);
  border: 1px solid var(--border-light);
}
.thumb-image {
  width: 100%;
  height: 100%;
  cursor: zoom-in;
}
.thumb-image:hover { opacity: 0.92; }
.thumb-more {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 8px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.62);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
  pointer-events: none;
}
.feed-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid var(--border-light);
  padding-top: 14px;
  gap: 12px;
}
.footer-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.author-box { display: flex; align-items: center; gap: 8px; cursor: pointer; flex-shrink: 0; }
.author-name { font-size: 13px; color: var(--text-primary); font-weight: 500; }
.author-box:hover .author-name { color: var(--accent-primary); }
.tags-box { display: flex; gap: 6px; flex-wrap: wrap; min-width: 0; }
.feed-tag {
  border: none;
  background: var(--bg-app);
  color: var(--text-secondary);
  cursor: pointer;
  border-radius: var(--radius-tag);
}
.feed-tag:hover { color: var(--text-filter-strip); background: var(--bg-filter-strip); }
.footer-right {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-shrink: 0;
}
.stat-item { display: flex; align-items: center; gap: 4px; }
.like-item { color: var(--text-secondary); transition: color 0.15s; }
.like-item:hover { color: var(--accent-danger); }
.heart-icon {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
