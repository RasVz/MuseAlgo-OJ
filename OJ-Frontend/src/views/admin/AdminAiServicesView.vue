<template>
  <div class="admin-ai-services admin-scroll-page" v-loading="loading">
    <div class="page-header">
      <div>
        <div class="page-title">AI 服务</div>
        <div class="page-desc">
          维护 Chat Completions 兼容接口；全站同时使用一条。连接测试会产生少量 API 请求，不发送学生代码。
        </div>
      </div>
      <div class="header-actions">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">添加服务</el-button>
      </div>
    </div>

    <el-card shadow="never" class="runtime-card">
      <div class="runtime-head">
        <div>
          <div class="runtime-label">当前运行</div>
          <div class="runtime-value">
            <el-tag :type="modeTagType" effect="dark">{{ modeLabel }}</el-tag>
            <template v-if="runtime.mode === 'MANAGED' && runtime.currentServiceName">
              <span class="runtime-name">{{ runtime.currentServiceName }}</span>
              <span class="runtime-model">{{ runtime.currentServiceModel }}</span>
              <el-tag v-if="!runtime.currentServiceReady" type="danger" size="small">配置不可用</el-tag>
            </template>
            <span v-else-if="runtime.mode === 'LEGACY'" class="runtime-model">
              旧环境模型：{{ runtime.legacyModel || '未配置' }}
            </span>
            <span v-else class="runtime-model">不发送任何模型请求</span>
          </div>
          <div class="runtime-desc">{{ modeDescription }}</div>
        </div>
        <div class="runtime-actions">
          <el-button
            v-if="runtime.mode !== 'OFF'"
            type="danger"
            plain
            :loading="actionLoading === 'runtime-off'"
            @click="stopAllAi"
          >
            停用 AI
          </el-button>
          <el-button
            v-if="runtime.mode !== 'LEGACY'"
            :loading="actionLoading === 'runtime-legacy'"
            @click="restoreLegacy"
          >
            恢复旧环境配置
          </el-button>
        </div>
      </div>
    </el-card>

    <el-alert
      v-if="!services.length"
      type="info"
      :closable="false"
      show-icon
      title="还没有后台服务"
      description="添加并测试一条服务后，管理员主动“设为当前服务”才会启用。升级后的默认来源仍是旧环境配置。"
      class="empty-tip"
    />

    <el-card shadow="never">
      <el-table :data="services" size="small" row-key="id" class="service-table">
        <el-table-column prop="name" label="服务名称" min-width="140" fixed="left" show-overflow-tooltip />
        <el-table-column label="接口地址" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ row.apiUrl }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="140" show-overflow-tooltip />
        <el-table-column label="密钥" width="86" align="center">
          <template #default="{ row }">
            <el-tag :type="row.hasApiKey ? 'success' : 'warning'" size="small">
              {{ row.hasApiKey ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="使用状态" width="108" align="center">
          <template #default="{ row }">
            <el-tag :type="usageTagType(row.usageStatus)" size="small">{{ usageLabel(row.usageStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近测试" min-width="176">
          <template #default="{ row }">
            <div class="test-cell">
              <el-tag :type="testTagType(row.testState)" size="small">{{ testLabel(row.testState) }}</el-tag>
              <span class="muted">
                <template v-if="row.lastTestAt">
                  v{{ row.lastTestVersion }} · {{ formatTime(row.lastTestAt) }}
                </template>
                <template v-else>尚未测试</template>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="66" align="center" />
        <el-table-column label="操作" min-width="345" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button
                link
                type="primary"
                :loading="actionLoading === `test-${row.id}`"
                @click="runTest(row)"
              >
                测试连接
              </el-button>
              <el-button
                link
                type="success"
                :disabled="row.usageStatus === 'CURRENT' || !row.enabled || row.testState !== 'PASSED'"
                :loading="actionLoading === `activate-${row.id}`"
                @click="activate(row)"
              >
                设为当前
              </el-button>
              <el-button
                v-if="row.enabled"
                link
                type="warning"
                :disabled="row.usageStatus === 'CURRENT'"
                :loading="actionLoading === `toggle-${row.id}`"
                @click="toggle(row, false)"
              >
                停用
              </el-button>
              <el-button
                v-else
                link
                type="success"
                :loading="actionLoading === `toggle-${row.id}`"
                @click="toggle(row, true)"
              >
                启用
              </el-button>
              <el-button
                link
                type="danger"
                :disabled="row.usageStatus === 'CURRENT'"
                :loading="actionLoading === `delete-${row.id}`"
                @click="remove(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer
      v-model="drawerVisible"
      :title="editingId ? '编辑 AI 服务' : '添加 AI 服务'"
      size="560px"
      :before-close="beforeDrawerClose"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top">
        <el-alert
          v-if="editingCurrent"
          type="warning"
          :closable="false"
          show-icon
          title="正在编辑当前服务"
          description="保存时会先用候选配置测试普通文本、流式文本和 JSON；全部通过且版本未变化才会原子应用，失败时线上配置保持不变。"
          class="form-alert"
        />
        <el-form-item label="服务名称" prop="name">
          <el-input v-model="form.name" maxlength="50" show-word-limit placeholder="例如：教学主服务" />
        </el-form-item>
        <el-form-item label="接口地址" prop="apiUrl">
          <el-input
            v-model="form.apiUrl"
            placeholder="https://api.example.com/v1/chat/completions"
            autocomplete="off"
          />
          <div class="field-help">填写完整请求地址，不自动拼接路径；公网默认只允许 HTTPS 443。</div>
        </el-form-item>
        <el-form-item label="模型名称" prop="model">
          <el-input v-model="form.model" maxlength="128" placeholder="服务商要求的精确模型 ID" />
        </el-form-item>
        <el-form-item label="API 密钥" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingId ? '留空表示保留现有密钥' : '输入服务商 API Key'"
          />
          <div class="field-help">密钥只保存为加密密文，不提供查看或复制；关闭抽屉后临时输入会被清空。</div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可选，例如用途、负责人或服务商说明"
          />
        </el-form-item>

        <el-collapse class="advanced">
          <el-collapse-item title="高级设置（兼容性）" name="advanced">
            <el-form-item label="协议级 JSON 输出">
              <el-switch v-model="form.jsonMode" />
              <div class="field-help">
                开启时请求 response_format=json_object，并在连接测试中额外验证 JSON。模型不支持时可关闭，业务仍会校验返回内容。
              </div>
            </el-form-item>
            <el-form-item label="流式末尾请求 usage">
              <el-switch v-model="form.streamIncludeUsage" />
              <div class="field-help">请求 stream_options.include_usage；供应商不支持时会忽略。</div>
            </el-form-item>
            <el-form-item label="输出上限（max_tokens）">
              <el-input-number v-model="form.maxTokens" :min="1" :max="65536" :step="1024" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="requestDrawerClose">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">
          {{ editingCurrent ? '测试并保存应用' : (editingId ? '保存修改' : '保存服务') }}
        </el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="reportVisible" title="连接测试结果" width="620px">
      <div v-if="report" class="report-summary">
        <el-tag :type="report.passed ? 'success' : 'danger'" effect="dark">
          {{ report.passed ? '全部必测项通过' : '存在失败项' }}
        </el-tag>
        <span class="muted">配置版本 v{{ report.configVersion }} · 总耗时 {{ report.totalLatencyMs }} ms</span>
      </div>
      <el-table v-if="report" :data="report.checks" size="small" border>
        <el-table-column label="检查项" width="110">
          <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="96">
          <template #default="{ row }">
            <el-tag :type="row.skipped ? 'info' : (row.success ? 'success' : 'danger')" size="small">
              {{ row.skipped ? '已跳过' : (row.success ? '通过' : '失败') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时 ms" width="90" />
        <el-table-column prop="errorType" label="类型" width="140" />
        <el-table-column prop="message" label="说明" min-width="180" />
      </el-table>
      <template #footer>
        <el-button type="primary" @click="reportVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  activateAiService,
  createAiService,
  deleteAiService,
  disableAiService,
  enableAiService,
  getAiRuntime,
  getAiServices,
  testAiService,
  updateAiRuntime,
  updateAiService
} from '@/api/adminAiServices'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

const loading = ref(false)
const services = ref([])
const runtime = ref({ mode: 'LEGACY', runtimeVersion: 1, currentServiceReady: false })
const actionLoading = ref('')
const drawerVisible = ref(false)
const saving = ref(false)
const editingId = ref(null)
const editingCurrent = ref(false)
const formRef = ref(null)
const formSnapshot = ref('')
const reportVisible = ref(false)
const report = ref(null)

const emptyForm = () => ({
  name: '',
  protocol: 'CHAT_COMPLETIONS',
  apiUrl: '',
  model: '',
  apiKey: '',
  remark: '',
  jsonMode: true,
  streamIncludeUsage: true,
  maxTokens: 8192
})
const form = ref(emptyForm())

const modeLabel = computed(() => ({
  LEGACY: '旧环境',
  MANAGED: '后台服务',
  OFF: '已停用'
}[runtime.value.mode] || runtime.value.mode || '未知'))

const modeTagType = computed(() => ({
  LEGACY: 'info',
  MANAGED: 'success',
  OFF: 'danger'
}[runtime.value.mode] || 'info'))

const modeDescription = computed(() => ({
  LEGACY: '使用环境变量中的 URL、Key 和旧动态模型配置；升级后的默认模式。',
  MANAGED: '每次请求读取当前服务完整配置；密钥不可用时安全失败，不退回旧环境。',
  OFF: '不构造任何模型请求，业务返回既有降级提示。'
}[runtime.value.mode] || '运行模式读取失败，请刷新后确认。'))

const formRules = {
  name: [
    { required: true, message: '请输入服务名称', trigger: 'blur' },
    { max: 50, message: '服务名称不能超过 50 个字符', trigger: 'blur' }
  ],
  apiUrl: [
    { required: true, message: '请输入完整接口地址', trigger: 'blur' },
    { validator: validateEndpoint, trigger: 'blur' }
  ],
  model: [
    { required: true, message: '请输入模型 ID', trigger: 'blur' },
    { max: 128, message: '模型 ID 不能超过 128 个字符', trigger: 'blur' }
  ],
  apiKey: [{ validator: validateApiKey, trigger: 'blur' }]
}

function validateEndpoint(rule, value, callback) {
  try {
    const parsed = new URL(String(value || ''))
    if (!['http:', 'https:'].includes(parsed.protocol)) {
      callback(new Error('只支持 HTTP 或 HTTPS 地址'))
      return
    }
    if (!parsed.hostname) {
      callback(new Error('接口地址缺少主机名'))
      return
    }
    if (parsed.username || parsed.password || parsed.search || parsed.hash) {
      callback(new Error('接口地址不能包含用户信息、query 或 fragment'))
      return
    }
    callback()
  } catch {
    callback(new Error('请输入完整 URL，例如 https://api.example.com/v1/chat/completions'))
  }
}

function validateApiKey(rule, value, callback) {
  const key = String(value || '')
  if (!editingId.value && !key.trim()) {
    callback(new Error('新增服务必须填写 API 密钥'))
    return
  }
  if (key && /^[*•●·]+$/.test(key.trim())) {
    callback(new Error('不能使用星号等占位字符'))
    return
  }
  callback()
}

const usageLabel = (status) => ({ CURRENT: '当前使用', AVAILABLE: '可用备选', DISABLED: '已停用' }[status] || status)
const usageTagType = (status) => ({ CURRENT: 'success', AVAILABLE: 'primary', DISABLED: 'info' }[status] || 'info')
const testLabel = (state) => ({ UNTESTED: '未测试', PASSED: '通过', FAILED: '失败', STALE: '已过期' }[state] || state)
const testTagType = (state) => ({ UNTESTED: 'info', PASSED: 'success', FAILED: 'danger', STALE: 'warning' }[state] || 'info')
const kindLabel = (kind) => ({ TEXT: '普通文本', STREAM: '流式文本', JSON: 'JSON 输出', CRYPTO: '密钥解密' }[kind] || kind)

const formatTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const [servicesRes, runtimeRes] = await Promise.all([getAiServices(), getAiRuntime()])
    if (isResultSuccess(servicesRes)) services.value = unwrapResultData(servicesRes) || []
    if (isResultSuccess(runtimeRes)) {
      runtime.value = unwrapResultData(runtimeRes) || runtime.value
    } else {
      ElMessage.error(getResultMessage(runtimeRes, '运行状态读取失败'))
    }
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  editingCurrent.value = false
  form.value = emptyForm()
  drawerVisible.value = true
  nextTick(() => {
    formRef.value?.clearValidate()
    formSnapshot.value = snapshotForm()
  })
}

function openEdit(row) {
  editingId.value = row.id
  editingCurrent.value = row.usageStatus === 'CURRENT'
  form.value = {
    name: row.name,
    protocol: row.protocol || 'CHAT_COMPLETIONS',
    apiUrl: row.apiUrl,
    model: row.model,
    apiKey: '',
    remark: row.remark || '',
    jsonMode: row.jsonMode !== false,
    streamIncludeUsage: row.streamIncludeUsage !== false,
    maxTokens: row.maxTokens || 8192
  }
  drawerVisible.value = true
  nextTick(() => {
    formRef.value?.clearValidate()
    formSnapshot.value = snapshotForm()
  })
}

function snapshotForm() {
  return JSON.stringify({ ...form.value, apiKey: form.value.apiKey ? '[provided]' : '' })
}

function isDirty() {
  return snapshotForm() !== formSnapshot.value
}

function clearTemporarySecret() {
  form.value.apiKey = ''
}

function beforeDrawerClose(done) {
  if (!isDirty()) {
    clearTemporarySecret()
    done()
    return
  }
  ElMessageBox.confirm('表单有未保存的修改，确定关闭吗？', '未保存的修改', {
    type: 'warning',
    confirmButtonText: '放弃修改',
    cancelButtonText: '继续编辑'
  }).then(() => {
    clearTemporarySecret()
    done()
  }).catch(() => {})
}

function requestDrawerClose() {
  beforeDrawerClose(() => {
    drawerVisible.value = false
  })
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      protocol: 'CHAT_COMPLETIONS',
      apiUrl: form.value.apiUrl.trim(),
      model: form.value.model.trim(),
      remark: form.value.remark.trim(),
      jsonMode: form.value.jsonMode,
      streamIncludeUsage: form.value.streamIncludeUsage,
      maxTokens: form.value.maxTokens
    }
    if (form.value.apiKey) payload.apiKey = form.value.apiKey

    let res
    if (editingId.value) {
      payload.version = services.value.find((item) => item.id === editingId.value)?.version
      if (editingCurrent.value) payload.runtimeVersion = runtime.value.runtimeVersion
      res = await updateAiService(editingId.value, payload)
    } else {
      res = await createAiService(payload)
    }
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }
    if (editingCurrent.value) {
      const data = unwrapResultData(res)
      if (data?.testReport) showReport(data.testReport)
      if (data && data.applied === false) {
        ElMessage.warning('连接测试未全部通过，线上配置未修改')
        await load()
        return
      }
      ElMessage.success('候选配置已测试通过并应用')
    } else {
      ElMessage.success(editingId.value ? '服务已保存' : '服务已添加，请测试后再设为当前')
    }
    clearTemporarySecret()
    drawerVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function runTest(row) {
  actionLoading.value = `test-${row.id}`
  try {
    const res = await testAiService(row.id, row.version)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '连接测试失败'))
      return
    }
    showReport(unwrapResultData(res))
    await load()
  } finally {
    actionLoading.value = ''
  }
}

function showReport(data) {
  report.value = data
  reportVisible.value = true
}

async function activate(row) {
  try {
    await ElMessageBox.confirm(
      `将全站 AI 切换到「${row.name}」（v${row.version}）。之后的新请求使用该服务，正在输出的流不受影响。`,
      '设为当前服务',
      { type: 'warning', confirmButtonText: '确认切换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = `activate-${row.id}`
  try {
    const res = await activateAiService(row.id, row.version, runtime.value.runtimeVersion)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '切换失败'))
      return
    }
    ElMessage.success('已切换当前 AI 服务')
    await load()
  } finally {
    actionLoading.value = ''
  }
}

async function toggle(row, enabled) {
  try {
    await ElMessageBox.confirm(
      enabled ? `启用备选服务「${row.name}」？启用不会自动设为当前服务。` : `停用备选服务「${row.name}」？`,
      enabled ? '启用服务' : '停用服务',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = `toggle-${row.id}`
  try {
    const res = enabled ? await enableAiService(row.id, row.version) : await disableAiService(row.id, row.version)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '操作失败'))
      return
    }
    ElMessage.success(enabled ? '服务已启用' : '服务已停用')
    await load()
  } finally {
    actionLoading.value = ''
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `软删除「${row.name}」？历史用量会保留，测试结果和密钥不再可读取。`,
      '删除服务',
      { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = `delete-${row.id}`
  try {
    const res = await deleteAiService(row.id, row.version)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '删除失败'))
      return
    }
    ElMessage.success('服务已删除')
    await load()
  } finally {
    actionLoading.value = ''
  }
}

