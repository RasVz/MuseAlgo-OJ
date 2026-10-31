<template>
  <div class="class-contest-manager">
    <div class="action-header">
      <el-button type="primary" @click="handleAdd">
        添加比赛
      </el-button>
    </div>

    <el-table
      :data="tableData"
      v-loading="loading"
      border
      style="width: 100%; margin-top: 20px;"
    >
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="title" label="比赛名称" min-width="150" />
      <el-table-column prop="description" label="比赛描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="startTime" label="开始时间" width="170" align="center" />
      <el-table-column prop="endTime" label="结束时间" width="170" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.startTime, row.endTime)">
            {{ getStatusText(row.startTime, row.endTime) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="handleEdit(row)">
            编辑
          </el-button>
          <!-- <el-button size="small" type="danger" link @click="handleDelete(row)">
            删除
          </el-button> -->
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-container">
      <el-pagination
        v-model:current-page="queryParams.page"
        v-model:page-size="queryParams.limit"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="fetchData"
        @current-change="fetchData"
      />
    </div>
  </div>
</template>

<script setup>
// 班级比赛列表：只读展示本班的比赛，实际的创建/编辑复用全局 ContestEditorView
// （带上 classId 参数即进入班级模式），因此本组件不含任何写操作。
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getClassContests,
} from '@/api/classContest'

const props = defineProps({
  classId: {
    type: [Number, String],
    required: true
  }
})

const router = useRouter()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const queryParams = reactive({
  page: 1,
  limit: 10
})

// 比赛状态由起止时间在前端实时推算：比赛列表页不做轮询，
// 用 computed 语义的方法比存一个 status 字段更不容易出现"已结束仍显示进行中"。
const getStatusText = (start, end) => {
  if (!start || !end) return '未知'
  const now = new Date().getTime()
  const startTime = new Date(start).getTime()
  const endTime = new Date(end).getTime()

  if (now < startTime) return '未开始'
  if (now >= startTime && now <= endTime) return '进行中'
  return '已结束'
}

const getStatusType = (start, end) => {
  const text = getStatusText(start, end)
  if (text === '未开始') return 'info'
  if (text === '进行中') return 'success'
  if (text === '已结束') return 'danger'
  return ''
}

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

// 班级接口返回的字段名与全局比赛接口不一致（contestName/beginTime 等），
// 归一到统一结构，模板只认归一后的字段。
const normalizeContest = (item) => ({
  id: item?.id ?? item?.contestId ?? null,
  title: item?.title ?? item?.contestName ?? '-',
  description: item?.description ?? item?.contestDesc ?? '',
  startTime: item?.startTime ?? item?.beginTime ?? '',
  endTime: item?.endTime ?? item?.finishTime ?? '',
  visible: Number(item?.visible ?? 1) === 0 ? 0 : 1
})

const fetchData = async () => {
  if (!props.classId) {
    tableData.value = []
    total.value = 0
    return
  }

  loading.value = true
  try {
    const res = await getClassContests(props.classId, { ...queryParams })

    if (res?.code != null && Number(res.code) !== 200) {
      ElMessage.error(res.message || '获取比赛列表失败')
      tableData.value = []
      total.value = 0
      return
    }

    const list = extractListData(res).map(item => normalizeContest(item))
    tableData.value = list
    total.value = extractTotal(res, list)
  } catch {
    ElMessage.error('获取比赛列表失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  router.push({
    name: 'AdminContestCreate',
    params: { classId: props.classId },
    query: { classId: String(props.classId) }
  })
}

const handleEdit = (row) => {
  router.push({
    name: 'AdminContestEdit',
    params: { classId: props.classId, id: row.id },
    query: { classId: String(props.classId) }
  })
}


watch(
  () => props.classId,
  () => {
    queryParams.page = 1
    fetchData()
  }
)

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.class-contest-manager {
  padding: 10px 0;
}
.action-header {
  margin-bottom: 20px;
  display: flex;
  justify-content: flex-start;
}
.pagination-container {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
