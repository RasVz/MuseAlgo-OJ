<template>
  <div class="admin-rejudge admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">受控重判</div>
        <div class="page-desc">单提交补救、范围化批量重判、操作记录与失败态查看（仅管理员）</div>
      </div>
      <el-button @click="loadOperations">刷新操作记录</el-button>
    </div>

    <el-tabs v-model="activeTab" class="rejudge-tabs">
      <el-tab-pane label="单提交重判" name="single">
        <el-card shadow="never">
          <el-alert
            title="重判会重置判题任务并按当前测试用例重新判题；已固化比赛/进行中比赛/VP 未结束会话会被后端拒绝。"
            type="warning"
            :closable="false"
            show-icon
            class="mb-16"
          />
          <el-form :model="singleForm" label-width="110px" style="max-width: 620px">
            <el-form-item label="提交 ID" required>
              <el-input v-model.trim="singleForm.submissionId" placeholder="例如 12345" />
            </el-form-item>
            <el-form-item label="重判原因" required>
              <el-input v-model.trim="singleForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                        placeholder="必填，写入审计与重判操作记录" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="singleLoading" @click="submitSingle">提交重判</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="范围化批量" name="batch">
        <el-card shadow="never">
          <el-alert
            title="必须先 dry-run 预览；预览只统计不写入。执行单批上限 200，同一范围执行时后端会重新校验。"
            type="info"
            :closable="false"
            show-icon
            class="mb-16"
          />
          <el-form :model="batchForm" label-width="110px" class="batch-form">
            <el-form-item label="范围类型" required>
              <el-select v-model="batchForm.scopeType" style="width: 240px">
                <el-option label="按题目" value="PROBLEM" />
                <el-option label="仅 FAILED" value="FAILED" />
                <el-option label="已结束比赛（未固化）" value="ENDED_CONTEST" />
                <el-option label="按时间窗" value="TIME_RANGE" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="needProblem" label="题目 ID" required>
              <el-input v-model.trim="batchForm.problemId" placeholder="题目 ID" style="width: 240px" />
            </el-form-item>
            <el-form-item v-if="needContest" label="比赛 ID" required>
              <el-input v-model.trim="batchForm.contestId" placeholder="已结束且未固化的比赛 ID" style="width: 240px" />
            </el-form-item>
            <el-form-item v-if="needTimeRange" label="时间窗" required>
              <el-date-picker
                v-model="batchForm.from"
                type="datetime"
                placeholder="开始（含）"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 220px"
              />
              <span class="range-sep">至</span>
              <el-date-picker
                v-model="batchForm.to"
                type="datetime"
                placeholder="结束（不含）"
                value-format="YYYY-MM-DDTHH:mm:ss"
                style="width: 220px"
              />
            </el-form-item>
            <el-form-item label="预览 / 执行原因">
              <el-input v-model.trim="batchForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                        placeholder="预览可空；执行时必填" />
            </el-form-item>
            <el-form-item>
              <el-button :loading="previewLoading" @click="handlePreview">dry-run 预览</el-button>
              <el-button type="danger" :loading="executeLoading" :disabled="!previewData || previewData.eligible <= 0"
                         @click="handleExecuteBatch">
                按预览范围执行
              </el-button>
            </el-form-item>
          </el-form>

          <template v-if="previewData">
            <el-divider content-position="left">预览结果</el-divider>
            <el-descriptions :column="4" border>
              <el-descriptions-item label="匹配">{{ previewData.matched }}</el-descriptions-item>
              <el-descriptions-item label="可重判">{{ previewData.eligible }}</el-descriptions-item>
              <el-descriptions-item label="上限">{{ previewData.limit }}</el-descriptions-item>
              <el-descriptions-item label="超限">
                <el-tag :type="previewData.overLimit ? 'danger' : 'success'">
                  {{ previewData.overLimit ? '是（需缩小范围）' : '否' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="比赛已固化">{{ previewData.blockedFinalized }}</el-descriptions-item>
              <el-descriptions-item label="比赛进行中">{{ previewData.blockedRunning }}</el-descriptions-item>
              <el-descriptions-item label="VP 未结束">{{ previewData.blockedVp }}</el-descriptions-item>
              <el-descriptions-item label="待判/在途">{{ previewData.blockedPending }}</el-descriptions-item>
              <el-descriptions-item label="无测试用例">{{ previewData.blockedNoTestCases }}</el-descriptions-item>
              <el-descriptions-item label="判题中">{{ previewData.blockedInFlight }}</el-descriptions-item>
              <el-descriptions-item label="比赛缺失">{{ previewData.blockedMissingContest }}</el-descriptions-item>
              <el-descriptions-item label="样例提交 ID">
                {{ (previewData.sampleSubmissionIds || []).join(', ') || '-' }}
              </el-descriptions-item>
            </el-descriptions>
          </template>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="操作记录" name="operations">
        <el-card shadow="never">
          <el-table :data="operations" v-loading="operationsLoading" style="width: 100%">
            <el-table-column prop="id" label="ID" width="80" align="center" />
            <el-table-column prop="scopeType" label="范围" width="150" />
            <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" width="120" align="center">
              <template #default="{ row }">{{ row.succeededItems }}/{{ row.totalItems }}（失败 {{ row.failedItems }}）</template>
            </el-table-column>
            <el-table-column prop="operatorName" label="操作人" width="120" />
            <el-table-column label="创建时间" width="170" align="center">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openOperation(row.id)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination-box">
            <el-pagination
              background
              layout="prev, pager, next, total"
              :total="operationTotal"
              :page-size="operationSize"
              :current-page="operationPage"
              @current-change="handleOperationPage"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="detailVisible" title="重判操作详情" width="900px">
      <template v-if="operationDetail">
        <el-descriptions :column="3" border class="mb-16">
          <el-descriptions-item label="操作 ID">{{ operationDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ operationDetail.status }}</el-descriptions-item>
          <el-descriptions-item label="范围">{{ operationDetail.scopeType }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="3">{{ operationDetail.reason }}</el-descriptions-item>
          <el-descriptions-item label="错误" :span="3">{{ operationDetail.errorMessage || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="operationDetail.items || []" max-height="420" style="width: 100%">
          <el-table-column prop="submissionId" label="提交" width="100" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column prop="testcaseDigest" label="用例摘要" width="160" show-overflow-tooltip />
          <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
          <el-table-column label="旧结果" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ shortJson(row.oldResultJson) }}</template>
          </el-table-column>
          <el-table-column label="新结果" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ shortJson(row.newResultJson) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import {
  executeRejudgeBatch,
  getRejudgeOperation,
  getRejudgeOperations,
  previewRejudgeBatch,
  rejudgeSubmission
} from '@/api/rejudge'

const activeTab = ref('single')
const singleLoading = ref(false)
const singleForm = ref({ submissionId: '', reason: '' })

const previewLoading = ref(false)
const executeLoading = ref(false)
const previewData = ref(null)
const batchForm = ref({
  scopeType: 'PROBLEM',
  problemId: '',
  contestId: '',
  from: '',
  to: '',
  reason: ''
})

const needProblem = computed(() => ['PROBLEM', 'FAILED'].includes(batchForm.value.scopeType))
const needContest = computed(() => ['ENDED_CONTEST', 'FAILED'].includes(batchForm.value.scopeType))
const needTimeRange = computed(() => ['TIME_RANGE', 'FAILED'].includes(batchForm.value.scopeType))

const operations = ref([])
const operationsLoading = ref(false)
const operationPage = ref(1)
const operationSize = 20
const operationTotal = ref(0)
const detailVisible = ref(false)
const operationDetail = ref(null)

function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}
function assertOk(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && Number(resp.code) !== 200) {
    throw new Error(resp.message || resp.msg || '请求失败')
  }
  return unwrap(resp)
}
function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}
function statusType(status) {
  return { COMPLETED: 'success', RUNNING: 'warning', PENDING: 'info', FAILED: 'danger', PARTIAL: 'warning' }[status] || 'info'
}
function shortJson(text) {
  if (!text) return '-'
  const value = String(text)
  return value.length > 120 ? value.slice(0, 120) + '…' : value
}

