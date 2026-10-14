<template>
  <el-dialog
    :model-value="modelValue"
    title="FPS 导入结果"
    width="min(680px, 92vw)"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div v-if="result" class="import-summary">
      <el-tag type="info">共 {{ result.total }} 题</el-tag>
      <el-tag type="success">成功 {{ result.succeeded }}</el-tag>
      <el-tag v-if="result.failed" type="danger">失败 {{ result.failed }}</el-tag>
    </div>
    <el-table :data="result?.items || []" max-height="420" stripe>
      <el-table-column prop="position" label="#" width="58" align="center" />
      <el-table-column prop="title" label="题目" min-width="190" show-overflow-tooltip />
      <el-table-column label="状态" width="82" align="center">
        <template #default="{ row }">
          <el-tag :type="row.success ? 'success' : 'danger'" size="small">
            {{ row.success ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="题目 ID" width="90" align="center">
        <template #default="{ row }">{{ row.problemId || '-' }}</template>
      </el-table-column>
      <el-table-column label="测试点" width="80" align="center">
        <template #default="{ row }">{{ row.success ? row.testCaseCount : '-' }}</template>
      </el-table-column>
      <el-table-column prop="message" label="结果" min-width="160" show-overflow-tooltip />
    </el-table>
    <template #footer>
      <el-button type="primary" @click="$emit('update:modelValue', false)">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/** FPS 导入结果弹窗（展示组件） */
defineProps({
  modelValue: { type: Boolean, default: false },
  result: { type: Object, default: null }
})

defineEmits(['update:modelValue'])
</script>

<style scoped>
.import-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
</style>
