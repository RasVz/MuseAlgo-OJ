<template>
  <div class="admin-ai admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">AI 管理</div>
        <div class="page-desc">用量统计、成本估算、配额排行与配置概览（数据源 ai_usage 流水）</div>
      </div>
      <el-radio-group v-model="days" @change="loadOverview">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
      </el-radio-group>
      <el-button type="warning" @click="openPrompts">提示词模板</el-button>
    </div>

    <!-- 总览卡片 -->
    <el-row :gutter="16" class="cards">
      <el-col v-for="card in cards" :key="card.label" :span="6">
        <el-card shadow="never">
          <div class="card-label">{{ card.label }}</div>
          <div class="card-value">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>调用量与 token 趋势</template>
      <div ref="trendChartRef" class="trend-chart"></div>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="sub-header">
              <span>用量明细</span>
              <div class="filters">
                <el-input v-model="filters.userId" placeholder="用户 ID" clearable style="width: 110px" />
                <el-select v-model="filters.scene" placeholder="场景" clearable style="width: 110px">
                  <el-option label="答疑" value="ASK" />
                  <el-option label="复盘" value="REVIEW" />
                </el-select>
                <el-select v-model="filters.status" placeholder="状态" clearable style="width: 130px">
                  <el-option label="成功" value="SUCCESS" />
                  <el-option label="失败" value="FAIL" />
                  <el-option label="配额拒绝" value="QUOTA_REJECTED" />
                  <el-option label="比赛拦截" value="CONTEST_BLOCKED" />
                </el-select>
                <el-date-picker v-model="filters.dates" type="daterange" value-format="YYYY-MM-DD"
                  start-placeholder="开始日期" end-placeholder="结束日期" style="width: 230px" />
                <el-button type="primary" @click="loadLogs">查询</el-button>
              </div>
            </div>
          </template>
          <el-table :data="logs" size="small" max-height="420">
            <el-table-column prop="userId" label="用户" width="70" />
            <el-table-column label="场景" width="80">
              <template #default="{ row }">{{ row.scene }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalTokens" label="token" width="90" />
            <el-table-column prop="model" label="模型" min-width="120" />
            <el-table-column prop="latencyMs" label="耗时ms" width="80" />
            <el-table-column prop="detail" label="详情" min-width="150" show-overflow-tooltip />
            <el-table-column prop="createdAt" label="时间" min-width="150" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="block">
          <template #header>用户 token 消耗排行（近 {{ days }} 天）</template>
          <el-table :data="ranking" size="small" max-height="420">
            <el-table-column prop="rank" label="#" width="50" />
            <el-table-column prop="username" label="用户" min-width="120" />
            <el-table-column prop="calls" label="次数" width="70" />
            <el-table-column prop="tokens" label="token" width="90" />
            <el-table-column label="今日配额" width="110">
              <template #default="{ row }">{{ row.usageRate ?? 0 }}%</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="sub-header">
          <span>AI 配置（DB 覆盖，保存后秒级生效）</span>
          <el-button type="primary" size="small" :loading="savingConfig" @click="saveConfig">保存配置</el-button>
        </div>
      </template>
      <el-form :model="editableConfig" label-width="170px" inline>
        <el-form-item label="配额开关">
          <el-switch v-model="editableConfig.ai_quota_enabled" active-value="true" inactive-value="false" />
        </el-form-item>
        <el-form-item label="每日配额 token">
          <el-input-number v-model="editableConfig.ai_quota_daily_tokens" :min="0" :step="1000" />
        </el-form-item>
        <el-form-item label="教师/管理员豁免">
          <el-switch v-model="editableConfig.ai_quota_exempt_teacher_admin" active-value="true" inactive-value="false" />
        </el-form-item>
        <el-form-item label="比赛拦截">
          <el-switch v-model="editableConfig.ai_contest_guard_enabled" active-value="true" inactive-value="false" />
        </el-form-item>
        <el-form-item label="拦截缓存秒数">
          <el-input-number v-model="editableConfig.ai_contest_guard_cache_seconds" :min="0" />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="editableConfig.ai_model" style="width: 220px" />
        </el-form-item>
        <el-form-item label="max_tokens">
          <el-input-number v-model="editableConfig.ai_max_tokens" :min="256" :step="1024" />
        </el-form-item>
        <el-form-item label="JSON 模式">
          <el-switch v-model="editableConfig.ai_json_mode" active-value="true" inactive-value="false" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>配额人工干预</template>
      <div class="quota-tool">
        <el-input v-model="quotaUserId" placeholder="用户 ID" style="width: 140px" />
        <el-button type="primary" @click="queryQuota">查询</el-button>
        <template v-if="quotaInfo">
          <span class="quota-info">
            今日已用 <b>{{ quotaInfo.usedToday }}</b> / {{ quotaInfo.dailyLimit }} token（{{ quotaInfo.date }}）
          </span>
          <el-input-number v-model="quotaSetValue" :min="0" placeholder="设置已用" style="width: 130px" />
          <el-button @click="setQuota">设置已用</el-button>
          <el-button type="danger" plain @click="resetQuota">重置当日</el-button>
        </template>
      </div>
    </el-card>

    <!-- 提示词模板管理 -->
    <el-dialog v-model="promptsVisible" title="提示词模板版本管理" width="820px" top="5vh">
      <div class="prompt-tool">
        <el-select v-model="promptKey" placeholder="选择模板 key" clearable style="width: 220px" @change="loadPrompts">
          <el-option label="tutor（答疑导师）" value="tutor" />
          <el-option label="review（AI 复盘）" value="review" />
        </el-select>
        <el-input v-model="promptNewContent" type="textarea" :rows="3" placeholder="新建版本内容（创建后自动激活）" />
        <el-button type="primary" @click="createPrompt">新建版本并激活</el-button>
      </div>
      <el-table :data="prompts" size="small" max-height="360">
        <el-table-column prop="promptKey" label="key" width="100" />
        <el-table-column prop="version" label="版本" width="70" align="center" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">{{ row.isActive === 1 ? '激活' : '历史' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button v-if="row.isActive !== 1" link type="primary" @click="activatePrompt(row)">激活</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
