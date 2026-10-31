<template>
  <div class="post-detail-page">
    
    <!-- 正文加载中 -->
    <div v-if="!post.id && loading" class="loading-wrapper" v-loading="true" />
    
    <!-- 文章不存在 -->
    <div v-else-if="!post.id" class="empty-wrapper">
      <el-empty description="文章不存在" />
    </div>

    <!-- 正常渲染 -->
    <template v-else>
      
      <!-- === 头部 === -->
      <header class="article-header">
        <div class="header-top">
          <el-tag effect="plain" size="small" round class="category-tag">{{ post.category }}</el-tag>
          <span class="header-meta">
            <span class="meta-item">{{ formatTime(post.publishTime) }}</span>
            <span class="meta-divider">·</span>
            <span class="meta-item">{{ post.views || 0 }} 阅读</span>
          </span>
        </div>
        <h1 class="article-title">{{ post.title }}</h1>
        <div class="author-row">
          <el-avatar :size="36" :src="post.author.avatar" class="author-avatar">
            {{ (post.author.name || 'U').charAt(0) }}
          </el-avatar>
          <div class="author-info">
            <span class="author-name" @click="goUser(post.author.id)">{{ post.author.name }}</span>
            <span class="author-motto">{{ post.author.motto || '' }}</span>
          </div>
          <el-button 
            v-if="!isSelf && !isFollowed" 
            size="small" round 
            @click="handleToggleFollow"
          >+ 关注</el-button>
          <el-button 
            v-else-if="!isSelf" 
            size="small" round plain
            @click="handleToggleFollow"
          >已关注</el-button>
        </div>
      </header>

      <div class="article-body-wrapper">
        
        <!-- === 正文 + 底部操作 + 评论 === -->
        <main class="article-main">
          <div class="article-content">
            <PostContent 
              :content="post.content" 
              @toc-updated="onTocUpdate"
            />
          </div>

          <div v-if="post.imagesArray && post.imagesArray.length > 0" class="post-images">
            <div :class="getGridClass(post.imagesArray.length)">
              <el-image 
                v-for="(img, index) in post.imagesArray" 
                :key="index" :src="img" fit="cover"
                class="grid-image"
                :preview-src-list="post.imagesArray"
                :initial-index="index"
                preview-teleported hide-on-click-modal
              />
            </div>
          </div>

          <!-- 底部操作栏 -->
          <div class="article-actions">
            <button :class="['act-btn', { active: post.isLiked }]" @click="handleLike">
              <svg viewBox="0 0 24 24" width="18" height="18" :fill="post.isLiked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              <span>点赞 {{ post.likeCount || 0 }}</span>
            </button>
            <button :class="['act-btn', { active: post.isFavorited }]" @click="handleFavorite">
              <svg viewBox="0 0 24 24" width="18" height="18" :fill="post.isFavorited ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
              <span>{{ post.isFavorited ? '已收藏' : '收藏' }}</span>
            </button>
            <button v-if="!isSelf" class="act-btn report-action" @click="handleReport">
              <el-icon><Flag /></el-icon><span>举报</span>
            </button>
          </div>

          <RelatedProblems :problems="post.relatedProblems" @open="goProblem" />

          <!-- 作者卡片 -->
          <PostAuthorCard
            :author="post.author"
            :is-self="isSelf"
            :is-followed="isFollowed"
            @toggle-follow="handleToggleFollow"
          />

          <!-- 评论 -->
          <div class="comments-section" id="comments">
            <h3 class="section-title">评论 {{ comments.length > 0 ? `(${comments.length})` : '' }}</h3>
            <CommentSection :comments="comments" @refresh="fetchComments(post.id)" />
          </div>
        </main>

        <!-- === 右侧固定目录 === -->
        <ArticleToc :toc-items="tocItems" />

      </div>
    </template>
  </div>
</template>

<script setup>
// 文章详情页：正文渲染 + 目录 + 点赞/收藏/关注 + 楼中楼评论 + 举报。
// 点赞与收藏都是"先请求后更新 UI"而非乐观更新：文章详情页是低频操作，
// 等一次往返换来的是"数字和服务端一定一致"，不值得为省 200ms 引入回滚逻辑。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Flag } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { getPostDetail, getPostComments, togglePostLike, reportPost } from '@/api/post'
import { toggleFollow } from '@/api/user'
import { addFavorite, checkFavorite, removeFavorite } from '@/api/favorite'
import PostContent from './components/PostContent.vue'
import CommentSection from './components/CommentSection.vue'
import ArticleToc from './components/ArticleToc.vue'
import PostAuthorCard from './components/PostAuthorCard.vue'
import RelatedProblems from './components/RelatedProblems.vue'
import { useUserStore } from '@/stores/userStore'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const post = ref({}) 
const comments = ref([])
const isFollowed = ref(false)
const currentId = userStore.userId

