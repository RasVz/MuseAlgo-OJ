<template>
  <div class="hint-ladder">
    <span class="hint-ladder-title">引导级别</span>
    <el-button
      v-for="(name, idx) in levelNames"
      :key="idx + 1"
      size="small"
      round
      :type="hintLevel === idx + 1 ? 'primary' : ''"
      :disabled="idx + 1 > unlockedLevel || disabled"
      @click="$emit('toggle', idx + 1)"
    >
      L{{ idx + 1 }} {{ name }}
    </el-button>
    <span v-if="hintLevel" class="hint-ladder-current">
      当前 L{{ hintLevel }} {{ levelNames[hintLevel - 1] }}
    </span>
  </div>
</template>

<script setup>
// Hint Ladder 阶梯条：纯展示组件，状态全在 AiAssistant 里。
// unlockedLevel 表示"最多能取到第几级"，比它更高级的档位在模板里被禁用，
// 但真正的边界校验在服务端，前端禁用只是给用户明确的视觉反馈。
defineProps({
  levelNames: { type: Array, default: () => [] },
  hintLevel: { type: Number, default: 0 },
  unlockedLevel: { type: Number, default: 1 },
  disabled: { type: Boolean, default: false }
})
defineEmits(['toggle'])
</script>

<style scoped>
.hint-ladder {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 8px 12px 4px;
}

.hint-ladder-title {
  font-size: 12px;
  color: #909399;
  margin-right: 2px;
}

.hint-ladder-current {
  font-size: 12px;
  color: #409eff;
  margin-left: 4px;
}
</style>
