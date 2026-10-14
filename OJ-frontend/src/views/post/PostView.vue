<template>
  <div class="columns-square-container">
    
    <NavbarContext>
      <div class="navbar-title">
        <el-icon class="mr-2"><Reading /></el-icon>
        文章广场
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
                :class="['sort-item', { active: sortBy === 'latest' }]" 
                @click="changeSort('latest')"
              >最新发布</span>
              <el-divider direction="vertical" />
              <span 
                :class="['sort-item', { active: sortBy === 'hot' }]" 
                @click="changeSort('hot')"
              >热门推荐</span>
            </div>
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

          <div v-if="selectedTags.length" class="active-tag-row">
            <span>标签筛选：</span>
            <el-tag v-for="tag in selectedTags" :key="tag" closable effect="plain" @close="removeTag(tag)">{{ tag }}</el-tag>
            <el-button link type="primary" size="small" @click="clearTags">清空</el-button>
          </div>

          <div class="article-list" v-loading="loading">
            <template v-if="articleList.length > 0">
              <el-card 
                v-for="article in articleList" 
                :key="article.id" 
                shadow="hover" 
                class="article-feed-card"
                :body-style="{ padding: '20px' }"
                @click="goToPostDetail(article.id)"
              >
                <div class="article-inner">
                  <h2 class="article-title">{{ article.title }}</h2>
                  
                  <p class="article-preview">{{ article.summary || '暂无摘要' }}</p>
                  
                  <div class="article-footer">
                    <div class="footer-left">
                      <div class="author-box" @click.stop="goToUserProfile(article.authorId)">
                        <el-avatar :size="24" :src="article.authorAvatar" class="author-avatar">
                          {{ (article.authorName || 'U').charAt(0) }}
                        </el-avatar>
                        <span class="author-name">{{ article.authorName }}</span>
                      </div>
                      
                      <div class="tags-box">
                        <el-tag v-for="tag in article.tags" 
                          :key="tag" size="small" type="info" 
                          effect="plain" class="article-tag" @click.stop="filterByTag(tag)">
                          # {{ tag }}
                        </el-tag>
                      </div>
                    </div>

                    <div class="footer-right">
                      <span class="stat-item">{{ formatTime(article.createTime) }}</span>
                      <span class="stat-item">
                        <el-icon><View /></el-icon> {{ article.views }}
                      </span>
                      <span class="stat-item">
                        <el-icon><ChatDotRound /></el-icon> {{ article.comments }}
                      </span>
                      <span class="stat-item like-item">
                        <svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>{{ article.likes }}
                      </span>
                    </div>
                  </div>
                </div>
              </el-card>
            </template>
            <el-empty v-else description="暂无相关文章" />
          </div>

          <div class="pagination-box">
            <el-pagination 
              background 
              layout="prev, pager, next" 
              :total="total" 
              :page-size="pageSize"
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
// 文章广场：分类 / 排序 / 关键词 / 标签四类筛选的组合。
// 所有数据与请求逻辑都在 usePublicPostFeed 里，本页只做展示与交互绑定。
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import NavbarContext from '@/components/NavbarContext.vue'
import PostTagSelectorDialog from './components/PostTagSelectorDialog.vue'
import { publicPostCategories, usePublicPostFeed } from './composables/usePublicPostFeed'
import { 
  Reading, Search, View, ChatDotRound,
  ArrowDown, Document, DataLine, Sunny, Coffee, Trophy, Bell
} from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const router = useRouter()

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
  load: fetchData,
  currentCategory,
  sortBy,
  searchKeyword,
  selectedTags,
  tagGroups,
  loadTagOptions,
  applyTags,
  removeTag,
  clearTags,
  filterByTag
} = usePublicPostFeed({ notify: (message) => ElMessage.error(message) })

const formatTime = (time) => {
  if (!time) return ''
  return dayjs(time).format('YYYY-MM-DD')
}

// 切换筛选维度后必须回到第 1 页：带着旧页码请求新条件会落到空页，
// 用户会以为"这个分类没有文章"。
const handleCategoryChange = (id) => {
  currentCategory.value = id
  fetchData(1)
}

const changeSort = (type) => {
  sortBy.value = type
  fetchData(1)
}

