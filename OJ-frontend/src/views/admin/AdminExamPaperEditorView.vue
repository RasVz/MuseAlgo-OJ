<template>
  <div class="exam-paper-editor admin-scroll-page">
    <div class="page-header">
      <div>
        <div class="page-title">{{ isEditMode ? '编辑试卷' : '新建试卷' }}</div>
        <div class="page-desc">填写基本信息并添加编程题与客观题，保存后可在训练试卷管理中继续维护</div>
      </div>
      <div class="header-actions">
        <el-button @click="goBack">返回</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存试卷</el-button>
      </div>
    </div>

    <el-card shadow="never" class="section-card" v-loading="loadingDetail">
      <template #header>基本信息</template>
      <el-form :model="form" label-width="110px" class="basic-form">
        <el-form-item label="试卷标题" required>
          <el-input v-model="form.title" placeholder="如：GESP 2级 2024-09 真题" style="max-width: 520px" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 200px">
            <el-option v-for="cat in CATEGORIES" :key="cat.value" :label="cat.label" :value="cat.value" />
          </el-select>
          <el-input v-model="form.level" placeholder="等级/组别（如 GESP 2级、CSP-J）" style="width: 300px; margin-left: 8px" />
        </el-form-item>
        <el-form-item label="时长/满分">
          <el-input-number v-model="form.durationMinutes" :min="10" :max="600" /> 分钟
          <el-input-number v-model="form.totalScore" :min="1" :max="1000" style="margin-left: 12px" /> 分
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="备考建议、试卷说明等" style="max-width: 720px" />
        </el-form-item>
        <el-form-item label="发布">
          <el-switch v-model="form.isPublished" :active-value="1" :inactive-value="0" active-text="发布" inactive-text="草稿" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>题目设置</template>

      <div class="sub-section">
        <div class="sub-head">
          <div>
            <div class="sub-title">编程题（从题库选择）</div>
            <div class="sub-desc">按试卷中的展示顺序排列，选择后可上下调整顺序</div>
          </div>
          <span class="sub-count">已选 {{ selectedProblemIds.length }} 题</span>
        </div>
        <el-select
          v-model="selectedProblemIds"
          multiple
          filterable
          remote
          :remote-method="searchProblems"
          :loading="searching"
          placeholder="搜索并选择题目，可多次搜索追加"
          style="width: 100%"
        >
          <el-option v-for="p in problemOptions" :key="p.id" :label="`#${p.id} ${p.title}`" :value="p.id" />
        </el-select>
        <div v-if="selectedProblemIds.length" class="selected-list">
          <div v-for="(pid, idx) in selectedProblemIds" :key="pid" class="selected-item">
            <span class="item-index">{{ idx + 1 }}</span>
            <span class="item-label">{{ problemLabel(pid) }}</span>
            <span class="item-actions">
              <el-button link :disabled="idx === 0" @click="moveProblem(idx, -1)">上移</el-button>
              <el-button link :disabled="idx === selectedProblemIds.length - 1" @click="moveProblem(idx, 1)">下移</el-button>
              <el-button link type="danger" @click="removeSelectedProblem(pid)">移除</el-button>
            </span>
          </div>
        </div>
        <el-empty v-else description="尚未选择编程题" :image-size="60" class="section-empty" />
      </div>

      <el-divider />

      <div class="sub-section">
        <div class="sub-head">
          <div>
            <div class="sub-title">客观题（自建题目）</div>
            <div class="sub-desc">单选 / 多选 / 判断，学生提交后即时自动判分</div>
          </div>
          <span class="sub-count">共 {{ questions.length }} 题</span>
        </div>
        <div v-if="questions.length" class="question-list">
          <div v-for="(q, qi) in questions" :key="qi" class="question-card">
            <div class="q-card-head">
              <span class="q-index">第 {{ qi + 1 }} 题</span>
              <el-select v-model="q.questionType" style="width: 110px">
                <el-option label="单选" value="SINGLE" />
                <el-option label="多选" value="MULTIPLE" />
                <el-option label="判断" value="JUDGE" />
              </el-select>
              <span class="q-score-label">分值</span>
              <el-input-number v-model="q.score" :min="0" :max="100" style="width: 100px" />
              <el-button type="danger" link @click="removeQuestion(qi)">删除本题</el-button>
            </div>
            <el-input v-model="q.statement" type="textarea" :rows="2" placeholder="请输入题干" class="q-statement" />
            <template v-if="q.questionType !== 'JUDGE'">
              <div class="q-options-title">选项</div>
              <div v-for="(opt, oi) in q.options" :key="oi" class="q-option-row">
                <el-input v-model="opt.key" placeholder="A" class="opt-key" />
                <el-input v-model="opt.text" placeholder="选项内容" />
                <el-button type="danger" link @click="removeOption(q, oi)">移除</el-button>
              </div>
              <el-button size="small" link type="primary" class="add-option-btn" @click="addOption(q)">+ 添加选项</el-button>
            </template>
            <div class="q-answer-row">
              <el-input v-model="q.answer" placeholder="答案：A / ABD / T-F" class="answer-input" />
              <el-input v-model="q.explanation" type="textarea" :rows="1" placeholder="解析（可空，判分后展示给学生）" />
            </div>
          </div>
        </div>
        <div class="question-actions">
          <el-button type="primary" plain @click="addQuestion">+ 添加客观题</el-button>
          <el-button type="warning" plain @click="batchDialogVisible = true">批量粘贴录入</el-button>
        </div>
      </div>
    </el-card>

    <!-- 批量录入 -->
    <el-dialog v-model="batchDialogVisible" title="客观题批量粘贴" width="640px">
      <div class="batch-tip">
        每行一条：<code>题型|题干|选项|答案|分值</code>，选项用分号分隔（如 <code>A.1;B.2;C.3</code>）；判断题为 <code>JUDGE|题干|对/错|5</code>
      </div>
      <el-input v-model="batchText" type="textarea" :rows="12" placeholder="SINGLE|1+1=？|A.1;B.2;C.3|B|5" />
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="applyBatch">导入到题目列表</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 训练试卷编辑器：编排一份套卷——选择题内联编辑，编程题按题目 ID 从题库批量引入。
// 题目顺序即卷面顺序，通过 selectedProblemIds 的数组顺序 + moveProblem 上下移动决定；
// 编程题的分值来自题目自身，不在本页设置（避免与题库配置冲突）。
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getExamPaperDetail, saveExamPaper } from '@/api/examPaper'
import { getProblemList } from '@/api/problem'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