// AI 管理后台（仅管理员）：平台侧 AI 能力的运营面板。
//
// 四块内容：调用概览 + 趋势图、调用日志与用户 token 排行、运行时配置（配额开关、
// 比赛拦截、模型与 max_tokens）、提示词模板版本管理。
// 配置以「DB 覆盖 + 逐 key 保存」的方式生效，因此 saveConfig 是串行逐项写入，
// 任一项失败即中断，避免出现半新半旧的配置组合。
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import echarts from '@/utils/echarts'
import {
  getAiOverview, getAiTrend, getAiLogs, getAiRanking, getAiConfig, updateAiConfig,
  getAiQuota, resetAiQuota, setAiQuota,
  getPromptTemplates, createPromptTemplate, activatePromptTemplate
} from '@/api/adminAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { ElMessage } from 'element-plus'

const days = ref(7)
const overview = ref({})
const logs = ref([])
const ranking = ref([])
const config = ref({})
const savingConfig = ref(false)
const editableConfig = ref({})
const quotaUserId = ref('')
const quotaInfo = ref(null)
const quotaSetValue = ref(0)
const promptsVisible = ref(false)
const promptKey = ref('tutor')
const promptNewContent = ref('')
const prompts = ref([])
const trendChartRef = ref(null)
let chart = null

const filters = reactive({ userId: '', scene: '', status: '', dates: [] })

const cards = computed(() => [
  { label: '调用次数', value: overview.value.calls ?? 0 },
  { label: 'token 总量', value: (overview.value.tokens ?? 0).toLocaleString() },
  { label: '失败率', value: `${overview.value.failRate ?? 0}%` },
  { label: '估算成本（¥）', value: overview.value.estimatedCostYuan ?? 0 }
])

const statusTag = (s) => ({
  SUCCESS: 'success', FAIL: 'danger',
  QUOTA_REJECTED: 'warning', CONTEST_BLOCKED: 'warning'
}[s] || 'info')

// 布尔开关在这里统一转成字符串：后端配置项以字符串存储，
  // el-switch 的 active-value/inactive-value 也只认字符串，直接传布尔会显示为未选中。
const loadOverview = async () => {
  overview.value = unwrapResultData(await getAiOverview(days.value)) || {}
  const trend = unwrapResultData(await getAiTrend(days.value)) || []
  ranking.value = unwrapResultData(await getAiRanking(days.value)) || []
  config.value = unwrapResultData(await getAiConfig()) || {}
  editableConfig.value = {
    ai_quota_enabled: String(config.value.quotaEnabled),
    ai_quota_daily_tokens: config.value.dailyTokens ?? 30000,
    ai_quota_exempt_teacher_admin: String(config.value.exemptTeacherAdmin),
    ai_contest_guard_enabled: String(config.value.contestGuardEnabled),
    ai_contest_guard_cache_seconds: config.value.contestGuardCacheSeconds ?? 60,
    ai_model: config.value.model || 'deepseek-chat',
    ai_max_tokens: config.value.maxTokens ?? 8192,
    ai_json_mode: String(config.value.jsonMode ?? true)
  }
  renderTrend(trend)
  loadLogs()
}

