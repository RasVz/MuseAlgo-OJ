<template>
  <div class="favorite-view">
    <NavbarContext>
      <div class="navbar-title">
        <el-icon><Collection /></el-icon>
        <span>我的收藏</span>
      </div>
    </NavbarContext>

    <header class="favorite-header">
      <div>
        <h1>我的收藏</h1>
        <p>保存过的内容会按类型归档，方便继续阅读和练习。</p>
      </div>
    </header>

    <div class="favorite-tabs" role="tablist" aria-label="收藏类型">
      <button
        v-for="tab in tabs"
        :key="tab.name"
        class="favorite-tab"
        :class="{ active: activeTab === tab.name }"
        role="tab"
        :aria-selected="activeTab === tab.name"
        @click="selectTab(tab.name)"
      >
        <el-icon><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
        <span class="tab-count">{{ tab.count }}</span>
      </button>
    </div>

    <section class="favorite-content" v-loading="loading">
      <template v-if="isPostTab && postList.length">
        <article
          v-for="item in postList"
          :key="item.id"
          class="favorite-entry post-entry"
          tabindex="0"
          @click="goPost(item.id)"
          @keydown.enter="goPost(item.id)"
        >
          <div class="entry-icon" :class="activeTab === 'article' ? 'article-icon' : 'post-icon'">
            <el-icon><component :is="activeTab === 'article' ? Document : ChatLineRound" /></el-icon>
          </div>
          <div class="entry-main">
            <h2>{{ item.title || '未命名动态' }}</h2>
            <p class="entry-summary">{{ item.summary || '暂未提供内容摘要' }}</p>
            <div class="entry-meta">
              <span>{{ item.authorName || '匿名用户' }}</span>
              <span>{{ formatTime(item.create_time || item.createTime) }}</span>
              <span class="meta-stat"><svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>{{ item.likes || 0 }}</span>
              <span class="meta-stat"><el-icon><ChatLineRound /></el-icon>{{ item.comment_count || item.commentCount || 0 }}</span>
            </div>
          </div>
          <el-tooltip content="取消收藏" placement="top">
            <button class="unfavorite-button" aria-label="取消收藏" @click.stop="confirmRemovePost(item)">
              <el-icon><StarFilled /></el-icon>
            </button>
          </el-tooltip>
        </article>
      </template>

      <template v-else-if="activeTab === 'problem_list' && problemList.length">
        <div class="problem-list-grid">
          <article
            v-for="item in problemList"
            :key="item.targetId || item.id"
            class="problem-list-card"
            tabindex="0"
            @click="goProblemList(item.targetId || item.id)"
            @keydown.enter="goProblemList(item.targetId || item.id)"
          >
            <div class="problem-card-top">
              <div class="entry-icon list-icon"><el-icon><Notebook /></el-icon></div>
              <el-tooltip content="取消收藏" placement="top">
                <button class="unfavorite-button" aria-label="取消收藏" @click.stop="confirmRemoveProblemList(item)">
                  <el-icon><StarFilled /></el-icon>
                </button>
              </el-tooltip>
            </div>
            <h2>{{ item.title || '未命名题单' }}</h2>
            <p>{{ item.description || '暂无题单简介' }}</p>
            <div class="problem-card-footer">
              <span><el-icon><List /></el-icon>{{ item.problemCount || 0 }} 题</span>
              <span>{{ item.creatorName || item.authorName || '公开题单' }}</span>
            </div>
          </article>
        </div>
      </template>

      <el-empty
        v-else-if="!loading"
        :description="emptyDescription"
        :image-size="88"
      >
        <template #image><el-icon class="empty-icon"><component :is="activeTab === 'problem_list' ? Notebook : Star" /></el-icon></template>
      </el-empty>
    </section>
  </div>
</template>

<script setup>
// 我的收藏：把帖子（动态/文章）与题单分 Tab 展示，并支持取消收藏。
// 收藏表用 targetType 区分目标种类，因此两类内容走不同的接口与不同的列表字段。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatLineRound,
  Collection,
  Document,
  List,
  Notebook,
  Star,
  StarFilled
} from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import NavbarContext from '@/components/NavbarContext.vue'
import { getFavoritePosts, getFavoriteProblemLists, removeFavorite } from '@/api/favorite'

const router = useRouter()
const activeTab = ref('post')
const loading = ref(false)
const postList = ref([])
const problemList = ref([])
const counts = ref({ post: 0, article: 0, problem_list: 0 })

