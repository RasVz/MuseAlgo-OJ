<template>
  <div class="table-scroll-area">
    <el-table 
      :data="homeworkList" 
      style="width: 100%" 
      v-loading="loading" 
      stripe 
      row-key="id" 
      :header-cell-style="{ 
        background: '#f8fafc', color: '#606266', fontWeight: 'bold', height: '55px'
      }"
    >
      <el-table-column label="作业名称" min-width="150">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" class="homework-title-link" @click="goToDoHomework(row)">
            {{ row.title }}
          </el-link>
        </template>
      </el-table-column>

      <el-table-column 
        v-if="showClassColumn" 
        label="所属班级" 
        min-width="150"
      >
        <template #default="{ row }">
          <el-link type="primary" :underline="false" class="homework-title-link" @click="goToClass(row)">
            {{ row.className }}
          </el-link>
        </template>
      </el-table-column>

      <el-table-column label="开始时间" width="220">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.startTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="截止时间" width="220">
        <template #default="{ row }">
          <span class="time-text">{{ formatTime(row.endTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="题目数" width="120">
        <template #default="{ row }">
          <span class="time-text">{{ row.problemCount }}</span>
        </template>
      </el-table-column>

      <!-- <el-table-column label="完成度" width="180">
        <template #default="{ row }">
          <div class="progress-wrapper">
            <el-progress 
              :percentage="calculateProgress(row)" 
              :status="getProgressStatus(row)" 
              :stroke-width="8"
            />
          </div>
        </template>
      </el-table-column> -->
    </el-table>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

defineProps({
  homeworkList: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  },
  // 新增 prop：控制是否显示班级列
  showClassColumn: {
    type: Boolean,
    default: true // 默认显示（用于“我的作业汇总”页面）
  },
})

const router = useRouter()

// --- 业务逻辑 ---

const goToDoHomework = (row) => {
  router.push({ name: 'ProblemListDetail', params: { id: row.id } })
}

const goToClass = (row) => {
  if (row.classId) {
    router.push({ name: 'ClassDirectory', params: { id:row.classId } })
  }
}

const formatTime = (t) => {
  if (!t) return '';
  const timeStr = String(t);
  return timeStr.replace('T', ' ').substring(0, 16);
};


</script>

<style scoped>
/* 样式保持不变 */
.table-scroll-area { flex: 1; overflow-y: auto; padding: 0; }
:deep(.el-table .el-table__cell) { padding: 22px 0; }
.homework-title-link { font-weight: 600; font-size: 15px; }
.homework-title-link:hover { text-decoration: underline; }
.time-text { font-family: 'Menlo', monospace; color: var(--text-secondary); font-size: 13px; }
.progress-wrapper { width: 90%; margin: 0 auto; }
</style>