async function stopAllAi() {
  try {
    await ElMessageBox.confirm(
      '停用全站 AI？即使旧环境仍保留 Key，也不会发送模型请求；业务返回既有降级提示。',
      '停用 AI',
      { type: 'error', confirmButtonText: '确认停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = 'runtime-off'
  try {
    const res = await updateAiRuntime('OFF', runtime.value.runtimeVersion)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '停用失败'))
      return
    }
    ElMessage.success('全站 AI 已停用')
    await load()
  } finally {
    actionLoading.value = ''
  }
}

async function restoreLegacy() {
  try {
    await ElMessageBox.confirm(
      '恢复旧环境配置？之后请求会使用环境变量中的 URL、Key 与旧动态模型，不再使用后台服务。',
      '恢复旧环境配置',
      { type: 'warning', confirmButtonText: '确认恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionLoading.value = 'runtime-legacy'
  try {
    const res = await updateAiRuntime('LEGACY', runtime.value.runtimeVersion)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '恢复失败'))
      return
    }
    ElMessage.success('已恢复旧环境配置')
    await load()
  } finally {
    actionLoading.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.admin-ai-services { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; }
.page-desc { color: var(--text-secondary); font-size: 13px; margin-top: 4px; max-width: 760px; line-height: 1.6; }
.header-actions { display: flex; gap: 8px; flex-shrink: 0; }
.runtime-card { margin-bottom: 16px; }
.runtime-head { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; }
.runtime-label { color: var(--text-secondary); font-size: 13px; }
.runtime-value { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-top: 8px; }
.runtime-name { font-size: 16px; font-weight: 600; }
.runtime-model { color: var(--text-regular); font-size: 13px; }
.runtime-desc { color: var(--text-secondary); font-size: 12px; margin-top: 8px; max-width: 760px; }
.runtime-actions { display: flex; gap: 8px; flex-shrink: 0; }
.empty-tip { margin-bottom: 16px; }
.test-cell { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.muted { color: var(--text-secondary); font-size: 12px; }
.row-actions { display: flex; flex-wrap: wrap; row-gap: 4px; }
.service-table { width: 100%; }
.form-alert { margin-bottom: 14px; }
.field-help { color: var(--text-secondary); font-size: 12px; line-height: 1.5; margin-top: 4px; }
.advanced { margin-top: 4px; }
.report-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
@media (max-width: 900px) {
  .runtime-head { flex-direction: column; }
  .page-header { flex-direction: column; }
}
</style>
