<template>
  <div class="class-tab-container" v-loading="loading">
    <div class="tab-header">
      <h3>班级题单</h3>
    </div>

    <div class="table-wrapper">
      <SharedProblemList @detail="goDetail" :problemList="problemList"/>
      <el-empty v-if="!loading && problemList.length === 0" description="暂无题单" />
    </div>

    <div class="pagination-footer">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="fetchData"
      />
    </div>
  </div>
</template>

<script setup>
// 班级题单（学生视角）：教师下发的题单列表，复用公共的 SharedProblemList 展示组件。
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getClassList } from '@/api/class'
import SharedProblemList from '@/views/problem-list/components/SharedProblemList.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const currentPage = ref(1)
const pageSize = 10
const total = ref(0)
const problemList = ref([])

const fetchData = async () => {
  const classId = route.params.id

  if (!classId) return
  loading.value = true
  try {
    const params = {
        page: currentPage.value,
        size: pageSize,
        classId: classId,
        type : 3,
        privacy : 1
    }
    const res = await getClassList(params)
    if (res.code === 200) {
      problemList.value = res.data?.records ?? []
      total.value = Number(res.data?.total ?? 0)
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const goDetail = (row) => {
  if (row && row.id) {
    router.push({ name: 'ProblemListDetail', params: { id: row.id } })
  }
}

// 只改 :id 时路由会复用组件，onMounted 不会再触发，必须监听参数变化，
// 否则从班级 A 切到班级 B 会继续显示 A 的题单（与 ClassHomeworkView 保持一致）。
watch(() => route.params.id, (newId) => {
  if (newId) {
    currentPage.value = 1
    fetchData()
  }
})

onMounted(fetchData)
</script>

<style scoped>
/* 样式与 Homework 保持一致 */
.class-tab-container {
  background: var(--bg-surface);
  border-radius: 0;
  height: 100%;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.tab-header {
  height: 52px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}
.tab-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
}

.table-wrapper {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.pagination-footer {
  height: 56px;
  margin-top: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
  flex-shrink: 0;
}
</style>
