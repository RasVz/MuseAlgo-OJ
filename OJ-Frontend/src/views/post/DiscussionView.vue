<template>
  <div class="discussion-page">
    <NavbarContext><div class="navbar-title"><el-icon><ChatDotRound /></el-icon>讨论广场</div></NavbarContext>
    <section class="discussion-hero">
      <div><h1>一起提问、复盘与成长</h1><p>交流思路和经验，不发布题目完整答案。</p></div>
      <el-button type="primary" @click="createDiscussion"><el-icon><EditPen /></el-icon>发起讨论</el-button>
    </section>
    <div class="discussion-toolbar">
      <WorkbenchSegmented
        v-model="category"
        :options="categoryOptions"
        aria-label="讨论分类"
        @change="reloadFromFirstPage"
      />
      <WorkbenchSegmented
        v-model="sort"
        :options="sortOptions"
        size="sm"
        aria-label="讨论排序"
        @change="reloadFromFirstPage"
      />
    </div>
    <main v-loading="loading" class="discussion-list">
      <article v-for="post in posts" :key="post.id" class="discussion-row" @click="goPost(post.id)">
        <span class="category-dot" :class="post.category" />
        <div class="discussion-main"><div class="discussion-title"><el-tag size="small" :type="categoryType(post.category)">{{ categoryLabel(post.category) }}</el-tag><h2>{{ post.title }}</h2></div><p>{{ post.summary || '暂无摘要' }}</p><small>{{ post.authorName || '匿名用户' }} · {{ formatTime(post.createTime) }}</small></div>
        <div class="discussion-stats"><span>♡ {{ post.likes || 0 }}</span><span>◌ {{ post.commentCount || 0 }}</span></div>
      </article>
      <el-empty v-if="!loading && !posts.length" description="还没有讨论，来发起第一条吧" />
    </main>
    <el-pagination v-if="total > pageSize" background class="discussion-pagination" layout="prev, pager, next" :total="total" :page-size="pageSize" @current-change="loadPosts" />
  </div>
</template>

<script setup>
// 讨论区：只看 type=0 的讨论贴，可按子分类与排序筛选。
// 分页状态复用 usePagedPostList，category/sort 在 fetchPage 内部读取当前值。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ChatDotRound, EditPen } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import WorkbenchSegmented from '@/components/WorkbenchSegmented.vue'
import { getPostList } from '@/api/post'
import { usePagedPostList } from './composables/usePagedPostList'

const categoryOptions = [
  { value: 'all', label: '全部' },
  { value: 'question', label: '提问' },
  { value: 'experience', label: '经验分享' },
  { value: 'contest-discussion', label: '比赛讨论' }
]
const sortOptions = [
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热门' }
]
const router = useRouter()
const category = ref('all')
const sort = ref('latest')
const {
  loading,
  records: posts,
  total,
  pageSize,
  load: loadPosts
} = usePagedPostList({
  pageSize: 12,
  fetchPage: ({ page, pageSize }) => getPostList({
    currentPage: page,
    pageSize,
    type: 0,
    category: category.value === 'all' ? undefined : category.value,
    sort: sort.value
  })
})

const categoryLabel = (value) => ({ question: '提问', experience: '经验分享', 'contest-discussion': '比赛讨论' }[value] || '讨论')
const categoryType = (value) => ({ question: 'warning', experience: 'success', 'contest-discussion': 'danger' }[value] || 'info')
const formatTime = (value) => value ? dayjs(value).format('MM-DD HH:mm') : ''
const reloadFromFirstPage = () => loadPosts(1)
const createDiscussion = () => window.open(router.resolve({ path: '/editor', query: { channel: 'discussion' } }).href, '_blank')
const goPost = (id) => router.push({ name: 'PostDetail', params: { id } })
onMounted(() => loadPosts())
</script>

<style scoped>
.discussion-page {
  padding: 16px 20px 40px; box-sizing: border-box;
  min-height: calc(100vh - var(--app-header-height)); background: var(--bg-surface);
}
.discussion-hero,
.discussion-toolbar,
.discussion-list,
.discussion-pagination { max-width: 1080px; margin-left: auto; margin-right: auto; }
.navbar-title { display:flex; gap:8px; align-items:center; font-size:18px; font-weight:650; }
.discussion-hero {
  display:flex; justify-content:space-between; align-items:center; padding:28px 32px;
  border-radius: var(--radius-card); color: var(--text-on-accent);
  background: linear-gradient(120deg, var(--accent-primary-dark), var(--accent-primary));
}
.discussion-hero h1 { margin:0 0 8px; font-size:25px; }
.discussion-hero p { margin:0; opacity:.82; }
.discussion-toolbar { display:flex; justify-content:space-between; align-items:center; height:52px; margin:12px 0 8px; }
.discussion-list { overflow:hidden; border:1px solid var(--border-light); border-radius: var(--radius-card); background:var(--bg-surface); }
.discussion-row { display:flex; gap:14px; align-items:flex-start; padding:18px 24px; border-bottom:1px solid var(--border-light); cursor:pointer; transition: background-color .15s; }
.discussion-row:last-child { border-bottom:none; }
.discussion-row:hover { background:var(--bg-surface-hover); }
.discussion-main { min-width:0; flex:1; }
.discussion-title { display:flex; align-items:center; gap:9px; }
.discussion-title h2 { overflow:hidden; margin:0; font-size:16px; text-overflow:ellipsis; white-space:nowrap; }
.discussion-main p { overflow:hidden; margin:7px 0; color:var(--text-regular); font-size:13px; text-overflow:ellipsis; white-space:nowrap; }
.discussion-main small,.discussion-stats { color:var(--text-secondary); font-size:12px; }
.discussion-stats { display:flex; gap:12px; padding-top:5px; white-space:nowrap; }
.category-dot { width:8px; height:8px; margin-top:9px; border-radius:50%; background:var(--text-placeholder); }
.category-dot.question { background:var(--accent-warning); }
.category-dot.experience { background:var(--accent-success); }
.category-dot.contest-discussion { background:var(--accent-danger); }
.discussion-pagination { display:flex; justify-content:center; align-items:center; height:56px; }
.discussion-pagination :deep(.btn-prev),
.discussion-pagination :deep(.btn-next),
.discussion-pagination :deep(.el-pager li) { border-radius: var(--radius-control); }
</style>