async function submitSingle() {
  const id = Number(singleForm.value.submissionId)
  if (!Number.isFinite(id) || id <= 0) return ElMessage.warning('请输入合法的提交 ID')
  if (!singleForm.value.reason.trim()) return ElMessage.warning('请填写重判原因')
  try {
    await ElMessageBox.confirm('确认重判该提交？重判期间该提交会重新排队判题。', '确认重判', { type: 'warning' })
  } catch { return }
  singleLoading.value = true
  try {
    const resp = await rejudgeSubmission(id, singleForm.value.reason.trim())
    const data = assertOk(resp)
    ElMessage.success('重判操作已创建：' + (data?.id ?? ''))
    activeTab.value = 'operations'
    loadOperations()
  } catch (e) {
    ElMessage.error(e.message || '重判失败')
  } finally {
    singleLoading.value = false
  }
}

function buildBatchPayload() {
  const form = batchForm.value
  const payload = { scopeType: form.scopeType }
  if (needProblem.value) {
    const id = Number(form.problemId)
    if (!Number.isFinite(id) || id <= 0) throw new Error('请输入合法的题目 ID')
    payload.problemId = id
  }
  if (needContest.value && form.contestId) {
    const id = Number(form.contestId)
    if (!Number.isFinite(id) || id <= 0) throw new Error('请输入合法的比赛 ID')
    payload.contestId = id
  }
  if (form.scopeType === 'ENDED_CONTEST') {
    const id = Number(form.contestId)
    if (!Number.isFinite(id) || id <= 0) throw new Error('按已结束比赛重判必须填写比赛 ID')
    payload.contestId = id
  }
  if (needTimeRange.value) {
    if (!form.from || !form.to) throw new Error('按时间窗重判必须填写起止时间')
    payload.from = form.from
    payload.to = form.to
  }
  payload.reason = form.reason.trim()
  return payload
}

