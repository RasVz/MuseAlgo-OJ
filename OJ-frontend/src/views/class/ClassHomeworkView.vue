<template>
  <div class="class-tab-container" v-loading="loading">
    <div class="tab-header">
      <h3>班级作业</h3>
    </div>

    <div class="table-wrapper">
      <HomeworkList 
        :homework-list="problemList" 
        :loading="loading" 
        :show-class-column="false"
      />
      <el-empty v-if="!loading && problemList.length === 0" description="暂无作业" />
    </div>

    <div class="pagination-footer">
      <el-pagination background layout="prev, pager, next"
        :total="total" :page-size="pageSize"
        v-model:current-page="currentPage"
        @current-change="fetchData"
      />
    </div>
  </div>
</template>

<script setup>
// 学生视角的班级作业列表：作业在数据模型上是「班级题单」（type=4），
// 这里按 classId + type=4 过滤，privacy 决定是未发布还是已发布。
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getProblemLists } from '@/api/problemList'
import HomeworkList from '@/components/HomeworkList.vue' 

const route = useRoute()
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 20
const total = ref(0)
const problemList = ref([])

// privacy 在作业语境下的语义是"是否已发布"（不是公开/私有）：
// 0=草稿（学生不可见），1=已发布。
const isPublishedHomework = (item) => Number(item?.privacy ?? 0) === 1

// classId 缺失直接返回：路由尚未就绪时发请求会拿到全站作业。
const fetchData = async () => {
  const classId = route.params.id
  
  if (!classId) {
    console.error("未找到班级ID")
    return
  }

  loading.value = true
  try {
    // type=4 表示班级作业；privacy=1 只查已发布
    const res = await getProblemLists({
      page: currentPage.value,
      size: pageSize,
      classId: classId,
      type: 4,
      privacy: 1
    })

    if (res.code === 200) {
      const records = Array.isArray(res.data?.records) ? res.data.records : []
      // 前端再过滤一次作为兜底：接口若未按 privacy 过滤，
      // 学生会看到教师还没发布的作业草稿。
      const publishedRecords = records.filter(isPublishedHomework)
      problemList.value = publishedRecords
      total.value = Number(res.data?.total ?? publishedRecords.length)
    }
  } catch (error) {
    console.error('获取作业列表失败', error)
  } finally {
    loading.value = false
  }
}

watch(() => route.params.id, (newId) => {
  if(newId) fetchData()
})

onMounted(fetchData)
</script>

<style scoped>
.class-tab-container {
  background: #fff;
  border-radius: 12px;
  height: 93%;
  padding: 20px;
  display: flex;
  flex-direction: column;
}

.tab-header {
  margin-bottom: 20px;
  flex-shrink: 0;
}
.tab-header h3 {
  margin: 0;
  font-size: 18px;
  color: #303133;
}

.table-wrapper {
  flex: 1;
  overflow-y: auto; 
}

.pagination-footer {
  margin-top: 20px;
  display: flex;
  justify-content: center;
  flex-shrink: 0;
}
</style>
