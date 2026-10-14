<template>
  <div class="panel-card" shadow="never">
    <div class="std-head">
      <div class="std-title">
        标准答案（std）
        <el-tag v-if="tested" size="small" type="success" effect="light" class="std-tag">已测试</el-tag>
        <el-tag v-else size="small" type="warning" effect="light" class="std-tag">未测试</el-tag>
      </div>
      <div class="std-actions">
        <el-button size="small" :loading="generating" @click="aiGenerate">AI 生成</el-button>
        <el-button size="small" :loading="savingForce" :disabled="!code.trim()" @click="forceSave">
          强制保存
        </el-button>
        <el-button size="small" type="primary" :loading="testing" :disabled="!code.trim()" @click="testAndSave">
          测试并保存
        </el-button>
        <el-button size="small" type="danger" plain :loading="clearing" :disabled="!code.trim()" @click="saveAndClearCases">
          清空测试用例并保存
        </el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="mb12">
      <template #title>
        「强制保存」：不验证直接保存，标记为<el-text type="warning">未测试</el-text>。<br />
        「测试并保存」：先用现有测试数据逐组验证新 std，全部通过才保存并标记为<el-text type="success">已测试</el-text>（用于优化原有 std）。<br />
        「清空测试用例并保存」：保存新 std 的同时清空该题全部测试数据（用于原 std 错误导致测试数据也错误的情况，清空后需重新生成测试数据）。
      </template>
    </el-alert>

    <div v-loading="loading" class="mb12">
      <div class="sub-title">C++ 代码（Monaco 可编辑）</div>
      <CodeBlockEditor v-model="code" language="cpp" :height="260" />
    </div>

    <div class="sub-title">算法思路（explanation）</div>
    <el-input v-model="explanation" type="textarea" :rows="4" placeholder="可选：说明算法思路与复杂度" />

    <!-- 测试并保存的结果 -->
    <div v-if="testResult" class="mb12" style="margin-top: 12px">
      <el-alert
        :type="testResult.saved ? 'success' : 'warning'"
        :closable="false"
        :title="testResult.saved
          ? `标准答案已通过全部 ${testResult.total} 组测试数据并保存`
          : `测试未通过：${testResult.passed}/${testResult.total} 组通过，${testResult.failed} 组失败（未保存）`"
      />
      <div v-if="testResult.failures?.length" class="fail-list">
        <div v-for="(f, i) in testResult.failures" :key="i" class="fail-item">
          第 {{ f.caseOrder }} 组：{{ f.reason }}
        </div>
      </div>
    </div>

    <el-alert v-if="msg" :type="msgOk ? 'success' : 'warning'" :closable="false" :title="msg" class="mb12" style="margin-top: 12px" />
  </div>
</template>

<script setup>
// 标准答案（std）面板：生成、编辑、试跑验证与保存。
// std 是"这道题的标准输出"的唯一来源，一旦保存错会导致全体用户的提交被误判，
// 因此面板把「已通过用例验证」作为显式状态展示，未验证的 std 保存时会额外确认。
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProblemDetail, getProblemStd, saveProblemStd, testAndSaveStd, saveStdAndClearCases } from '@/api/problem'
import { wbStd } from '@/api/teacherAi'
import { unwrapResultData, isResultSuccess, getResultMessage } from '@/utils/response'
import CodeBlockEditor from '@/views/teacher/tools/components/CodeBlockEditor.vue'

const props = defineProps({
  problemId: { type: [Number, String], default: null }
})
const emit = defineEmits(['saved'])

const loading = ref(false)
const generating = ref(false)
const testing = ref(false)
const clearing = ref(false)
const savingForce = ref(false)
const code = ref('')
const explanation = ref('')
const tested = ref(false) // std 是否已通过测试数据验证
const testResult = ref(null)
const msg = ref('')
const msgOk = ref(true)

// 题目详情（AI 生成 std 时构建题面）
const problem = ref(null)

// AI 生成 std 时要拼一份完整题面（含输入输出格式与提示），
// 只给标题和正文会让模型误解题意，生成的 std 大概率对不上用例。
const buildStatement = (p) => {
  let t = `# ${p.title}\n\n${p.content || ''}\n`
  if (p.inputDesc) t += `\n### 输入格式\n${p.inputDesc}\n`
  if (p.outputDesc) t += `\n### 输出格式\n${p.outputDesc}\n`
  if (p.hint) t += `\n### 提示\n${p.hint}\n`
  return t
}

const load = async () => {
  if (!props.problemId) return
  loading.value = true
  try {
    const res = await getProblemStd(props.problemId)
    if (isResultSuccess(res) && res.data) {
      code.value = res.data.code || ''
      explanation.value = res.data.explanation || ''
      tested.value = !!res.data.tested
    }
    const pRes = await getProblemDetail(props.problemId)
    if (isResultSuccess(pRes)) problem.value = pRes.data || {}
  } catch (e) {
    console.error(e)
    ElMessage.error('加载标准答案失败')
  } finally {
    loading.value = false
  }
}

watch(() => props.problemId, (v) => { if (v) load() }, { immediate: true })

