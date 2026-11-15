<template>
  <div class="columns-square-container">
    
    <NavbarContext>
      <div class="navbar-title">
        <el-icon class="mr-2"><ChatDotRound /></el-icon>
        社区
      </div>
    </NavbarContext>

    <div class="main-content-wrapper">
      <el-row :gutter="20">
        
        <el-col :span="6" class="left-sidebar">
          
          <el-card shadow="never" class="category-card mb-3">
            <div class="sidebar-header">
              <span class="title">内容板块</span>
            </div>
            <ul class="category-list">
              <li 
                v-for="cat in categories" 
                :key="cat.id"
                :class="['category-item', { active: currentCategory === cat.id }]"
                @click="handleCategoryChange(cat.id)"
              >
                <div class="cat-left">
                  <el-icon :class="cat.iconClass"><component :is="cat.icon" /></el-icon>
                  <span>{{ cat.name }}</span>
                </div>
              </li>
            </ul>
          </el-card>

          <!-- <el-card shadow="never" class="collection-card">
            <div class="sidebar-header">
              <span class="title">精选合集</span>
              <el-button link type="primary" size="small">更多</el-button>
            </div>
            <div class="collection-list">
              <div v-for="col in collections" :key="col.id" class="collection-item">
                <el-image :src="col.cover" class="col-cover" fit="cover" />
                <div class="col-info">
                  <div class="col-name">{{ col.name }}</div>
                  <div class="col-meta">{{ col.articleCount }} 篇文章</div>
                </div>
              </div>
            </div>
          </el-card> -->

        </el-col>

        <el-col :span="18" class="right-feed">
          
          <div class="feed-filter-bar">
            <div class="sort-tabs">
              <span 
                :class="['sort-item', { active: sortBy === 'hot' }]" 
                @click="changeSort('hot')"
              >推荐</span>
              <el-divider direction="vertical" />
              <span 
                :class="['sort-item', { active: sortBy === 'latest' }]" 
                @click="changeSort('latest')"
              >最新</span>
            </div>
            <div class="feed-actions">
              <button class="feed-tag-trigger" @click="tagDialogVisible = true">
                <span v-if="selectedTags.length">已选 {{ selectedTags.length }} 个标签</span>
                <span v-else>筛选标签</span>
                <el-icon><ArrowDown /></el-icon>
              </button>
              <el-input 
                v-model="searchKeyword" 
                placeholder="搜索感兴趣的内容..." 
                :prefix-icon="Search"
                class="feed-search"
                @keyup.enter="fetchData(1)"
              />
            </div>
          </div>

          <div v-if="selectedTags.length" class="active-tag-row">
            <span>标签筛选：</span>
            <el-tag v-for="tag in selectedTags" :key="tag" closable effect="plain" @close="removeTag(tag)">{{ tag }}</el-tag>
            <el-button link type="primary" size="small" @click="clearTags">清空</el-button>
          </div>

          <div class="article-list" v-loading="loading">
            <template v-if="articleList.length > 0">
              <PostFeedCard
                v-for="article in articleList"
                :key="article.id"
                :post="article"
                @view="goToPostDetail"
                @user="goToUserProfile"
                @tag="filterByTag"
              />
            </template>
            <el-empty v-else description="暂无相关文章" />
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

        </el-col>
      </el-row>
    </div>
  </div>

  <PostTagSelectorDialog
    v-model="tagDialogVisible"
    title="筛选文章标签"
    :tag-groups="tagGroups"
    :selected-tags="selectedTags"
    :max-tags="0"
    @confirm="applyTags"
  />
</template>

<script setup>
// 社区：分类 / 排序 / 关键词 / 标签四类筛选的组合。
// 所有数据与请求逻辑都在 usePublicPostFeed 里，本页只做展示与交互绑定。
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NavbarContext from '@/components/NavbarContext.vue'
import PostTagSelectorDialog from './components/PostTagSelectorDialog.vue'
import PostFeedCard from './components/PostFeedCard.vue'
import { publicPostCategories, usePublicPostFeed } from './composables/usePublicPostFeed'
import { 
  Reading, Search, ChatDotRound,
  ArrowDown, Document, DataLine, Sunny, Coffee, Trophy, Bell
} from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()

const tagDialogVisible = ref(false)
// 分类表里存的是图标名字符串，这里映射成组件对象供模板直接渲染。
// 用名字而不是组件本身，是为了让 publicPostCategories 能被 Object.freeze 冻结。
const iconByName = { Reading, Bell, Document, DataLine, Sunny, Coffee, Trophy }
const categories = publicPostCategories.map((category) => ({
  ...category,
  icon: iconByName[category.icon]
}))

const {
  loading,
  records: articleList,
  total,
  pageSize,
  currentPage,
  load: fetchData,
  currentCategory,
  sortBy,
  searchKeyword,
  selectedTags,
  tagGroups,
  loadTagOptions,
  applyTags: applyTagsRaw,
  removeTag: removeTagRaw,
  clearTags: clearTagsRaw,
  filterByTag: filterByTagRaw
} = usePublicPostFeed({ notify: (message) => ElMessage.error(message) })

