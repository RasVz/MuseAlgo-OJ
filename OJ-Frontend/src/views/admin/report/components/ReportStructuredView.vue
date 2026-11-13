<template>
  <div class="structured-view">
    <aside class="toc">
      <div class="toc-title">报告目录</div>
      <button
        v-for="section in sections"
        :key="section.id"
        type="button"
        class="toc-link"
        :class="{ active: activeSection === section.id }"
        @click="scrollTo(section.id)"
      >
        {{ section.label }}
      </button>
      <div class="toc-note">
        结构化视图由后端同源数据渲染，字号与栅格由前端控制。打印、PNG 与 HTML 下载始终使用原始版。
      </div>
    </aside>

    <article class="report-body">
      <header class="report-hero">
        <div class="hero-kicker">信奥学习周报 · 第 {{ data.header.weekNo || '—' }} 周</div>
        <h1 class="hero-title">{{ data.header.studentName }} 的学习周报</h1>
        <div class="hero-meta">
          <span v-if="data.header.className">班级：{{ data.header.className }}</span>
          <span>统计周期：{{ data.header.periodStart }} ～ {{ data.header.periodEnd }}</span>
        </div>
      </header>

      <section id="sec-overview" class="report-section">
        <h2 class="section-title">本周概览</h2>
        <div class="metric-grid">
          <div v-for="metric in metrics" :key="metric.label" class="metric-card">
            <div class="metric-value">{{ metric.value }}</div>
            <div class="metric-label">{{ metric.label }}</div>
          </div>
        </div>
      </section>

      <section id="sec-radar" class="report-section">
        <h2 class="section-title">能力雷达</h2>
        <div v-if="data.radar" class="chart-card">
          <ReportRadarChart :dimensions="data.radar.dimensions" :values="data.radar.values" />
        </div>
        <el-empty v-else description="本报告未返回能力雷达数据" :image-size="64" />
      </section>

      <section id="sec-comparison" class="report-section">
        <h2 class="section-title">本周能力变化</h2>
        <div v-if="data.comparison" class="chart-card">
          <ReportComparisonChart
            :dimensions="data.comparison.dimensions"
            :base="data.comparison.base"
            :growth="data.comparison.growth"
          />
        </div>
        <el-empty v-else description="无上周对比数据" :image-size="64" />
      </section>

      <section id="sec-knowledge" class="report-section">
        <h2 class="section-title">知识点掌握明细</h2>
        <el-table v-if="data.tagRows.length" :data="data.tagRows" class="knowledge-table" stripe>
          <el-table-column prop="tagName" label="知识点" min-width="150" />
          <el-table-column prop="attempted" label="尝试题数" width="110" align="center" />
          <el-table-column prop="passed" label="通过题数" width="110" align="center" />
          <el-table-column label="一次通过率" width="120" align="center">
            <template #default="{ row }">{{ row.firstTryRate }}%</template>
          </el-table-column>
          <el-table-column label="掌握度" width="130" align="center">
            <template #default="{ row }">
              <div class="mastery-cell">
                <div class="mastery-track"><div class="mastery-fill" :style="{ width: Math.min(100, row.mastery) + '%' }"></div></div>
                <span>{{ row.mastery }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="评价" width="100" align="center">
            <template #default="{ row }">
              <span class="level-badge" :class="levelClass(row.levelLabel)">{{ row.levelLabel }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="本周期内暂无刷题记录" :image-size="64" />
      </section>

      <section id="sec-weak" class="report-section">
        <h2 class="section-title">薄弱环节与练习建议</h2>
        <div v-if="data.weakPoints.length" class="weak-list">
          <span v-for="(point, index) in data.weakPoints" :key="`${point.tagName}-${index}`" class="weak-chip">
            {{ point.tagName }}（{{ point.mastery }} 分）
          </span>
        </div>
        <div class="advice-box">{{ data.advice || '本报告未提供练习建议。' }}</div>
      </section>

      <section id="sec-recommend" class="report-section">
        <h2 class="section-title">下周推荐题单</h2>
        <div v-if="data.recommendations.length" class="reco-list">
          <div v-for="(item, index) in data.recommendations" :key="`${item.title}-${index}`" class="reco-row">
            <span class="reco-title">{{ item.title }} <span class="reco-tag">[{{ item.tagName }}]</span></span>
            <span class="reco-diff">{{ item.difficultyLabel }}</span>
          </div>
        </div>
        <el-empty v-else description="本周期未生成推荐题单" :image-size="64" />
      </section>

      <footer class="report-footer">
        本报告由系统自动生成，数据来源于学生的真实提交记录 · 掌握度综合通过率、题目难度、一次通过率计算
      </footer>
    </article>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import ReportRadarChart from './ReportRadarChart.vue'
import ReportComparisonChart from './ReportComparisonChart.vue'

const props = defineProps({
  data: { type: Object, required: true }
})

const sections = [
  { id: 'sec-overview', label: '本周概览' },
  { id: 'sec-radar', label: '能力雷达' },
  { id: 'sec-comparison', label: '本周能力变化' },
  { id: 'sec-knowledge', label: '知识点明细' },
  { id: 'sec-weak', label: '薄弱建议' },
  { id: 'sec-recommend', label: '推荐题单' }
]

const activeSection = ref(sections[0].id)

const metrics = computed(() => [
  { label: '提交次数', value: props.data.overview.totalSubmissions },
  { label: 'AC 题目数', value: props.data.overview.acceptedProblemCount },
  { label: '活跃天数', value: props.data.overview.activeDays }
])

function levelClass(label) {
  if (label === '优秀') return 'level-excellent'
  if (label === '良好') return 'level-good'
  if (label === '及格') return 'level-adequate'
  return 'level-weak'
}

function scrollTo(id) {
  activeSection.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onScroll() {
  let current = sections[0].id
  for (const section of sections) {
    const element = document.getElementById(section.id)
    if (element && element.getBoundingClientRect().top <= 180) current = section.id
  }
  activeSection.value = current
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

<style scoped>
.structured-view { display: grid; grid-template-columns: 190px minmax(0, 1fr); gap: 24px; max-width: 1240px; margin: 0 auto; padding: 24px 24px 64px; }
.toc { position: sticky; top: 88px; align-self: start; display: flex; flex-direction: column; gap: 4px; }
.toc-title { font-size: 13px; font-weight: 600; color: var(--text-primary); margin-bottom: 8px; }
.toc-link { display: block; text-align: left; padding: 8px 12px; border: none; border-left: 2px solid transparent; background: transparent; color: var(--text-regular); font-size: 13px; cursor: pointer; border-radius: 0 6px 6px 0; }
.toc-link:hover { background: var(--bg-surface-hover); color: var(--text-primary); }
.toc-link.active { border-left-color: var(--accent-primary); background: var(--accent-primary-light); color: var(--accent-primary); }
.toc-note { margin-top: 16px; font-size: 12px; line-height: 1.6; color: var(--text-secondary); }
.report-body { min-width: 0; }
.report-hero { background: var(--bg-surface); border: 1px solid var(--border-light); border-left: 4px solid var(--accent-primary); border-radius: var(--radius-md); padding: 24px 28px; margin-bottom: 20px; }
.hero-kicker { font-size: 13px; color: var(--accent-primary); }
.hero-title { font-size: 26px; font-weight: 700; color: var(--text-primary); margin: 6px 0 10px; }
.hero-meta { display: flex; gap: 20px; flex-wrap: wrap; font-size: 13px; color: var(--text-secondary); }
.report-section { scroll-margin-top: 88px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); padding: 20px 24px; margin-bottom: 20px; }
.section-title { font-size: 17px; font-weight: 600; color: var(--text-primary); border-left: 3px solid var(--accent-primary); padding-left: 10px; margin: 0 0 16px; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 14px; }
.metric-card { background: var(--bg-app); border-radius: var(--radius-md); padding: 18px; text-align: center; }
.metric-value { font-size: 30px; font-weight: 700; color: var(--accent-primary); line-height: 1.2; }
.metric-label { font-size: 13px; color: var(--text-regular); margin-top: 6px; }
.chart-card { padding: 8px 4px; }
.knowledge-table { width: 100%; }
.mastery-cell { display: flex; align-items: center; gap: 8px; justify-content: center; }
.mastery-track { width: 64px; height: 6px; background: var(--bg-surface-hover); border-radius: 999px; overflow: hidden; }
.mastery-fill { height: 100%; background: var(--accent-primary); border-radius: 999px; }
.level-badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; }
.level-excellent { background: #dcfce7; color: #15803d; }
.level-good { background: #dbeafe; color: #1d4ed8; }
.level-adequate { background: #fef9c3; color: #a16207; }
.level-weak { background: #fee2e2; color: #b91c1c; }
.weak-list { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
.weak-chip { background: var(--accent-danger-light); border: 1px solid #fecaca; color: var(--accent-danger); border-radius: var(--radius-sm); padding: 5px 12px; font-size: 13px; }
.advice-box { background: var(--bg-app); border: 1px solid var(--border-light); border-radius: var(--radius-md); padding: 16px 18px; font-size: 14px; line-height: 1.8; color: var(--text-primary); white-space: pre-wrap; }
.reco-list { display: flex; flex-direction: column; }
.reco-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 4px; border-bottom: 1px dashed var(--border-light); font-size: 14px; color: var(--text-primary); }
.reco-row:last-child { border-bottom: none; }
.reco-tag { font-size: 12px; color: var(--text-secondary); }
.reco-diff { font-size: 12px; padding: 2px 10px; border-radius: var(--radius-sm); background: var(--bg-surface-hover); color: var(--text-regular); }
.report-footer { text-align: center; font-size: 12px; color: var(--text-secondary); padding: 12px 0 0; border-top: 1px solid var(--border-light); }
@media (max-width: 900px) {
  .structured-view { grid-template-columns: minmax(0, 1fr); padding: 16px; }
  .toc { position: static; flex-direction: row; flex-wrap: wrap; }
  .toc-title, .toc-note { width: 100%; }
  .toc-link { border-left: none; border-bottom: 2px solid transparent; }
  .toc-link.active { border-left-color: transparent; border-bottom-color: var(--accent-primary); }
}
</style>
