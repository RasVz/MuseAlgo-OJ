<template>
  <div class="class-post-container">
    <div class="hero-header">
      <div class="hero-left">
        <h2 class="page-title">班级动态</h2>
        <p class="page-subtitle">记录学习瞬间，分享思路与成长</p>
      </div>
      <div class="hero-right">
        <div class="count-pill">
          <span class="count-label">动态总数</span>
          <span class="count-value">{{ total }}</span>
        </div>
        <el-button type="primary" :icon="Edit" size="large" class="publish-btn" @click="openEditor">
          发布新动态
        </el-button>
      </div>
    </div>

    <div class="post-feed" v-loading="loadingPosts">
      <template v-if="postList.length > 0">
        <PostFeedCard
          v-for="post in postList"
          :key="post.id"
          :post="post"
          @view="goToPostDetail"
        />
      </template>

      <div v-else-if="loadError" class="empty-wrap">
        <el-empty :description="loadError">
          <el-button type="primary" @click="fetchPostList({ page: currentPage })">重新加载</el-button>
        </el-empty>
      </div>

      <div v-else-if="!loadingPosts" class="empty-wrap">
        <el-empty description="班级里还没有人发动态哦，快来抢沙发！" />
      </div>
    </div>

    <div v-if="total > 0" class="pagination-box">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup>
// 班级动态：只显示本班 type=3 的动态，按发布时间倒序分页。
// 发布与编辑统一使用 /editor 写作台，发布后通过 window.focus 静默刷新回第 1 页。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Edit } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { getPostList } from '@/api/post'
import { parsePostImages } from '@/utils/post-images'
import PostFeedCard from '@/views/post/components/PostFeedCard.vue'

const route = useRoute()
const router = useRouter()
const classId = computed(() => route.params.id)

const loadingPosts = ref(false)
const loadError = ref('')
const postList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
// 从写作台返回时只刷新一次；新建后回到第 1 页。
const pendingRefresh = ref(false)

const normalizeRecord = (record) => ({
  ...record,
  comments: record.commentCount ?? record.comments ?? 0,
  tags: record.tags || [],
  images: parsePostImages(record.images)
})

const fetchPostList = async ({ page = currentPage.value, silent = false } = {}) => {
  if (!silent) loadingPosts.value = true
  try {
    const res = await getPostList({
      currentPage: page,
      pageSize,
      classId: classId.value
    })

    if (res.code !== 200) {
      loadError.value = res.message || '动态加载失败，请稍后重试'
      postList.value = []
      total.value = 0
      return
    }

    const data = res.data || {}
    postList.value = (data.records || []).map(normalizeRecord)
    total.value = Number(data.total) || 0
    currentPage.value = page
    loadError.value = ''
  } catch (error) {
    console.error('获取动态列表失败', error)
    loadError.value = '动态加载失败，请稍后重试'
    postList.value = []
    total.value = 0
  } finally {
    loadingPosts.value = false
  }
}

const handlePageChange = (page) => {
  fetchPostList({ page })
}

const openEditor = () => {
  pendingRefresh.value = true
  const target = router.resolve({
    path: '/editor',
    query: { channel: 'class', classId: classId.value }
  })
  window.open(target.href, '_blank')
}

const refreshFromEditor = () => {
  if (!pendingRefresh.value) return
  pendingRefresh.value = false
  fetchPostList({ page: 1, silent: true })
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') refreshFromEditor()
}

const goToPostDetail = (id) => router.push({ name: 'PostDetail', params: { id } })

watch(() => route.params.id, () => {
  currentPage.value = 1
  fetchPostList({ page: 1 })
})

onMounted(() => {
  window.addEventListener('focus', refreshFromEditor)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  fetchPostList({ page: 1 })
})

onBeforeUnmount(() => {
  window.removeEventListener('focus', refreshFromEditor)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<style scoped>
.class-post-container {
  height: 100%;
  padding: 20px 24px 24px;
  max-width: 920px;
  margin: 0 auto;
  box-sizing: border-box;
  overflow-y: auto;
}

/* 顶部头图 */
.hero-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  padding: 16px 20px;
  border-radius: var(--radius-card);
  background: var(--bg-app);
  border: 1px solid var(--border-light);
}

.hero-left {
  min-width: 0;
}

.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 4px;
  letter-spacing: 0.2px;
}

.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--text-regular);
}

.hero-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.count-pill {
  display: flex;
  align-items: baseline;
  gap: 8px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-tag);
  padding: 8px 14px;
}

.count-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.count-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--accent-primary);
  line-height: 1;
}

.publish-btn {
  border-radius: var(--radius-control);
}

/* 列表 */
.post-feed {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 180px;
}

.empty-wrap {
  background: var(--bg-surface);
  border: 1px dashed var(--border-light);
  border-radius: var(--radius-card);
  padding: 14px;
}

.pagination-box {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 56px;
  margin-top: 12px;
}

/* 响应式 */
@media (max-width: 768px) {
  .class-post-container {
    padding: 12px;
  }

  .hero-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .hero-right {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
