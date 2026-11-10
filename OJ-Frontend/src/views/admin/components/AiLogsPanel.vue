<template>
  <div class="logs-page">
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">用量明细</span>
        <span class="panel-hint">按时间倒序 · 服务端分页</span>
      </div>
      <div class="filters">
        <el-input v-model="filters.userId" placeholder="用户 ID" clearable style="width: 120px" />
        <el-select v-model="filters.scene" placeholder="场景" clearable style="width: 130px">
          <el-option v-for="item in sceneOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 140px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-date-picker v-model="filters.dates" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" style="width: 240px" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div v-loading="loading">
        <el-table :data="logs" size="small" border stripe max-height="420" empty-text="当前条件下没有调用记录">
          <el-table-column prop="userId" label="用户" width="80" />
          <el-table-column label="场景" width="110">
            <template #default="{ row }">{{ sceneLabel(row.scene) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalTokens" label="token" width="100" />
          <el-table-column label="服务" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.serviceName || '旧环境' }}</template>
          </el-table-column>
          <el-table-column prop="model" label="模型" min-width="130" show-overflow-tooltip />
          <el-table-column label="配置版本" width="100">
            <template #default="{ row }">{{ row.configVersion ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="latencyMs" label="耗时 ms" width="90" />
          <el-table-column prop="detail" label="详情" min-width="160" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="时间" min-width="160" />
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadLogs"
            @size-change="onSizeChange"
          />
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">配额人工干预</span>
        <span class="panel-hint">仅影响当日配额计数</span>
      </div>
      <div class="quota-tool">
        <el-input v-model="quotaUserId" placeholder="用户 ID" clearable style="width: 150px" />
        <el-button type="primary" @click="queryQuota">查询</el-button>
        <template v-if="quotaInfo">
          <span class="quota-info">
            今日已用 <b>{{ quotaInfo.usedToday }}</b> / {{ quotaInfo.dailyLimit }} token（{{ quotaInfo.date }}）
          </span>
          <el-input-number v-model="quotaSetValue" :min="0" style="width: 140px" aria-label="设置已用 token" />
          <el-button @click="setQuota">设置已用</el-button>
          <el-button type="danger" plain @click="resetQuota">重置当日</el-button>
        </template>
      </div>
      <p class="panel-foot">重置会清除该用户当日已用配额记录，操作会记入审计日志。</p>
    </div>
  </div>
</template>

<script setup>
// 日志与配额面板：服务端分页筛选 + 配额人工干预。
// 状态/场景使用中文标签；URL 中的 status 用于从概览异常流定位到本页。
import { ref, reactive, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAiLogs, getAiQuota, resetAiQuota, setAiQuota
} from '@/api/adminAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const route = useRoute()

const sceneOptions = [
  { label: '答疑', value: 'ASK' },
  { label: '复盘', value: 'REVIEW' },
  { label: '建议', value: 'ADVICE' },
  { label: '间接对话', value: 'INDIRECT' },
  { label: '题面格式化', value: 'INDIRECT_JSON' },
  { label: '学情解读', value: 'TOOL' }
]
const statusOptions = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAIL' },
  { label: '用户停止', value: 'CANCELLED' },
  { label: '配额拒绝', value: 'QUOTA_REJECTED' },
  { label: '比赛拦截', value: 'CONTEST_BLOCKED' }
]

const sceneLabel = (s) => sceneOptions.find((i) => i.value === s)?.label || s || '—'
const statusLabel = (s) => statusOptions.find((i) => i.value === s)?.label || s || '未知'
const statusType = (s) => ({
  SUCCESS: 'success', FAIL: 'danger', CANCELLED: 'info',
  QUOTA_REJECTED: 'warning', CONTEST_BLOCKED: 'warning'
}[s] || 'info')

const filters = reactive({
  userId: '', scene: '', status: statusOptions.some((i) => i.value === route.query.status) ? route.query.status : '',
  dates: []
})
const page = ref(1)
const size = ref(20)
const total = ref(0)
const logs = ref([])
const loading = ref(false)

let generation = 0
const loadLogs = async () => {
  const version = ++generation
  loading.value = true
  try {
    const res = await getAiLogs({
      page: page.value,
      size: size.value,
      userId: filters.userId || undefined,
      scene: filters.scene || undefined,
      status: filters.status || undefined,
      start: filters.dates?.[0] || undefined,
      end: filters.dates?.[1] || undefined
    })
    if (version !== generation) return
    const result = unwrapResultData(res) || {}
    logs.value = Array.isArray(result.records) ? result.records : []
    total.value = Number(result.total) || 0
  } catch (error) {
    if (version === generation) showApiError(error, '调用日志加载失败')
  } finally {
    if (version === generation) loading.value = false
  }
}

const search = () => { page.value = 1; loadLogs() }
const onSizeChange = () => { page.value = 1; loadLogs() }
const resetFilters = () => {
  filters.userId = ''
  filters.scene = ''
  filters.status = ''
  filters.dates = []
  search()
}

watch(() => route.query.status, (value) => {
  filters.status = statusOptions.some((i) => i.value === value) ? value : ''
  search()
})

const quotaUserId = ref('')
const quotaInfo = ref(null)
const quotaSetValue = ref(0)

const queryQuota = async () => {
  if (!quotaUserId.value) return
  try {
    const res = await getAiQuota(quotaUserId.value)
    const data = unwrapResultData(res)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '配额查询失败'))
      return
    }
    quotaInfo.value = data || null
    quotaSetValue.value = data?.usedToday ?? 0
  } catch (error) {
    showApiError(error, '配额查询失败')
  }
}

const setQuota = async () => {
  if (!quotaUserId.value) return
  try {
    const res = await setAiQuota(quotaUserId.value, quotaSetValue.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '设置失败'))
      return
    }
    ElMessage.success('已设置')
    queryQuota()
  } catch (error) {
    showApiError(error, '设置失败，已保留原值')
  }
}

const resetQuota = async () => {
  if (!quotaUserId.value) return
  try {
    await ElMessageBox.confirm(
      `确认重置用户 ${quotaUserId.value} 的当日配额？该用户今日已用记录将被清除。`,
      '重置当日配额',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await resetAiQuota(quotaUserId.value)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '重置失败'))
      return
    }
    ElMessage.success('已重置')
    queryQuota()
  } catch (error) {
    showApiError(error, '重置失败')
  }
}

onMounted(loadLogs)
</script>

<style scoped>
.logs-page { display: flex; flex-direction: column; gap: 12px; }
.panel { min-width: 0; padding: 12px 14px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.panel-title { color: var(--text-primary); font-size: 14px; font-weight: 600; }
.panel-hint, .panel-foot { color: var(--text-secondary); font-size: 12px; }
.panel-foot { margin: 10px 0 0; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.quota-tool { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.quota-info { color: var(--text-regular); font-size: 13px; }
</style>
