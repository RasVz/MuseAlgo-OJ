<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="780px"
    top="6vh"
    destroy-on-close
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div v-loading="loading || generating" class="ai-dialog">
      <!-- ===== 模式：标准答案 ===== -->
      <StdAnswerPanel
        v-if="mode === 'std'"
        :std="std"
        :edit-mode="editMode"
        :saving-std="savingStd"
        :clearing-std="clearingStd"
        :saved-msg="stdSavedMsg"
        :saved-ok="stdSavedOk"
        @update:edit-mode="editMode = $event"
        @update:code="std.code = $event"
        @save="saveStd"
        @clear="clearStd"
        @regenerate="generate('std')"
      />

      <!-- ===== 模式：测试数据（生成器模式） ===== -->
      <GeneratorPanel
        v-else
        :has-std="hasStd"
        v-model:generator-code="generatorCode"
        :plan="genPlan"
        :generating="generating"
        :verifying="verifying"
        :result="verifyResult"
        :saving="saving"
        :saved-msg="savedMsg"
        :saved-ok="savedOk"
        @regenerate="generate('testdata')"
        @verify="verifyGenerated"
        @save="saveCases"
        @goto-std="emit('goto-std')"
      />
    </div>
  </el-dialog>
</template>

<script setup>
// AI 题目工具弹窗：生成标准答案（std）或测试数据生成器，两种模式共用一个弹窗。
//
// 强依赖：生成测试数据的前提是该题已有 std——没有标准答案就无法验证生成的用例，
// 因此打开 testdata 模式时先查 std，不存在则让用户先去生成 std。
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProblemDetail, saveProblemStd, getProblemStd, clearProblemStd } from '@/api/problem'
import { wbStd, wbGenerator, generateAndVerifyCases, appendProblemCases } from '@/api/teacherAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import StdAnswerPanel from './components/StdAnswerPanel.vue'
import GeneratorPanel from './components/GeneratorPanel.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  problemId: { type: Number, default: null },
  mode: { type: String, default: 'std' } // std | testdata
})
const emit = defineEmits(['update:modelValue', 'saved', 'goto-std'])

const dialogTitle = computed(() => {
  const map = { std: '标准答案生成', testdata: '测试数据生成' }
  return `${map[props.mode] || 'AI 工具'} · #${props.problemId ?? ''}`
})

const loading = ref(false)
const generating = ref(false)
const problem = ref(null) // 题目详情（用于保存）

// ---- std ----
const std = ref({ code: '', explanation: '', complexity: '' })
const editMode = ref(false)
const savingStd = ref(false)
const clearingStd = ref(false)
const stdSavedMsg = ref('')
const stdSavedOk = ref(true)

// ---- testdata（生成器模式） ----
const hasStd = ref(false)
const generatorCode = ref('')
const genPlan = ref([])
const verifyResult = ref(null)
const verifying = ref(false)

// ---- 保存状态 ----
const saving = ref(false)
const savedMsg = ref('')
const savedOk = ref(true)

// ===== 加载题目上下文 =====
// 拼完整题面再交给 AI：只给标题会让模型误解题意，
// 生成出来的 std 与用例几乎必然对不上真实要求。
const buildStatement = (p) => {
  let t = `# ${p.title}\n\n${p.content || ''}\n`
  if (p.inputDesc) t += `\n### 输入格式\n${p.inputDesc}\n`
  if (p.outputDesc) t += `\n### 输出格式\n${p.outputDesc}\n`
  if (p.hint) t += `\n### 提示\n${p.hint}\n`
  return t
}

const reset = () => {
  std.value = { code: '', explanation: '', complexity: '' }
  savedMsg.value = ''
  editMode.value = false
  savingStd.value = false
  clearingStd.value = false
  stdSavedMsg.value = ''
  hasStd.value = false
  generatorCode.value = ''
  genPlan.value = []
  verifyResult.value = null
  verifying.value = false
}

