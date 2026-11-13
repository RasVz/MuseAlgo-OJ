<template>
  <div class="editor-page admin-scroll-page">
    <div class="editor-hero">
      <div class="hero-left">
        <div class="hero-badge">Problem Studio</div>
        <h1 class="hero-title">{{ isEditMode ? `编辑题目 #${form.id || routeId}` : '新建题目' }}</h1>
        <p class="hero-subtitle">
          {{ isEditMode ? '逐段编辑题面，AI 结果先成为草稿；保存才产生新版本' : '填写基础信息并保存，随后进入测试用例与题面优化' }}
        </p>
      </div>
      <div class="hero-right">
        <el-button class="ghost-btn" @click="goBack">返回题库</el-button>
        <el-button type="primary" class="save-btn" :loading="saving" @click="handleSaveBasic">
          {{ isEditMode ? (studioDirty ? '保存为新版本' : '保存修改') : '保存并创建' }}
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="editor-tabs" @tab-change="handleTabChange">
      <el-tab-pane v-if="!isEditMode" label="基本信息" name="basic">
        <el-card class="panel-card" shadow="never">
          <ProblemBasicForm ref="basicFormRef" :form="form" :rules="rules" @update:form="Object.assign(form, $event)" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="isEditMode" label="题面" name="statement">
        <div class="meta-row">
          <label class="meta-item">
            <span>来源</span>
            <el-input v-model="form.source" size="small" placeholder="如 LeetCode" />
          </label>
          <label class="meta-item">
            <span>难度分</span>
            <el-input-number v-model="form.difficulty" :min="800" :max="3000" :step="100" size="small" controls-position="right" />
          </label>
          <label class="meta-item is-grow">
            <span>标签</span>
            <el-input v-model="form.tags" size="small" placeholder="多个标签用逗号分隔" />
          </label>
          <label class="meta-item">
            <span>可见性</span>
            <el-switch v-model="form.isPublic" :active-value="1" :inactive-value="0" active-text="公开" inactive-text="私有" inline-prompt />
          </label>
        </div>

        <StatementStudio
          ref="studioRef"
          :problem-id="form.id"
          :form="form"
          :initial-version-id="route.query.version"
          :saving="saving"
          @update:form="Object.assign(form, $event)"
          @dirty-change="handleStudioDirty"
        />
      </el-tab-pane>

      <el-tab-pane label="测试用例管理" name="cases" :disabled="!form.id" lazy>
        <ProblemCaseManager ref="caseManagerRef" :problem-id="form.id" @changed="handleCasesChanged" />
      </el-tab-pane>

      <el-tab-pane label="标准答案管理" name="std" :disabled="!form.id" lazy>
        <StdManagerPanel ref="stdPanelRef" :problem-id="form.id" @saved="handleStdSaved" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
// 题目编辑器：题面 / 测试用例 / 标准答案。
//
// 题面不再区分「编辑」和「优化」两套界面：StatementStudio 里编辑的是同一份 form，
// AI 只是把生成结果写进同一块编辑区（作为草稿），保存时统一走 compose 接口落成版本。
// 新建题目仍用基础表单，因为题目还没创建、没有可版本化的对象。
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getProblemDetail,
  createProblem,
  updateProblem,
  composeStatement
} from '@/api/problem'
import { showApiError } from '@/utils/errorFeedback'
import ProblemBasicForm from './components/ProblemBasicForm.vue'
import ProblemCaseManager from './components/ProblemCaseManager.vue'
import StdManagerPanel from './components/StdManagerPanel.vue'
import StatementStudio from './problemFormat/StatementStudio.vue'
import { toFormattedPayload } from './problemFormat/formatReview'

const route = useRoute()
const router = useRouter()

const routeId = computed(() => {
  const id = Number(route.params.id)
  return Number.isNaN(id) ? null : id
})
const isEditMode = computed(() => !!routeId.value)

const activeTab = ref(isEditMode.value ? 'statement' : 'basic')
const saving = ref(false)
const basicFormRef = ref(null)
const caseManagerRef = ref(null)
const stdPanelRef = ref(null)
const studioRef = ref(null)
const studioDirty = ref(false)
const studioAiDraft = ref(false)

const form = reactive({
  id: null,
  title: '',
  source: 'System',
  content: '',
  inputDesc: '',
  outputDesc: '',
  samples: [],
  hint: '',
  difficulty: 800,
  timeLimit: 1000,
  memoryLimit: 512,
  tags: '',
  isPublic: 1
})

// 仅新建时使用：题面字段的必填校验
const rules = {
  title: [{ required: true, message: '请输入题目名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入题目内容', trigger: 'blur' }]
}