// 强制保存：不验证直接保存，标记为未测试。
// 之所以保留这条"危险路径"，是因为某些题（如需要特判的构造题）本来就无法用现有用例验证，
// 但必须把"未测试"这个状态显式写进数据里，让判题侧能识别出风险。
const forceSave = async () => {
  if (!props.problemId || !code.value.trim()) return
  try {
    await ElMessageBox.confirm(
      '将不经过测试直接保存标准答案，并标记为「未测试」。' +
      '建议后续点击「测试并保存」用现有测试数据验证，通过后会自动标记为「已测试」。',
      '确认强制保存',
      { type: 'warning', confirmButtonText: '强制保存', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  savingForce.value = true
  msg.value = ''
  try {
    const res = await saveProblemStd(props.problemId, code.value, explanation.value)
    if (!isResultSuccess(res)) {
      msg.value = `保存失败：${getResultMessage(res, '未知错误')}`
      msgOk.value = false
      return
    }
    tested.value = false
    msg.value = '标准答案已保存（未测试）'
    msgOk.value = true
    ElMessage.success(msg.value)
    emit('saved')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    savingForce.value = false
  }
}

// AI 结果是 JSON 字符串而非对象，需要解析；解析失败或 code 为空都要明确报错，
// 否则用户会对着空白编辑器以为生成成功。
const aiGenerate = async () => {
  if (!problem.value?.title) { ElMessage.warning('请先保存基本信息'); return }
  try {
    await ElMessageBox.confirm(
      '将调用 AI 生成标准答案（C++ 代码 + 算法思路），消耗一次 AI 调用。生成内容会填充到编辑器，确认无误后再保存。',
      '确认 AI 生成标准答案',
      { type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  generating.value = true
  msg.value = ''
  try {
    const res = await wbStd({ title: problem.value.title, statement: buildStatement(problem.value) })
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '生成失败')); return }
    const json = JSON.parse(unwrapResultData(res) || '{}')
    code.value = json.code || ''
    explanation.value = json.explanation || ''
    if (!code.value) ElMessage.error('生成内容为空，请重试')
    else ElMessage.success('已生成，可修改后保存')
  } catch (e) {
    console.error(e)
    ElMessage.error('生成失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

// 测试并保存：现有测试数据全部通过才保存。
// 全通过才落库是硬约束——保存一个错的 std 比不保存危害大得多。
const testAndSave = async () => {
  if (!props.problemId || !code.value.trim()) return
  try {
    await ElMessageBox.confirm(
      '将先用该题现有测试数据逐组验证新 std，全部通过（AC）才会保存；' +
      '有失败组则不保存并列出原因。验证可能耗时 1~2 分钟。',
      '确认测试并保存',
      { type: 'warning', confirmButtonText: '测试并保存', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  testing.value = true
  msg.value = ''
  testResult.value = null
  try {
    const res = await testAndSaveStd({
      problemId: props.problemId,
      code: code.value,
      explanation: explanation.value
    })
    if (!isResultSuccess(res)) { ElMessage.error(getResultMessage(res, '测试并保存失败')); return }
    testResult.value = res.data
    if (res.data?.saved) {
      tested.value = true
      msg.value = res.data?.message || '标准答案已保存'
      msgOk.value = true
      emit('saved')
    } else {
      msg.value = '未保存'
      msgOk.value = false
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('测试并保存失败，请稍后重试')
  } finally {
    testing.value = false
  }
}

// 清空测试用例并保存：用于 std 变更后旧用例必然失效的场景（如改了输出格式）。
// 代价是该题立刻变为不可判题，确认文案必须把这一点说清楚。
const saveAndClearCases = async () => {
  if (!props.problemId || !code.value.trim()) return
  try {
    await ElMessageBox.confirm(
      '保存新标准答案的同时，该题全部测试数据将被清空（文件与记录一并删除）。' +
      '清空后该题暂无可判题数据，需重新生成测试数据。确定继续？',
      '清空测试用例并保存',
      { type: 'warning', confirmButtonText: '清空并保存', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  clearing.value = true
  msg.value = ''
  testResult.value = null
  try {
    const res = await saveStdAndClearCases({
      problemId: props.problemId,
      code: code.value,
      explanation: explanation.value
    })
    if (!isResultSuccess(res)) {
      msg.value = `保存失败：${getResultMessage(res, '未知错误')}`
      msgOk.value = false
      return
    }
    msg.value = res.message || '标准答案已保存，该题测试数据已全部清空'
    msgOk.value = true
    tested.value = false // 清空用例后未验证
    ElMessage.success(msg.value)
    emit('saved')
  } catch (e) {
    console.error(e)
    ElMessage.error('保存失败，请稍后重试')
  } finally {
    clearing.value = false
  }
}
</script>

<style scoped>
.panel-card { border-radius: 16px; border: 1px solid var(--border-light); background: var(--bg-surface); padding: 16px 18px; }
.std-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.std-title { font-size: 15px; font-weight: 700; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
.std-tag { margin-left: 2px; }
.std-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.sub-title { font-size: 13px; font-weight: 600; color: var(--text-primary); margin: 12px 0 8px; }
.fail-list { border: 1px solid var(--border-light); border-radius: 8px; padding: 8px 12px; max-height: 160px; overflow-y: auto; background: var(--bg-surface-soft); margin-top: 10px; }
.fail-item { font-size: 12px; color: var(--accent-danger); line-height: 1.9; word-break: break-all; }
.mb12 { margin-bottom: 12px; }
</style>
