<template>
  <div class="class-homework-status-page">
    <div class="page-header">
      <div class="header-main">
        <div class="page-title">{{ pageTitle }}</div>
        <div class="page-subtitle">可视化查看班级作业完成情况与提交记录</div>
      </div>
      <div class="header-actions">
        <el-button @click="goBack">返回课程管理</el-button>
      </div>
    </div>

    <el-card shadow="never" class="page-card">
      <ClassHomeworkStatus
        :class-id="route.params.classId"
        :homework-id="route.params.homeworkId"
      />
    </el-card>
  </div>
</template>

<script setup>
// 作业完成情况独立页：从作业管理里以新标签页打开，承载较宽的完成度表格。
// 标题通过 query 传入而不是再请求一次接口——打开它的上下文里已经有作业名。
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ClassHomeworkStatus from './components/ClassHomeworkStatus.vue'

const route = useRoute()
const router = useRouter()

const pageTitle = computed(() => {
  const title = route.query.title
  return typeof title === 'string' && title ? `作业完成情况 - ${title}` : '作业完成情况'
})

// 返回优先用 history.back()：本页多由新标签页直接打开，
// 这种情况下 history.length 为 1，退回会退到空白页，所以要兜底到班级详情。
const goBack = () => {
  if (window.history.length > 1) {
    router.back()
    return
  }

  router.push({
    name: 'AdminClassDetail',
    params: {
      classId: String(route.params.classId)
    }
  })
}
</script>

<style scoped>
.class-homework-status-page {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.header-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.page-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.4;
}

.page-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
}

.page-card {
  border-radius: 12px;
}

@media (max-width: 768px) {
  .class-homework-status-page {
    padding: 12px;
  }
}
</style>
