<template>
  <el-card shadow="hover" class="home-card discuss-card">
    <template #header>
      <div class="card-header">
        <span class="header-title"><el-icon><ChatDotRound /></el-icon> 近期讨论</span>
        <el-link type="primary" :underline="false" @click="$router.push({ name: 'Post' })">更多 ></el-link>
      </div>
    </template>
    <div class="discuss-list" v-loading="loading">
      <div v-for="item in list" :key="item.id" class="d-item" @click="$router.push({ name: 'PostDetail', params: { id: item.id } })">
        <el-avatar :size="36" :src="item.authorAvatar" class="d-avatar">{{ (item.authorName || 'U').charAt(0) }}</el-avatar>
        <div class="d-content">
          <div class="d-title">{{ item.title }}</div>
          <div class="d-preview">{{ getPreviewText(item) }}</div>
          <div class="d-meta">{{ item.authorName }} · {{ formatTime(item.createTime) }}</div>
        </div>
        <div class="d-side">
          <div class="d-time">{{ formatTime(item.createTime) }}</div>
          <div class="d-stats">
            <span class="ds-item"><el-icon><View /></el-icon> {{ item.views }}</span>
            <span class="ds-item"><el-icon><ChatLineSquare /></el-icon> {{ item.commentCount || 0 }}</span>
          </div>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
/**
 * 首页「近期讨论」卡片：按热度展示社区最新讨论，点击进入帖子详情。
 *
 * 数据来自 getPostList，卡片只关心「非公告的热门帖子」，
 * 因此做了一次前端过滤：公告由首页另一张卡片专门展示，
 * 混进讨论列表会让「近期讨论」名不副实。
 * 多取 20 条再过滤截 6 条，是因为公告在列表里占比不固定，
 * 只取 6 条的话公告一多就会出现空卡片。
 */
import { ref, onMounted } from 'vue'
import { getPostList } from '@/api/post'
import dayjs from 'dayjs'
import { ChatDotRound, View, ChatLineSquare } from '@element-plus/icons-vue'

const list = ref([])
const loading = ref(false)

/**
 * 去掉富文本标签得到纯文本摘要。
 * 不能用 v-html 渲染接口返回的正文——那等于把 XSS 交给帖子内容，
 * 卡片这里只需要纯文本，所以剥标签后配合 CSS 单行截断即可。
 */
const stripHtml = (text) => String(text || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()

/**
 * 生成列表项的摘要文案。
 * summary/content/description 三级兜底是因为不同帖子类型回填的字段不一致；
 * 剥完标签后为空（例如正文只有图片）时给一句占位，
 * 避免出现标题下面空一行、比没有摘要更显眼的空白。
 */
const getPreviewText = (item) => {
  const text = stripHtml(item?.summary || item?.content || item?.description || '')
  if (!text) return '暂无内容摘要'
  // 48 字是按卡片宽度试出来的截断长度：两行放不下，第三行会破坏列表项高度的一致性
  return text.length > 48 ? `${text.slice(0, 48)}...` : text
}

const fetchData = async () => {
  loading.value = true
  try {
    // sort: 'hot' 按热度取近期讨论（而非最新），type: 0 表示不限分类
    // 请求失败时列表保持为空，卡片自然呈现为空白容器；
    // 首页多卡片并存，不为单张卡片弹错误提示。
    const res = await getPostList({ currentPage: 1, pageSize: 20, sort: 'hot', type: 0 })
    if (res.code === 200) {
      const records = res.data.records || []
      // 先剔除公告再截断：顺序不能反，否则公告会占掉前 6 个名额把卡片挤成空列表
      list.value = records.filter((p) => p.category !== 'announcement').slice(0, 6)
    }
  } finally { loading.value = false }
}

// 只展示月日不展示年份：讨论流的时间跨度以「近期」为名，超过一年的内容由归档页负责
const formatTime = (t) => dayjs(t).format('MM-DD')

onMounted(fetchData)
</script>

<style scoped>
.home-card { border-radius: 12px; border: none; height: 100%; display: flex; flex-direction: column; }
:deep(.el-card__body) { flex: 1; display: flex; flex-direction: column; padding: 0 12px 0 12px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-title { font-weight: bold; font-size: 16px; display: flex; align-items: center; gap: 6px; }

.discuss-list { flex: 1; display: flex; flex-direction: column; }
.d-item { display: flex; align-items: flex-start; gap: 12px; margin: 0 -12px; padding: 10px 12px; border-bottom: 1px solid var(--border-light); cursor: pointer; }
.d-item:last-child { border-bottom: none; }
.d-item:hover { background: var(--bg-surface-hover); }
.d-avatar { flex-shrink: 0; margin-top: 2px; }
.d-content { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; }
.d-title { font-size: 15px; font-weight: 600; color: var(--text-primary); margin-bottom: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.d-title:hover { color: var(--accent-primary); }
.d-preview { font-size: 13px; line-height: 1.5; color: var(--text-regular); margin-bottom: 6px; display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
.d-meta { font-size: 12px; color: var(--text-secondary); }
.d-side { width: 92px; flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; justify-content: flex-start; gap: 4px; }
.d-time { font-size: 12px; color: var(--text-secondary); line-height: 1.5; }
.d-stats { display: flex; flex-direction: column; gap: 2px; font-size: 12px; color: var(--text-placeholder); align-items: flex-end; }
.ds-item { display: flex; align-items: center; gap: 4px; }
</style>
