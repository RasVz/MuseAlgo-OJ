<template>
  <div class="training-page">
    <NavbarContext>
      <div class="nav-header">
        <div>
          <div class="nav-title">训练</div>
          <div class="nav-desc">GESP / CSP / NOIP 历年真题与模拟套卷，独立于题库、题单与课程</div>
        </div>
      </div>
    </NavbarContext>

    <main class="main-content">
      <section class="library-toolbar" aria-label="试卷筛选">
        <el-radio-group v-model="category" class="category-filter" aria-label="试卷分类" @change="onCategoryChange">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button v-for="cat in CATEGORIES" :key="cat.value" :value="cat.value">
            {{ cat.label }}
          </el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keywordDraft"
          class="search-input"
          clearable
          placeholder="搜索试卷标题"
          aria-label="搜索试卷标题"
          @keyup.enter="applySearch"
          @clear="applySearch"
        >
          <template #append>
            <el-button aria-label="搜索" @click="applySearch">搜索</el-button>
          </template>
        </el-input>
      </section>

      <div class="result-heading">
        <div>
          <h1>试卷库</h1>
          <span>共 {{ total }} 份试卷</span>
        </div>
        <span v-if="keyword" class="active-search">搜索：{{ keyword }}</span>
      </div>

      <el-alert v-if="loadError" class="load-error" type="error" show-icon :closable="false" title="试卷暂时无法加载">
        <template #default>
          <div class="error-content">
            <span>检查网络连接后重试。</span>
            <el-button size="small" type="danger" plain :loading="loading" @click="fetchList">重试</el-button>
          </div>
        </template>
      </el-alert>

      <div v-loading="loading" class="paper-grid" aria-live="polite">
        <RouterLink
          v-for="paper in papers"
          :key="paper.id"
          :to="paperLocation(paper.id)"
          class="paper-link"
          :aria-label="`${paper.title}，${paper.problemCount ?? 0} 题，${paper.totalScore ?? 100} 分`"
        >
          <el-card shadow="hover" class="paper-card">
            <div class="paper-card-top">
              <span class="paper-category">{{ categoryLabel(paper.category) }}</span>
              <span v-if="paper.level" class="paper-level">{{ paper.level }}</span>
            </div>
            <h2 class="paper-title">{{ paper.title }}</h2>
            <p class="paper-meta">
              <span>{{ paper.problemCount ?? 0 }} 题</span>
              <span>{{ paper.totalScore ?? 100 }} 分</span>
              <span>{{ paper.durationMinutes ?? 120 }} 分钟</span>
            </p>
            <p v-if="paper.description" class="paper-desc">{{ paper.description }}</p>
            <span class="paper-action">查看试卷 <span aria-hidden="true">→</span></span>
          </el-card>
        </RouterLink>

        <el-empty v-if="!loading && !loadError && papers.length === 0" description="没有找到符合条件的试卷">
          <el-button v-if="keyword || category" @click="clearFilters">清除筛选</el-button>
        </el-empty>
      </div>

      <el-pagination
        v-if="total > PAGE_SIZE"
        class="paper-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        aria-label="试卷分页"
        @current-change="changePage"
      />
    </main>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavbarContext from '@/components/NavbarContext.vue'
import { getExamPaperList } from '@/api/examPaper'
import { unwrapResultData } from '@/utils/response'

const route = useRoute()
const router = useRouter()
const PAGE_SIZE = 12
const CATEGORIES = [
  { value: 'GESP', label: 'GESP' },
  { value: 'CSP_J', label: 'CSP-J' },
  { value: 'CSP_S', label: 'CSP-S' },
  { value: 'NOIP', label: 'NOIP' },
  { value: 'MOCK', label: '模拟' }
]

const category = ref('')
const keywordDraft = ref('')
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const papers = ref([])
const loading = ref(false)
const loadError = ref(false)
let requestSequence = 0

const categoryLabel = (value) => CATEGORIES.find((item) => item.value === value)?.label || value || '套卷'

