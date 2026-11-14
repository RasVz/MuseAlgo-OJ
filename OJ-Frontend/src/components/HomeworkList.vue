<template>
  <div class="table-scroll-area">
    <el-table 
      :data="homeworkList" 
      style="width: 100%" 
      v-loading="loading" 
      row-key="id" 
      class="homework-table"
      :header-cell-style="{ 
        background: 'var(--bg-app)', color: 'var(--text-regular)', fontWeight: '600'
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

      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag
            :type="homeworkStatus(row).type"
            size="small"
            effect="plain"
            class="status-tag"
            :class="homeworkStatus(row).tone"
          >
            {{ homeworkStatus(row).label }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="完成进度" width="160">
        <template #default="{ row }">
          <div class="progress-cell">
            <el-progress
              :percentage="progressPercent(row)"
              :stroke-width="4"
              :show-text="false"
              :status="progressPercent(row) >= 100 ? 'success' : ''"
            />
            <span class="progress-text">{{ row.solvedCount || 0 }} / {{ row.problemCount || 0 }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="时间范围" width="240">
        <template #default="{ row }">
          <div class="time-range">
            <span class="time-line"><em>开始</em>{{ formatTime(row.startTime) || '未设置' }}</span>
            <span class="time-line"><em>截止</em>{{ formatTime(row.endTime) || '未设置' }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="题目数" width="90">
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
import dayjs from 'dayjs'

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

// 作业状态按起止时间实时推算，不依赖后端额外字段。
// 未开始/已结束用中性色，进行中保留成功色，避免整列都是蓝色标签。
const homeworkStatus = (row) => {
  if (!row.startTime || !row.endTime) return { label: '未设置', type: 'info', tone: 'muted' }
  const now = dayjs()
  if (now.isBefore(dayjs(row.startTime))) return { label: '未开始', type: 'info', tone: 'muted' }
  if (now.isBefore(dayjs(row.endTime))) return { label: '进行中', type: 'success', tone: 'active' }
  return { label: '已结束', type: 'info', tone: 'muted' }
};

// 完成度用已通过题数 / 题目数；题目数缺失时返回 0，避免除零。
const progressPercent = (row) => {
  const total = Number(row.problemCount) || 0
  const solved = Number(row.solvedCount) || 0
  if (!total) return 0
  return Math.min(100, Math.round((solved / total) * 100))
};


</script>

<style scoped>
/* 样式保持不变 */
.table-scroll-area { flex: 1; overflow-y: auto; padding: 0; }
.homework-table { --el-table-border-color: var(--border-light); }
.homework-table :deep(th.el-table__cell) { height: var(--table-header-height); padding: 0; }
/* 行内含两行时间与进度条，行高放宽到 56px */
.homework-table :deep(td.el-table__cell) { height: 56px; padding: 0; }
.homework-table :deep(.el-table__cell .cell) { padding: 0 12px; }
.homework-table :deep(th:first-child .cell),
.homework-table :deep(td:first-child .cell) { padding-left: 24px; }
.homework-table :deep(th:last-child .cell),
.homework-table :deep(td:last-child .cell) { padding-right: 24px; }
.homework-title-link { font-weight: 600; font-size: 14px; }
.homework-title-link:hover { text-decoration: underline; }
.time-text { font-family: var(--font-mono); color: var(--text-secondary); font-size: 13px; }

.status-tag { border-radius: 999px; }
.status-tag.muted { background: var(--bg-app); border-color: var(--border-base); color: var(--text-secondary); }

.progress-cell { display: flex; align-items: center; gap: 8px; }
.progress-cell :deep(.el-progress) { flex: none; width: 70px; }
.progress-text { flex: none; font-family: var(--font-mono); color: var(--text-secondary); font-size: 12px; }

.time-range { display: flex; flex-direction: column; gap: 2px; line-height: 1.4; }
.time-line { display: flex; align-items: center; gap: 6px; font-family: var(--font-mono); color: var(--text-secondary); font-size: 12px; }
.time-line em { font-style: normal; font-family: var(--font-family); color: var(--text-placeholder); font-size: 11px; }
.progress-wrapper { width: 90%; margin: 0 auto; }
</style>