// --- TOC ---
const tocItems = ref([])

const onTocUpdate = (items) => {
  tocItems.value = items
}

// --- 辅助 ---
// 自己的文章不显示关注按钮：关注自己是后端会拒绝的无效请求。
const isSelf = computed(() => {
  const authorId = post.value.author?.id
  return currentId && authorId && String(currentId) === String(authorId)
})

const formatTime = (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''

const getGridClass = (count) => {
  if (count === 1) return 'grid-1'
  if (count === 4) return 'grid-4'
  return 'grid-9'
}

const goUser = (id) => router.push({ name: 'UserProfile', params: { id } })
const goProblem = (id) => router.push({ name: 'ProblemDetail', params: { id } })
// 举报理由必填：空理由的举报会让管理员无法判断，纯粹增加处理成本。
const handleReport = async () => {
  try {
    const { value: reason } = await ElMessageBox.prompt('请简要说明举报原因，例如：人身攻击、广告或内容不当。', '举报内容', {
      confirmButtonText: '提交举报', cancelButtonText: '取消', inputPlaceholder: '举报原因', inputValidator: value => value?.trim() ? true : '请填写举报原因'
    })
    const res = await reportPost({ postId: String(post.value.id), reason: reason.trim() })
    if (res.code === 200) ElMessage.success('举报已提交，管理员会尽快处理')
    else ElMessage.error(res.msg || res.message || '提交失败')
  } catch (error) { if (error !== 'cancel' && error !== 'close') console.error(error) }
}

// --- 数据 ---
// 点赞数与点赞态一律以接口返回为准，不做本地 ±1：
// 同一篇文章可能被很多人同时点赞，本地加减会与服务端越差越远。
const applyReactionData = (data = {}) => {
  post.value.likeCount = Number(data.likeCount || 0)
  post.value.isLiked = Boolean(data.isLiked)
}

// 收藏状态用详情接口返回的 isFavorited 不可靠（可能未按当前用户计算），
// 单独查一次收藏接口，确保按钮显示的是真实状态。
const fetchFavoriteState = async (postId) => {
  if (!currentId) return false
  try {
    const res = await checkFavorite({ targetId: postId, type: 'post' })
    return res.code === 200 && Boolean(res.data)
  } catch {
    return false
  }
}

const fetchDetail = async (id) => {
  loading.value = true
  try {
    const res = await getPostDetail(id)
    if (res.code === 200 && res.data) {
      const data = res.data
      let imagesArray = []
      if (data.images) imagesArray = data.images.split(',').filter(url => url)
      
      post.value = {
        id: data.id, title: data.title, content: data.content,
        imagesArray, category: data.category || '默认',
        views: data.views, publishTime: data.createTime, 
        author: {
          id: data.authorId, name: data.authorName || '匿名用户',
          avatar: data.authorAvatar,
          motto: data.motto || '这个人很懒，什么也没写' 
        },
        likeCount: Number(data.likeCount || 0),
        isLiked: Boolean(data.isLiked),
        isFavorited: data.isFavorited || false,
        relatedProblems: Array.isArray(data.relatedProblems) ? data.relatedProblems : []
      }
      isFollowed.value = data.isFollowed || false
      post.value.isFavorited = await fetchFavoriteState(id)
      fetchComments(id)
    }
  } catch (e) { console.error(e) }
  finally { loading.value = false }
}

// --- 操作 ---
const handleLike = async () => {
  if (!currentId) return ElMessage.warning('请先登录后操作')
  try {
    const res = await togglePostLike(post.value.id, !post.value.isLiked)
    if (res.code === 200 && res.data) applyReactionData(res.data)
  } catch { ElMessage.error('操作失败') }
}

const handleFavorite = async () => {
  if (!currentId) return ElMessage.warning('请先登录后操作')
  const wasFavorited = post.value.isFavorited
  try {
    const res = wasFavorited
      ? await removeFavorite({ targetId: post.value.id, type: 'post' })
      : await addFavorite({ targetId: post.value.id, type: 'post' })
    if (res.code !== 200) return ElMessage.error(res.msg || '收藏操作失败')

    post.value.isFavorited = !wasFavorited
    ElMessage.success(wasFavorited ? '已取消收藏' : '已收藏')
  } catch {
    ElMessage.error('收藏操作失败')
  }
}

// --- 评论 ---
// 评论是后端返回的扁平列表，前端组装成树。
// 用 String() 归一 parentId/id：接口在这两处可能给出不同类型（数字 vs 字符串），
// 直接用 Map 查找会全部落空，评论就都变成一级了。
const buildCommentTree = (flatList) => {
  const result = [], map = new Map()
  flatList.forEach(item => { item.children = []; map.set(String(item.id), item) })
  flatList.forEach(item => {
    const pid = item.parentId
    if (!pid || pid === 0 || pid === '0') result.push(item)
    // 找得到父评论就挂到父评论下，找不到（父级被删）就提升为一级：
// 不能因为父级不存在就把评论丢掉。
    else { const parent = map.get(String(pid)); parent ? parent.children.push(item) : result.push(item) }
  })
  return result
}

const fetchComments = async (id) => {
  try {
    const res = await getPostComments(id)
    if (res.code === 200) comments.value = buildCommentTree(res.data || [])
  } catch (e) { console.error('获取评论失败', e) }
}

// 关注接口返回的是状态字符串而非布尔：'followed' / 'unfollowed'，要显式转成布尔。
const handleToggleFollow = async () => {
  if (!currentId) { ElMessage.warning('请先登录后操作'); return }
  if (isSelf.value) return
  try {
    const res = await toggleFollow({ followedId: post.value.author.id })
    if (res.code === 200) {
      isFollowed.value = res.data === 'followed'
      ElMessage.success(isFollowed.value ? '关注成功' : '已取消关注')
    }
  } catch (error) { console.error(error) }
}

onMounted(() => {
  const id = route.params.id
  if (id) fetchDetail(id)
})
</script>

<style scoped>
/* ================================================================
   DETAIL PAGE LAYOUT
   ================================================================ */
.post-detail-page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px;
}

