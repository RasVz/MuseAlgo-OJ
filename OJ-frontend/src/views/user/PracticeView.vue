<template>
  <div class="practice-stats-container">
    
    <StatsOverview />

    <el-row :gutter="20">
      <el-col :span="10">
        
        <el-card shadow="never" class="chart-card mb-20">
          <template #header><span class="card-title">⚔️ 能力六维图</span></template>
          <SkillRadar :data="radarData" />
        </el-card>

        <el-card shadow="never" class="chart-card">
          <template #header><span class="card-title">🏷️ 标签分布 (Tags Solved)</span></template>
          <TagsDonut :data="tagData" />
        </el-card>

      </el-col>

      <el-col :span="14">
        
        <el-card shadow="never" class="chart-card mb-20">
          <template #header><span class="card-title">📊 难度分布 (Rating)</span></template>
          <RatingHistogram :data="ratingData" />
        </el-card>

        <el-card shadow="never" class="list-card">
          <template #header>
            <div class="card-header-row">
              <span class="card-title">✅ 已通过题目</span>
              <el-radio-group v-model="listType" size="small">
                <el-radio-button label="passed">Passed</el-radio-button>
                <el-radio-button label="tried">Tried</el-radio-button>
              </el-radio-group>
            </div>
          </template>
          
          <el-scrollbar max-height="400px">
            <ProblemListGroup v-if="listType === 'passed'" :groupedProblems="passedData" />
            <div v-else class="simple-list">
              <el-link v-for="p in triedData" :key="p" class="simple-link">{{ p }}</el-link>
            </div>
          </el-scrollbar>
        </el-card>

      </el-col>
    </el-row>

  </div>
</template>

<script setup>
// 练习分析页（原型页）：用写死的样例数据展示统计/雷达/直方图/标签分布等组件的组合效果，
// 数据尚未接入真实接口，页面只负责验证图表布局与联动。
import { ref } from 'vue'
// 引入所有组件
import StatsOverview from '@/components/StatsOverview.vue'
import SkillRadar from '@/components/SkillRadar.vue'
import RatingHistogram from '@/components/RatingHistogram.vue'
import TagsDonut from '@/components/TagsDonut.vue'
import ProblemListGroup from '@/components/ProblemListGroup.vue'

const listType = ref('passed')

// --- Mock Data ---

// 1. 雷达图数据 (不止6维)
const radarData = [
  { name: '动态规划', value: 85 },
  { name: '图论', value: 65 },
  { name: '搜索', value: 75 },
  { name: '数据结构', value: 90 },
  { name: '数学', value: 55 },
  { name: '字符串', value: 70 },
  { name: '贪心', value: 80 }, // 第7维
  { name: '几何', value: 40 }  // 第8维
]

// 2. Rating 直方图数据
const ratingData = [
  { rating: 800, count: 270 },
  { rating: 900, count: 56 },
  { rating: 1000, count: 60 },
  { rating: 1100, count: 82 },
  { rating: 1200, count: 75 }, // Green start
  { rating: 1300, count: 83 },
  { rating: 1400, count: 90 }, // Cyan start
  { rating: 1500, count: 89 },
  { rating: 1600, count: 110 }, // Blue start
  { rating: 1700, count: 106 },
  { rating: 1800, count: 102 },
  { rating: 1900, count: 85 }, // Violet start
  { rating: 2000, count: 57 },
  { rating: 2100, count: 26 }, // Orange start
  { rating: 2400, count: 8 },  // Red start
]

// 3. 标签甜甜圈数据
const tagData = [
  { name: 'greedy', count: 607, color: '#ff8888' },
  { name: 'math', count: 560, color: '#ff88bb' },
  { name: 'implementation', count: 397, color: '#dd88ff' },
  { name: 'dp', count: 288, color: '#88ccff' },
  { name: 'data structures', count: 230, color: '#88ffff' },
  { name: 'graphs', count: 116, color: '#ffee88' },
  { name: 'trees', count: 88, color: '#ffaa88' },
]

// 4. 题目列表数据
const passedData = [
  { level: '入门', type: 'danger', linkType: 'primary', items: ['P1000', 'P1001', 'P1035', 'CF1A', 'CF4A'] },
  { level: '普及-', type: 'warning', linkType: 'warning', items: ['P1048', 'P1049', 'P1055', 'AT_abc306'] },
  { level: '普及/提高-', type: 'success', linkType: 'success', items: ['P3372', 'P3374', 'CF899C'] },
  { level: '省选/NOI-', type: 'info', linkType: 'primary', items: ['P3369', 'P3805'] }
]

const triedData = ['P1043', 'P1126', 'P1255', 'P1273', 'CF1468K']
</script>

<style scoped>
.practice-stats-container {
  height: calc(100vh - 85px - 40px); /* 适应 BasicLayout */
  overflow-y: auto;
  padding-bottom: 20px;
}

.mb-20 { margin-bottom: 20px; }
.card-title { font-weight: bold; font-size: 16px; color: var(--text-primary); }
.card-header-row { display: flex; justify-content: space-between; align-items: center; }

/* 题目列表卡片 */
.list-card { min-height: 400px; }
.simple-list { display: flex; flex-wrap: wrap; gap: 15px; padding: 10px 0; }
.simple-link { font-family: monospace; color: var(--text-regular); }
</style>