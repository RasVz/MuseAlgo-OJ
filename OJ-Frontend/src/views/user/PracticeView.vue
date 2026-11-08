<template>
  <div class="practice-stats-container" v-loading="loading">
    <div class="page-header">
      <div class="page-header-main">
        <h2 class="page-title">练习分析</h2>
        <p class="page-subtitle">基于本人真实提交记录统计</p>
      </div>
      <el-radio-group v-model="range" size="small" @change="loadData">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
      </el-radio-group>
    </div>

    <el-alert
      v-if="errorMessage"
      class="mb-20"
      type="error"
      show-icon
      :closable="false"
      title="练习分析加载失败"
    >
      <div class="alert-body">
        <span class="alert-message">{{ errorMessage }}</span>
        <el-button size="small" type="primary" plain :loading="loading" @click="loadData">重试</el-button>
      </div>
    </el-alert>

    <template v-if="data">
      <StatsOverview :stats="overview" />

      <el-row :gutter="20">
        <el-col :span="10">
          <el-card shadow="never" class="chart-card mb-20">
            <template #header><span class="card-title">能力分布</span></template>
            <SkillRadar v-if="hasRadarData" :data="radarData" />
            <el-empty v-else :image-size="80" :description="emptyHint" />
          </el-card>

          <el-card shadow="never" class="chart-card">
            <template #header><span class="card-title">标签分布（已通过题目）</span></template>
            <TagsDonut v-if="tagChartData.length" :data="tagChartData" />
            <el-empty v-else :image-size="80" :description="emptyHint" />
          </el-card>
        </el-col>

        <el-col :span="14">
          <el-card shadow="never" class="chart-card mb-20">
            <template #header><span class="card-title">已通过题目难度分布</span></template>
            <RatingHistogram v-if="hasDifficultyData" :data="difficultyChartData" />
            <el-empty v-else :image-size="80" :description="emptyHint" />
          </el-card>

          <el-card shadow="never" class="list-card">
            <template #header>
              <div class="card-header-row">
                <div class="card-header-left">
                  <span class="card-title">题目记录</span>
                  <el-radio-group v-model="listType" size="small">
                    <el-radio-button value="passed">已通过</el-radio-button>
                    <el-radio-button value="tried">尝试过</el-radio-button>
                  </el-radio-group>
                </div>
                <el-link type="primary" :underline="false" @click="goAllProblems">查看全部</el-link>
              </div>
            </template>

            <el-scrollbar v-if="groupedProblems.length" max-height="400px">
              <ProblemListGroup :groupedProblems="groupedProblems" />
            </el-scrollbar>
            <el-empty v-else :image-size="80" :description="emptyHint" />
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-empty v-else-if="!loading && !errorMessage" :image-size="100" :description="emptyHint" />
  </div>
</template>

<script setup>
// 练习分析（本人）：时间范围切换 + 四格指标 + 能力雷达 + 标签分布 + 难度分布 + 最近通过/尝试题目。
// 数据全部来自 GET /profile/practice/me，契约字段由后端任务冻结；前端只做展示、分组与格式化，
// 不做二次统计（通过题去重、AC 率、时间窗都由后端算好），避免两端口径漂移。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getMyPractice } from '@/api/profile'
import { isResultSuccess, unwrapResultData, getResultMessage } from '@/utils/response'
import { DIFFICULTY_RANGES, getDifficultyLabel, getDifficultyRange } from '@/utils/difficulty'
import StatsOverview from '@/components/StatsOverview.vue'
import SkillRadar from '@/components/SkillRadar.vue'
import RatingHistogram from '@/components/RatingHistogram.vue'
import TagsDonut from '@/components/TagsDonut.vue'
import ProblemListGroup from '@/components/ProblemListGroup.vue'

const router = useRouter()

// 知识域 → 标签分布配色；未匹配维度统一灰色，避免后端新增维度时配色表漏项。
const DIMENSION_COLORS = {
  ALGORITHM: '#5B8FF9',
  DATA_STRUCTURE: '#5AD8A6',
  MATH: '#F6BD16',
  GRAPH: '#7262FD',
  STRING: '#FF9845'
}
const UNKNOWN_DIMENSION_COLOR = '#909399'
const TAG_LIMIT = 12
// el-tag / el-link 的类型枚举有限，分组配色按难度档位顺序循环分配。
const GROUP_TYPE_CYCLE = ['success', 'warning', 'info', 'primary', 'danger']

const range = ref('all')
const listType = ref('passed')
const loading = ref(true)
const errorMessage = ref('')
const data = ref(null)

// 竞态保护：快速切换时间范围时只采纳最后一次请求的结果，旧请求不覆盖新数据。
let requestSeq = 0

const overview = computed(() => data.value?.overview ?? {})
const dimensions = computed(() => (Array.isArray(data.value?.dimensions) ? data.value.dimensions : []))
const tags = computed(() => (Array.isArray(data.value?.tags) ? data.value.tags : []))
const difficulty = computed(() => (Array.isArray(data.value?.difficulty) ? data.value.difficulty : []))
const solvedProblems = computed(() => (Array.isArray(data.value?.solvedProblems) ? data.value.solvedProblems : []))
const triedProblems = computed(() => (Array.isArray(data.value?.triedProblems) ? data.value.triedProblems : []))

const emptyHint = computed(() => (range.value === '30d' ? '近 30 天暂无数据' : '暂无练习记录'))