// 元数据基线：只改了难度/标签/可见性时不产生新版本
const metaBaseline = ref(null)
const metaDirty = computed(() => {
  const base = metaBaseline.value
  if (!base) return false
  return String(form.source || '') !== base.source
    || Number(form.difficulty) !== base.difficulty
    || String(form.tags || '') !== base.tags
    || Number(form.isPublic) !== base.isPublic
})

function snapshotMeta() {
  metaBaseline.value = {
    source: String(form.source || ''),
    difficulty: Number(form.difficulty) || 0,
    tags: String(form.tags || ''),
    isPublic: Number(form.isPublic) || 0
  }
}

function handleStudioDirty(state) {
  studioDirty.value = Boolean(state?.dirty)
  studioAiDraft.value = Boolean(state?.aiDraft)
}

// 创建接口返回的新 ID 位置不固定（数字/字符串/对象/消息文本都可能带出来），
// 逐层兜底；全部失败时只能提示用户手动刷新，不做任何猜测性跳转。
const extractCreatedProblemId = (res) => {
  const data = res?.data

  if (typeof data === 'number' && Number.isFinite(data) && data > 0) {
    return data
  }

  if (typeof data === 'string' && /^\d+$/.test(data.trim())) {
    return Number(data.trim())
  }

  if (data && typeof data === 'object') {
    const rawId = data.id ?? data.problemId
    const parsedId = Number(rawId)
    if (Number.isFinite(parsedId) && parsedId > 0) {
      return parsedId
    }
  }

  const rawMessage = String(res?.message || res?.msg || '')
  const matched = rawMessage.match(/(\d+)/)
  return matched ? Number(matched[1]) : null
}

const resetForm = () => {
  form.id = null
  form.title = ''
  form.source = 'System'
  form.content = ''
  form.inputDesc = ''
  form.outputDesc = ''
  form.samples = []
  form.hint = ''
  form.difficulty = 800
  form.timeLimit = 1000
  form.memoryLimit = 512
  form.tags = ''
  form.isPublic = 1
  metaBaseline.value = null
  activeTab.value = 'basic'
}

const TAB_NAMES = ['statement', 'basic', 'cases', 'std']

// ?tab=std 这类外链参数：只有题目加载成功、对应 tab 可用时才切换。
const applyRequestedTab = () => {
  const tab = String(route.query.tab || '')
  if (!TAB_NAMES.includes(tab)) return
  if (tab !== 'basic' && !form.id) {
    const query = { ...route.query }
    delete query.tab
    router.replace({ query })
    return
  }
  activeTab.value = tab
}

// 详情回填时把 isPublic 归一到 0/1：后端布尔与数字混用，
// 归一后 el-switch 才能正确显示选中态，提交时也不用再特判。
async function init() {
  if (!isEditMode.value) {
    resetForm()
    return
  }

  try {
    const res = await getProblemDetail(routeId.value)
    if (res.code === 200) {
      Object.assign(form, res.data)
      if (form.isPublic === true) form.isPublic = 1
      else if (form.isPublic === false) form.isPublic = 0
      form.samples = (Array.isArray(res.data.samples) ? res.data.samples : []).map(s => ({
        input: s?.input ?? '',
        output: s?.output ?? ''
      }))
      snapshotMeta()
      applyRequestedTab()
    }
  } catch (e) {
    console.error(e)
    showApiError(e, '加载题目详情失败')
  }
}

