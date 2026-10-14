<template>
  <div class="my-post-container">
    <div class="main-content">
      
      <!-- 顶部个人创作 Banner -->
      <div class="creator-banner">
        <div class="banner-text">
          <h1 class="banner-title">我的创作</h1>
          <p class="banner-subtitle">知识文章、题解和讨论贴，都有清晰的内容标识。</p>
        </div>
        <el-button type="primary" size="large" class="write-btn" :icon="EditPen" @click="handleCreate">
          写文章
        </el-button>
      </div>

      <!-- 文章列表区块 -->
      <div class="article-board">
        <div class="board-header">
          <div>
            <div class="board-title">我的内容</div>
            <div class="board-hint">按内容类型浏览和管理你的发布</div>
          </div>
          <div class="board-stats">{{ activeTab === 'all' ? '共发布' : `${activeTabMeta.label} ·` }} {{ total }} 条</div>
        </div>

        <el-tabs v-model="activeTab" class="content-tabs" @tab-change="handleTabChange">
          <el-tab-pane name="all">
            <template #label><span class="tab-label"><el-icon><Grid /></el-icon>全部</span></template>
          </el-tab-pane>
          <el-tab-pane name="article">
            <template #label><span class="tab-label"><el-icon><Reading /></el-icon>知识文章</span></template>
          </el-tab-pane>
          <el-tab-pane name="solution">
            <template #label><span class="tab-label"><el-icon><Document /></el-icon>题解</span></template>
          </el-tab-pane>
          <el-tab-pane name="discussion">
            <template #label><span class="tab-label"><el-icon><ChatDotRound /></el-icon>讨论贴</span></template>
          </el-tab-pane>
        </el-tabs>

        <div class="article-list" v-loading="loading">
          <template v-if="postList.length > 0">
            <MyPostListItem
              v-for="post in postList"
              :key="post.id"
              :post="post"
              @view="goDetail"
              @edit="handleEdit"
              @delete="handleDelete"
            />
          </template>

          <el-empty v-else :description="emptyDescription" :image-size="200">
            <el-button type="primary" @click="handleCreate">立即创作</el-button>
          </el-empty>
        </div>
        
        <!-- 分页 -->
        <div class="pagination-wrapper" v-if="total > 0">
          <el-pagination 
            background 
            layout="total, prev, pager, next, jumper" 
            :total="total" 
            :page-size="pageSize"
            :current-page="currentPage"
            @current-change="handlePageChange"
          />
        </div>
      </div>
      
    </div>
  </div>
</template>

<script setup>
// 我的内容：按「全部 / 知识文章 / 题解 / 讨论贴」分 Tab 展示我发布的帖子。
// 分页与响应式状态复用 usePagedPostList，本页只负责 Tab 与操作。
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Grid, Reading } from '@element-plus/icons-vue'
import { getMyPosts, deletePost } from '@/api/post'
import { usePagedPostList } from './composables/usePagedPostList'
import MyPostListItem from './components/MyPostListItem.vue'

const router = useRouter()

// --- 状态定义 ---
const activeTab = ref('all')

// fetchPage 里读 activeTab 而不是把它作为参数传进来：
// 切 Tab 时只需改 activeTab 再调用 fetchData(1)，不必重复维护一份当前筛选值。
const {
  loading,
  records: postList,
  total,
  currentPage,
  pageSize,
  load: fetchData
} = usePagedPostList({
  pageSize: 10,
  fetchPage: ({ page, pageSize }) => getMyPosts({
    page,
    size: pageSize,
    contentType: activeTab.value
  }),
  onError: (message) => ElMessage.error(message || '获取文章列表失败')
})

const tabMeta = {
  all: { label: '全部内容' },
  article: { label: '知识文章' },
  solution: { label: '题解' },
  discussion: { label: '讨论贴' }
}
const activeTabMeta = computed(() => tabMeta[activeTab.value])
const emptyDescription = computed(() => {
  if (activeTab.value === 'all') return '你还没有发布内容，快去开启创作之旅吧！'
  return `暂无${activeTabMeta.value.label}，去发布第一条吧！`
})

const handlePageChange = (val) => {
  fetchData(val)
}

const handleTabChange = () => {
  fetchData(1)
}

// --- 操作逻辑 ---
const handleCreate = () => {
  const routeUrl = router.resolve({ path: '/post/editor' })
  window.open(routeUrl.href, '_blank')
}

// 编辑器在新标签页打开：写长文时容易被切走，
// 在本页开会把已填的半篇内容留在路由跳转里，得不偿失。
const handleEdit = (post) => {
  const routeUrl = router.resolve({ path: `/post/editor/${post.id}` })
  window.open(routeUrl.href, '_blank')
}

// 删除是永久的，先确认；
// 删掉的是本页最后一条且不在第一页时要退回一页，否则会停在空白页上。
const handleDelete = (post) => {
  ElMessageBox.confirm(
    '确定要永久删除这篇文章吗？此操作不可恢复。',
    '删除确认',
    { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
  ).then(async () => {
    try {
      const res = await deletePost(post.id)
      if (res.code === 200) {
        ElMessage.success('删除成功')
        if (postList.value.length === 1 && currentPage.value > 1) {
          fetchData(currentPage.value - 1)
        } else {
          fetchData(currentPage.value)
        }
      } else {
        ElMessage.error(res.message || '删除失败')
      }
    } catch {
      ElMessage.error('请求失败')
    }
  }).catch(() => {})
}

const goDetail = (id) => {
  router.push({ name: 'PostDetail', params: { id: id } })
}

onMounted(() => fetchData())
</script>

<style scoped>
/* 整个页面的底色与布局 */
.my-post-container {
  min-height: calc(100vh - 60px);
  background-color: var(--bg-app);
  padding: 24px 0 60px 0;
}

.main-content {
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 0 20px;
}

/* 顶部创作中心 Banner（刻意浅蓝渐变设计色，dark 下覆盖为深蓝渐变） */
.creator-banner {
  background: linear-gradient(135deg, #e0f2fe 0%, #bae6fd 100%);
  border-radius: 12px;
  padding: 32px 40px;
  margin-bottom: 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 4px 14px rgba(2, 105, 161, 0.08);
}
.dark .creator-banner {
  background: linear-gradient(135deg, #1e3a5f 0%, #0c4a6e 100%);
}

.banner-text {
  color: #0369a1;
}
.dark .banner-text {
  color: #7dd3fc;
}

.banner-title {
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 10px 0;
  letter-spacing: 1px;
}

.banner-subtitle {
  font-size: 15px;
  margin: 0;
  opacity: 0.85;
}

.write-btn {
  border-radius: 8px;
  font-weight: 600;
  box-shadow: 0 4px 10px rgba(64, 158, 255, 0.3);
  transition: transform 0.2s;
}
.write-btn:hover {
  transform: translateY(-2px);
}

/* 纯白面板承载文章列表 */
.article-board {
  background: var(--bg-surface);
  border-radius: 12px;
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.board-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-light);
}

.board-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.board-hint {
  margin-top: 5px;
  color: var(--text-secondary);
  font-size: 13px;
}

.board-stats {
  font-size: 14px;
  color: var(--text-secondary);
}

.content-tabs {
  padding: 0 24px;
}

.content-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.content-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--border-light);
}

.content-tabs :deep(.el-tabs__item) {
  height: 48px;
  padding: 0 16px;
  color: var(--text-secondary);
  font-weight: 500;
}

.content-tabs :deep(.el-tabs__item.is-active) {
  color: var(--accent-primary);
  font-weight: 600;
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 分页区 */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  padding: 24px 0;
  background: var(--bg-surface);
}
</style>
