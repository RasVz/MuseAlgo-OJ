<template>
  <div class="solutions-view" v-loading="loading">
    <NavbarContext>
      <div class="navbar-title"><el-icon><Document /></el-icon><span>题解</span></div>
    </NavbarContext>

    <header class="solutions-header">
      <div>
        <button class="back-link" @click="goProblem"><el-icon><ArrowLeft /></el-icon>返回题目</button>
        <h1>{{ problem.title || `题目 #${problemId}` }} 的题解</h1>
        <p>阅读其他同学的思路与实现，也可以分享你的解法。</p>
      </div>
      <el-button type="primary" @click="writeSolution"><el-icon><EditPen /></el-icon>写题解</el-button>
    </header>

    <div class="solutions-toolbar">
      <span>{{ total }} 篇题解</span>
      <el-radio-group v-model="sort" size="small" @change="fetchSolutions(1)">
        <el-radio-button label="time">最新发布</el-radio-button>
        <el-radio-button label="likes">最有帮助</el-radio-button>
      </el-radio-group>
    </div>

    <main v-if="solutions.length" class="solution-list">
      <article v-for="solution in solutions" :key="solution.id" class="solution-row" @click="goPost(solution.id)">
        <div class="solution-icon"><el-icon><Document /></el-icon></div>
        <div class="solution-main">
          <div class="solution-title-row">
            <h2>{{ solution.title || '未命名题解' }}</h2>
            <el-tag v-if="isOfficial(solution)" size="small" type="danger" effect="dark">官方题解</el-tag>
            <el-tag v-if="isEssence(solution)" size="small" type="warning" effect="plain">精选</el-tag>
          </div>
          <p>{{ solution.summary || '暂无摘要' }}</p>
          <div class="solution-meta">
            <span>{{ solution.authorName || '匿名用户' }}</span>
            <span>{{ formatTime(solution.createTime) }}</span>
            <button class="helpful-button" :class="{ active: solution.isLiked }" @click.stop="toggleHelpful(solution)">
              <svg class="heart-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
              {{ solution.isLiked ? '已觉得有帮助' : '有帮助' }} {{ solution.likes || 0 }}
            </button>
            <span><el-icon><ChatLineRound /></el-icon>{{ solution.commentCount || 0 }}</span>
          </div>
        </div>
        <el-icon class="row-arrow"><ArrowRight /></el-icon>
      </article>
    </main>
    <el-empty v-else-if="!loading" description="这道题还没有题解">
      <el-button type="primary" @click="writeSolution">写第一篇题解</el-button>
    </el-empty>

    <div v-if="total > pageSize" class="pagination">
      <el-pagination background layout="prev, pager, next" :total="total" :page-size="pageSize" :current-page="currentPage" @current-change="fetchSolutions" />
    </div>
  </div>
</template>

<script setup>
// 某题的题解列表：支持排序、翻页与"有帮助"标记。
// 写题解入口通过 URL query 带上 problemId 与 type=2，新建后会自动关联到这道题。
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ArrowLeft, ArrowRight, ChatLineRound, Document, EditPen } from '@element-plus/icons-vue'
import NavbarContext from '@/components/NavbarContext.vue'
import { getProblemDetail } from '@/api/problem'
import { getPostList } from '@/api/post'
import { togglePostLike } from '@/api/post'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/userStore'

const route = useRoute()
const router = useRouter()
const problemId = route.params.id
const loading = ref(false)
const problem = ref({})
const solutions = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const sort = ref('time')
const userStore = useUserStore()

