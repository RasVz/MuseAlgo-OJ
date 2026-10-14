<template>
  <main class="review-book" v-loading="loading">
    <NavbarContext>
      <div class="navbar-title"><el-icon><Reading /></el-icon><span>错题本</span></div>
    </NavbarContext>

    <header class="page-header">
      <div>
        <span class="eyebrow">REVIEW PLAN</span>
        <h1>错题本与复习计划</h1>
        <p>系统会自动收录反复未通过、或长时间未解决的题目。掌握后标记完成，让复习列表保持专注。</p>
      </div>
      <div class="header-badge"><el-icon><RefreshRight /></el-icon> 每日优先 5 题</div>
    </header>

    <section class="weakness-panel">
      <div class="weakness-head">
        <div>
          <div class="weakness-title">AI 错题归因与训练计划</div>
          <div class="weakness-desc">基于近 30 天提交统计薄弱知识点，推荐补强题目并生成训练建议</div>
        </div>
        <el-button type="primary" plain :loading="analyzing" @click="analyzeWeakness">
          <el-icon><MagicStick /></el-icon> 开始归因
        </el-button>
      </div>

      <div v-if="weaknessResult" class="weakness-body">
        <div class="weakness-tags">
          <span class="section-label">薄弱知识点</span>
          <el-tag
            v-for="w in weaknessResult.weaknesses"
            :key="w.tagId"
            type="danger"
            effect="plain"
          >
            {{ w.tagName }}：{{ w.mastery }} 分（错 {{ w.failCount }} 题）
          </el-tag>
          <span v-if="!weaknessResult.weaknesses.length" class="no-data">暂无薄弱知识点 🎉</span>
        </div>

        <div v-if="weaknessResult.recommendations.length" class="weakness-recs">
          <span class="section-label">推荐补强题目</span>
          <el-tag
            v-for="r in weaknessResult.recommendations"
            :key="r.problemId"
            class="rec-tag"
            effect="plain"
            @click="goProblem(r.problemId)"
          >
            #{{ r.problemId }} {{ r.title }}（{{ r.difficulty ?? '-' }}）
          </el-tag>
        </div>

        <div v-if="weaknessResult.aiAdvice" class="weakness-advice">
          <span class="section-label">训练建议</span>
          <p>{{ weaknessResult.aiAdvice }}</p>
        </div>
      </div>
    </section>

    <div class="scope-tabs" role="tablist" aria-label="错题范围">
      <button v-for="tab in tabs" :key="tab.value" :class="{ active: scope === tab.value }" @click="changeScope(tab.value)">
        <el-icon><component :is="tab.icon" /></el-icon>{{ tab.label }}
      </button>
    </div>

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
            <span>收录于 {{ formatDate(item.firstFlaggedAt) }}</span>
            <span v-if="item.lastReviewedAt">上次复习 {{ formatDate(item.lastReviewedAt) }}</span>
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

    <el-empty v-else-if="!loading" :image-size="100" :description="scope === 'today' ? '今天没有待复习题，继续保持！' : '还没有待复习的错题'">
      <el-button type="primary" @click="router.push({ name: 'ProblemSet' })">去题库练习</el-button>
    </el-empty>
  </main>
</template>

<script setup>
// 错题本：汇总"未通过 / 久未通过"的题目，供学生复习与标记已掌握。
// 入选规则由后端判定（多次未通过、超过 7 天未通过），前端只做展示与状态切换。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheck, Collection, MagicStick, Reading, RefreshRight, WarningFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import NavbarContext from '@/components/NavbarContext.vue'
import { getReviewBook, markReviewMastered, getWeaknessAnalysis } from '@/api/user'
import { getDifficultyColor, getDifficultyLabel } from '@/utils/difficulty'

const router = useRouter()
const scope = ref('today')
const loading = ref(false)
const updatingId = ref(null)
const items = ref([])
const analyzing = ref(false)
const weaknessResult = ref(null)
const tabs = [
  { value: 'today', label: '今日复习', icon: Reading },
  { value: 'all', label: '全部错题', icon: Collection }
]
const difficultyColor = getDifficultyColor
const difficultyLabel = getDifficultyLabel
const reasonLabel = (reason) => reason === 'UNSOLVED_7_DAYS' ? '超过 7 天未通过' : '多次未通过'
const formatDate = (value) => value ? dayjs(value).format('YYYY-MM-DD') : '今日'