async function handlePreview() {
  previewLoading.value = true
  previewData.value = null
  try {
    const payload = buildBatchPayload()
    previewData.value = assertOk(await previewRejudgeBatch(payload))
    if (!previewData.value.eligible) ElMessage.warning('没有可重判的提交，请调整范围')
  } catch (e) {
    ElMessage.error(e.message || '预览失败')
  } finally {
    previewLoading.value = false
  }
}

async function handleExecuteBatch() {
  try {
    const payload = buildBatchPayload()
    if (!payload.reason) return ElMessage.warning('执行批量重判必须填写原因')
    await ElMessageBox.confirm(
      '按当前预览范围执行？可重判 ' + previewData.value.eligible + ' 条，超过上限会被拒绝。',
      '确认执行批量重判',
      { type: 'warning' }
    )
    executeLoading.value = true
    const data = assertOk(await executeRejudgeBatch(payload))
    ElMessage.success('批量重判已创建：' + (data?.id ?? ''))
    activeTab.value = 'operations'
    loadOperations()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '执行失败')
  } finally {
    executeLoading.value = false
  }
}

async function loadOperations() {
  operationsLoading.value = true
  try {
    const data = assertOk(await getRejudgeOperations({ page: operationPage.value, size: operationSize }))
    operations.value = data?.records || []
    operationTotal.value = Number(data?.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '获取操作记录失败')
  } finally {
    operationsLoading.value = false
  }
}

function handleOperationPage(page) {
  operationPage.value = page
  loadOperations()
}

async function openOperation(id) {
  try {
    operationDetail.value = assertOk(await getRejudgeOperation(id))
    detailVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '获取操作详情失败')
  }
}

watch(activeTab, (tab) => {
  if (tab === 'operations') loadOperations()
})

onMounted(loadOperations)
</script>

<style scoped>
.admin-rejudge { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; gap: 12px; }
.page-title { font-size: 22px; font-weight: 700; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; }
.rejudge-tabs { background: transparent; }
.mb-16 { margin-bottom: 16px; }
.batch-form { max-width: 860px; }
.range-sep { margin: 0 10px; color: var(--text-secondary); }
.pagination-box { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