const formatTime = (time) => time ? dayjs(time).format('YYYY-MM-DD') : '时间未知'
const goProblem = () => router.push({ name: 'ProblemDetail', params: { id: problemId } })
const goPost = (id) => router.push({ name: 'PostDetail', params: { id } })
const writeSolution = () => router.push({ name: 'PostCreate', query: { problemId, type: '2' } })
// 官方题解与精选标记都是 0/1 或布尔两种返回，统一按 Number>0 判断。
const isOfficial = (solution) => Number(solution.isOfficial) === 1 || solution.isOfficial === true
const isEssence = (solution) => Number(solution.isEssence) === 1 || solution.isEssence === true
// 标记"有帮助"后完全用接口返回值覆盖本地状态：
// 点赞数会被并发修改，本地 ±1 在高频点击下必然与服务端越差越远。
const toggleHelpful = async (solution) => {
  if (!userStore.userId) return ElMessage.warning('请先登录后标记')
  try {
    const res = await togglePostLike(solution.id, !solution.isLiked)
    if (res.code !== 200 || !res.data) throw new Error(res.message || '操作失败')
    solution.isLiked = Boolean(res.data.isLiked)
    solution.likes = Number(res.data.likeCount || 0)
  } catch (error) {
    ElMessage.error(error.message || '操作失败，请稍后重试')
  }
}

const fetchProblem = async () => {
  const res = await getProblemDetail(problemId)
  if (res.code === 200) problem.value = res.data || {}
}

const fetchSolutions = async (page = currentPage.value) => {
  loading.value = true
  try {
    // 题目关联是题解的唯一可靠归属条件。旧数据的 type 曾有不同取值，
    // 不再用它过滤，以免已经成功发布的题解被错误排除。
    const res = await getPostList({ problemId, currentPage: page, pageSize, sort: sort.value })
    if (res.code === 200) {
      solutions.value = res.data?.records || []
      total.value = Number(res.data?.total || solutions.value.length)
      currentPage.value = page
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchProblem()
  fetchSolutions(1)
})
</script>

<style scoped>
.solutions-view { max-width: 1040px; min-height: calc(100vh - 88px); margin: 0 auto; padding: 28px 24px 48px; }
.navbar-title { display: flex; align-items: center; gap: 8px; color: var(--text-primary); font-size: 18px; font-weight: 650; }
.solutions-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 24px; }
.back-link { display: inline-flex; align-items: center; gap: 4px; margin: 0 0 12px; padding: 0; border: 0; background: none; color: var(--text-regular); cursor: pointer; font-size: 13px; }
.back-link:hover { color: var(--accent-primary); }
.solutions-header h1 { margin: 0; color: var(--text-primary); font-size: 24px; font-weight: 650; }
.solutions-header p { margin: 7px 0 0; color: var(--text-regular); font-size: 14px; }
.solutions-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; border-top: 1px solid var(--border-light); border-bottom: 1px solid var(--border-light); color: var(--text-secondary); font-size: 13px; }
.solution-list { margin-top: 6px; }
.solution-row { display: flex; align-items: flex-start; gap: 14px; padding: 18px 12px; border-bottom: 1px solid var(--border-light); border-radius: var(--radius-sm); cursor: pointer; transition: background .15s; }
.solution-row:hover { background: var(--bg-surface-hover); }
.solution-icon { display: grid; flex: 0 0 36px; width: 36px; height: 36px; place-items: center; border: 1px solid #bfdbfe; border-radius: var(--radius-sm); background: var(--accent-primary-light); color: var(--accent-primary); }
.solution-main { min-width: 0; flex: 1; }
.solution-title-row { display: flex; min-width: 0; align-items: center; gap: 7px; }
.solution-main h2 { overflow: hidden; flex: 0 1 auto; margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 600; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.solution-main p { display: -webkit-box; overflow: hidden; margin: 5px 0 8px; color: var(--text-regular); font-size: 13px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.solution-meta { display: flex; flex-wrap: wrap; gap: 12px; color: var(--text-secondary); font-size: 12px; }
.solution-meta span { display: inline-flex; align-items: center; gap: 4px; }
.heart-icon { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.helpful-button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; background: transparent; color: var(--text-secondary); cursor: pointer; font: inherit; }
.helpful-button:hover, .helpful-button.active { color: #e85d75; }
.helpful-button.active .heart-icon { fill: currentColor; }
.row-arrow { align-self: center; color: var(--text-placeholder); }
.pagination { display: flex; justify-content: center; padding-top: 24px; }
@media (max-width: 640px) { .solutions-view { padding: 20px 16px 36px; } .solutions-header { align-items: flex-start; flex-direction: column; } .solution-meta { gap: 6px 12px; } }
</style>