.loading-wrapper, .empty-wrapper {
  min-height: 400px;
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-light);
}

/* ================================================================
   HEADER — 与正文视觉区分
   ================================================================ */
.article-header {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md) var(--radius-md) 0 0;
  padding: 36px 48px 28px;
  border-bottom: none;
}

.header-top {
  display: flex; align-items: center; gap: 14px; margin-bottom: 18px;
}
.category-tag { font-weight: 500; }
.header-meta { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-secondary); }
.meta-divider { color: var(--border-hover); }
.meta-item { display: flex; align-items: center; }

.article-title {
  font-size: 30px; font-weight: 800; color: var(--text-primary);
  line-height: 1.4; margin: 0 0 22px 0; letter-spacing: -0.3px;
}

.author-row {
  display: flex; align-items: center; gap: 14px;
  padding-top: 20px; border-top: 1px solid var(--border-light);
}
.author-avatar { flex-shrink: 0; }
.author-info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.author-name { font-weight: 600; font-size: 15px; color: var(--text-primary); cursor: pointer; }
.author-name:hover { color: var(--accent-primary); }
.author-motto { font-size: 13px; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* ================================================================
   BODY — 文章 + TOC 双栏
   ================================================================ */
.article-body-wrapper {
  display: flex; gap: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-top: none;
  border-radius: 0 0 var(--radius-md) var(--radius-md);
}

.article-main {
  flex: 1; min-width: 0;
  padding: 32px 48px 48px;
}

.article-content {
  font-size: 16px; line-height: 1.8; color: var(--text-primary);
}

/* ================================================================
   IMAGES
   ================================================================ */
.post-images { margin-top: 28px; margin-bottom: 24px; }
.grid-1 { width: 100%; max-width: 600px; }
.grid-1 .grid-image { width: 100%; border-radius: var(--radius-sm); }
.grid-4 { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; width: 100%; max-width: 500px; }
.grid-9 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; width: 100%; max-width: 600px; }
.grid-image { width: 100%; aspect-ratio: 1/1; border-radius: var(--radius-sm); cursor: zoom-in; border: 1px solid var(--border-light); }
.grid-image:hover { opacity: 0.92; }

/* ================================================================
   BOTTOM ACTIONS — 收藏 / 点赞
   ================================================================ */
.article-actions {
  display: flex; justify-content: center; align-items: center; gap: 12px;
  margin: 40px 0 36px; padding: 24px 0;
  border-top: 1px solid var(--border-light);
  border-bottom: 1px solid var(--border-light);
}
.act-btn {
  display: inline-flex; align-items: center; gap: 8px;
  padding: 10px 24px; border-radius: 24px;
  border: 1px solid var(--border-light);
  background: var(--bg-surface); color: var(--text-regular);
  cursor: pointer; font-size: 14px; transition: all 0.15s;
}
.act-btn:hover { border-color: var(--border-hover); color: var(--text-primary); }
.act-btn.active { color: var(--accent-primary); border-color: var(--accent-primary); background: var(--accent-primary-light); }

/* ================================================================
   COMMENTS
   ================================================================ */
.comments-section { margin-top: 8px; }
.section-title {
  font-size: 16px; font-weight: 700; color: var(--text-primary);
  margin: 0 0 20px 0; padding-bottom: 12px;
  border-bottom: 1px solid var(--border-light);
}
</style>