// 筛选与页码写入 query：从详情返回时能恢复用户刚才的浏览位置。
const syncQuery = (page) => {
  const query = {}
  if (currentCategory.value !== 'all') query.category = currentCategory.value
  if (sortBy.value !== 'hot') query.sort = sortBy.value
  if (selectedTags.value.length) query.tags = selectedTags.value.join(',')
  if (page > 1) query.page = String(page)
  router.replace({ query })
}

const applyTags = (tags) => {
  applyTagsRaw(tags)
  syncQuery(1)
}
const removeTag = (tag) => {
  removeTagRaw(tag)
  syncQuery(1)
}
const clearTags = () => {
  clearTagsRaw()
  syncQuery(1)
}
const filterByTag = (tag) => {
  filterByTagRaw(tag)
  syncQuery(1)
}

// 切换筛选维度后必须回到第 1 页：带着旧页码请求新条件会落到空页，
// 用户会以为"这个分类没有文章"。
const handleCategoryChange = (id) => {
  currentCategory.value = id
  syncQuery(1)
  fetchData(1)
}

const changeSort = (type) => {
  sortBy.value = type
  syncQuery(1)
  fetchData(1)
}

const handlePageChange = (val) => {
  syncQuery(val)
  fetchData(val)
}

const goToPostDetail = (id) => router.push({ name: 'PostDetail', params: { id: id } })
const goToUserProfile = (id) => router.push({ name: 'UserProfile', params: { id: id } })

// 标签选项与首屏列表并发拉：两者互不依赖，串行会让首屏多等一个往返。
// loadTagOptions 内部已捕获异常，失败只影响标签弹窗，不阻塞列表。
onMounted(() => {
  const category = String(route.query.category || 'all')
  if (categories.some((item) => item.id === category)) currentCategory.value = category
  if (route.query.sort === 'latest') sortBy.value = 'latest'
  const tags = typeof route.query.tags === 'string' ? route.query.tags.split(',').filter(Boolean) : []
  if (tags.length) selectedTags.value = tags

  const page = Number(route.query.page)
  loadTagOptions()
  fetchData(Number.isFinite(page) && page > 0 ? page : 1)
})
</script>

<style scoped>
/* ===== PostView ===== */
.columns-square-container {
  min-height: calc(100vh - var(--app-header-height));
  background: var(--bg-surface);
}
.navbar-title { font-size: 18px; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; }
.main-content-wrapper { max-width: 1280px; margin: 0 auto; padding: 16px 20px; box-sizing: border-box; }

/* --- 侧边栏 --- */
.category-card { border-radius: var(--radius-card); border: 1px solid var(--border-light); box-shadow: none; }
.mb-3 { margin-bottom: 20px; }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--border-light); }
.title { font-weight: 600; font-size: 15px; color: var(--text-primary); }
.category-list { list-style: none; padding: 0; margin: 0; }
.category-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 12px; cursor: pointer; border-radius: var(--radius-sm);
  transition: background 0.15s; color: var(--text-regular); margin-bottom: 2px; font-size: 14px;
}
.category-item:hover { background: var(--bg-app); color: var(--text-primary); }
.category-item.active { background: var(--bg-filter-strip); color: var(--text-filter-strip); font-weight: 500; }
.cat-left { display: flex; align-items: center; gap: 10px; }
.count-badge { font-size: 12px; background: var(--bg-input); color: var(--text-secondary); padding: 2px 8px; border-radius: 10px; }

/* --- 右侧内容区 --- */
.right-feed { min-width: 0; }
.feed-filter-bar {
  display: flex; justify-content: space-between; align-items: center;
  background: var(--bg-surface); padding: 0 20px; height: 52px; border-radius: var(--radius-card);
  margin-bottom: 16px; border: 1px solid var(--border-light);
}
.sort-tabs { display: flex; align-items: center; gap: 16px; font-size: 14px; }
.feed-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.sort-item { cursor: pointer; color: var(--text-secondary); transition: color 0.15s; padding-bottom: 2px; }
.sort-item:hover { color: var(--text-primary); }
.sort-item.active { color: var(--accent-primary); font-weight: 600; border-bottom: 2px solid var(--accent-primary); }
.feed-search { width: 220px; }
.feed-tag-trigger { display: inline-flex; align-items: center; gap: 5px; height: 32px; padding: 0 10px; border: 1px solid var(--border-light); border-radius: var(--radius-control); background: var(--bg-surface); color: var(--text-regular); cursor: pointer; font-size: 13px; white-space: nowrap; }
.feed-tag-trigger:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.active-tag-row {
  display: flex; align-items: center; flex-wrap: wrap; gap: 8px;
  margin: 0 0 16px; padding: 10px 16px;
  background: var(--bg-filter-strip); border: 1px solid var(--border-filter-strip);
  border-radius: var(--radius-control); color: var(--text-filter-strip); font-size: 13px;
}
.active-tag-row :deep(.el-tag) {
  background: var(--bg-surface); border-color: var(--border-filter-strip);
  color: var(--text-filter-strip); border-radius: var(--radius-tag);
}
.active-tag-row :deep(.el-button) { color: var(--text-filter-strip); }

/* --- 卡片样式在 PostFeedCard 内，与班级动态共用 --- */
.pagination-box { display: flex; justify-content: center; align-items: center; height: 56px; margin: 0 0 8px; }
</style>
