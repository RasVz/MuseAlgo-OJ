<template>
  <div class="review-records-page">
    <header class="page-heading">
      <div>
        <span class="eyebrow">MY AI REVIEWS</span>
        <h1>复盘记录</h1>
        <p>你发起的代码复盘会保存在这里，生成中也可以先离开页面。</p>
      </div>
      <el-button @click="router.push({ name: 'Submission' })">查看提交记录</el-button>
    </header>

    <section class="filter-card">
      <el-input v-model="keyword" clearable placeholder="搜索题目名称或编号" @keyup.enter="search" />
      <el-input v-model="submissionId" clearable placeholder="提交编号" @keyup.enter="search" />
      <el-select v-model="status" clearable placeholder="全部状态">
        <el-option label="排队中" value="QUEUED" />
        <el-option label="生成中" value="RUNNING" />
        <el-option label="停止中" value="CANCELLING" />
        <el-option label="已完成" value="SUCCEEDED" />
        <el-option label="生成失败" value="FAILED" />
        <el-option label="已停止" value="CANCELLED" />
      </el-select>
      <div class="filter-actions">
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </section>

    <section class="records-card">
      <div class="records-toolbar">
        <strong>共 {{ total }} 条复盘记录</strong>
        <span v-if="hasActive">生成中的记录会自动更新</span>
      </div>
      <el-alert v-if="errorMessage" type="error" :closable="false" show-icon class="inline-error">
        <template #title>{{ errorMessage }}</template>
        <el-button size="small" @click="loadData">重试</el-button>
      </el-alert>
      <el-table v-loading="loading" :data="items" row-key="id" empty-text="还没有复盘记录">
        <el-table-column prop="id" label="复盘编号" width="115">
          <template #default="{ row }"><el-link type="primary" @click="openReview(row)">#{{ row.id }}</el-link></template>
        </el-table-column>
        <el-table-column label="原提交" width="115">
          <template #default="{ row }"><el-link @click="openSubmission(row)">#{{ row.submissionId }}</el-link></template>
        </el-table-column>
        <el-table-column label="题目" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">P{{ row.problemId }} {{ row.problemTitle || '题目' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }"><span>{{ aiReviewRelation(row.relationType) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="aiReviewStatus(row.status).type" effect="light">{{ aiReviewStatus(row.status).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="发起时间" min-width="165">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="115">
          <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openReview(row)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <div class="pagination-row">
        <el-pagination background v-model:current-page="page" v-model:page-size="pageSize"
          :page-sizes="[10, 15, 20, 50]" :total="total" layout="prev, pager, next, sizes, jumper"
          @current-change="changePage" @size-change="changeSize" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAiReviewList } from '@/api/aiReview'
import { aiReviewRelation, aiReviewStatus, isAiReviewActive } from '@/utils/aiReview'

const route = useRoute()
const router = useRouter()
const items = ref([])
const total = ref(0)
const loading = ref(false)
const errorMessage = ref('')
const keyword = ref('')
const submissionId = ref('')
const status = ref('')
const page = ref(1)
const pageSize = ref(15)
let generation = 0
let pollTimer

const hasActive = computed(() => items.value.some(item => isAiReviewActive(item.status)))
const queryKey = () => JSON.stringify(route.query)

const syncFromRoute = () => {
  const query = route.query
  keyword.value = typeof query.keyword === 'string' ? query.keyword : ''
  submissionId.value = typeof query.submissionId === 'string' ? query.submissionId : ''
  status.value = typeof query.status === 'string' ? query.status : ''
  page.value = Math.max(1, Number(query.page) || 1)
  pageSize.value = Math.min(50, Math.max(1, Number(query.pageSize) || 15))
}

const buildQuery = (resetPage = false) => {
  const query = {}
  if (keyword.value.trim()) query.keyword = keyword.value.trim()
  if (submissionId.value.trim()) query.submissionId = submissionId.value.trim()
  if (status.value) query.status = status.value
  const nextPage = resetPage ? 1 : page.value
  if (nextPage > 1) query.page = String(nextPage)
  if (pageSize.value !== 15) query.pageSize = String(pageSize.value)
  router.replace({ name: 'AiReviewRecords', query })
}

const loadData = async ({ silent = false } = {}) => {
  const currentGeneration = ++generation
  clearTimeout(pollTimer)
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    const response = await getAiReviewList({
      currentPage: page.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim() || undefined,
      submissionId: submissionId.value.trim() || undefined,
      status: status.value || undefined
    })
    if (currentGeneration !== generation) return
    if (response.code !== 200) throw new Error(response.message || '复盘记录读取失败')
    items.value = response.data?.items || []
    total.value = Number(response.data?.total) || 0
    if (items.value.some(item => isAiReviewActive(item.status))) schedulePoll()
  } catch (error) {
    if (currentGeneration !== generation) return
    errorMessage.value = error.message || '暂时无法读取复盘记录'
  } finally {
    if (currentGeneration === generation && !silent) loading.value = false
  }
}

const schedulePoll = () => {
  clearTimeout(pollTimer)
  if (document.hidden) return
  pollTimer = setTimeout(() => loadData({ silent: true }), 5000)
}
const onVisibilityChange = () => {
  if (document.hidden) clearTimeout(pollTimer)
  else if (hasActive.value) loadData({ silent: true })
}
const search = () => { page.value = 1; buildQuery(true) }
const reset = () => {
  keyword.value = ''
  submissionId.value = ''
  status.value = ''
  page.value = 1
  pageSize.value = 15
  buildQuery(true)
}
const changePage = value => { page.value = value; buildQuery() }
const changeSize = value => { pageSize.value = value; page.value = 1; buildQuery(true) }
const openReview = row => router.push({ name: 'AiReviewDetail', params: { id: row.id } })
const openSubmission = row => router.push({ name: 'SubmissionDetail', params: { id: row.submissionId } })
const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
const formatDuration = value => Number(value) > 0 ? `${(Number(value) / 1000).toFixed(1)} 秒` : '—'

watch(queryKey, async () => {
  syncFromRoute()
  await loadData()
})
onMounted(() => {
  syncFromRoute()
  loadData()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  generation++
  clearTimeout(pollTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.review-records-page { display: grid; gap: 18px; padding: 26px clamp(18px, 3vw, 42px) 34px; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.page-heading h1 { margin: 4px 0 6px; color: var(--text-primary); font-size: 27px; }
.page-heading p, .eyebrow { margin: 0; color: var(--text-secondary); }
.eyebrow { font-size: 11px; letter-spacing: .12em; font-weight: 700; }
.filter-card, .records-card { border: 1px solid var(--border-light); border-radius: var(--radius-card); background: var(--bg-surface); }
.filter-card { display: grid; grid-template-columns: minmax(200px, 1.4fr) minmax(150px, .9fr) minmax(150px, .8fr) auto; align-items: center; gap: 12px; padding: 16px; }
.filter-actions { display: flex; gap: 8px; }
.records-card { overflow: hidden; }
.records-toolbar { min-height: 54px; display: flex; align-items: center; justify-content: space-between; padding: 0 18px; border-bottom: 1px solid var(--border-light); color: var(--text-primary); }
.records-toolbar span { color: var(--text-secondary); font-size: 13px; }
.inline-error { margin: 14px; }
.pagination-row { display: flex; justify-content: flex-end; padding: 16px 18px; }
:deep(.el-table), :deep(.el-table tr), :deep(.el-table th.el-table__cell) { background: var(--bg-surface); color: var(--text-primary); }
:deep(.el-table th.el-table__cell) { color: var(--text-secondary); }
@media (max-width: 900px) { .filter-card { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .page-heading { align-items: flex-start; flex-direction: column; } .filter-card { grid-template-columns: 1fr; } .records-toolbar { align-items: flex-start; flex-direction: column; justify-content: center; gap: 4px; padding: 10px 14px; } .pagination-row { justify-content: center; } }
</style>
