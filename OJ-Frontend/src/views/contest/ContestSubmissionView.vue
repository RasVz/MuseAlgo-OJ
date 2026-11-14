<template>
  <div class="contest-submission-page">
    <div class="main-area">
      <div class="table-toolbar">
        <div class="title-wrap">
          <el-icon><Document /></el-icon>
          <span>比赛测评记录</span>
          <el-tag size="small" type="info" effect="plain">共 {{ total }} 条</el-tag>
        </div>

        <div class="toolbar-actions">
          <el-checkbox v-model="mineOnly" @change="onMineOnlyChange">只看自己</el-checkbox>
          <el-button text @click="resetFilters">重置筛选</el-button>
          <el-button type="primary" @click="fetchData">刷新</el-button>
        </div>
      </div>

      <div class="table-body">
        <ContestSubmissionTable
          :list="list"
          :loading="loading"
          :is-contest-ended="isContestEnded"
          :my-user-id="userStore.userId"
          @view-user="goToUser"
          @view-problem="goToProblem"
          @view-detail="goDetail"
        />
      </div>

      <div class="pagination-footer">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          v-model:current-page="currentPage"
          :page-size="pageSize"
          @current-change="fetchData"
        />
      </div>
    </div>

    <aside class="side-area">
      <SubmissionFilterPanel
        :filters="filters"
        :problem-options="problemOptions"
        :language-options="languageOptions"
        @update:filters="Object.assign(filters, $event)"
        @apply="onApplyFilter"
      />
    </aside>
  </div>
</template>

<script setup>
import { showApiError } from '@/utils/errorFeedback'

// 比赛测评记录页：按题目/语言/状态筛选提交记录，默认只看自己的。
// 比赛未结束时他人代码不可见（限制在 ContestSubmissionTable 里），
// 赛后自动放开——这里只负责把 isContestEnded 传下去。
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import {
  getContestSubmissionList,
  getContestProblemList
} from '@/api/contest'
import { useContestStore } from '@/stores/contestContext'
import { useUserStore } from '@/stores/userStore'
import ContestSubmissionTable from './components/ContestSubmissionTable.vue'
import SubmissionFilterPanel from './components/SubmissionFilterPanel.vue'

const route = useRoute()
const router = useRouter()
const contestStore = useContestStore()
const userStore = useUserStore()

const contestId = computed(() => route.params.id)

const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)

const mineOnly = ref(true)

const filters = reactive({
  problemId: null,
  language: '',
  status: null
})

const problemOptions = ref([])
const languageOptions = ref([])

const isContestEnded = computed(() => contestStore.status === 'Ended')

const normalizeRecords = (records = []) => {
  return records.map((item) => {
    const problemId = item.problemId ?? item.pid ?? item.problem?.id
    const problemTitle = item.problemTitle ?? item.problemName ?? item.title ?? item.problem?.title ?? ''
    const language = item.language || item.lang || item.programmingLanguage || ''
    return {
      id: item.id ?? item.submissionId,
      userId: item.userId ?? item.uid ?? item.user?.id,
      username: item.username ?? item.userName ?? item.nickname ?? item.user?.username,
      userAvatar: item.userAvatar ?? item.avatar ?? item.user?.avatar,
      problemId,
      problemTitle,
      language,
      status: item.status ?? item.result,
      createTime: item.createTime ?? item.submitTime
    }
  })
}

const buildParams = () => {
  const params = {
    currentPage: currentPage.value,
    pageSize: pageSize.value,
    mineOnly: mineOnly.value,
    contestId: contestId.value
  }

  if (filters.problemId) params.problemId = filters.problemId
  if (filters.language) params.language = filters.language
  if (filters.status !== null && filters.status !== undefined && filters.status !== '') {
    params.status = filters.status
  }

  return params
}

// Only this endpoint enforces the current contest's submission scope.
const requestWithFallback = () => getContestSubmissionList(contestId.value, buildParams())

const parseResponse = (res) => {
  if (!res || res.code !== 200) {
    return { records: [], total: 0, message: res?.message || '获取数据失败' }
  }

  const data = res.data
  if (Array.isArray(data)) {
    return { records: data, total: data.length, message: '' }
  }

  if (data?.records) {
    return { records: data.records, total: data.total || 0, message: '' }
  }

  if (data?.list) {
    return { records: data.list, total: data.total || data.list.length || 0, message: '' }
  }

  return { records: [], total: 0, message: '返回格式暂不支持' }
}