const tabs = computed(() => [
  { name: 'post', label: '动态', icon: ChatLineRound, count: counts.value.post },
  { name: 'article', label: '文章', icon: Document, count: counts.value.article },
  { name: 'problem_list', label: '题单', icon: Notebook, count: counts.value.problem_list }
])

const isPostTab = computed(() => activeTab.value === 'post' || activeTab.value === 'article')
const emptyDescription = computed(() => {
  const labels = { post: '还没有收藏动态', article: '还没有收藏文章', problem_list: '还没有收藏题单' }
  return labels[activeTab.value]
})

const formatTime = (time) => time ? dayjs(time).format('YYYY-MM-DD') : '时间未知'

// 动态与文章共用同一个列表组件与接口，只靠 type 参数区分（0=动态，1=文章）。
const fetchPosts = async () => {
  loading.value = true
  try {
    const type = activeTab.value === 'post' ? 0 : 1
    const res = await getFavoritePosts(type)
    postList.value = res.code === 200 ? res.data || [] : []
    counts.value[activeTab.value] = postList.value.length
  } catch (error) {
    console.error(error)
    postList.value = []
  } finally {
    loading.value = false
  }
}

const fetchProblemLists = async () => {
  loading.value = true
  try {
    const res = await getFavoriteProblemLists({ page: 1, size: 50 })
    problemList.value = res.code === 200 ? res.data?.records || [] : []
    counts.value.problem_list = problemList.value.length
  } catch (error) {
    console.error(error)
    problemList.value = []
  } finally {
    loading.value = false
  }
}

// 三个 Tab 的计数要在首屏就拿全，否则 Tab 上的数字全是 0，
// 用户会以为只有当前这个 Tab 有收藏。三个请求并发。
const fetchFavoriteCounts = async () => {
  try {
    const [postsRes, articlesRes, listsRes] = await Promise.all([
      getFavoritePosts(0),
      getFavoritePosts(1),
      getFavoriteProblemLists({ page: 1, size: 50 })
    ])
    counts.value.post = postsRes.code === 200 ? (postsRes.data || []).length : 0
    counts.value.article = articlesRes.code === 200 ? (articlesRes.data || []).length : 0
    counts.value.problem_list = listsRes.code === 200 ? (listsRes.data?.records || []).length : 0
  } catch (error) {
    console.error(error)
  }
}

const loadActiveTab = () => activeTab.value === 'problem_list' ? fetchProblemLists() : fetchPosts()

const selectTab = (tab) => {
  if (activeTab.value === tab) return
  activeTab.value = tab
  loadActiveTab()
}

const goPost = (id) => router.push({ name: 'PostDetail', params: { id } })
const goProblemList = (id) => router.push({ name: 'ProblemListDetail', params: { id } })

const confirmRemove = async (id, type, list) => {
  try {
    await ElMessageBox.confirm('取消收藏后，该内容将从这里移除。', '取消收藏', {
      confirmButtonText: '取消收藏',
      cancelButtonText: '保留',
      type: 'warning'
    })
    const res = await removeFavorite({ targetId: id, type })
    if (res.code !== 200) return ElMessage.error(res.msg || '取消收藏失败')

    // 取消收藏后本地移除该行而不是重拉整页：用户连续取消多个时体验最好。
// 题单项的主键在不同接口下可能是 targetId 或 id，两个都兜住。
    if (list === 'post') {
      postList.value = postList.value.filter(item => item.id !== id)
      counts.value[activeTab.value] = postList.value.length
    } else {
      problemList.value = problemList.value.filter(item => (item.targetId || item.id) !== id)
      counts.value.problem_list = problemList.value.length
    }
    ElMessage.success('已取消收藏')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') console.error(error)
  }
}

const confirmRemovePost = (item) => confirmRemove(item.id, 'post', 'post')
const confirmRemoveProblemList = (item) => confirmRemove(item.targetId || item.id, 'problem_list', 'problem_list')

// 首屏数据与 Tab 计数分别触发：先让列表可见，计数慢一点不影响阅读。
onMounted(() => {
  loadActiveTab()
  fetchFavoriteCounts()
})
</script>

<style scoped>
.favorite-view { max-width: 980px; margin: 0 auto; padding: 28px 24px 48px; }
.navbar-title { display: flex; align-items: center; gap: 8px; color: var(--text-primary); font-size: 18px; font-weight: 650; }

.favorite-header { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 24px; }
.favorite-header h1 { margin: 0; color: var(--text-primary); font-size: 24px; font-weight: 650; letter-spacing: 0; }
.favorite-header p { margin: 7px 0 0; color: var(--text-regular); font-size: 14px; }