// 雷达图：维度与数量都以后端为准，mastery 已是 0-100；全 0 时不渲染空雷达而交给空态。
const radarData = computed(() => dimensions.value.map((item) => ({
  name: item.label || item.key || '未命名维度',
  value: Math.max(0, Math.min(100, Number(item.mastery) || 0))
})))
const hasRadarData = computed(() => radarData.value.some((item) => item.value > 0))

// 标签分布只统计「已通过」数；passed=0 的标签不进入图例，避免甜甜圈出现 0 值图例。
const tagChartData = computed(() => tags.value
  .map((item) => ({
    name: item.tagName || '#' + item.tagId,
    count: Number(item.passed) || 0,
    color: DIMENSION_COLORS[String(item.dimension || '').toUpperCase()] || UNKNOWN_DIMENSION_COLOR
  }))
  .filter((item) => item.count > 0)
  .sort((a, b) => b.count - a.count)
  .slice(0, TAG_LIMIT))

// 难度分布：后端按 100 分步长给桶，前端只按 rating 升序保证 X 轴顺序稳定。
const difficultyChartData = computed(() => difficulty.value
  .map((item) => ({ rating: Number(item.rating), count: Number(item.solved) || 0 }))
  .filter((item) => Number.isFinite(item.rating))
  .sort((a, b) => a.rating - b.rating))
const hasDifficultyData = computed(() => difficultyChartData.value.some((item) => item.count > 0))

// 分组顺序以 utils/difficulty 的 DIFFICULTY_RANGES 为准，未匹配到档位的题放在最后。
const difficultyOrder = (rating) => {
  const rangeInfo = getDifficultyRange(rating)
  const index = rangeInfo ? DIFFICULTY_RANGES.findIndex((item) => item.key === rangeInfo.key) : -1
  return index === -1 ? DIFFICULTY_RANGES.length : index
}

// 时间倒序用 Date.parse：后端返回 ISO 字符串；解析失败按 0 处理（不因脏数据抛错）。
const timeValue = (value) => {
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : 0
}

const groupRowsByDifficulty = (rows, timeField) => {
  const groups = new Map()
  rows.forEach((row) => {
    const rating = Number(row.difficulty)
    const label = getDifficultyLabel(Number.isFinite(rating) ? rating : null)
    if (!groups.has(label)) {
      groups.set(label, {
        level: label,
        order: difficultyOrder(Number.isFinite(rating) ? rating : null),
        items: []
      })
    }
    groups.get(label).items.push(row)
  })

  return [...groups.values()]
    .sort((a, b) => a.order - b.order)
    .map((group, index) => ({
      level: group.level,
      type: GROUP_TYPE_CYCLE[index % GROUP_TYPE_CYCLE.length],
      linkType: GROUP_TYPE_CYCLE[(index + 2) % GROUP_TYPE_CYCLE.length],
      items: group.items
        .slice()
        .sort((a, b) => timeValue(b[timeField]) - timeValue(a[timeField]))
        .map((row) => ({ id: row.problemId, title: row.title }))
    }))
}

const currentRows = computed(() => (listType.value === 'passed' ? solvedProblems.value : triedProblems.value))
const currentTimeField = computed(() => (listType.value === 'passed' ? 'solvedAt' : 'lastAttemptAt'))
const groupedProblems = computed(() => (currentRows.value.length
  ? groupRowsByDifficulty(currentRows.value, currentTimeField.value)
  : []))

// 「查看全部」复用题库的解题状态筛选；ProblemSetView 会从 URL 恢复 userStatus。
const goAllProblems = () => {
  router.push({ path: '/problem', query: { userStatus: listType.value === 'passed' ? '1' : '2' } })
}

const loadData = async () => {
  const seq = ++requestSeq
  loading.value = true
  errorMessage.value = ''
  try {
    const res = await getMyPractice(range.value)
    if (seq !== requestSeq) return
    if (!isResultSuccess(res)) {
      data.value = null
      errorMessage.value = getResultMessage(res, '练习分析加载失败')
      return
    }
    data.value = unwrapResultData(res) || null
    if (!data.value) {
      errorMessage.value = '练习分析返回空数据'
    }
  } catch (error) {
    if (seq !== requestSeq) return
    // 失败时清空旧数据，避免时间范围切换失败后仍显示上一个范围的结果。
    data.value = null
    errorMessage.value = error?.response?.data?.message || error?.message || '练习分析加载失败'
  } finally {
    // 无论成功、失败还是 403，都在 finally 里结束 loading，不能像旧个人主页那样提前 return 卡住。
    if (seq === requestSeq) loading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.practice-stats-container {
  height: calc(100vh - 85px - 40px); /* 适应 BasicLayout */
  overflow-y: auto;
  padding-bottom: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}
.page-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--text-primary); }
.page-subtitle { margin: 4px 0 0; font-size: 13px; color: var(--text-secondary); }

.mb-20 { margin-bottom: 20px; }
.card-title { font-weight: bold; font-size: 16px; color: var(--text-primary); }
.card-header-row { display: flex; justify-content: space-between; align-items: center; }
.card-header-left { display: flex; align-items: center; gap: 12px; }

.alert-body { display: flex; align-items: center; gap: 12px; }
.alert-message { flex: 1; }

/* 题目列表卡片 */
.list-card { min-height: 400px; }
</style>
