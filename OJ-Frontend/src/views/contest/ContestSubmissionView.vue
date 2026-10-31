<template>
  <div class="contest-submission-page">
    <div class="main-area">
      <el-card shadow="never" class="table-card">
        <template #header>
          <div class="header-row">
            <div class="title-wrap">
              <el-icon><Document /></el-icon>
              <span>比赛测评记录</span>
              <el-tag size="small" type="info" effect="plain">共 {{ total }} 条</el-tag>
            </div>

            <div class="toolbar-wrap">
              <el-checkbox v-model="mineOnly" @change="onMineOnlyChange">只看自己</el-checkbox>
              <el-button text @click="resetFilters">重置筛选</el-button>
              <el-button type="primary" @click="fetchData">刷新</el-button>
            </div>
          </div>
        </template>

        <ContestSubmissionTable
          :list="list"
          :loading="loading"
          :is-contest-ended="isContestEnded"
          :my-user-id="userStore.userId"
          @view-user="goToUser"
          @view-problem="goToProblem"
          @view-detail="goDetail"
        />

        <div class="pagination-box">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :total="total"
            v-model:current-page="currentPage"
            :page-size="pageSize"
            @current-change="fetchData"
          />
        </div>
      </el-card>
    </div>

    <div class="side-area">
      <SubmissionFilterPanel
        :filters="filters"
        :problem-options="problemOptions"
        :language-options="languageOptions"
        @update:filters="Object.assign(filters, $event)"
        @apply="onApplyFilter"
      />
    </div>
  </div>
</template>

<script setup>
// 比赛测评记录页：按题目/语言/状态筛选提交记录，默认只看自己的。
// 比赛未结束时他人代码不可见（限制在 ContestSubmissionTable 里），
// 赛后自动放开——这里只负责把 isContestEnded 传下去。
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import {
  getContestSubmissionList,
  getContestSubmissionListFallbackA,
  getContestSubmissionListFallbackB,
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

// 提交记录接口尚未定型，这里依次尝试三条路径：
// 主接口 → 备用 A（不同参数风格）→ 备用 B（路径参数风格）。
// 联调完成后应删掉这段兜底，当前保留是为了让页面在任一版本下都能出数据。
const requestWithFallback = async () => {
  const params = buildParams()

  try {
    return await getContestSubmissionList(contestId.value, params)
  } catch {
    try {
      return await getContestSubmissionListFallbackA(contestId.value, params)
    } catch {
      return await getContestSubmissionListFallbackB({
        ...params,
        contestId: contestId.value
      })
    }
  }
}

// 三个接口的响应形状不一致，统一在这里抹平；遇到不认识的形状要显式报出来，
// 静默返回空列表会让人误判成"这个比赛没人提交"。
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
    ElMessage.error('获取比赛测评记录失败')
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
  const routeUrl = router.resolve({ path: `/problem/${problemId}` })
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
.contest-submission-page {
  display: flex;
  gap: 16px;
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.main-area {
  flex: 1;
  min-width: 0;
}

.side-area {
  width: 260px;
  flex-shrink: 0;
}

.table-card {
  border-radius: 12px;
  border: none;
  box-shadow: var(--shadow-md);
}

.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.toolbar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pagination-box {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.filter-card {
  border-radius: 12px;
  border: none;
  box-shadow: var(--shadow-md);
}

@media (max-width: 900px) {
  .contest-submission-page {
    flex-direction: column;
  }
  .side-area {
    width: 100%;
  }
}
</style>
