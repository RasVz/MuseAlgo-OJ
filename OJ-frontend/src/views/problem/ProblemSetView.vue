<template>
  <div class="problems-page-container">
    <ProblemFilter 
      v-model:filters="filters" 
      @search="fetchData" 
    />

    <transition name="el-zoom-in-top">
      <div class="selected-tags-panel" v-if="hasFilters">
        <div class="panel-header">
          <span class="panel-title">已选标签 ({{ filterCount }})</span>
          <el-button link type="info" size="small" @click="resetAll">
            <el-icon><Delete /></el-icon> 清空
          </el-button>
        </div>
        
        <div class="panel-content">
          <el-tag v-if="filters.difficulty" closable class="custom-tag-blue" @close="clearDifficulty">
            难度: {{ getDiffLabel(filters.difficulty) }}
          </el-tag>
          <el-tag v-for="tag in filters.tags" :key="tag" closable class="custom-tag-blue" @close="removeTag(tag)">
            {{ tag }}
          </el-tag>
          <el-tag v-if="filters.source" closable class="custom-tag-blue" @close="clearSource">
            来源: {{ filters.source }}
          </el-tag>
          <el-tag v-if="filters.userStatus !== ''" closable class="custom-tag-blue" @close="clearStatus">
            状态: {{ getStatusLabel(filters.userStatus) }}
          </el-tag>
          <el-tag v-if="filters.keyword" closable class="custom-tag-blue" @close="clearKeyword">
            关键词: {{ filters.keyword }}
          </el-tag>
        </div>
      </div>
    </transition>

    <el-card shadow="never" class="table-card-wrapper">
      <ProblemTable 
        :data="list" 
        :loading="loading"
        :total="total"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        @change="fetchData"
      />
    </el-card>
  </div>
</template>

<script setup>
// 题库检索页：支持难度、关键词、标签、来源与个人解题状态组合筛选。
// 难度用区间 key 而非具体分数传给后端（由 utils/difficulty 换算），
// "我的解题状态"必须走后端查询而不是前端过滤——一个用户可能有上万条提交记录。
import { ref, onMounted, computed } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import ProblemFilter from './components/ProblemFilter.vue'
import ProblemTable from './components/ProblemTable.vue'
import { getProblemList, searchProblems } from '@/api/problem'
import { useUserStore } from '@/stores/userStore'
import { getDifficultyRangeByKey } from '@/utils/difficulty'

// --- 状态 ---
const loading = ref(false)
const list = ref([])
const total = ref(0) 
const currentPage = ref(1)
const pageSize = ref(50)

const filters = ref({ 
  difficulty: '', 
  keyword: '',
  tags: [],
  source: '',
  userStatus: ''
})

// 注意：这里删除了 tableLayout，因为它现在是 ProblemTable 内部管理的

// --- 计算属性 ---
const hasFilters = computed(() => {
  const f = filters.value
  return !!f.difficulty || !!f.keyword || !!f.source || f.userStatus !== '' || (f.tags && f.tags.length > 0)
})

const filterCount = computed(() => {
  const f = filters.value
  let count = f.tags ? f.tags.length : 0
  if (f.difficulty) count++
  if (f.keyword) count++
  if (f.source) count++
  if (f.userStatus !== '') count++
  return count
})