const route = useRoute()
const router = useRouter()

const CATEGORIES = [
  { value: 'GESP', label: 'GESP' },
  { value: 'CSP_J', label: 'CSP-J' },
  { value: 'CSP_S', label: 'CSP-S' },
  { value: 'NOIP', label: 'NOIP' },
  { value: 'MOCK', label: '模拟' }
]

const isEditMode = computed(() => Boolean(route.params.id))

const form = ref({
  id: null,
  title: '',
  description: '',
  category: 'GESP',
  level: '',
  durationMinutes: 120,
  totalScore: 100,
  isPublished: 0
})
const selectedProblemIds = ref([])
const problemOptions = ref([])
const questions = ref([])
const searching = ref(false)
const saving = ref(false)
const loadingDetail = ref(false)
const batchDialogVisible = ref(false)
const batchText = ref('')

const problemLabel = (pid) => {
  const p = problemOptions.value.find((x) => x.id === pid)
  return p ? `#${pid} ${p.title}` : `#${pid}`
}

const addQuestion = () => {
  questions.value.push({
    questionType: 'SINGLE',
    statement: '',
    options: [{ key: 'A', text: '' }, { key: 'B', text: '' }],
    answer: '',
    explanation: '',
    score: 5
  })
}

const removeQuestion = (index) => questions.value.splice(index, 1)

// 选项 key 按已有数量顺延 A、B、C...：手写 key 容易在增删选项后出现重复或跳号。
const addOption = (q) => {
  const nextKey = String.fromCharCode(65 + q.options.length)
  q.options.push({ key: nextKey, text: '' })
}

const removeOption = (q, index) => q.options.splice(index, 1)

const removeSelectedProblem = (pid) => {
  selectedProblemIds.value = selectedProblemIds.value.filter((x) => x !== pid)
}

const moveProblem = (index, delta) => {
  const target = index + delta
  if (target < 0 || target >= selectedProblemIds.value.length) return
  const arr = selectedProblemIds.value
  ;[arr[index], arr[target]] = [arr[target], arr[index]]
}

const searchProblems = async (keyword) => {
  searching.value = true
  try {
    const res = await getProblemList({ currentPage: 1, pageSize: 30, keyword: keyword || undefined })
    problemOptions.value = unwrapResultData(res)?.records ?? []
  } finally {
    searching.value = false
  }
}

const loadDetail = async () => {
  if (!isEditMode.value) return
  loadingDetail.value = true
  try {
    const res = await getExamPaperDetail(route.params.id)
    const detail = unwrapResultData(res)
    if (!detail) return
    form.value = {
      id: detail.id,
      title: detail.title,
      description: detail.description || '',
      category: detail.category || 'GESP',
      level: detail.level || '',
      durationMinutes: detail.durationMinutes ?? 120,
      totalScore: detail.totalScore ?? 100,
      isPublished: detail.isPublished ?? 0
    }
    selectedProblemIds.value = (detail.problems ?? []).map((p) => p.problemId)
    problemOptions.value = (detail.problems ?? []).map((p) => ({ id: p.problemId, title: p.title || `#${p.problemId}` }))
    questions.value = (detail.questions ?? []).map((q) => ({
      questionType: q.questionType,
      statement: q.statement,
      options: (q.options ?? []).map((o) => ({ key: o.key, text: o.text })),
      answer: q.answer || '',
      explanation: q.explanation || '',
      score: q.score ?? 5
    }))
  } finally {
    loadingDetail.value = false
  }
}

