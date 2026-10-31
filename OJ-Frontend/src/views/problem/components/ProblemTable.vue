<template>
  <div class="problem-table-container">
    <div class="table-toolbar">
      <span class="total-text">共 {{ total }} 道题目</span>
      
      <div class="toolbar-actions">
        <el-button 
          size="small"
          :type="layout.showTags ? 'primary' : ''" 
          :plain="!layout.showTags"
          round
          @click="layout.showTags = !layout.showTags"
        >
          显示算法标签
        </el-button>

        <el-button 
          size="small"
          :type="layout.showDifficulty ? 'primary' : ''" 
          :plain="!layout.showDifficulty"
          round
          @click="layout.showDifficulty = !layout.showDifficulty"
        >
          显示题目难度
        </el-button>
      </div>
    </div>

    <div class="table-body">
      <el-table 
        :data="data" 
        style="width: 100%" 
        stripe 
        v-loading="loading" 
        :header-cell-style="{ background: 'var(--el-fill-color-light)', color: 'var(--text-regular)', fontWeight: 'bold' }"
        @row-click="handleRowClick"
        class="problem-table-pointer"
      >
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-icon v-if="row.status === 'pass'" color="var(--accent-success)" size="18"><CircleCheckFilled /></el-icon>
            <el-icon v-else-if="row.status === 'tried'" color="var(--accent-warning)" size="18"><CircleCloseFilled /></el-icon>
          </template>
        </el-table-column>
        
        <el-table-column prop="id" label="ID" width="100" />
        
        <el-table-column label="题目名称" min-width="250">
          <template #default="{ row }">
            <span class="title-text">{{ row.title }}</span>
          </template>
        </el-table-column>

        <el-table-column 
          label="算法标签" 
          min-width="180" 
          v-if="layout.showTags"
        >
          <template #default="{ row }">
            <div class="tags-box">
              <el-tag 
                v-for="t in row.tags" 
                :key="t" 
                size="small" 
                effect="plain" 
                class="m-tag"
              >
                {{ t }}
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column 
          label="难度" 
          width="120"
          v-if="layout.showDifficulty"
        >
          <template #default="{ row }">
            <span class="diff" :style="{ color: getDifficultyColor(row.difficulty) }">
              {{ getDifficultyLabel(row.difficulty) }} <small>{{ row.difficulty || '-' }}</small>
            </span>
          </template>
        </el-table-column>

        <el-table-column 
          prop="totalCount" 
          label="提交" 
          width="100" 
          align="center"
        >
          <template #default="{ row }">
            <span style="font-family: monospace; color: var(--text-regular);">{{ row.totalCount }}</span>
          </template>
        </el-table-column>

        <el-table-column 
          prop="acceptance" 
          label="通过率" 
          width="100" 
          align="center"
        />
      </el-table>
    </div>

    <div class="pagination-footer" v-if="showPagination">
      <el-pagination 
        background 
        layout="prev, pager, next, sizes, jumper" 
        :total="total" 
        :page-sizes="[20, 50, 100]"
        v-model:current-page="currentPageInner"
        v-model:page-size="pageSizeInner"
        @size-change="handlePaginationChange"
        @current-change="handlePaginationChange"
      />
    </div>
  </div>
</template>

<script setup>
// 题库结果表格：受控展示组件，行点击跳转到题面页。
// 排序与分页都由父级 ProblemSetView 持有，这里只发意图，
// 保证"筛选条件 + 页码 + 排序"始终在同一处组合，不会各管各的。
import { reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const router = useRouter()

const props = defineProps({
  data: { type: Array, default: () => [] }, // 统一改名为 data
  loading: Boolean,
  total: { type: Number, default: 0 },
  
  // 翻页控制
  showPagination: { type: Boolean, default: true },
  currentPage: { type: Number, default: 1 },
  pageSize: { type: Number, default: 20 }
})

const emit = defineEmits(['update:currentPage', 'update:pageSize', 'change'])

// 内部状态管理显示布局
const layout = reactive({
  showTags: false,
  showDifficulty: true
})

// 使用计算属性处理 v-model 透传
const currentPageInner = computed({
  get: () => props.currentPage,
  set: (val) => emit('update:currentPage', val)
})

const pageSizeInner = computed({
  get: () => props.pageSize,
  set: (val) => emit('update:pageSize', val)
})

// 触发翻页事件
const handlePaginationChange = () => {
  emit('change')
}

// 行点击跳转
const handleRowClick = (row) => router.push({ name: 'ProblemDetail', params: { id: row.id } })

</script>

<style scoped>
.problem-table-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-surface);
  border-radius: 8px;
}

/* 工具栏样式 */
.table-toolbar {
  padding: 10px 20px;
  border-bottom: 1px solid var(--border-light);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.total-text { font-size: 13px; color: var(--text-secondary); }
.toolbar-actions { display: flex; gap: 10px; }

/* 表格主体样式 */
.table-body {
  flex: 1;
  overflow: auto; /* 允许表格内部滚动 */
}

/* 翻页样式 */
.pagination-footer {
  padding: 10px 0;
  display: flex;
  justify-content: center;
  align-items: center;
  border-top: 1px solid var(--border-light);
}

.problem-table-pointer { cursor: pointer; }
.title-text { font-weight: 600; font-size: 15px; color: var(--text-primary); transition: color 0.2s; }
.problem-table-pointer :deep(.el-table__row:hover) .title-text { color: var(--accent-primary); }
.tags-box { display: flex; gap: 4px; flex-wrap: wrap; }
.m-tag { border-radius: 4px; font-size: 11px; background-color: var(--el-fill-color-light); border: none; color: var(--text-regular); }
.diff { font-weight: 600; }
.diff small { margin-left: 4px; color: var(--text-secondary); font-family: var(--font-mono); font-size: 11px; font-weight: 500; }
</style>