// 每次打开都要 reset：弹窗是复用的，不重置会把上一题的生成结果
// 显示给教师，造成"改 A 题保存到 B 题"这类严重误操作。
const open = async () => {
  if (!props.problemId) return
  reset()
  loading.value = true
  try {
    const res = await getProblemDetail(props.problemId)
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '加载题目失败')); return }
    problem.value = res.data || {}
    if (props.mode === 'testdata') {
      // 无 std 不允许生成：先检查该题已保存的标准答案
      const stdRes = await getProblemStd(props.problemId)
      hasStd.value = isResultSuccess(stdRes) && !!stdRes.data?.code
      if (hasStd.value) await generate('testdata')
    } else {
      await generate('std')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('加载题目失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

// 打开即生成：教师点开这个弹窗的目的就是要生成内容，
// 让用户再点一次"生成"没有意义；生成失败会明确提示，可手动重试。
watch(() => props.modelValue, (v) => { if (v) open() })

// ===== 生成 =====
const generate = async (mode) => {
  if (!problem.value?.title) return
  generating.value = true
  savedMsg.value = ''
  try {
    const p = problem.value
    const statement = buildStatement(p)
    if (mode === 'std') {
      const res = await wbStd({ title: p.title, statement })
      if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '生成失败')); return }
      const json = JSON.parse(unwrapResultData(res) || '{}')
      std.value = { code: json.code || '', explanation: json.explanation || '', complexity: json.complexity || '' }
      if (!std.value.code) ElMessage.error('生成内容为空，请重试')
    } else {
      // 生成器模式：AI 生成 Python 生成器代码 + 数据分布规划
      const res = await wbGenerator({
        title: p.title, statement,
        inputDesc: p.inputDesc || '', outputDesc: p.outputDesc || '',
        constraints: '', caseCount: 30
      })
      if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '生成失败')); return }
      const json = JSON.parse(unwrapResultData(res) || '{}')
      generatorCode.value = json.code || ''
      genPlan.value = Array.isArray(json.plan) ? json.plan : []
      verifyResult.value = null
      savedMsg.value = ''
      if (!generatorCode.value) ElMessage.error('生成内容为空，请重试')
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('生成失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

// ===== 标准答案：保存到该题 =====
const saveStd = async () => {
  if (!props.problemId || !std.value.code) { ElMessage.warning('请先生成标准答案'); return }
  savingStd.value = true
  try {
    const res = await saveProblemStd(props.problemId, std.value.code, std.value.explanation)
    if (!isResultSuccess(res)) {
      stdSavedOk.value = false
      stdSavedMsg.value = `保存失败：${getResultMessage(res, '未知错误')}`
      return
    }
    stdSavedOk.value = true
    stdSavedMsg.value = '标准答案已保存到该题（生成测试数据时会自动用它实测输出）'
    ElMessage.success(stdSavedMsg.value)
    emit('saved')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    savingStd.value = false
  }
}

// ===== 标准答案：清除 =====
// 清除 std 会让该题失去生成测试数据的前提，且已保存的用例标准输出也会失效，
// 属于不可恢复操作，必须二次确认。
const clearStd = async () => {
  if (!props.problemId) return
  try {
    await ElMessageBox.confirm(
      '确定清除该题的标准答案？清除后该题将无法生成测试数据（需重新生成并保存）。',
      '清除标准答案',
      { type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  clearingStd.value = true
  try {
    const res = await clearProblemStd(props.problemId)
    if (!isResultSuccess(res)) {
      stdSavedOk.value = false
      stdSavedMsg.value = `清除失败：${getResultMessage(res, '未知错误')}`
      return
    }
    std.value = { code: '', explanation: '', complexity: '' }
    stdSavedOk.value = true
    stdSavedMsg.value = '标准答案已清除'
    ElMessage.success(stdSavedMsg.value)
    emit('saved')
  } catch (e) {
    console.error(e)
    ElMessage.error('清除失败，请稍后重试')
  } finally {
    clearingStd.value = false
  }
}

// ===== 测试数据：执行生成器并用 std 逐组实测（后端聚合，不落库） =====
// 生成 + 验证一步完成：由后端跑一遍生成器，再用该题的 std 逐组实测输出。
// 这一步不落库——教师要先在界面上看到"多少组通过、失败在哪"，确认无误再保存。
const verifyGenerated = async () => {
  if (!props.problemId || !generatorCode.value) return
  verifying.value = true
  savedMsg.value = ''
  try {
    const res = await generateAndVerifyCases({
      problemId: props.problemId,
      generatorCode: generatorCode.value
    })
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '生成并验证失败')); return }
    verifyResult.value = res.data
    const d = res.data || {}
    if (d.failed) {
      ElMessage.warning(`生成 ${d.total ?? 0} 组，通过 ${d.passed ?? 0} 组，失败 ${d.failed ?? 0} 组（失败组已丢弃）`)
    } else {
      ElMessage.success(`全部 ${d.total ?? 0} 组均通过标准答案实测`)
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('生成并验证失败，请稍后重试')
  } finally {
    verifying.value = false
  }
}

// ===== 测试数据：追加保存到该题（不覆盖旧用例，重复自动跳过） =====
// 保存是"追加"而非覆盖：教师可能已经手工上传过关键用例，
// 不能因为重新生成了一次就把它清掉。重复输入由后端去重。
const saveCases = async () => {
  const cases = verifyResult.value?.cases
  if (!props.problemId || !Array.isArray(cases) || !cases.length) return
  try {
    await ElMessageBox.confirm(
      `将追加保存 ${cases.length} 组测试数据到该题（不覆盖旧用例，重复输入自动跳过）。`,
      '确认追加保存',
      { type: 'warning', confirmButtonText: '追加保存', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    const res = await appendProblemCases(props.problemId, cases)
    if (!isResultSuccess(res)) {
      savedOk.value = false
      savedMsg.value = `保存失败：${getResultMessage(res, '未知错误')}`
      return
    }
    savedOk.value = true
    const d = res.data || {}
    savedMsg.value = `已追加 ${d.appended ?? 0} 组测试数据（跳过重复 ${d.skipped ?? 0} 组），该题现有 ${d.totalCount ?? 0} 组`
    ElMessage.success(savedMsg.value)
    emit('saved')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.ai-dialog { min-height: 200px; }
</style>
