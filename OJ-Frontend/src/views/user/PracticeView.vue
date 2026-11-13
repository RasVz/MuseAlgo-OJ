<template>
  <div class="practice-page" v-loading="loading">
    <header class="practice-page-header">
      <div>
        <span class="page-eyebrow">MY PRACTICE</span>
        <h1>练习分析</h1>
        <p>{{ periodLabel }}</p>
      </div>
      <el-radio-group v-model="range" aria-label="练习统计时间范围" @change="loadData"
        ><el-radio-button value="all">全部</el-radio-button
        ><el-radio-button value="30d">近 30 天</el-radio-button></el-radio-group
      >
    </header>
    <el-alert v-if="errorMessage" type="error" show-icon :closable="false" title="练习分析加载失败"
      ><div class="error-content">
        <span>{{ errorMessage }}</span
        ><el-button size="small" @click="loadData">重试</el-button>
      </div></el-alert
    >
    <template v-if="data">
      <StatsOverview :stats="data.overview" />
      <div class="analysis-grid">
        <section class="analysis-card ability-card">
          <div class="section-heading">
            <div>
              <h2>能力分布</h2>
              <p>结合尝试、通过与首次通过情况的掌握度</p>
            </div>
            <span class="section-unit">0–100</span>
          </div>
          <div v-if="hasAttempts" class="ability-layout">
            <SkillRadar :data="radarData" />
            <div class="dimension-list">
              <div v-for="item in dimensions" :key="item.key" class="dimension-row">
                <div>
                  <span>{{ item.label || item.key }}</span
                  ><strong v-if="item.attempted > 0">{{ item.mastery }}<small> / 100</small></strong
                  ><span v-else class="unpracticed">尚未练习</span>
                </div>
                <div class="dimension-track">
                  <i
                    :style="{ width: `${Math.min(100, Math.max(0, Number(item.mastery) || 0))}%` }"
                  ></i>
                </div>
                <small>{{
                  item.attempted > 0
                    ? `尝试 ${item.attempted} 题 · 通过 ${item.passed} 题`
                    : '练习后逐步形成记录'
                }}</small>
              </div>
            </div>
          </div>
          <el-empty v-else :image-size="72" :description="emptyHint" />
        </section>
        <section class="analysis-card difficulty-card">
          <div class="section-heading">
            <div>
              <h2>题目难度分布</h2>
              <p>所选时间内已通过的题目 · 单位：题</p>
            </div>
            <el-button
              v-if="hasDifficulty"
              text
              type="primary"
              size="small"
              :aria-pressed="fullDifficulty"
              @click="fullDifficulty = !fullDifficulty"
              >{{ fullDifficulty ? '聚焦练习区间' : '完整区间' }}</el-button
            >
          </div>
          <RatingHistogram v-if="hasDifficulty" :data="difficultyChartData" /><el-empty
            v-else
            :image-size="72"
            :description="emptyHint"
          />
          <p v-if="hasDifficulty" class="chart-footnote">
            {{ fullDifficulty ? '显示全部难度区间' : '聚焦已有记录的难度区间' }} ·
            横轴为题目难度分数
          </p>
        </section>
      </div>
      <section class="analysis-card tag-card">
        <div class="section-heading">
          <div>
            <h2>标签覆盖</h2>
            <p>已通过题目的标签数量，一题可包含多个标签，数量不可相加。</p>
          </div>
          <span class="section-unit">{{ tagChartData.length }} 项标签</span>
        </div>
        <div v-if="tagChartData.length" class="tag-bars">
          <div v-for="item in tagChartData" :key="item.tagId" class="tag-bar-row">
            <span class="tag-name">{{ item.tagName || `#${item.tagId}` }}</span>
            <div class="tag-track">
              <i :style="{ width: `${(item.passed / maxTagCount) * 100}%` }"></i>
            </div>
            <strong>{{ item.passed }}<small> 题</small></strong>
          </div>
        </div>
        <el-empty v-else :image-size="64" :description="emptyHint" />
      </section>
      <section class="analysis-card records-card">
        <div class="records-header">
          <div>
            <h2>题目记录</h2>
            <p>最近记录，最多 {{ data.listLimit || 50 }} 条 · 按时间倒序</p>
          </div>
          <el-radio-group v-model="listType" size="small" aria-label="题目记录类型"
            ><el-radio-button value="passed">已通过</el-radio-button
            ><el-radio-button value="tried">尝试未通过</el-radio-button></el-radio-group
          >
        </div>
        <PracticeProblemTable
          v-if="currentRows.length"
          :rows="currentRows"
          :type="listType"
        /><el-empty
          v-else
          :image-size="64"
          :description="
            listType === 'tried' && hasAttempts ? '所选时间内没有尝试未通过的题目' : emptyHint
          "
        />
        <div class="records-footer">
          <span>题库入口将查看全部时间的对应状态题目</span
          ><RouterLink
            :to="{ path: '/problem', query: { userStatus: listType === 'passed' ? '1' : '2' } }"
            >前往题库 <span aria-hidden="true">↗</span></RouterLink
          >
        </div>
      </section>
    </template>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { getMyPractice } from '@/api/profile'
