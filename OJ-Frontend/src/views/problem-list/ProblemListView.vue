<template>
  <div class="problem-list-page">
    <FilterBar 
      v-model:searchKey="searchKey" 
      v-model:viewMode="viewMode"
      @search="handleSearch" 
    />

    <div class="list-content-wrapper" v-loading="loading">
      <OfficialProblemListPage 
        v-if="viewMode === 'official'" 
        :problemList="officialList" 
        :is-class-mode="isClassMode" 
        @detail="goDetail"
      />
      
      <SharedProblemList 
        v-else 
        :problemList="sharedList" 
        @detail="goDetail" 
      />
      
      <el-empty 
        v-if="!loading && currentList.length === 0" 
        description="暂无相关题单" 
      />
    </div>

    <div class="pagination-footer">
      <el-pagination 
        background 
        layout="prev, pager, next" 
        :total="total" 
        :page-size="pageSize" 
        :current-page="currentPage"
        @current-change="handlePageChange" 
      />
    </div>
  </div>
</template>

<script setup>
// 题单广场：公开浏览入口，分「官方题单」与「用户共享题单」两种视图。
// 两种视图用同一个接口按 type 区分（官方=2，个人公开=1），所以请求参数由 viewMode 推导。
// 无论哪种视图都强制 privacy=1：私有题单不应出现在任何公开列表里。
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import FilterBar from './components/FilterBar.vue'
import OfficialProblemListPage from './components/OfficialProblemListPage.vue'
import SharedProblemList from './components/SharedProblemList.vue'
import { getProblemLists } from '@/api/problemList'

const props = defineProps({ mode: { type: String, default: 'public' } })
const router = useRouter()

const loading = ref(false)
const searchKey = ref('')
const viewMode = ref('official') // 'official' | 'shared'
const total = ref(0) 
const pageSize = ref(50)
const currentPage = ref(1)

const officialList = ref([])
const sharedList = ref([])

const isClassMode = computed(() => props.mode === 'class_homework')
const currentList = computed(() => viewMode.value === 'official' ? officialList.value : sharedList.value)

// 获取数据
const fetchData = async () => {
  loading.value = true
  try {
    // viewMode 映射到后端 type 参数: 官方=2, 共享=1
    const requestType = viewMode.value === 'official' ? 2 : 1

    const res = await getProblemLists({
      page: currentPage.value,
      size: pageSize.value,
      keyword: searchKey.value,
      type: requestType,
      privacy: 1 // 只查询公开的
    })

    if (res && res.code === 200) {
      const records = res.data.records.map(item => ({
        ...item,
        views: item.favoriteCount || 0 // 字段映射
      }))

      if (viewMode.value === 'official') {
        officialList.value = records
      } else {
        sharedList.value = records
      }
      total.value = res.data.total
    }
  } catch (error) {
    console.error('获取题单列表失败:', error)
  } finally {
    loading.value = false
  }
}

// 跳转详情页
const goDetail = (item) => {
  if (item && item.id) {
    router.push({ name: 'ProblemListDetail', params: { id: item.id } })
  }
}

const handleSearch = () => {
  currentPage.value = 1
  fetchData()
}

const handlePageChange = (p) => { 
  currentPage.value = p
  fetchData() 
}

// 视图切换 → 清空搜索词并回到第 1 页：
// 保留上一个视图的关键词会让用户看到"官方题单 0 条"这种假空结果。
watch(viewMode, () => {
  currentPage.value = 1
  searchKey.value = ''
  fetchData()
})

onMounted(fetchData)
</script>

<style scoped>
.problem-list-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 125px); 
  background: var(--bg-surface);
  border-radius: 12px;
  overflow: hidden; 
}

.list-content-wrapper {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.pagination-footer {
  flex-shrink: 0;
  height: 64px;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
  background: var(--bg-surface);
}
</style>