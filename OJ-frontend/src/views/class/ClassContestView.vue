<template>
  <div class="class-contest-container">
    <el-card class="box-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="header-title">班级比赛列表</span>
          <div class="header-actions">
            <el-input
              v-model="queryParams.keyword"
              placeholder="搜索比赛名称"
              class="search-input"
              clearable
              @keyup.enter="handleSearch"
              @clear="handleSearch"
            >
              <template #append>
                <el-button :icon="Search" @click="handleSearch" />
              </template>
            </el-input>
          </div>
        </div>
      </template>

      <div class="card-content-wrapper">
        <div class="table-wrapper">
          <el-table
            v-loading="loading"
            :data="contestList"
            height="100%"
            style="width: 100%"
            stripe
            @sort-change="handleSortChange"
          >
            <el-table-column prop="id" label="ID" width="100" align="center" sortable="custom" />
            
            <el-table-column prop="title" label="比赛名称" min-width="235" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goToContest(row)" class="contest-title">
                  {{ row.title }}
                </el-link>
              </template>
            </el-table-column>

            <el-table-column prop="startTime" label="开始时间" width="180" align="center">
              <template #default="{ row }">
                {{ formatDate(row.startTime) }}
              </template>
            </el-table-column>

            <el-table-column prop="endTime" label="结束时间" width="180" align="center">
              <template #default="{ row }">
                 {{ formatDate(row.endTime) }}
              </template>
            </el-table-column>

            <el-table-column prop="status" label="状态" width="120" align="center">
              <template #default="{ row }">
                <el-tag v-if="getContestStatus(row) === 'Not Started'" type="info" effect="light">未开始</el-tag>
                <el-tag v-else-if="getContestStatus(row) === 'Running'" type="success" effect="dark">进行中</el-tag>
                <el-tag v-else type="danger" effect="plain">已结束</el-tag>
              </template>
            </el-table-column>

            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="goToContest(row)">
                  进入比赛
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="pagination-container">
          <el-pagination
            v-model:current-page="queryParams.currentPage"
            :background="true"
            layout="total, prev, pager, next, jumper"
            :total="total"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
          />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 学生视角的班级比赛列表：只看本班已发布的比赛，可进入题面或排行榜。
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getClassContests } from '@/api/classContest'

const route = useRoute()
const router = useRouter()
const classId = computed(() => {
  const value = route.params.id ?? route.params.classId ?? route.query.classId
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
})

const loading = ref(false)
const contestList = ref([])
const total = ref(0)

const queryParams = ref({
  currentPage: 1,
  pageSize: 10,
  keyword: '',
  sortField: '', // 新增：排序字段
  sortOrder: ''  // 新增：排序方向 (asc/desc)
})

const extractResponseData = (res) => res?.data ?? res ?? {}

const extractListData = (res) => {
  const data = extractResponseData(res)
  if (Array.isArray(data)) return data
  if (Array.isArray(data.records)) return data.records
  if (Array.isArray(data.list)) return data.list
  if (Array.isArray(data.rows)) return data.rows
  return []
}

const extractTotal = (res, list) => {
  const data = extractResponseData(res)
  const rawTotal = data?.total ?? data?.count ?? list.length
  const parsedTotal = Number(rawTotal)
  return Number.isFinite(parsedTotal) && parsedTotal >= 0 ? parsedTotal : list.length
}

// 班级接口与全局比赛接口的字段名不同（contestName/beginTime/finishTime），
// 归一后模板只认一套字段。
const normalizeContest = (item) => ({
  id: item?.id ?? item?.contestId ?? null,
  title: item?.title ?? item?.contestName ?? '-',
  startTime: item?.startTime ?? item?.beginTime ?? null,
  endTime: item?.endTime ?? item?.finishTime ?? null,
  visible: Number(item?.visible ?? 1) === 0 ? 0 : 1
})

// 表格排序变化处理：order 为 null 表示用户取消了排序，
// 此时也要清空 sortOrder，否则下次点击其他列会带着上一次的排序方向。
const handleSortChange = ({ prop, order }) => {
  queryParams.value.sortField = prop || ''
  if (order === 'ascending') {
    queryParams.value.sortOrder = 'asc'
  } else if (order === 'descending') {
    queryParams.value.sortOrder = 'desc'
  } else {
    queryParams.value.sortOrder = '' // 取消排序
  }
  
  queryParams.value.currentPage = 1 // 重新排序后回到第一页
  fetchContests()
}

const formatDate = (date) => {
  if (!date) return '-'
  return dayjs(date).format('YYYY-MM-DD HH:mm')
}

// 比赛状态由起止时间实时推算：不存字段、不轮询，
// 页面开着不动时状态也会在跨过起点/终点的那一刻自动翻转。
const getContestStatus = (contest) => {
  const now = dayjs()
  const start = dayjs(contest.startTime)
  const end = dayjs(contest.endTime)
  
  if (now.isBefore(start)) {
    return 'Not Started'
  } else if (now.isAfter(end)) {
    return 'Ended'
  } else {
    return 'Running'
  }
}

const fetchContests = async () => {
  if (!classId.value) {
    contestList.value = []
    total.value = 0
    return
  }

  loading.value = true
  try {
    const res = await getClassContests(classId.value, {
      ...queryParams.value,
      visible: 1
    })

    if (res?.code != null && Number(res.code) !== 200) {
      ElMessage.error(res.message || '获取比赛列表失败')
      contestList.value = []
      total.value = 0
      return
    }

    // visible=1 只取已发布的比赛；前端再过滤一次作为兜底，
// 防止接口未按该参数过滤时把教师草稿态比赛暴露给学生。
    const list = extractListData(res)
      .map(item => normalizeContest(item))
      .filter(item => Number(item.visible) !== 0)

    contestList.value = list
    total.value = extractTotal(res, list)
  } catch (error) {
    console.error(error)
    ElMessage.error('网络或服务器异常，获取数据失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.value.currentPage = 1
  fetchContests()
}

const handleSizeChange = (val) => {
  queryParams.value.pageSize = val
  fetchContests()
}

const handleCurrentChange = (val) => {
  queryParams.value.currentPage = val
  fetchContests()
}

const goToContest = (row) => {
  router.push(`/contest/${row.id}`)
}

watch(
  classId,
  () => {
    queryParams.value.currentPage = 1
    fetchContests()
  },
  { immediate: true }
)
</script>

<style scoped>
/* 整个容器撑满剩余高度 */
.class-contest-container {
  padding: 10px;
  height: calc(100vh - 145px); /* 视口高度减去顶栏等高度 */
  display: flex;
  flex-direction: column;
}

/* 卡片使用 Flex 布局撑满 */
.box-card {
  border-radius: 10px;
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* 重写 el-card 的 body 样式，让它成为 Flex 容器 */
:deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden; /* 防止内容撑破卡片 */
  padding: 15px 20px 20px 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.search-input {
  width: 280px;
}

.contest-title {
  font-weight: 500;
  font-size: 15px;
}

/* 将包裹器设为 Flex 列 */
.card-content-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0; /* 这是 Flexbox 滚动必须的神奇属性 */
}

/* 表格容器占据所有剩余空间 */
.table-wrapper {
  flex: 1;
  min-height: 0;
}

/* 分页容器固定且居中 */
.pagination-container {
  margin-top: 15px;
  display: flex;
  justify-content: center;
  flex-shrink: 0; /* 防止被压缩 */
}

:deep(.el-table .el-table__row) {
  height: 51.5px; /* 每页 10 行刚好填满容器；改每页条数时须同步调整此值 */
}
</style>
