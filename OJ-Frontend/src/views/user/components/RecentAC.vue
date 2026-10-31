<template>
  <el-card shadow="never" class="recent-ac-card mb-20">
    <template #header><span style="font-weight: 600;">最近通过</span></template>
    <el-table v-if="recentAC.length > 0" :data="recentAC" size="small" table-layout="fixed" style="width: 100%;">
      <el-table-column prop="title" label="题目" min-width="180" show-overflow-tooltip />
      <el-table-column prop="language" label="语言" width="70" />
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.runTime }}ms</template>
      </el-table-column>
      <el-table-column label="时间" width="132">
        <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="暂无通过记录" />
  </el-card>
</template>

<script setup>
// 最近 AC 列表：展示用户最近通过的题目。纯展示组件。
import dayjs from 'dayjs'

defineProps({
  recentAC: { type: Array, default: () => [] }
})

const formatTime = (value) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
</script>

<style scoped>
.recent-ac-card { border-radius: 12px; border: none; }
.mb-20 { margin-bottom: 20px; }
.recent-ac-card :deep(.el-table .cell) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