const load = async () => {
  loading.value = true
  try {
    const res = await getReviewBook(scope.value)
    items.value = res.code === 200 ? res.data || [] : []
    if (res.code !== 200) ElMessage.error(res.msg || '错题本加载失败')
  } catch (error) {
    console.error(error)
    items.value = []
  } finally {
    loading.value = false
  }
}
const changeScope = (value) => {
  if (scope.value === value) return
  scope.value = value
  load()
}

// 归因分析会调用 AI，耗时较长且要花钱，用 analyzing 做互斥防重复点击。
const analyzeWeakness = async () => {
  analyzing.value = true
  try {
    const res = await getWeaknessAnalysis({ days: 30, withAi: true })
    weaknessResult.value = res.code === 200 ? res.data : null
    if (res.code !== 200) ElMessage.error(res.msg || '归因分析失败')
  } finally {
    analyzing.value = false
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
.review-book { max-width: 980px; margin: 0 auto; padding: 28px 24px 54px; }
.navbar-title { display: flex; align-items: center; gap: 8px; color: var(--text-primary); font-size: 18px; font-weight: 650; }
.page-header { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 25px; }
.eyebrow { display: block; margin-bottom: 5px; color: var(--accent-primary); font-size: 10px; font-weight: 750; letter-spacing: .13em; }
h1 { margin: 0; color: var(--text-primary); font-size: 26px; line-height: 1.35; }
.page-header p { max-width: 680px; margin: 8px 0 0; color: var(--text-regular); font-size: 14px; line-height: 1.65; }
.header-badge { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 6px; padding: 8px 10px; border-radius: 9px; background: var(--accent-primary-light); color: var(--accent-primary); font-size: 12px; font-weight: 600; }
.scope-tabs { display: inline-flex; gap: 3px; padding: 4px; border-radius: 11px; background: var(--bg-app); }
.scope-tabs button { display: inline-flex; gap: 6px; align-items: center; padding: 8px 13px; border: 0; border-radius: 8px; background: transparent; color: var(--text-regular); cursor: pointer; font-size: 14px; transition: .15s; }
.scope-tabs button.active { background: var(--bg-surface); color: var(--accent-primary-dark); box-shadow: 0 1px 4px rgb(15 23 42 / 9%); font-weight: 600; }
.review-list { margin-top: 16px; border-top: 1px solid var(--border-light); }
.review-item { display: flex; gap: 15px; align-items: center; padding: 18px 6px; border-bottom: 1px solid var(--border-light); }
.review-index { width: 48px; color: var(--text-placeholder); font-size: 13px; font-variant-numeric: tabular-nums; }
.review-content { min-width: 0; flex: 1; }
.title-row { display: flex; align-items: center; gap: 9px; min-width: 0; }
h2 { overflow: hidden; margin: 0; color: var(--text-primary); font-size: 15px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.difficulty { flex: 0 0 auto; padding: 3px 7px; border-radius: 5px; font-size: 11px; font-weight: 700; }
.review-meta { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 7px; color: var(--text-secondary); font-size: 12px; }
.reason { display: inline-flex; align-items: center; gap: 4px; color: var(--accent-warning); }
.review-actions { display: flex; flex: 0 0 auto; gap: 8px; }
@media (max-width: 660px) { .review-book { padding: 22px 16px 42px; } .header-badge { display: none; } .review-item { align-items: flex-start; flex-wrap: wrap; } .review-index { display: none; } .review-content { flex-basis: 100%; } .review-actions { margin-left: auto; } }
.weakness-panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 12px;
  padding: 14px 16px;
  margin-bottom: 16px;
}
.weakness-head { display: flex; justify-content: space-between; align-items: center; }
.weakness-title { font-size: 15px; font-weight: 600; }
.weakness-desc { color: var(--text-secondary); font-size: 12px; margin-top: 2px; }
.weakness-body { margin-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.weakness-tags, .weakness-recs { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.section-label { font-size: 12px; color: var(--text-regular); margin-right: 4px; }
.rec-tag { cursor: pointer; }
.weakness-advice p { margin: 6px 0 0; color: var(--text-primary); font-size: 14px; line-height: 1.7; }
.no-data { color: var(--accent-success); font-size: 13px; }
</style>