const handlePageChange = (val) => {
  fetchData(val)
}

const goToPostDetail = (id) => router.push({ name: 'PostDetail', params: { id: id } })
const goToUserProfile = (id) => router.push({ name: 'UserProfile', params: { id: id } })

// 标签选项与首屏列表并发拉：两者互不依赖，串行会让首屏多等一个往返。
onMounted(async () => {
  await loadTagOptions()
  fetchData()
})
</script>

<style scoped>
/* ===== PostView ===== */
.columns-square-container { min-height: 100%; }
.navbar-title { font-size: 18px; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; }
.main-content-wrapper { max-width: 1280px; margin: 0 auto; padding: 10px 0; }

/* --- 侧边栏 --- */
.category-card { border-radius: var(--radius-md); border: 1px solid var(--border-light); box-shadow: none; }
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
.category-item.active { background: var(--accent-primary-light); color: var(--accent-primary); font-weight: 500; }
.cat-left { display: flex; align-items: center; gap: 10px; }
.count-badge { font-size: 12px; background: var(--bg-input); color: var(--text-secondary); padding: 2px 8px; border-radius: 10px; }

/* --- 右侧内容区 --- */
.right-feed { min-width: 0; }
.feed-filter-bar {
  display: flex; justify-content: space-between; align-items: center;
  background: var(--bg-surface); padding: 14px 20px; border-radius: var(--radius-md);
  margin-bottom: 16px; border: 1px solid var(--border-light);
}
.sort-tabs { display: flex; align-items: center; gap: 16px; font-size: 14px; }
.sort-item { cursor: pointer; color: var(--text-secondary); transition: color 0.15s; padding-bottom: 2px; }
.sort-item:hover { color: var(--text-primary); }
.sort-item.active { color: var(--accent-primary); font-weight: 600; border-bottom: 2px solid var(--accent-primary); }
.feed-search { width: 220px; }
.feed-tag-trigger { display: inline-flex; align-items: center; gap: 5px; height: 32px; padding: 0 10px; border: 1px solid var(--border-light); border-radius: var(--radius-sm); background: var(--bg-surface); color: var(--text-regular); cursor: pointer; font-size: 13px; white-space: nowrap; }
.feed-tag-trigger:hover { border-color: var(--accent-primary); color: var(--accent-primary); }
.active-tag-row { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin: -6px 0 12px; padding: 0 4px; color: var(--text-secondary); font-size: 13px; }

/* --- 文章卡片 --- */
.article-feed-card {
  margin-bottom: 14px; border: 1px solid var(--border-light); border-radius: var(--radius-md);
  cursor: pointer; transition: box-shadow 0.2s; background: var(--bg-surface);
}
.article-feed-card:hover { box-shadow: var(--shadow-md); }
.article-title {
  font-size: 18px; font-weight: 700; color: var(--text-primary);
  margin: 0 0 10px 0; line-height: 1.45;
}
.article-title:hover { color: var(--accent-primary); }
.article-preview {
  font-size: 14px; color: var(--text-regular); line-height: 1.7;
  margin: 0 0 16px 0; display: -webkit-box;
  -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden;
}
.article-footer { display: flex; justify-content: space-between; align-items: center; border-top: 1px solid var(--border-light); padding-top: 14px; }
.footer-left { display: flex; align-items: center; gap: 14px; }
.author-box { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.author-name { font-size: 13px; color: var(--text-primary); font-weight: 500; }
.author-box:hover .author-name { color: var(--accent-primary); }
.tags-box { display: flex; gap: 6px; }
.article-tag { border: none; background: var(--bg-app); color: var(--text-secondary); cursor: pointer; }
.article-tag:hover { color: var(--accent-primary); background: var(--accent-primary-light); }
.footer-right { display: flex; align-items: center; gap: 14px; color: var(--text-secondary); font-size: 13px; }
.stat-item { display: flex; align-items: center; gap: 4px; }
.like-item { color: var(--text-secondary); transition: color 0.15s; }
.like-item:hover { color: var(--accent-danger); }
.heart-icon { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.pagination-box { display: flex; justify-content: center; margin-top: 28px; margin-bottom: 50px; }
</style>