import { isResultSuccess, unwrapResultData, getResultMessage } from '@/utils/response'
import { visibleDifficultyBuckets, recentPracticeRows } from '@/utils/practicePresentation'
import StatsOverview from '@/components/StatsOverview.vue'
import SkillRadar from '@/components/SkillRadar.vue'
import RatingHistogram from '@/components/RatingHistogram.vue'
import PracticeProblemTable from './components/PracticeProblemTable.vue'
const range = ref('all'),
  listType = ref('passed'),
  loading = ref(true),
  errorMessage = ref(''),
  data = ref(null),
  fullDifficulty = ref(false)
const dimensions = computed(() =>
  Array.isArray(data.value?.dimensions) ? data.value.dimensions : [],
)
const tags = computed(() => (Array.isArray(data.value?.tags) ? data.value.tags : []))
const difficulty = computed(() =>
  Array.isArray(data.value?.difficulty) ? data.value.difficulty : [],
)
const radarData = computed(() =>
  dimensions.value.map((item) => ({
    name: item.label || item.key,
    value: Math.min(100, Math.max(0, Number(item.mastery) || 0)),
  })),
)
const hasAttempts = computed(
  () =>
    dimensions.value.some((item) => item.attempted > 0) ||
    data.value?.overview?.totalSubmissions > 0,
)
const tagChartData = computed(() =>
  tags.value
    .filter((item) => item.passed > 0)
    .slice()
    .sort((a, b) => b.passed - a.passed)
    .slice(0, 12),
)
const maxTagCount = computed(() => Math.max(1, ...tagChartData.value.map((item) => item.passed)))
const difficultyChartData = computed(() =>
  visibleDifficultyBuckets(difficulty.value, fullDifficulty.value),
)
const hasDifficulty = computed(() => difficulty.value.some((item) => item.solved > 0))
const currentRows = computed(() =>
  recentPracticeRows(
    (listType.value === 'passed' ? data.value?.solvedProblems : data.value?.triedProblems) || [],
    listType.value,
  ),
)
const emptyHint = computed(() =>
  range.value === '30d' ? '近 30 天暂无相关练习记录' : '暂无相关练习记录',
)
const periodLabel = computed(() => {
  if (range.value === 'all') return '全部时间 · 回顾你的练习积累'
  if (data.value?.range === '30d' && data.value.periodStart && data.value.periodEnd)
    return `${dayjs(data.value.periodStart).format('YYYY.MM.DD')} — ${dayjs(data.value.periodEnd).format('YYYY.MM.DD')}`
  return '近 30 天 · 查看近期练习情况'
})
let requestSeq = 0
async function loadData() {
  const seq = ++requestSeq
  loading.value = true
  errorMessage.value = ''
  data.value = null
  try {
    const result = await getMyPractice(range.value)
    if (seq !== requestSeq) return
    if (!isResultSuccess(result)) throw new Error(getResultMessage(result, '练习分析加载失败'))
    data.value = unwrapResultData(result) || null
    if (!data.value) throw new Error('练习分析返回空数据')
  } catch (cause) {
    if (seq === requestSeq) {
      data.value = null
      errorMessage.value = cause.message || '练习分析加载失败，请重试'
    }
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}
onMounted(loadData)
</script>
<style scoped>
.practice-page {
  font-family: var(--font-family);
  max-width: 1400px;
  min-width: 0;
  margin: 0 auto;
  padding: 4px 4px 28px;
}
.practice-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 26px;
}
.page-eyebrow {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.8px;
  color: var(--el-color-primary);
}
.practice-page-header h1 {
  margin: 6px 0 8px;
  color: var(--text-primary);
  font-size: 26px;
  line-height: 1.3;
  font-weight: 650;
}
.practice-page-header p {
  font-size: 13px;
  margin: 0;
  color: var(--text-secondary);
}
.analysis-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 20px;
  margin-bottom: 20px;
}
.analysis-card {
  padding: 22px 24px;
  border: 1px solid var(--border-light);
  border-radius: 14px;
  background: var(--bg-surface);
  min-width: 0;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}
