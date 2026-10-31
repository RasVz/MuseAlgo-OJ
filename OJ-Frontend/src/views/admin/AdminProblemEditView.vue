<template>
  <div class="editor-page admin-scroll-page">
    <div class="editor-hero">
      <div class="hero-left">
        <div class="hero-badge">Problem Studio</div>
        <h1 class="hero-title">{{ isEditMode ? `编辑题目 #${form.id || routeId}` : '新建题目' }}</h1>
        <p class="hero-subtitle">高效维护题目信息与测试用例，打造更专业的题库管理体验</p>
      </div>
      <div class="hero-right">
        <el-button class="ghost-btn" @click="goBack">返回题库</el-button>
        <el-button type="primary" class="save-btn" :loading="saving" @click="handleSaveBasic">
          {{ isEditMode ? '保存修改' : '保存并创建' }}
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="editor-tabs">
      <el-tab-pane label="基本信息" name="basic">
        <div class="basic-toolbar">
          <el-button :icon="MagicStick" :disabled="!form.id" :loading="formatting" @click="handleFormatOneFromEdit">
            AI 格式化
          </el-button>
          <div class="ip-toggle">
            <el-switch v-model="ipRenameEnabled" size="small" />
            <span class="ip-toggle-label">IP 改名</span>
          </div>
          <span class="basic-toolbar-hint">AI 整理题面结构、抽取时限/输入输出格式/样例并翻译；勾选 IP 改名后与格式化一次调用完成，预览确认后才保存</span>
        </div>
        <el-card class="panel-card" shadow="never">
          <ProblemBasicForm ref="basicFormRef" :form="form" :rules="rules" @update:form="Object.assign(form, $event)" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="测试用例管理" name="cases" :disabled="!form.id">
        <ProblemCaseManager ref="caseManagerRef" :problem-id="form.id" @changed="handleCasesChanged" />
      </el-tab-pane>

      <el-tab-pane label="标准答案管理" name="std" :disabled="!form.id">
        <StdManagerPanel ref="stdPanelRef" :problem-id="form.id" @saved="handleStdSaved" />
      </el-tab-pane>
    </el-tabs>

    <FormatPreviewDialog
      v-model="formatPreviewVisible"
      :preview="formatPreview"
      :applying="applyingFormat"
      @apply="handleApplyFormatWithGuard"
    />
  </div>
</template>

<script setup>
// 题目编辑器：以「基本信息 / 测试用例 / 标准答案」三个 Tab 组织一道题的完整生命周期。
//
// 关键流程：新建时只有基本信息，保存成功拿到题目 ID 后 replace 到编辑路由并自动跳到
// 测试用例 Tab——因为题目在用例上传完成前不可判题，刻意把这条路径做成引导。
// 测试用例与标准答案分别由 ProblemCaseManager 与 StdManagerPanel 各自管理并独立保存。
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getProblemDetail,
  createProblem,
  updateProblem
} from '@/api/problem'
import ProblemBasicForm from './components/ProblemBasicForm.vue'
import ProblemCaseManager from './components/ProblemCaseManager.vue'
import StdManagerPanel from './components/StdManagerPanel.vue'
import FormatPreviewDialog from './components/FormatPreviewDialog.vue'
import { useProblemFormat } from './composables/useProblemFormat'
import {
  changedFormattedFields,
  describeFieldChanges,
  mergeFormattedFields,
  pickFormattedFields
} from './problemFormatMerge'
import { MagicStick } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const routeId = computed(() => {
  const id = Number(route.params.id)
  return Number.isNaN(id) ? null : id
})
const isEditMode = computed(() => !!routeId.value)

const activeTab = ref('basic')
const saving = ref(false)
const basicFormRef = ref(null)
const caseManagerRef = ref(null)
const stdPanelRef = ref(null)

// 详情基线：记录「服务端当前值」中由格式化负责的字段，
// 用于在应用格式化前判断用户是否还有未保存修改会被覆盖。
const formatBaseline = ref(null)

