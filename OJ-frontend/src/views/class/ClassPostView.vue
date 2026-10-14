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
          <span class="count-value">{{ postCount }}</span>
        </div>
        <el-button type="primary" :icon="Edit" @click="publishDialogVisible = true" round size="large" class="publish-btn">
          发布新动态
        </el-button>
      </div>
    </div>

    <div class="post-feed" v-loading="loadingPosts">
      <template v-if="postList.length > 0">
        <ClassPostCard
          v-for="post in postList"
          :key="post.id"
          :post="post"
          @view="goToPostDetail"
        />
      </template>

      <div v-else-if="!loadingPosts" class="empty-wrap">
        <el-empty description="班级里还没有人发动态哦，快来抢沙发！" />
      </div>
    </div>

    <PublishPostDialog
      v-model:visible="publishDialogVisible"
      :class-id="classId"
      @published="fetchPostList"
    />
  </div>
</template>

<script setup>
// 班级讨论区：班级内的帖子流，可发布新帖。
// 与公共广场的区别是这些帖子带 classId，只对本班成员可见。
import { ref, onMounted, computed } from 'vue'
import { Edit } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'

import { getPostList } from '@/api/post'
import ClassPostCard from './components/ClassPostCard.vue'
import PublishPostDialog from './components/PublishPostDialog.vue'

const route = useRoute()
const router = useRouter()
const classId = route.params.id

const publishDialogVisible = ref(false)
const loadingPosts = ref(false)
const postList = ref([])

const postCount = computed(() => postList.value.length)

const fetchPostList = async () => {
  loadingPosts.value = true
  try {
    const res = await getPostList({
      currentPage: 1,
      pageSize: 20,
      classId,
      type: 3
    })

    if (res.code === 200) {
      const records = res.data.records || []
      records.forEach(post => {
        post.imagesArray = post.images ? post.images.split(',').filter(url => url) : []
      })
      postList.value = records
    }
  } catch (error) {
    console.error('获取动态列表失败', error)
  } finally {
    loadingPosts.value = false
  }
}

const goToPostDetail = (id) => router.push({ name: 'PostDetail', params: { id } })

onMounted(() => {
  fetchPostList()
})
</script>

<style scoped>
.class-post-container {
  padding: 16px 20px 24px;
  max-width: 920px;
  margin: 0 auto;
}

/* 顶部头图 */
.hero-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 22px;
  padding: 20px 22px;
  border-radius: 16px;
  background: linear-gradient(135deg, #f5f9ff 0%, #eef6ff 55%, #f4f8ff 100%);
  border: 1px solid var(--border-light);
}

.hero-left {
  min-width: 0;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 6px;
  letter-spacing: 0.2px;
}

.page-subtitle {
  margin: 0;
  font-size: 14px;
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
  border-radius: 999px;
  padding: 8px 14px;
}

.count-label {
  font-size: 12px;
  color: var(--text-secondary);
}

.count-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1;
}

.publish-btn {
  box-shadow: 0 8px 18px rgba(64, 158, 255, 0.2);
}

/* 列表 */
.post-feed {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 180px;
}

.empty-wrap {
  background: var(--bg-surface);
  border: 1px dashed var(--border-light);
  border-radius: 14px;
  padding: 14px;
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
