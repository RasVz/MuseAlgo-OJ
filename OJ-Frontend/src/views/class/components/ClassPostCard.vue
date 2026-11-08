<template>
  <el-card class="post-card" shadow="never">
    <div class="post-author-info">
      <el-avatar :size="44" src="https://cube.elemecdn.com/3/7c/3ea6beec64369c2642b92c6726f1epng.png" />
      <div class="author-details">
        <span class="author-name">{{ post.authorName || '匿名同学' }}</span>
        <span class="post-time">{{ formatDate(post.createTime) }}</span>
      </div>
    </div>

    <div class="post-body">
      <h3 v-if="post.title" class="post-title">{{ post.title }}</h3>
      <p class="post-text">{{ post.content }}</p>
    </div>

    <div v-if="post.imagesArray && post.imagesArray.length > 0" :class="['image-grid', getGridClass(post.imagesArray.length)]">
      <el-image
        v-for="(img, index) in post.imagesArray"
        :key="index"
        :src="img"
        fit="cover"
        class="grid-image"
        :preview-src-list="post.imagesArray"
        :initial-index="index"
        preview-teleported
        hide-on-click-modal
      />
    </div>

    <div class="post-footer">
      <el-button link type="primary" @click="$emit('view', post.id)">查看详情</el-button>
    </div>
  </el-card>
</template>

<script setup>
// 班级帖子卡片：图片按数量切换网格布局（1/2/4/多张）。
import dayjs from 'dayjs'

defineProps({
  post: { type: Object, required: true }
})
defineEmits(['view'])

const formatDate = (date) => (date ? dayjs(date).format('MM-DD HH:mm') : '')

// 图片网格按数量分档：1 张大图、2 张并排、4 张四宫格，更多则九宫格截断。
// 不做自适应是为了避免按容器宽度反复重排，长列表里会明显抖动。
const getGridClass = (count) => {
  if (count === 1) return 'grid-single'
  if (count === 2) return 'grid-double'
  if (count === 4) return 'grid-quad'
  return 'grid-nine'
}
</script>

<style scoped>
.post-card {
  border-radius: 14px;
  border: 1px solid var(--border-light);
  transition: all 0.25s ease;
  background: var(--bg-surface);
}

.post-card:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
  border-color: var(--border-hover);
}

.post-author-info {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
}

.author-details {
  margin-left: 12px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.author-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.post-time {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 3px;
}

.post-body {
  margin-bottom: 14px;
}

.post-title {
  font-size: 17px;
  line-height: 1.45;
  margin: 0 0 8px;
  color: var(--text-primary);
  font-weight: 700;
}

.post-text {
  margin: 0;
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.image-grid {
  margin-top: 6px;
}

.grid-single {
  width: 62%;
  max-width: 360px;
}

.grid-double {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  width: 62%;
  max-width: 360px;
}

.grid-quad {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  width: 68%;
  max-width: 420px;
}

.grid-nine {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  width: 80%;
  max-width: 520px;
}

.grid-image {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 10px;
  cursor: zoom-in;
  border: 1px solid var(--border-light);
  overflow: hidden;
}

.grid-image:hover {
  opacity: 0.92;
}

.post-footer {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-light);
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .grid-single,
  .grid-double,
  .grid-quad,
  .grid-nine {
    width: 100%;
    max-width: none;
  }
}
</style>