// --- 题库数据获取 ---
const fetchData = async () => {
  loading.value = true
  try {
    const userId = useUserStore().userId

    const selectedDifficulty = getDifficultyRangeByKey(filters.value.difficulty)
    const keyword = filters.value.keyword

    let res
    if (keyword) {
      // 关键词搜索走 Meilisearch 全文搜索（后端故障自动回退 LIKE，返回结构与 /problem/list 一致）
      res = await searchProblems({
        page: currentPage.value,
        size: pageSize.value,
        keyword: keyword,
        minDifficulty: selectedDifficulty?.min,
        maxDifficulty: selectedDifficulty?.max
      })
    } else {
      const params = {
        page: currentPage.value,
        size: pageSize.value,
        keyword: keyword,
        minDifficulty: selectedDifficulty?.min,
        maxDifficulty: selectedDifficulty?.max,
        tags: filters.value.tags.join(','),
        source: filters.value.source || undefined,
        userStatus: filters.value.userStatus === '' ? undefined : filters.value.userStatus,
        userId: userId
      }
      res = await getProblemList(params)
    }
    
    if (res.code === 200) {
      const pageData = res.data
      list.value = pageData.records.map(item => {
        let tagsArr = []
        try {
          if (item.tags) tagsArr = JSON.parse(item.tags)
        } catch { tagsArr = [item.tags] }

        // 难度由后端以 800-3000 分返回，保留原始数值供表格统一渲染。
        const submitCount = item.submitCount || item.totalCount || 0
        const passCount = item.passCount || 0
        
        // userStatus 语义：0=未开始、1=已通过、2+ = 尝试过但未通过。
// 用 >0 的写法而不是 === 2，是为了兼容后端把"尝试过"记成更大数值（如按提交次数）的情况。
        let statusStr = ''
        if (item.userStatus === 1) statusStr = 'pass'
        else if (item.userStatus === 2 || (item.userStatus > 0)) statusStr = 'tried'

        return {
          id: item.id,
          title: item.title,
          difficulty: Number(item.difficulty),
          tags: tagsArr,
          totalCount: submitCount,
          acceptance: submitCount > 0 
            ? ((passCount / submitCount) * 100).toFixed(1) + '%' 
            : '0.0%',
          status: statusStr
        }
      })
      total.value = pageData.total
    }
  } catch (error) {
    console.error('获取题目列表失败:', error)
  } finally {
    loading.value = false
  }
}

// --- 辅助方法 ---
const getDiffLabel = (key) => getDifficultyRangeByKey(key)?.label || key
const getStatusLabel = (status) => ({ 0: '未开始', 1: '已通过', 2: '尝试过' })[status] || ''

// 每个筛选维度单独清除：整体重置会连带丢掉用户其他条件，
// 在标签很多的页面上很容易让用户重新搭一遍筛选。
const clearDifficulty = () => { 
  filters.value = { ...filters.value, difficulty: '' }
  fetchData() 
}
const clearKeyword = () => { 
  filters.value = { ...filters.value, keyword: '' }
  fetchData() 
}
const removeTag = (tag) => {
  const newTags = filters.value.tags.filter(t => t !== tag)
  filters.value = { ...filters.value, tags: newTags }
  fetchData()
}
const clearSource = () => {
  filters.value = { ...filters.value, source: '' }
  fetchData()
}
const clearStatus = () => {
  filters.value = { ...filters.value, userStatus: '' }
  fetchData()
}

const resetAll = () => {
  filters.value = { difficulty: '', keyword: '', tags: [], source: '', userStatus: '' }
  fetchData()
}

onMounted(() => fetchData())
</script>

<style scoped>
/* 保持原有样式，去除了被子组件吸走的 toolbar 样式 */
.problems-page-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 125px);
  overflow: hidden; 
  position: relative;
}

.selected-tags-panel {
  background: var(--bg-surface);
  box-shadow: var(--shadow-md); 
  border-radius: 8px;
  margin-bottom: 15px; 
  padding: 12px 20px;
  flex-shrink: 0; 
  border: 1px solid var(--border-light);
  z-index: 10;
}
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.panel-title { font-size: 14px; color: var(--text-secondary); }
.panel-content { display: flex; flex-wrap: wrap; gap: 10px; }
.custom-tag-blue {
  background-color: var(--accent-primary-dark) !important;
  border-color: var(--accent-primary-dark) !important;
  color: var(--text-on-accent) !important;
  font-size: 13px;
  padding: 0 12px;
  height: 32px;
  line-height: 30px;
  border-radius: 4px;
}
.custom-tag-blue :deep(.el-tag__close) { color: var(--text-on-accent); }

.table-card-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 12px;
  border: none;
  min-height: 0;
}
/* 这里修改了 padding，因为 toolbar 移进去了，wrapper 本身不需要 padding */
.table-card-wrapper :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0; 
  min-height: 0;
}
</style>