// ---- AI 格式化（单题，基本信息 tab 内；IP 改名在调用前选择，与格式化一次调用完成） ----
const ipRenameEnabled = ref(false)
// 格式化落库后只刷新「格式化负责的字段」，不再整体重载表单，
// 否则用户在难度/来源/标签等字段上的未保存修改会被一起冲掉。
const { formatting, formatPreviewVisible, formatPreview, applyingFormat, handleFormatOne, handleApplyFormat } =
  useProblemFormat({ selectedProblems: ref([]), onChanged: refreshAfterFormat })

const handleFormatOneFromEdit = () => {
  if (!form.id) return
  handleFormatOne({ id: form.id }, ipRenameEnabled.value)
}

// 格式化结果落库后再回读服务端内容：服务端会做 HTML 消毒与字段裁剪，
// 直接拿预览对象回填会和数据库真实值不一致。
async function refreshAfterFormat() {
  if (!form.id) return
  try {
    const res = await getProblemDetail(form.id)
    if (res.code !== 200 || !res.data) {
      ElMessage.warning('格式化已保存，但刷新题目内容失败，请手动刷新页面确认')
      return
    }
    formatBaseline.value = mergeFormattedFields(form, res.data)
    stdPanelRef.value?.reloadProblem?.()
  } catch (e) {
    console.error(e)
    ElMessage.warning('格式化已保存，但刷新题目内容失败，请手动刷新页面确认')
  }
}

// 应用格式化前先确认：格式化的字段上若有未保存修改，会被服务端结果覆盖。
const handleApplyFormatWithGuard = async () => {
  const dirtyFields = changedFormattedFields(form, formatBaseline.value)
  if (dirtyFields.length) {
    try {
      await ElMessageBox.confirm(
        `AI 格式化会覆盖以下字段中尚未保存的修改：${describeFieldChanges(dirtyFields)}。` +
        '保存格式化结果后这些修改将丢失，是否继续？',
        '格式化将覆盖未保存的修改',
        { type: 'warning', confirmButtonText: '继续应用', cancelButtonText: '取消' }
      )
    } catch {
      return // 用户取消
    }
  }
  await handleApplyFormat()
}

// 用例增删改后，标准答案的「已测试」状态可能已失效：只刷新状态，不动编辑器内容。
const handleCasesChanged = () => {
  stdPanelRef.value?.refreshStatus?.()
}

const handleStdSaved = () => {
  ElMessage.success('标准答案已保存')
}

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

const rules = {
  title: [{ required: true, message: '请输入题目名称', trigger: 'blur' }],
  content: [{ required: true, message: '请输入题目内容', trigger: 'blur' }]
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
  formatBaseline.value = null
  activeTab.value = 'basic'
}

// 函数声明（提升）：供上方 useProblemFormat 的 onChanged 引用，避免 TDZ 报错
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
      formatBaseline.value = pickFormattedFields(res.data)
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('加载题目详情失败')
  }
}

const handleSaveBasic = async () => {
  if (!basicFormRef.value) return

  try {
    await basicFormRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const payload = { ...form }
    payload.timeLimit = Number(payload.timeLimit)
    payload.memoryLimit = Number(payload.memoryLimit)
    payload.difficulty = Number(payload.difficulty)
    payload.isPublic = Number(payload.isPublic)
    payload.samples = (form.samples || []).map(s => ({
      input: s?.input || '',
      output: s?.output || ''
    }))

    if (form.id) {
      const res = await updateProblem(payload)
      if (res.code === 200) {
        ElMessage.success('更新成功')
        // 保存成功后本地即服务端值，重置格式化基线，避免下次格式化误报「有未保存修改」
        formatBaseline.value = pickFormattedFields(form)
        // 用例与基本信息分离保存，改了题面不代表用例失效，重新拉一次避免陈旧展示
        caseManagerRef.value?.loadCases()
      } else {
        ElMessage.error(res.message || '更新失败')
      }
      return
    }

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
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

const goBack = () => {
  router.push('/admin/problem')
}

// 切到标准答案 tab 时对齐一次「已测试」状态：用例可能刚在另一个 tab 里被改动。
watch(activeTab, (tab) => {
  if (tab === 'std') stdPanelRef.value?.refreshStatus?.()
})

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

.basic-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding: 0 2px;
}

.basic-toolbar-hint {
  font-size: 12px;
  color: var(--text-secondary);
}

.ip-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ip-toggle-label {
  font-size: 13px;
  color: var(--text-primary);
}
</style>
