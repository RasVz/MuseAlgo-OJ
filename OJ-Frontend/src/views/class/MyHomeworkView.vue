<template>
  <div class="homework-page-container">
    
    <NavbarContext>
      <div class="navbar-title">
        <el-icon class="mr-2"><Collection /></el-icon>
        我的作业汇总
      </div>
    </NavbarContext>

    <el-card shadow="never" class="main-card">
      
      <HomeworkList 
        :homework-list="homeworkList" 
        :loading="loading" 
        :show-class-column="true"
        :show-creator-column="true"
      />

      <div class="pagination-footer">
        <el-pagination 
          background 
          layout="prev, pager, next, total, sizes" 
          :total="total" 
          :page-sizes="[10, 20, 50]"
          v-model:page-size="pageSize"
          v-model:current-page="currentPage"
          @size-change="handleSizeChange"
          @current-change="fetchData"
        />
      </div>

    </el-card>
  </div>
</template>

<script setup>
// 我的作业：跨班级汇总分派给我的作业。
import { ref, onMounted } from 'vue'
import { Collection } from '@element-plus/icons-vue'
import { getMyHomeworks } from '@/api/problemList'
import NavbarContext from '@/components/NavbarContext.vue'
import HomeworkList from '@/components/HomeworkList.vue' 
import { useUserStore } from '@/stores/userStore'

const loading = ref(false)
const homeworkList = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const userStore = useUserStore()
const userId = userStore.userId

const fetchData = async () => {
  if (!userId) return
  loading.value = true
  try {
    const params = {
        page: currentPage.value,
        size: pageSize.value,
        userId: userId
    }
    const res = await getMyHomeworks(params)
    if (res.code === 200) {
      homeworkList.value = res.data.records
      total.value = res.data.total
    }
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  fetchData()
}

onMounted(() => fetchData())
</script>

<style scoped>
.homework-page-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 85px - 40px);
  overflow: hidden;
}

.navbar-title {
  font-size: 18px;
  font-weight: bold;
  color: var(--text-primary);
  display: flex;
  align-items: center;
}
.mr-2 { margin-right: 8px; }

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  border: none;
  min-height: 0;
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
  min-height: 0;
  overflow: hidden;
}

/* 分页样式 */
.pagination-footer {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
  z-index: 5;
}
</style>