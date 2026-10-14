<template>
  <div class="class-homework-manager">
    <div class="manager-header">
      <div class="left-tools">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索作业名称..."
          prefix-icon="Search"
          style="width: 240px"
          clearable
          @keyup.enter="fetchData"
        />
      </div>
      <el-button type="primary" icon="Plus" @click="handleCreate">
        新建作业
      </el-button>
    </div>

    <el-table
      :data="list"
      style="width: 100%"
      v-loading="loading"
      :header-cell-style="{ background: 'var(--bg-surface-hover)', color: 'var(--text-regular)', fontWeight: 'bold' }"
    >
      <el-table-column prop="title" label="作业名称" min-width="200">
        <template #default="{ row }">
          <div class="homework-title-cell">
            <span class="homework-title">{{ row.title }}</span>
            <el-tag v-if="Number(row.privacy) === 0" size="small" type="info" class="ml-2">草稿</el-tag>
            <el-tag v-else size="small" type="success" class="ml-2">已发布</el-tag>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="起止时间" width="320">
        <template #default="{ row }">
          <div v-if="row.startTime && row.endTime" class="time-range">
            <span>{{ formatTime(row.startTime) }}</span>
            <span class="separator">至</span>
            <span>{{ formatTime(row.endTime) }}</span>
          </div>
          <span v-else class="text-gray">长期有效</span>
        </template>
      </el-table-column>

      <el-table-column prop="problemCount" label="题目数" width="100" align="center" />

      <el-table-column label="更新时间" width="160" align="center">
        <template #default="{ row }">
          <span class="text-gray">{{ formatTimeSimple(row.updateTime || row.createTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="250" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" icon="Edit" @click="handleEdit(row)">编辑作业</el-button>
          <el-button link type="primary" icon="DataLine" @click="handleViewProgress(row)">完成情况</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-box">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="handlePageChange"
      />
    </div>

  </div>
</template>

<script setup>
// 班级作业管理：作业在数据模型上就是「班级题单」（problem_list 的 type=4 且绑定 classId），
// 因此这里直接复用题单接口按 type=4 过滤，作业的增删改跳转到题单编辑器完成。
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProblemLists } from '@/api/problemList'
import dayjs from 'dayjs'

const props = defineProps({
  classId: { type: String, required: true }
})

const router = useRouter()
const loading = ref(false)
const list = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = 10
const searchKeyword = ref('')

// classId 缺失时直接清空而不是发请求：班级切换过程中的空值不应触发无意义的加载态。
const fetchData = async () => {
  if (!props.classId) {
    list.value = []
    total.value = 0
    return
  }

  loading.value = true
  try {
    const params = {
      page: currentPage.value,
      size: pageSize,
      type: 4,
      classId: Number(props.classId),
      keyword: searchKeyword.value
    }
    const res = await getProblemLists(params)
    if (res.code === 200) {
      list.value = res.data?.records || []
      total.value = res.data?.total || 0
    } else {
      ElMessage.error(res.message || '获取作业列表失败')
    }
  } catch (error) {
    console.error(error)
    ElMessage.error('获取作业列表失败')
  } finally {
    loading.value = false
  }
}

const openHomeworkEditor = (id) => {
  const target = router.resolve({
    name: 'ProblemListEdit',
    params: { id: String(id) },
    query: {
      classId: String(props.classId),
      mode: 'homework'
    }
  })
  window.open(target.href, '_blank', 'noopener')
}

const handlePageChange = (val) => {
  currentPage.value = val
  fetchData()
}

const handleCreate = () => {
  openHomeworkEditor('create')
}

const handleEdit = (row) => {
  if (!row?.id) return
  openHomeworkEditor(row.id)
}

const handleViewProgress = (row) => {
  if (!row?.id) {
    ElMessage.warning('作业信息不完整')
    return
  }

  const target = router.resolve({
    name: 'AdminClassHomeworkStatus',
    params: {
      classId: String(props.classId),
      homeworkId: String(row.id)
    },
    query: {
      title: row.title || '作业完成情况'
    }
  })

  window.open(target.href, '_blank', 'noopener')
}

const formatTime = (t) => t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'
const formatTimeSimple = (t) => t ? dayjs(t).format('MM-DD HH:mm') : '-'

watch(
  () => props.classId,
  () => {
    currentPage.value = 1
    fetchData()
  },
  { immediate: true }
)
</script>

<style scoped>
.class-homework-manager {
  padding: 0;
}
.manager-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.homework-title-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.homework-title { font-weight: 600; color: var(--text-primary); }
.ml-2 { margin-left: 8px; }
.time-range { display: flex; align-items: center; gap: 8px; font-family: 'Menlo', monospace; font-size: 13px; color: var(--text-regular); }
.separator { color: var(--text-secondary); }
.text-gray { color: var(--text-secondary); font-size: 13px; }
.pagination-box {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