const onSave = async () => {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写试卷标题')
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form.value,
      problems: selectedProblemIds.value.map((problemId) => ({ problemId, score: 0 })),
      questions: questions.value.filter((q) => q.statement.trim()).map((q) => ({
        questionType: q.questionType,
        statement: q.statement,
        options: q.questionType === 'JUDGE' ? [] : q.options.filter((o) => o.text.trim()),
        answer: q.answer.trim(),
        explanation: q.explanation || undefined,
        score: q.score ?? 0
      }))
    }
    const res = await saveExamPaper(payload)
    if (!isResultSuccess(res)) {
      ElMessage.error(getResultMessage(res, '保存失败'))
      return
    }
    ElMessage.success('保存成功')
    setTimeout(() => {
      if (window.opener) {
        window.close()
      } else {
        router.push('/admin/exam-paper')
      }
    }, 800)
  } finally {
    saving.value = false
  }
}

const applyBatch = () => {
  const lines = batchText.value.split('\n').map((l) => l.trim()).filter(Boolean)
  const parsed = []
  for (const line of lines) {
    const parts = line.split('|').map((p) => p.trim())
    if (parts.length < 2) continue
    const type = (parts[0] || 'SINGLE').toUpperCase()
    const statement = parts[1]
    const answer = parts[3] || parts[2] || ''
    const score = Number(parts[4] || 5)
    const options = []
    if (type !== 'JUDGE' && parts[2]) {
      for (const opt of parts[2].split(';')) {
        const m = opt.match(/^([A-Z])[.、．]?\s*(.*)$/)
        options.push(m ? { key: m[1], text: m[2] } : { key: String.fromCharCode(65 + options.length), text: opt })
      }
    }
    parsed.push({ questionType: type, statement, options, answer, explanation: '', score })
  }
  questions.value.push(...parsed)
  batchDialogVisible.value = false
  batchText.value = ''
  ElMessage.success(`已导入 ${parsed.length} 道客观题`)
}

const goBack = () => {
  if (window.opener) {
    window.close()
  } else {
    router.push('/admin/exam-paper')
  }
}

onMounted(loadDetail)
</script>

<style scoped>
.exam-paper-editor { padding: 16px; background: var(--bg-surface-hover); }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 18px; font-weight: 600; color: var(--text-primary); }
.page-desc { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.header-actions { display: flex; gap: 10px; }
.section-card { max-width: 1100px; margin: 0 auto 16px; border-radius: 12px; }
.basic-form { padding-top: 8px; }
.sub-section { padding: 0 4px; }
.sub-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; gap: 12px; }
.sub-title { font-size: 15px; font-weight: 600; color: var(--text-primary); }
.sub-desc { font-size: 12px; color: var(--text-secondary); margin-top: 3px; }
.sub-count { font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
.selected-list { margin-top: 12px; border: 1px solid var(--border-light); border-radius: 8px; overflow: hidden; }
.selected-item { display: flex; align-items: center; gap: 10px; padding: 8px 12px; }
.selected-item + .selected-item { border-top: 1px solid var(--el-fill-color-light); }
.item-index { width: 26px; height: 26px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: var(--el-fill-color-light); color: var(--text-regular); font-size: 12px; font-weight: 600; }
.item-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--text-primary); }
.item-actions { flex-shrink: 0; display: flex; align-items: center; }
.section-empty { margin-top: 8px; }
.question-list { display: flex; flex-direction: column; gap: 14px; }
.question-card { border: 1px solid var(--border-light); border-radius: 10px; padding: 14px; background: var(--bg-surface-soft); }
.q-card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.q-index { font-size: 14px; font-weight: 600; color: var(--text-primary); margin-right: auto; }
.q-score-label { font-size: 12px; color: var(--text-secondary); margin-left: 8px; }
.q-statement { margin-bottom: 10px; }
.q-options-title { font-size: 12px; color: var(--text-secondary); margin: 10px 0 8px; }
.q-option-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.opt-key { width: 80px; flex-shrink: 0; }
.add-option-btn { margin-top: 2px; }
.q-answer-row { display: flex; gap: 10px; margin-top: 12px; }
.answer-input { width: 180px; flex-shrink: 0; }
.question-actions { display: flex; gap: 10px; margin-top: 16px; }
.batch-tip { color: var(--text-regular); font-size: 13px; margin-bottom: 10px; }
.batch-tip code { background: var(--bg-surface-hover); padding: 1px 4px; border-radius: 3px; }
@media (max-width: 720px) {
  .q-answer-row { flex-direction: column; }
  .answer-input { width: 100%; }
}
</style>