const saveConfig = async () => {
  savingConfig.value = true
  try {
    const entries = Object.entries(editableConfig.value)
    // 串行逐 key 保存并任一失败即中断：并发保存会互相覆盖，
    // 中途失败继续则可能留下"配额已关但 token 上限还是旧的"这种不一致状态。
    for (const [key, value] of entries) {
      const res = await updateAiConfig(key, String(value))
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, `保存 ${key} 失败`))
        return
      }
    }
    ElMessage.success('配置已保存，秒级生效')
    config.value = unwrapResultData(await getAiConfig()) || {}
  } finally {
    savingConfig.value = false
  }
}

const queryQuota = async () => {
  if (!quotaUserId.value) return
  const res = await getAiQuota(quotaUserId.value)
  quotaInfo.value = unwrapResultData(res) || null
  quotaSetValue.value = quotaInfo.value?.usedToday ?? 0
}

const resetQuota = async () => {
  const res = await resetAiQuota(quotaUserId.value)
  if (isResultSuccess(res)) {
    ElMessage.success('已重置')
    queryQuota()
  }
}

const setQuota = async () => {
  const res = await setAiQuota(quotaUserId.value, quotaSetValue.value)
  if (isResultSuccess(res)) {
    ElMessage.success('已设置')
    queryQuota()
  }
}

const openPrompts = async () => {
  promptsVisible.value = true
  loadPrompts()
}

const loadPrompts = async () => {
  prompts.value = unwrapResultData(await getPromptTemplates(promptKey.value || undefined)) || []
}

const createPrompt = async () => {
  if (!promptNewContent.value.trim()) {
    ElMessage.warning('请输入模板内容')
    return
  }
  const res = await createPromptTemplate(promptKey.value || 'tutor', promptNewContent.value)
  if (!isResultSuccess(res)) {
    ElMessage.error(getResultMessage(res, '创建失败'))
    return
  }
  ElMessage.success('新版本已创建并激活')
  promptNewContent.value = ''
  loadPrompts()
}

const activatePrompt = async (row) => {
  const res = await activatePromptTemplate(row.id)
  if (isResultSuccess(res)) {
    ElMessage.success('已激活')
    loadPrompts()
  }
}

// 趋势图挂在常驻 DOM 上，只需 init 一次；重复 init 会在同一容器上叠加实例。
// 用 nextTick 等容器真正渲染出非零尺寸，否则 echarts 会拿到 0 宽高而画出空白图。
const renderTrend = (trend) => {
  nextTick(() => {
    if (!trendChartRef.value) return
    if (!chart) chart = echarts.init(trendChartRef.value)
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['调用次数', 'token'] },
      grid: { left: 60, right: 60, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: trend.map((t) => t.day) },
      yAxis: [
        { type: 'value', name: '次数' },
        { type: 'value', name: 'token' }
      ],
      series: [
        { name: '调用次数', type: 'line', smooth: true, data: trend.map((t) => t.calls) },
        { name: 'token', type: 'line', smooth: true, yAxisIndex: 1, data: trend.map((t) => t.tokens) }
      ]
    })
  })
}

const loadLogs = async () => {
  const res = await getAiLogs({
    page: 1, size: 100,
    userId: filters.userId || undefined,
    scene: filters.scene || undefined,
    status: filters.status || undefined,
    start: filters.dates?.[0] || undefined,
    end: filters.dates?.[1] || undefined
  })
  logs.value = unwrapResultData(res)?.records ?? []
}

onMounted(loadOverview)
// 必须 dispose：ECharts 实例内部持有 ResizeObserver 与定时器，不销毁会在离开页面后继续占用内存。
onUnmounted(() => chart && chart.dispose())
</script>

<style scoped>
.admin-ai { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.cards { margin-bottom: 16px; }
.card-label { color: var(--text-secondary); font-size: 13px; }
.card-value { font-size: 26px; font-weight: 700; margin-top: 6px; }
.block { margin-bottom: 16px; }
.trend-chart { height: 280px; }
.sub-header { display: flex; justify-content: space-between; align-items: center; }
.filters { display: flex; gap: 8px; }
.quota-tool { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.quota-info { color: var(--text-regular); font-size: 13px; }
.prompt-tool { display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
</style>