.favorite-tabs { display: flex; gap: 6px; padding-bottom: 14px; border-bottom: 1px solid var(--border-light); }
.favorite-tab { display: inline-flex; align-items: center; gap: 7px; min-height: 36px; padding: 0 12px; border: 1px solid transparent; border-radius: var(--radius-sm); background: transparent; color: var(--text-regular); cursor: pointer; font-size: 14px; transition: background .15s, color .15s, border-color .15s; }
.favorite-tab:hover { background: var(--bg-surface-hover); color: var(--text-primary); }
.favorite-tab.active { background: var(--accent-primary-light); border-color: var(--el-color-primary-light-7); color: var(--accent-primary-dark); font-weight: 600; }
.tab-count { min-width: 20px; padding: 0 5px; border-radius: 10px; background: var(--bg-app); color: var(--text-secondary); font-size: 12px; line-height: 20px; text-align: center; }
.favorite-tab.active .tab-count { background: var(--el-color-primary-light-8); color: var(--accent-primary-dark); }

.favorite-content { min-height: 280px; padding-top: 12px; }
.favorite-entry { position: relative; display: flex; align-items: flex-start; gap: 14px; padding: 18px 46px 18px 14px; border-bottom: 1px solid var(--border-light); border-radius: var(--radius-sm); cursor: pointer; outline: none; transition: background .15s; }
.favorite-entry:hover, .favorite-entry:focus-visible { background: var(--bg-surface-hover); }
.favorite-entry:focus-visible, .problem-list-card:focus-visible { box-shadow: 0 0 0 2px var(--accent-primary-light); }
.entry-icon { flex: 0 0 36px; display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid; border-radius: var(--radius-sm); font-size: 17px; }
.post-icon { border-color: var(--el-color-info-light-7); background: var(--accent-info-light); color: var(--el-color-info-dark-2); }
.article-icon { border-color: var(--el-color-primary-light-7); background: var(--accent-primary-light); color: var(--accent-primary); }
.list-icon { border-color: var(--el-color-success-light-7); background: var(--accent-success-light); color: var(--accent-success); }
.entry-main { min-width: 0; flex: 1; }
.entry-main h2, .problem-list-card h2 { overflow: hidden; margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 600; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.entry-summary { display: -webkit-box; overflow: hidden; margin: 5px 0 8px; color: var(--text-regular); font-size: 13px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.entry-meta, .problem-card-footer { display: flex; align-items: center; gap: 14px; color: var(--text-secondary); font-size: 12px; }
.meta-stat, .problem-card-footer span { display: inline-flex; align-items: center; gap: 4px; }
.meta-stat .el-icon { font-size: 13px; }
.heart-icon { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }

.unfavorite-button { position: absolute; top: 18px; right: 12px; display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: var(--radius-sm); background: transparent; color: var(--accent-warning); cursor: pointer; font-size: 17px; transition: background .15s, color .15s; }
.unfavorite-button:hover { background: var(--accent-danger-light); color: var(--accent-danger); }

.problem-list-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; padding-top: 4px; }
.problem-list-card { position: relative; min-height: 178px; padding: 18px; border: 1px solid var(--border-light); border-radius: var(--radius-sm); background: var(--bg-surface); cursor: pointer; outline: none; transition: border-color .15s, box-shadow .15s, transform .15s; }
.problem-list-card:hover { border-color: var(--border-hover); box-shadow: var(--shadow-sm); transform: translateY(-1px); }
.problem-card-top { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.problem-card-top .unfavorite-button { position: static; }
.problem-list-card > p { display: -webkit-box; overflow: hidden; min-height: 40px; margin: 7px 0 14px; color: var(--text-regular); font-size: 13px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.problem-card-footer { justify-content: space-between; padding-top: 12px; border-top: 1px solid var(--border-light); }

.empty-icon { width: 72px; height: 72px; color: var(--text-placeholder); font-size: 52px; }

@media (max-width: 640px) {
  .favorite-view { padding: 20px 16px 36px; }
  .favorite-header h1 { font-size: 21px; }
  .favorite-header p { line-height: 1.5; }
  .favorite-tabs { gap: 2px; overflow-x: auto; }
  .favorite-tab { padding: 0 10px; white-space: nowrap; }
  .favorite-entry { gap: 10px; padding-right: 42px; }
  .entry-meta { flex-wrap: wrap; gap: 6px 12px; }
  .problem-list-grid { grid-template-columns: 1fr; }
}
</style>