h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.section-heading p,
.records-header p {
  font-size: 12px;
  color: var(--text-secondary);
  margin: 7px 0 0;
  line-height: 1.6;
}
.section-unit {
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  padding-top: 4px;
}
.ability-layout {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}
.dimension-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dimension-row > div:first-child {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--text-regular);
  font-size: 12px;
}
.dimension-row strong {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}
.dimension-row small {
  font-size: 10px;
  color: var(--text-secondary);
}
.dimension-track {
  height: 4px;
  border-radius: 3px;
  background: var(--bg-app);
  margin: 6px 0 4px;
}
.dimension-track i {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--el-color-primary);
}
.unpracticed {
  color: var(--text-secondary);
  font-size: 11px;
}
.chart-footnote {
  color: var(--text-secondary);
  font-size: 11px;
  margin: 8px 0 0;
}
.tag-card {
  margin-bottom: 20px;
}
.tag-bars {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 56px;
  padding: 12px 0 6px;
}
.tag-bar-row {
  display: grid;
  grid-template-columns: minmax(70px, 110px) minmax(0, 1fr) 45px;
  gap: 14px;
  align-items: center;
  min-width: 0;
}
.tag-name {
  font-size: 13px;
  color: var(--text-regular);
  overflow-wrap: anywhere;
}
.tag-track {
  height: 7px;
  background: var(--bg-app);
  border-radius: 4px;
}
.tag-track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary);
  opacity: 0.75;
}
.tag-bar-row strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  text-align: right;
}
.tag-bar-row small {
  font-weight: 400;
  font-size: 11px;
  color: var(--text-secondary);
}
.records-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}
.records-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--border-light);
  margin-top: 14px;
  padding-top: 16px;
}
.records-footer span {
  font-size: 11px;
  color: var(--text-secondary);
}
.records-footer a {
  text-decoration: none;
  color: var(--el-color-primary);
  font-size: 13px;
}
.records-footer a span {
  color: inherit;
}
.error-content {
  display: flex;
  align-items: center;
  gap: 16px;
}
@media (max-width: 1200px) {
  .ability-layout {
    grid-template-columns: 1fr;
  }
  .ability-layout :deep(.radar-svg) {
    max-height: 200px;
  }
  .dimension-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1000px) {
  .analysis-grid {
    grid-template-columns: 1fr;
  }
  .ability-layout {
    grid-template-columns: minmax(160px, 1fr) minmax(0, 1fr);
  }
  .dimension-list {
    display: flex;
  }
}
@media (max-width: 680px) {
  .practice-page {
    font-family: var(--font-family);
    padding: 0 0 20px;
  }
  .practice-page-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 16px;
  }
  .practice-page-header h1 {
    font-size: 24px;
  }
  .analysis-card {
    padding: 18px 16px;
  }
  .analysis-grid {
    gap: 16px;
  }
  .ability-layout {
    grid-template-columns: 1fr;
  }
  .dimension-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .tag-bars {
    grid-template-columns: 1fr;
    gap: 16px;
  }
  .records-header {
    align-items: flex-start;
    flex-direction: column;
  }
  .section-heading {
    flex-wrap: wrap;
  }
}
</style>
