<template>
  <div class="diagnostics-page">
    <div class="panel">
      <div class="panel-head">
        <div>
          <span class="panel-title">教学诊断与试跑</span>
          <p class="panel-hint">试跑不产生正式提交，次数单独统计；AI 请求与 token 见「概览」。统计保留 32 天。</p>
        </div>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="block" />
      <div class="metric-grid">
        <template v-for="group in metricGroups" :key="group.title">
          <div class="group-title">{{ group.title }}</div>
          <div v-for="item in group.items" :key="item[0]" class="metric">
            <span class="metric-label">{{ item[1] }}</span>
            <strong class="metric-value">
              {{ metricValue(item[0]) }}<small v-if="item[2]">{{ item[2] }}</small>
            </strong>
          </div>
        </template>
      </div>
    </div>

    <div class="two-col">
      <div class="panel">
        <div class="panel-head"><span class="panel-title">诊断预算</span></div>
        <el-form label-width="150px" class="budget-form">
          <el-form-item label="每次提问验证输入数">
            <el-input-number v-model="settings.maxCases" :min="0" :max="2" />
          </el-form-item>
          <el-form-item label="取证等待上限（秒）">
            <el-input-number v-model="settings.waitSeconds" :min="2" :max="30" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="saving" :disabled="!settingsLoaded" @click="saveSettings">保存诊断预算</el-button>
        <p class="panel-foot">默认一个输入、一次取证轮；设为 0 暂停自动试跑，仍可依据题面和已有记录回答。</p>
      </div>

      <div class="panel">
        <div class="panel-head"><span class="panel-title">试跑次数排行</span></div>
        <el-table :data="overview.ranking || []" size="small" max-height="200" empty-text="暂无试跑记录">
          <el-table-column prop="userId" label="用户 ID" />
          <el-table-column prop="runs" label="试跑次数（含参考程序）" />
        </el-table>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><span class="panel-title">参考题解查询与指定</span></div>
      <div class="reference-query">
        <el-input v-model="problemId" placeholder="题目 ID" aria-label="查询参考题解的题目 ID" />
        <el-button :loading="loadingReference" @click="loadReference">查询参考题解</el-button>
      </div>
      <div v-if="reference" class="reference-result">
        <p role="status" class="panel-foot">
          {{ reference.available
            ? `已连通：${reference.title}（${reference.originalChars} 字符，${reference.truncated ? '头尾节选' : '完整材料'}）`
            : '未找到可用的公开官方题解，AI 将只依据题面、代码与运行事实分析' }}
        </p>
        <div class="reference-select">
          <el-select v-model="selectedPost" aria-label="指定参考题解" style="width: min(420px, 100%)">
            <el-option label="自动选择公开官方题解" :value="0" />
            <el-option v-for="post in reference.candidates || []" :key="post.id" :label="`${post.id} · ${post.title}`" :value="Number(post.id)" />
          </el-select>
          <el-button :loading="savingReference" @click="saveReference">保存题解选择</el-button>
        </div>
        <p class="panel-foot">仅可选择同题公开官方题解；教师私有参考代码只用于后台核验，不发送给模型或学生。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
// 诊断试跑面板：11 项指标按用途分组，预算、排行与参考题解沿用原有接口。
// 请求统一走 api/adminAi.js；异步结果用版本号丢弃过期响应。
import { ref, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAiDiagnostics, getAiDiagnosticsSettings, updateAiDiagnosticsSettings,
  getAiDiagnosticsReference, updateAiDiagnosticsReference
} from '@/api/adminAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import { showApiError } from '@/utils/errorFeedback'

const props = defineProps({ days: { type: Number, default: 7 } })

const metricGroups = [
  {
    title: '试跑',
    items: [['run.manual', '手动试跑'], ['run.auto', '诊断试跑（含参考）'], ['run.reused', '复用记录']]
  },
  {
    title: '取证质量',
    items: [
      ['diagnosis.counterexamples', '已验证反例'], ['diagnosis.unavailable', '取证未完成'],
      ['run.code-error', '代码错误'], ['run.timeout', '运行超时'], ['run.service-error', '执行服务异常']
    ]
  },
  { title: '题解', items: [['reference.hit', '题解命中'], ['reference.miss', '题解缺失']] },
  { title: '耗时', items: [['run.elapsed-ms', '执行总耗时', 'ms']] }
]

