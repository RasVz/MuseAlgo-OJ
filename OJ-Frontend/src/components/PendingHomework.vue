<template>
  <el-card shadow="hover" class="side-card homework-card">
    <div class="mini-stat-box">
      <div class="stat-left">
        <div class="stat-label">待完成作业</div>
        <div class="stat-number" :class="{ 'text-danger': totalTodo > 0 }">{{ totalTodo }}</div>
      </div>
      <el-button v-if="totalTodo > 0" type="danger" plain size="small" round @click="$router.push({ name: 'MyHomework' })">
        去完成
      </el-button>
      <div v-else class="stat-tip">暂无待办 🎉</div>
    </div>
  </el-card>
</template>

<script setup>
/**
 * 侧栏「待完成作业」计数卡：只显示待办数量与一个跳转按钮，不列具体作业。
 *
 * 之所以不展开作业列表：班级页右栏空间有限且下方还有题目推荐卡，
 * 真正的作业处理在独立的作业页完成，这里只负责「提醒有多少事没做」。
 *
 * 数据来自 getTodoHomeworkCount(userId)，返回的是聚合计数而非列表——
 * 首页只需要数字，没必要把每条作业的题目、截止时间都拉回来。
 *
 * 失败处理：catch 里只记日志，计数保持初始的 0。
 * 0 在模板里的语义是「暂无待办」，把请求失败呈现成「没有待办」并不准确，
 * 但侧栏没有展示错误态的位置，用 0 至少不会给出「点进去却是空的」的误导性指引；
 * 若要更严谨，需要给这张卡加一个独立的错误提示位。
 */
import { ref, onMounted } from 'vue'
import { getTodoHomeworkCount } from '@/api/problemList'
import { useUserStore } from '@/stores/userStore'

// 初始为 0 而非 null：模板直接对它做 > 0 判断与渲染，null 会渲染出空数字
const totalTodo = ref(0)

const userStore = useUserStore()

const fetchTodoData = async () => {
  const userId = userStore.userId
  // 未登录直接返回：这张卡只对登录用户有意义，
  // 且游客访问会得到 401，没必要让请求失败信息进控制台。
  if (!userId) return
  
  try {
    const res = await getTodoHomeworkCount(userId)
    if (res.code === 200) {
      totalTodo.value = res.data 
    }
  } catch (error) {
    console.error("获取待办作业失败", error)
  }
}

onMounted(() => {
  fetchTodoData()
})
</script>

<style scoped>
.side-card {
  border-radius: 16px;
  border: none;
}

.homework-card :deep(.el-card__body) {
  padding: 15px 20px;
}

.mini-stat-box { display: flex; justify-content: space-between; align-items: center; }
.stat-left { display: flex; align-items: baseline; gap: 10px; }
.stat-label { font-size: 14px; color: var(--text-secondary); }
.stat-number { font-size: 24px; font-weight: bold; line-height: 1; color: var(--accent-success); }
.text-danger { color: var(--accent-danger); }
.stat-tip { font-size: 13px; color: var(--text-secondary); }
</style>