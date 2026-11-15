<template>
  <div class="official-view-container">
    <el-row :gutter="20">
      <el-col 
        v-for="item in problemList" 
        :key="item.id" 
        :xs="24" 
        :sm="12" 
        :md="8" 
        :lg="6"
      >
        <OfficialProblemListCard 
          :data="item" 
          :gradient="getGradient(item.id)"
          :is-class-mode="isClassMode"
          @click="handleCardClick(item)"
        />
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
// 官方题单展示区：把题单数据渲染成卡片网格，点击上抛 detail 事件。
// 渐变配色由父级按题单 ID 分配而不是卡片内部随机生成——
// 保证同一题单每次渲染颜色一致，跨页面切换时不会"变色"。
import OfficialProblemListCard from './OfficialProblemListCard.vue'

defineProps({
  // 题单列表数据
  problemList: {
    type: Array,
    required: true
  },
  // 是否为班级模式（作业模式）
  isClassMode: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['detail'])

/**
 * 渐变色按题单 ID 取模分配（而不是按下标）：
 * 这样新增或删除某个题单时，其余题单的颜色不会整体错位。
 */
const getGradient = (id) => {
  // 用语义色 token 生成封面渐变：深色主题下仍然可用，且白字始终可读。
  const gradients = [
    'linear-gradient(135deg, var(--accent-primary) 0%, var(--accent-info) 100%)',
    'linear-gradient(135deg, var(--accent-info) 0%, var(--accent-primary) 100%)',
    'linear-gradient(135deg, var(--accent-success) 0%, var(--accent-info) 100%)',
    'linear-gradient(135deg, var(--accent-warning) 0%, var(--accent-danger) 100%)',
    'linear-gradient(135deg, var(--accent-primary) 0%, var(--accent-success) 100%)'
  ]
  return gradients[id % gradients.length]
}

const handleCardClick = (item) => {
  emit('detail', item)
}
</script>

<style scoped>
.official-view-container {
  /* 卡片间距由外层内容区留白提供，这里不再叠加内边距 */
  padding: 0;
}

/* 移除 el-row 可能带来的横向滚动条 */
:deep(.el-row) {
  margin-left: 0 !important;
  margin-right: 0 !important;
}
</style>