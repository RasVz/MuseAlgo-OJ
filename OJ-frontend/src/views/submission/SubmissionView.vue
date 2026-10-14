<template>
  <div class="submissions-page-wrapper">
    <SubmissionFilter 
      v-model:filters="filters" 
      @search="handleSearch"
      @reset="resetFilters"
    />

    <el-card shadow="never" class="main-table-card">
      <SubmissionTable 
        :data="list" 
        :loading="loading"
        @view-user="handleViewUser"
        @view-problem="handleViewProblem"
        @view-detail="handleViewDetail"
      />

      <div class="pagination-container">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"     
          :page-sizes="[10, 15, 20, 50]"   
          :total="total"
          layout="total, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 我的提交记录页：支持题目 / 状态 / 语言 / 范围四类筛选，并把筛选状态同步到 URL。
//
// URL 是筛选状态的唯一持久化位置：浏览器后退、刷新、分享链接都能还原，
// 也让"从题目页跳过来看自己的提交"这条常见路径能保留 problemId。
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getSubmissionList } from '@/api/submission'
import { useUserStore } from '@/stores/userStore' 
import SubmissionFilter from './components/SubmissionFilter.vue'
import SubmissionTable from './components/SubmissionTable.vue'

const router = useRouter()
const route = useRoute()

// 获取用户信息
const userStore = useUserStore()

// 数据
const list = ref([])
const total = ref(0)
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(15)

// myOnly 默认 true：绝大多数用户来这个页面就是看自己的提交，
// 默认展示全站记录既没意义又慢。
const filters = ref({
  problemId: '',
  username: '',
  status: null,
  language: '',
  myOnly: true,
  friendOnly: false
})

// 返回是否真的发生了变化：路由 watch 会把结果回灌到 fetchData，
// 不做判断就会出现"URL 变了 → 拉数据 → 写 URL → 又变了"的请求死循环。
const syncFiltersFromRoute = () => {
  const q = route.query
  const next = {
    problemId: q.problemId ? String(q.problemId) : '',
    username: '',
    status: q.status !== undefined && q.status !== '' ? Number(q.status) : null,
    language: q.language ? String(q.language) : '',
    myOnly: q.myOnly === undefined ? true : q.myOnly !== 'false',
    friendOnly: q.friendOnly === 'true'
  }

  const filtersChanged = ['problemId', 'status', 'language', 'myOnly', 'friendOnly'].some(
    key => String(filters.value[key] ?? '') !== String(next[key] ?? '')
  )
  if (filtersChanged) {
    filters.value = next
  }

  const page = q.page && Number(q.page) > 0 ? Number(q.page) : 1
  const pageChanged = currentPage.value !== page
  if (pageChanged) {
    currentPage.value = page
  }

  return filtersChanged || pageChanged
}

// 用 replace 而不是 push：改筛选不该在历史里堆记录，
// 否则用户按一次后退要连点很多次才能真正离开这个页面。
// 默认值（myOnly=true、第 1 页）不写进 URL，保持地址栏干净。
const pushFiltersToRoute = () => {
  const f = filters.value
  const query = {}
  if (f.problemId) query.problemId = f.problemId
  if (f.status !== null && f.status !== undefined && f.status !== '') query.status = String(f.status)
  if (f.language) query.language = f.language
  query.myOnly = String(f.myOnly)
  if (f.friendOnly) query.friendOnly = 'true'
  if (currentPage.value > 1) query.page = String(currentPage.value)
  router.replace({ query })
}

// API 请求
const fetchData = async () => {
  loading.value = true
  try {
    // currentUserId 必须显式传：mineOnly / followOnly 是相对于"当前用户"的筛选，
    // 后端需要知道以谁为准，而不是从 token 之外再要一个参数。
    const f = filters.value
    const params = {
      currentPage: currentPage.value,
      pageSize: pageSize.value,
      problemId: f.problemId || undefined,
      status: f.status,
      language: f.language || undefined,
      currentUserId: userStore.userId,
      mineOnly: f.myOnly,
      followOnly: f.friendOnly
    }

    const res = await getSubmissionList(params)
    
    if (res.code === 200) {
      list.value = res.data.records
      total.value = res.data.total
    }
  } catch (e) {
    console.error('加载提交记录失败:', e)
  } finally {
    loading.value = false
  }
}

// 事件处理
const handleSearch = () => {
  currentPage.value = 1
  pushFiltersToRoute()
  fetchData()
}

// 重置时保留 problemId：用户从题目页跳过来看自己的提交，
// 点"重置"想清的是筛选而不是"回到这道题"。
const resetFilters = () => {
  filters.value = {
    problemId: route.query.problemId ? String(route.query.problemId) : '',
    username: '',
    status: null,
    language: '',
    myOnly: true,
    friendOnly: false
  }
  currentPage.value = 1
  pushFiltersToRoute()
  fetchData()
}

const handleSizeChange = () => {
  pushFiltersToRoute()
  fetchData()
}

const handlePageChange = () => {
  pushFiltersToRoute()
  fetchData()
}

const handleViewUser = (id) => router.push({ name: 'UserProfile', params: { id } })
const handleViewProblem = (id) => router.push({ name: 'ProblemDetail', params: { id } })
const handleViewDetail = (id) => router.push({ name: 'SubmissionDetail', params: { id } })

onMounted(() => {
  syncFiltersFromRoute()
  fetchData()
})

// 浏览器前进/后退（route.query 变化）→ 还原筛选并重新拉数据。
// syncFiltersFromRoute 的返回值是"是否真的变了"的判据，避免无谓请求。
watch(() => route.query, () => {
  if (syncFiltersFromRoute()) fetchData()
}, { deep: true })
</script>

<style scoped>
.submissions-page-wrapper {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 125px);
  padding: 0px;
  background-color: var(--bg-app);
}

.main-table-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  overflow: hidden;
}

.main-table-card :deep(.el-card__body) {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.pagination-container {
  padding: 10px 15px;
  border-top: 1px solid var(--border-light);
  display: flex;
  justify-content: center;
  background-color: var(--bg-surface);
}
</style>