/** 新建题目：仍走原来的基础表单，创建成功后进入编辑态。 */
async function createProblemFlow() {
  if (!basicFormRef.value) return
  try {
    await basicFormRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = buildPayload()
    const res = await createProblem(payload)
    const createdId = extractCreatedProblemId(res)

    if (res.code === 200 && createdId) {
      form.id = createdId
      ElMessage.success('创建成功，请继续上传测试用例')
      // 用 replace 而非 push：新建后地址栏必须变成正式编辑地址，
      // 否则用户刷新会回到空白新建页，已上传的用例也无处找回。
      await router.replace(`/admin/problem/edit/${createdId}`)
      activeTab.value = 'cases'
      return
    }

    if (res.code === 200) {
      ElMessage.warning('创建成功，但未获取到题目ID，请刷新后重试')
      return
    }

    ElMessage.error(res.message || '创建失败')
  } catch (e) {
    console.error(e)
    showApiError(e, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function buildPayload() {
  const payload = { ...form }
  payload.timeLimit = Number(payload.timeLimit)
  payload.memoryLimit = Number(payload.memoryLimit)
  payload.difficulty = Number(payload.difficulty)
  payload.isPublic = Number(payload.isPublic)
  payload.samples = (form.samples || []).map(s => ({
    input: s?.input || '',
    output: s?.output || ''
  }))
  return payload
}

const handleSaveBasic = async () => {
  if (!form.id) {
    await createProblemFlow()
    return
  }

  if (!String(form.title || '').trim()) {
    activeTab.value = 'statement'
    ElMessage.warning('题名不能为空')
    return
  }
  if (!String(form.content || '').trim()) {
    activeTab.value = 'statement'
    ElMessage.warning('题目正文不能为空')
    return
  }

  const statementDirty = studioRef.value?.isDirty?.() === true
  if (!statementDirty && !metaDirty.value) {
    ElMessage.info('没有需要保存的修改')
    return
  }

  saving.value = true
  try {
    // 先落版本（写题面 + 建版本），再补元数据；顺序反了会出现「版本里是旧内容」
    if (statementDirty) {
      await composeStatement(form.id, {
        baseVersionId: studioRef.value?.baseVersionId ?? null,
        source: studioAiDraft.value ? 'AI_APPLIED' : 'MANUAL',
        note: '',
        formatted: toFormattedPayload({
          title: form.title,
          content: form.content,
          inputDesc: form.inputDesc,
          outputDesc: form.outputDesc,
          hint: form.hint,
          timeLimit: form.timeLimit,
          memoryLimit: form.memoryLimit,
          samples: form.samples
        })
      })
    }

    if (metaDirty.value) {
      const res = await updateProblem(buildPayload())
      if (res.code !== 200) {
        ElMessage.error(res.message || '元数据保存失败')
        return
      }
    }

    ElMessage.success(statementDirty ? '已保存为新版本' : '修改已保存')
    snapshotMeta()
    await studioRef.value?.reload?.()
    caseManagerRef.value?.loadCases?.()
  } catch (e) {
    console.error(e)
    showApiError(e, '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

const goBack = () => {
  router.push('/admin/problem')
}

// 切到标准答案 tab 时对齐一次「已测试」状态：用例可能刚在另一个 tab 里被改动。
const handleTabChange = (name) => {
  if (name === 'std') stdPanelRef.value?.refreshStatus?.()
}

const handleCasesChanged = () => {
  stdPanelRef.value?.refreshStatus?.()
}

const handleStdSaved = () => {
  ElMessage.success('标准答案已保存')
}

watch(
  () => route.query.tab,
  () => applyRequestedTab()
)

onMounted(() => init())
</script>

<style scoped>
.editor-page {
  min-height: calc(100vh - 70px);
  padding: 20px 24px 30px;
  background: radial-gradient(circle at top right, #eef4ff 0%, #f8faff 30%, #f4f6fb 100%);
}
.dark .editor-page {
  background: radial-gradient(circle at top right, #16233f 0%, #0f1b33 30%, #0d1526 100%);
}

.editor-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
  padding: 20px 24px;
  border-radius: 16px;
  background: linear-gradient(135deg, #ffffff, #f5f8ff 55%, #edf3ff);
  box-shadow: 0 6px 22px rgba(47, 84, 235, 0.08);
  border: 1px solid #e7ecf8;
}
.dark .editor-hero {
  background: linear-gradient(135deg, #1b2944, #16233f 55%, #111c33);
  border-color: #26365c;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: #3a63d1;
  background: #edf3ff;
  border: 1px solid #dbe6ff;
  border-radius: 999px;
  padding: 3px 10px;
  margin-bottom: 10px;
  font-weight: 600;
}

.hero-title {
  margin: 0;
  font-size: 28px;
  line-height: 1.25;
  letter-spacing: 0.5px;
  color: #1d2b53;
}
.dark .hero-title {
  color: #dbe6ff;
}

.hero-subtitle {
  margin: 8px 0 0;
  font-size: 14px;
  color: #5f6b87;
}
.dark .hero-subtitle {
  color: #8fa3c8;
}
.dark .hero-badge {
  color: #7ea6ff;
  background: #1d2b53;
  border-color: #2f4a85;
}

.hero-right {
  display: flex;
  gap: 10px;
  margin-left: 18px;
}

.ghost-btn {
  border-radius: 10px;
}

.save-btn {
  border-radius: 10px;
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.28);
}

.editor-tabs {
  border-radius: 16px;
  overflow: hidden;
}

.panel-card {
  border-radius: 16px;
  border: 1px solid var(--border-light);
  background: var(--bg-surface);
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 12px;
  border: 1px solid var(--border-light);
  border-radius: 10px;
  background: var(--bg-surface);
}
.meta-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--text-secondary);
}
.meta-item.is-grow { flex: 1; min-width: 220px; }
.meta-item.is-grow :deep(.el-input) { flex: 1; }
.meta-item :deep(.el-input) { width: 150px; }
.meta-item.is-grow :deep(.el-input) { width: auto; }
</style>