const readPage = (value) => {
  const parsed = Number.parseInt(Array.isArray(value) ? value[0] : value, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 1
}

const updateQuery = (changes) => {
  const query = { ...route.query }
  for (const [key, value] of Object.entries(changes)) {
    if (value == null || value === '') delete query[key]
    else query[key] = String(value)
  }
  return router.replace({ name: 'Training', query })
}

const fetchList = async () => {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = false
  try {
    const res = await getExamPaperList({
      page: page.value,
      size: PAGE_SIZE,
      category: category.value || undefined,
      keyword: keyword.value || undefined
    })
    if (sequence !== requestSequence) return
    const data = unwrapResultData(res)
    papers.value = data?.records ?? []
    total.value = Number(data?.total ?? 0)
  } catch {
    if (sequence !== requestSequence) return
    loadError.value = true
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

const onCategoryChange = () => updateQuery({ category: category.value, page: 1 })
const applySearch = () => updateQuery({ keyword: keywordDraft.value.trim(), page: 1 })
const changePage = (nextPage) => updateQuery({ page: nextPage })
const clearFilters = () => {
  keywordDraft.value = ''
  updateQuery({ category: '', keyword: '', page: 1 })
}
const paperLocation = (id) => ({ name: 'TrainingDetail', params: { id }, query: { ...route.query } })

watch(
  () => [route.query.category, route.query.keyword, route.query.page],
  () => {
    const requestedCategory = Array.isArray(route.query.category) ? route.query.category[0] : route.query.category
    category.value = CATEGORIES.some((item) => item.value === requestedCategory) ? requestedCategory : ''
    keyword.value = String(Array.isArray(route.query.keyword) ? route.query.keyword[0] ?? '' : route.query.keyword ?? '').trim()
    keywordDraft.value = keyword.value
    page.value = readPage(route.query.page)
    fetchList()
  },
  { immediate: true }
)
</script>

<style scoped>
.training-page { min-height: 100vh; background: var(--bg-surface-hover); }
.nav-header { padding: 8px 0; }
.nav-title { font-size: 20px; font-weight: 600; }
.nav-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.main-content { max-width: 1180px; margin: 0 auto; padding: 24px; }
.library-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 26px; }
.category-filter { max-width: 100%; overflow-x: auto; }
.search-input { max-width: 320px; }
.result-heading { display: flex; justify-content: space-between; align-items: end; margin-bottom: 16px; }
.result-heading h1 { display: inline; margin: 0 10px 0 0; font-size: 20px; color: var(--text-primary); }
.result-heading span { color: var(--text-secondary); font-size: 13px; }
.active-search { max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.paper-grid { min-height: 180px; display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; align-items: stretch; }
.paper-link { min-width: 0; color: inherit; text-decoration: none; border-radius: 10px; outline-offset: 3px; }
.paper-card { height: 100%; transition: transform .16s ease, border-color .16s ease; }
.paper-link:hover .paper-card { transform: translateY(-2px); border-color: var(--accent-primary); }
.paper-link:focus-visible { outline: 2px solid var(--accent-primary); }
.paper-card :deep(.el-card__body) { height: 100%; min-height: 170px; display: flex; flex-direction: column; padding: 18px; }
.paper-card-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.paper-category { display: inline-flex; align-items: center; font-size: 12px; color: var(--accent-primary); background: var(--bg-surface-soft); border: 1px solid var(--border-light); border-radius: 999px; padding: 3px 10px; }
.paper-title { margin: 13px 0 0; font-size: 16px; line-height: 1.45; font-weight: 650; color: var(--text-primary); }
.paper-level { font-size: 12px; color: var(--accent-warning); }
.paper-meta { display: flex; flex-wrap: wrap; gap: 12px; margin: 12px 0 0; font-size: 12px; color: var(--text-secondary); }
.paper-desc { color: var(--text-regular); font-size: 13px; line-height: 1.55; margin: 10px 0 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.paper-action { margin-top: auto; padding-top: 16px; color: var(--accent-primary); font-size: 13px; font-weight: 600; }
.load-error { margin-bottom: 16px; }
.error-content { display: flex; align-items: center; gap: 12px; }
.paper-pagination { justify-content: center; margin-top: 24px; }
@media (max-width: 760px) {
  .main-content { padding: 16px; }
  .library-toolbar { align-items: stretch; flex-direction: column; gap: 12px; margin-bottom: 20px; }
  .category-filter { overflow-x: auto; white-space: nowrap; }
  .search-input { max-width: none; }
  .paper-grid { grid-template-columns: 1fr; gap: 12px; }
}
@media (prefers-reduced-motion: reduce) {
  .paper-card { transition: none; }
}
</style>
