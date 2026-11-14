<template>
  <main class="review-book" v-loading="loading">
    <NavbarContext>
      <div class="navbar-title"><el-icon><Reading /></el-icon><span>错题本</span></div>
    </NavbarContext>

    <header class="page-header">
      <div>
        <span class="eyebrow">REVIEW PLAN</span>
        <h1>错题本与复习计划</h1>
        <p>系统会自动收录反复未通过、或长时间未解决的题目，并记录每题的尝试次数与首次、最后尝试时间。掌握后标记完成，让复习列表保持专注。</p>
      </div>
    </header>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <section v-if="items.length" class="review-list">
      <article v-for="item in items" :key="item.problemId" class="review-item">
        <div class="review-index">#{{ item.problemId }}</div>
        <div class="review-content">
          <div class="title-row">
            <h2>{{ item.title }}</h2>
            <span v-if="item.difficulty" class="difficulty" :style="{ color: difficultyColor(item.difficulty), backgroundColor: difficultyColor(item.difficulty) + '14' }">{{ difficultyLabel(item.difficulty) }} · {{ item.difficulty }}</span>
          </div>
          <div class="review-meta">
            <span class="reason"><el-icon><WarningFilled /></el-icon>{{ reasonLabel(item.reason) }}</span>
            <span>尝试 {{ item.attemptCount ?? 0 }} 次</span>
            <span>首次尝试 {{ timeAgo(item.firstAttemptAt) }}</span>
            <span>最后尝试 {{ timeAgo(item.lastAttemptAt) }}</span>
          </div>
        </div>
        <div class="review-actions">
          <el-button @click="goProblem(item.problemId)">去做题</el-button>
          <el-button type="primary" plain :loading="updatingId === item.problemId" @click="markMastered(item)">
            <el-icon><CircleCheck /></el-icon> 标记掌握
          </el-button>
        </div>
      </article>
    </section>

    <el-empty v-else-if="!loading && !loadError" :image-size="100" description="还没有待复习的错题">
      <el-button type="primary" @click="router.push({ name: 'ProblemSet' })">去题库练习</el-button>
    </el-empty>
  </main>
</template>

<script setup>
// 错题本：汇总"未通过 / 久未通过"的题目，展示尝试次数与首次、最后尝试时间，供学生复习与标记已掌握。
// 入选规则由后端判定（多次未通过、超过 7 天未通过），前端只做展示与状态切换。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, Reading, WarningFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import NavbarContext from '@/components/NavbarContext.vue'
import { getReviewBook, markReviewMastered } from '@/api/user'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
let loadGeneration = 0
const updatingId = ref(null)
const items = ref([])
const difficultyColor = getDifficultyColor
const difficultyLabel = getDifficultyLabel
const reasonLabel = (reason) => reason === 'UNSOLVED_7_DAYS' ? '超过 7 天未通过' : '多次未通过'
// 列表展示"多久之前"，避免让学生自己换算日期。
const timeAgo = (value) => {
  if (!value) return '-'
  const minutes = dayjs().diff(dayjs(value), 'minute')
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  const months = Math.floor(days / 30)
  return months < 12 ? `${months} 个月前` : `${Math.floor(months / 12)} 年前`
}

const load = async () => {
  const generation = ++loadGeneration
  loading.value = true
  loadError.value = ''
  try {
    const res = await getReviewBook()
    if (generation === loadGeneration) items.value = res.data || []
  } catch (error) {
    if (generation === loadGeneration) loadError.value = error.message || '错题本加载失败，请重试'
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}
const goProblem = (id) => router.push({ name: 'ProblemDetail', params: { id } })
// 标记掌握后直接从列表移除：这道题已不再需要复习，
// 留在原地会让"待复习数量"与实际不符。
const markMastered = async (item) => {
  updatingId.value = item.problemId
  try {
    const res = await markReviewMastered(item.problemId, true)
    if (res.code !== 200) return ElMessage.error(res.msg || '更新失败')
    items.value = items.value.filter(entry => entry.problemId !== item.problemId)
    ElMessage.success('已标记掌握，做得很好！')
  } catch (error) {
    console.error(error)
  } finally {
    updatingId.value = null
  }
}
onMounted(load)
</script>

<style scoped>
.review-book { min-height: calc(100vh - var(--app-header-height)); box-sizing: border-box; background: var(--bg-surface); padding: 20px 20px 54px; }
.review-book > .page-header, .review-book > .el-alert, .review-book > .review-list, .review-book > .el-empty { max-width: 980px; margin-left: auto; margin-right: auto; }
.navbar-title { display: flex; align-items: center; gap: 8px; color: var(--text-primary); font-size: 18px; font-weight: 650; }
.page-header { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 25px; }
.eyebrow { display: block; margin-bottom: 5px; color: var(--accent-primary); font-size: 10px; font-weight: 750; letter-spacing: .13em; }
h1 { margin: 0; color: var(--text-primary); font-size: 26px; line-height: 1.35; }
.page-header p { max-width: 680px; margin: 8px 0 0; color: var(--text-regular); font-size: 14px; line-height: 1.65; }
.review-list { margin-top: 16px; border-top: 1px solid var(--border-light); }
.review-item { display: flex; gap: 15px; align-items: center; padding: 18px 6px; border-bottom: 1px solid var(--border-light); }
.review-index { width: 48px; color: var(--text-placeholder); font-size: 13px; font-variant-numeric: tabular-nums; }
.review-content { min-width: 0; flex: 1; }
.title-row { display: flex; align-items: center; gap: 9px; min-width: 0; }
h2 { overflow: hidden; margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.difficulty { flex: 0 0 auto; padding: 3px 7px; border-radius: var(--radius-tag); font-size: 11px; font-weight: 700; }
.review-meta { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 7px; color: var(--text-secondary); font-size: 12px; }
.reason { display: inline-flex; align-items: center; gap: 4px; color: var(--accent-warning); }
.review-actions { display: flex; flex: 0 0 auto; gap: 8px; }
@media (max-width: 660px) { .review-book { padding: 22px 16px 42px; } .review-item { align-items: flex-start; flex-wrap: wrap; } .review-index { display: none; } .review-content { flex-basis: 100%; } .review-actions { margin-left: auto; } }
</style>