const fetchData = async () => {
  if (!contestId.value) return
  loading.value = true
  try {
    const res = await requestWithFallback()
    const parsed = parseResponse(res)
    if (parsed.message) {
      ElMessage.warning(parsed.message)
    }
    const normalized = normalizeRecords(parsed.records)
    list.value = normalized
    total.value = parsed.total

    // 语言选项从已加载的记录里累积：翻到不同页会遇到不同语言，
// 累积后筛选下拉始终包含用户实际用过的语言，不必预先维护一份枚举。
    const langSet = new Set(languageOptions.value)
    normalized.forEach(r => {
      if (r.language) langSet.add(r.language)
    })
    languageOptions.value = Array.from(langSet)
  } catch (e) {
    console.error(e)
    showApiError(e, e.message || '获取比赛测评记录失败')
  } finally {
    loading.value = false
  }
}

const fetchProblems = async () => {
  try {
    const res = await getContestProblemList(contestId.value)
    if (res.code === 200 && Array.isArray(res.data)) {
      problemOptions.value = res.data.map(p => ({
        label: `P${p.id} ${p.title}`,
        value: p.id
      }))
    }
  } catch (e) {
    console.error(e)
  }
}

// 提交详情、用户主页、题目页都在新标签页打开：
// 比赛中途离开比赛记录页去对照题目，再回来时筛选状态与页码都还在。
const goToUser = (userId) => {
  if (!userId) return
  const routeUrl = router.resolve({ path: `/user/${userId}` })
  window.open(routeUrl.href, '_blank')
}

const goToProblem = (problemId) => {
  if (!problemId) return
  const routeUrl = router.resolve({ path: `/problem/${problemId}`, query: { contestId: contestId.value } })
  window.open(routeUrl.href, '_blank')
}

// 权限判断在跳转前再做一次：表格里的按钮禁用只是视觉反馈，
// 这里才是真正的关口——直接改 URL 或绕过禁用都能被拦住。
const goDetail = (row) => {
  const canView = isContestEnded.value || (userStore.userId !== null && Number(row.userId) === Number(userStore.userId))
  if (!canView) {
    ElMessage.warning('比赛进行中，仅可查看自己的提交代码')
    return
  }
  if (!row?.id) {
    ElMessage.warning('提交记录ID不存在，无法查看详情')
    return
  }
  // 带上 contestId 让提交详情页知道来源比赛（用于返回路径与赛题上下文）
  const routeUrl = router.resolve({
    path: `/submission/${row.id}`,
    query: {
      contestId: contestId.value
    }
  })
  window.open(routeUrl.href, '_blank')
}

// 筛选条件变化一律回到第 1 页：带着旧页码请求新条件多半落到空页。
const onApplyFilter = () => {
  currentPage.value = 1
  fetchData()
}

const onMineOnlyChange = () => {
  currentPage.value = 1
  fetchData()
}

const resetFilters = () => {
  filters.problemId = null
  filters.language = ''
  filters.status = null
  mineOnly.value = true
  currentPage.value = 1
  fetchData()
}

onMounted(() => {
  fetchData()
  fetchProblems()
})

watch(contestId, () => {
  currentPage.value = 1
  fetchData()
  fetchProblems()
})
</script>

<style scoped>
/* 白底贴边数据页：52px 顶栏 + 内部滚动表格 + 56px 居中分页；48px 为 ContestLayout 页签栏高度 */
.contest-submission-page {
  display: flex;
  height: calc(100vh - var(--app-header-height) - 48px);
  overflow: hidden;
  background: var(--bg-surface);
}

.main-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.side-area {
  width: 260px;
  flex-shrink: 0;
  padding: 20px 24px;
  overflow-y: auto;
  border-left: 1px solid var(--border-light);
  background: var(--bg-surface);
}

.table-toolbar {
  height: 52px;
  flex: none;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border-light);
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.toolbar-actions :deep(.el-button) { border-radius: var(--radius-control); }

.table-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.pagination-footer {
  height: 56px;
  flex: none;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
}
.pagination-footer :deep(.el-pagination.is-background .btn-prev),
.pagination-footer :deep(.el-pagination.is-background .btn-next),
.pagination-footer :deep(.el-pagination.is-background .el-pager li) {
  border-radius: var(--radius-control);
}
</style>