const overview = ref({})
const settings = reactive({ maxCases: 1, waitSeconds: 12 })
const settingsLoaded = ref(false)
const error = ref('')
const saving = ref(false)
const reference = ref(null)
const problemId = ref('')
const selectedPost = ref(0)
const loadingReference = ref(false)
const savingReference = ref(false)
let generation = 0
let referenceGeneration = 0

const metricValue = (key) => {
  const value = overview.value.metrics?.[key]
  return typeof value === 'number' ? value.toLocaleString() : value ?? '—'
}

const load = async () => {
  const version = ++generation
  try {
    const [stats, config] = await Promise.all([
      getAiDiagnostics(props.days), getAiDiagnosticsSettings()
    ])
    if (version !== generation) return
    overview.value = unwrapResultData(stats) || {}
    Object.assign(settings, unwrapResultData(config) || {})
    settingsLoaded.value = true
    error.value = ''
  } catch (failure) {
    if (version === generation) {
      error.value = '诊断管理信息暂不可用'
      showApiError(failure, '诊断管理信息暂不可用')
    }
  }
}

const saveSettings = async () => {
  saving.value = true
  try {
    const res = await updateAiDiagnosticsSettings({ maxCases: settings.maxCases, waitSeconds: settings.waitSeconds })
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
    } else {
      ElMessage.success('诊断预算已保存')
    }
  } catch (failure) {
    showApiError(failure, '保存失败，将重新读取实际配置')
  } finally {
    await load()
    saving.value = false
  }
}

const loadReference = async () => {
  if (!/^[1-9]\d*$/.test(problemId.value) || !Number.isSafeInteger(Number(problemId.value))) {
    ElMessage.info('请输入有效题目 ID')
    return
  }
  const version = ++referenceGeneration
  const id = Number(problemId.value)
  loadingReference.value = true
  reference.value = null
  try {
    const result = await getAiDiagnosticsReference(id)
    if (version !== referenceGeneration) return
    reference.value = unwrapResultData(result) || null
    selectedPost.value = Number(reference.value?.selectedPostId) || 0
  } catch (failure) {
    if (version === referenceGeneration) showApiError(failure, '参考题解查询失败')
  } finally {
    if (version === referenceGeneration) loadingReference.value = false
  }
}

const saveReference = async () => {
  const id = reference.value?.problemId
  if (!id) return
  savingReference.value = true
  try {
    const res = await updateAiDiagnosticsReference(id, selectedPost.value || null)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '参考题解保存失败'))
      return
    }
    ElMessage.success('参考题解已保存')
    if (Number(problemId.value) === id) await loadReference()
  } catch (failure) {
    showApiError(failure, '参考题解保存失败')
  } finally {
    savingReference.value = false
  }
}

watch(() => props.days, load, { immediate: true })
watch(problemId, () => {
  referenceGeneration++
  reference.value = null
  loadingReference.value = false
})
</script>

<style scoped>
.diagnostics-page { display: flex; flex-direction: column; gap: 12px; }
.two-col { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.panel { min-width: 0; padding: 12px 14px; background: var(--bg-surface); border: 1px solid var(--border-light); border-radius: var(--radius-md); }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.panel-title { font-size: 14px; font-weight: 600; }
.panel-hint, .panel-foot { margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.panel-foot { margin-top: 10px; }
.block { margin-bottom: 12px; }
.group-title { grid-column: 1 / -1; margin: 4px 0 0; color: var(--text-secondary); font-size: 12px; font-weight: 600; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; }
.metric { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; background: var(--bg-input); border-radius: var(--radius-sm); }
.metric-label { color: var(--text-secondary); font-size: 12px; }
.metric-value { color: var(--text-primary); font-size: 20px; font-weight: 600; line-height: 1.2; }
.metric-value small { margin-left: 3px; color: var(--text-secondary); font-size: 12px; font-weight: 400; }
.budget-form { margin-bottom: 4px; }
.reference-query, .reference-select { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.reference-query { max-width: 460px; }
.reference-select { margin-top: 10px; }
@media (max-width: 1000px) { .two-col { grid-template-columns: minmax(0, 1fr); } }
</style>
