<template>
  <div class="my-post-container">
    <div class="main-content">
      
      <!-- 顶部个人创作 Banner -->
      <div class="creator-banner">
        <div class="banner-text">
          <h1 class="banner-title">我的创作</h1>
          <p class="banner-subtitle">知识文章、题解、讨论贴与班级动态，都有清晰的内容标识。</p>
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
          <div class="board-stats">{{ boardStats }}</div>
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
          <el-tab-pane name="class">
            <template #label><span class="tab-label"><el-icon><School /></el-icon>班级内容</span></template>
          </el-tab-pane>
        </el-tabs>

        <!-- 班级内容一级：我发过动态的班级 -->
        <div
          v-if="activeTab === 'class' && !activeClass"
          class="class-summary-list"
          v-loading="classLoading"
        >
          <template v-if="classSummaries.length > 0">
            <div
              v-for="item in classSummaries"
              :key="item.classId"
              class="class-summary-item"
              @click="openClass(item)"
            >
              <div class="class-summary-main">
                <span class="class-summary-name">{{ item.className }}</span>
                <span class="class-summary-meta">{{ item.postCount }} 条动态</span>
              </div>
              <span class="class-summary-time">最后发布 {{ formatTime(item.lastPostTime) }}</span>
            </div>
          </template>
          <el-empty
            v-else-if="!classLoading"
            description="还没有在任何班级发布过动态"
            :image-size="200"
          />
        </div>

        <template v-else>
          <div v-if="activeTab === 'class'" class="class-back-row">
            <el-button link type="primary" @click="backToClassList">
              <el-icon><ArrowLeft /></el-icon>
              返回班级列表
            </el-button>
            <span class="class-current-name">{{ activeClass.className }}</span>
          </div>

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
        </template>
      </div>
      
    </div>
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 我的内容：按「全部 / 知识文章 / 题解 / 讨论贴 / 班级内容」分 Tab 展示我发布的帖子。
// 班级内容分两级：先按班级聚合，再进入该班查看、编辑、删除自己的动态。
// 分页与响应式状态复用 usePagedPostList，本页只负责 Tab 与操作。
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ChatDotRound, EditPen, Grid, Reading, School } from '@element-plus/icons-vue'
import { deletePost, getMyClassSummaries, getMyPosts } from '@/api/post'
import { usePagedPostList } from './composables/usePagedPostList'
import MyPostListItem from './components/MyPostListItem.vue'

const router = useRouter()

// --- 状态定义 ---
const activeTab = ref('all')
const activeClass = ref(null)
const classSummaries = ref([])
const classLoading = ref(false)

// fetchPage 里读 activeTab 而不是把它作为参数传进来：
// 切 Tab 时只需改 activeTab 再调用 fetchData(1)，不必重复维护一份当前筛选值。
const {
  loading,
  records: postList,
  total,
  currentPage,
  pageSize,
  load: fetchData,
  clear: clearPosts
} = usePagedPostList({
  pageSize: 10,
  fetchPage: ({ page, pageSize }) => getMyPosts({
    page,
    size: pageSize,
    contentType: activeTab.value,
    classId: activeTab.value === 'class' ? activeClass.value?.classId : undefined
  }),
  onError: (message) => ElMessage.error(message || '获取文章列表失败')
})

const tabMeta = {
  all: { label: '全部内容' },
  article: { label: '知识文章' },
  solution: { label: '题解' },
  discussion: { label: '讨论贴' },
  class: { label: '班级内容' }
}
const activeTabMeta = computed(() => tabMeta[activeTab.value])
const emptyDescription = computed(() => {
  if (activeTab.value === 'all') return '你还没有发布内容，快去开启创作之旅吧！'
  if (activeTab.value === 'class') return '这个班级还没有你发布的动态，去班级页发布第一条吧！'
  return `暂无${activeTabMeta.value.label}，去发布第一条吧！`
})

const boardStats = computed(() => {
  if (activeTab.value === 'class' && !activeClass.value) {
    return `共 ${classSummaries.value.length} 个班级`
  }
  return `${activeTab.value === 'all' ? '共发布' : `${activeTabMeta.value.label} ·`} ${total.value} 条`
})

const fetchClassSummaries = async () => {
  classLoading.value = true
  try {
    const res = await getMyClassSummaries()
    if (res.code === 200) {
      classSummaries.value = res.data || []
    } else {
      ElMessage.error(res.message || '获取班级列表失败')
      classSummaries.value = []
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('获取班级列表失败')
    classSummaries.value = []
  } finally {
    classLoading.value = false
  }
}

const openClass = (item) => {
  activeClass.value = item
  fetchData(1)
}

const backToClassList = () => {
  activeClass.value = null
  clearPosts()
  fetchClassSummaries()
}

const handlePageChange = (val) => {
  fetchData(val)
}

const handleTabChange = () => {
  activeClass.value = null
  if (activeTab.value === 'class') {
    clearPosts()
    fetchClassSummaries()
    return
  }
  fetchData(1)
}

// --- 操作逻辑 ---
// 写作台已迁到独立的 /editor（块编辑器）；旧 /post/editor 仅作为班级内容与
// 历史特殊结构的兜底入口保留，不再从列表直接进入。
const handleCreate = () => {
  const routeUrl = router.resolve({ path: '/editor' })
  window.open(routeUrl.href, '_blank')
}

// 编辑器在新标签页打开：写长文时容易被切走，
// 在本页开会把已填的半篇内容留在路由跳转里，得不偿失。
const handleEdit = (post) => {
  const routeUrl = router.resolve({ path: '/editor', query: { id: post.id } })
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
    } catch (caughtError) {
      showApiError(caughtError, '请求失败')
    }
  }).catch(() => {})
}

const formatTime = (value) => {
  if (!value) return ''
  return String(value).replace('T', ' ').substring(0, 16)
}

const goDetail = (id) => {
  router.push({ name: 'PostDetail', params: { id: id } })
}

onMounted(() => fetchData())
</script>

<style scoped>
/* 整个页面的底色与布局 */
.my-post-container {
  min-height: calc(100vh - var(--app-header-height));
  background-color: var(--bg-surface);
  padding: 16px 20px 40px;
  box-sizing: border-box;
}

.main-content {
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  padding: 0;
}

/* 顶部创作 Banner：语义色提示带，深浅色主题共用同一组 tokens */
.creator-banner {
  background: var(--bg-filter-strip);
  border: 1px solid var(--border-filter-strip);
  border-radius: var(--radius-card);
  padding: 24px 28px;
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.banner-text {
  color: var(--text-filter-strip);
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
  border-radius: var(--radius-control);
  font-weight: 600;
}

/* 纯白面板承载文章列表 */
.article-board {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-card);
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

/* 班级内容一级列表 */
.class-summary-list {
  min-height: 180px;
}

.class-summary-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-light);
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.class-summary-item:last-child {
  border-bottom: none;
}

.class-summary-item:hover {
  background-color: var(--bg-surface-hover);
}

.class-summary-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}

.class-summary-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.class-summary-meta {
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.class-summary-time {
  font-size: 13px;
  color: var(--text-placeholder);
  flex-shrink: 0;
}

.class-back-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 24px;
  border-bottom: 1px solid var(--border-light);
}

.class-current-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 分页区 */
.pagination-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 56px;
  background: var(--bg-surface);
  border-top: 1px solid var(--border-light);
}
.pagination-wrapper :deep(.btn-prev),
.pagination-wrapper :deep(.btn-next),
.pagination-wrapper :deep(.el-pager li) {
  border-radius: var(--radius-control);
}
</style>
