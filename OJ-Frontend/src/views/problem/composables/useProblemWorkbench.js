import { markRaw, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { runCodeApi } from '@/api/problem'
import { createLocalTestCases } from '@/utils/localTestCases'
import { normalizeProblemSamples } from '@/utils/problemSamples'
import { showApiError } from '@/utils/errorFeedback'

/**
 * 题目自测工作区：官方样例、本地样例、试跑与停止。
 *
 * 只关心「当前题目 + 当前编辑器快照」，不关心题目来自题库、比赛还是试卷。
 * 场景壳负责提供 getProblemId / getSnapshot，普通题目页和成套试卷共用这一套行为。
 */
export function useProblemWorkbench({ getProblemId, getSnapshot }) {
  const testCases = ref([])
  const caseModel = ref(markRaw(createLocalTestCases()))
  const officialSamples = ref([])
  const runningCases = ref(false)
  let runGeneration = 0
  let runController = null

  const stopCases = () => {
    runGeneration++
    runController?.abort()
    runningCases.value = false
  }

  const initializeCases = data => {
    stopCases()
    const official = normalizeProblemSamples(data)
    officialSamples.value = official
    caseModel.value = markRaw(createLocalTestCases(official, items => {
      testCases.value = items
    }))
    testCases.value = [...caseModel.value.items]
  }

  const runSelectedCases = async items => {
    if (runningCases.value) return
    const model = caseModel.value
    const problemId = getProblemId()
    const generation = ++runGeneration
    runController = new AbortController()
    const signal = runController.signal
    runningCases.value = true
    try {
      const snapshot = await getSnapshot()
      if (generation !== runGeneration) return
      if (!snapshot.code?.trim()) return ElMessage.info('请先进入编辑器编写代码')
      for (const item of items) {
        if (generation !== runGeneration) break
        // 代码/语言变化后，旧回执不匹配新版本，直接跳过。
        if (model.result(item.id, snapshot)) continue
        const result = await runCodeApi({ ...snapshot, input: item.input, problemId }, signal)
        if (generation !== runGeneration) break
        if (result.code !== 200) throw new Error(result.message || '试跑失败')
        if (model.items.some(existing => existing.id === item.id && existing.input === item.input)) {
          model.record(item.id, snapshot, result.data)
        }
      }
    } catch (error) {
      if (generation === runGeneration && !signal.aborted) showApiError(error, '样例运行失败')
    } finally {
      if (generation === runGeneration) runningCases.value = false
    }
  }

  const runCase = item => runSelectedCases([{ ...item }])
  const runSelectedCase = () => {
    const item = caseModel.value.items.find(candidate => candidate.id === caseModel.value.selectedId)
    if (!item) return ElMessage.info('请先选择或新增样例')
    return runCase(item)
  }
  const appendCounterexample = event => {
    try {
      const id = caseModel.value.addCounterexample(event)
      if (id) ElMessage.success('已验证反例已同步到本页样例区')
    } catch (error) {
      ElMessage.info(error.message)
    }
  }

  return {
    testCases,
    caseModel,
    officialSamples,
    runningCases,
    initializeCases,
    runSelectedCases,
    runCase,
    runSelectedCase,
    appendCounterexample,
    stopCases
  }
}
