import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addProblemContest,
  batchAddProblemContest,
  removeProblemContest,
  toggleProblemContestVisible,
  getContestProblemListAdmin,
  updateProblemContest
} from '@/api/problemContest'
import { getResultMessage, isResultSuccess, unwrapResultData } from '@/utils/response'

/**
 * 比赛编辑器——题目管理逻辑（从 ContestEditorView 抽离）。
 *
 * 职责：题目列表加载/规范化、添加（单个/批量带回退）、配置保存、
 * 可见性切换、移除、拖拽重排（乐观更新 + 失败回滚）。
 *
 * @param {import('vue').Ref<number>} contestId 当前比赛 ID（响应式）
 */
export function useContestProblems(contestId) {
  const problemList = ref([])
  const loadingProblems = ref(false)
  const addingProblem = ref(false)
  const updatingProblemId = ref(null)
  const togglingProblemId = ref(null)
  const removingProblemRelationId = ref(null)

  // 后端在不同接口下会返回裸数组或包装对象（records/list/rows），
  // 这里统一兜底成数组，避免调用方每处都写一遍兼容判断。
  function extractListData(data) {
    if (Array.isArray(data)) return data
    if (data && Array.isArray(data.records)) return data.records
    if (data && Array.isArray(data.list)) return data.list
    if (data && Array.isArray(data.rows)) return data.rows
    return []
  }

  // 把后端字段名不统一的关联行压平成前端统一结构；order/score/visible 都带默认值，
  // 因为老比赛可能没有这些列，直接透传会让表格渲染出 undefined。
  function normalizeProblemRow(raw, index) {
    const relationIdRaw = raw?.id ?? raw?.problemContestId ?? raw?.relationId ?? raw?.pcId
    const problemIdRaw = raw?.problemId ?? raw?.problem_id ?? raw?.pid ?? raw?.problem?.id
    const orderRaw = raw?.problemOrder ?? raw?.problem_order ?? index + 1
    const scoreRaw = raw?.score ?? 100
    const visibleRaw = raw?.visible ?? 1

    const relationId = Number(relationIdRaw)
    const problemId = Number(problemIdRaw)
    const problemOrder = Number(orderRaw)
    const score = Number(scoreRaw)
    const visible = Number(visibleRaw)

    return {
      relationId: Number.isFinite(relationId) ? relationId : null,
      problemId: Number.isFinite(problemId) ? problemId : null,
      title: raw?.problemTitle ?? raw?.title ?? raw?.problemName ?? '',
      difficulty: raw?.difficulty ?? raw?.level ?? '-',
      problemOrder: Number.isFinite(problemOrder) && problemOrder > 0 ? problemOrder : index + 1,
      score: Number.isFinite(score) && score >= 0 ? score : 100,
      visible: visible === 0 ? 0 : 1
    }
  }

  function parseProblemIds(idsArray) {
    if (!idsArray || !idsArray.length) return []
    return idsArray.filter(v => Number.isInteger(v) && v > 0)
  }

  // 新题按当前最大 order 追加到末尾，而不是用列表长度+1：
// 拖拽重排后 order 可能不连续，用 max+1 才能保证不与已有题目撞号。
  function getNextProblemOrder() {
    if (!problemList.value.length) return 1
    const maxOrder = Math.max(...problemList.value.map(p => Number(p.problemOrder) || 0))
    return maxOrder + 1
  }

  function clearList() {
    problemList.value = []
  }

  async function loadProblemList() {
    if (!contestId.value) return
    loadingProblems.value = true
    try {
      const res = await getContestProblemListAdmin(contestId.value)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '获取题目列表失败'))
        return
      }
      const data = unwrapResultData(res)
      const list = extractListData(data).map((item, idx) => normalizeProblemRow(item, idx))
      list.sort((a, b) => (a.problemOrder || 0) - (b.problemOrder || 0))
      problemList.value = list.map((item, idx) => ({
        ...item,
        problemOrder: idx + 1
      }))
    } catch (e) {
      ElMessage.error(e?.message || '获取题目列表失败')
    } finally {
      loadingProblems.value = false
    }
  }

  async function handleAddProblems(ids) {
    if (!contestId.value) return
    const validIds = parseProblemIds(ids)
    if (!validIds.length) {
      ElMessage.warning('请输入合法的题目ID')
      return
    }

    addingProblem.value = true
    try {
      await addProblemsByIds(validIds)
    } catch (e) {
      ElMessage.error(e?.message || '添加题目失败')
    } finally {
      addingProblem.value = false
    }
  }

  // 批量添加先走批量接口；若后端未部署该接口则退化为逐条添加，
    // 逐条过程中不中断，最后按成功数汇总提示，避免"部分失败"被当成全失败。
  async function addProblemsByIds(ids) {
    if (ids.length === 1) {
      const payload = {
        contestId: contestId.value,
        problemId: ids[0],
        problemOrder: getNextProblemOrder(),
        score: 100,
        visible: 1
      }
      const res = await addProblemContest(payload)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '添加题目失败'))
        return
      }
      ElMessage.success('添加题目成功')
    } else {
      try {
        const batchRes = await batchAddProblemContest(contestId.value, ids)
        if (!isResultSuccess(batchRes)) {
          throw new Error(getResultMessage(batchRes, '批量添加失败'))
        }
        ElMessage.success(`批量添加成功，共 ${ids.length} 题`)
      } catch (e) {
        let successCount = 0
        let order = getNextProblemOrder()

        for (const pid of ids) {
          try {
            const itemRes = await addProblemContest({
              contestId: contestId.value,
              problemId: pid,
              problemOrder: order++,
              score: 100,
              visible: 1
            })
            if (isResultSuccess(itemRes)) successCount++
          } catch (_) {
            // 单个失败不中断，最后统一汇总
          }
        }

        if (successCount === 0) {
          throw e
        } else {
          ElMessage.warning(`批量接口不可用，已逐个添加成功 ${successCount}/${ids.length}`)
        }
      }
    }

    await loadProblemList()
  }

  // silent=true 用于拖拽重排的批量提交：此时不希望每题弹一次 toast，
  // 也不希望每题都重新拉列表（会打断拖拽），失败由调用方统一处理。
  async function handleSaveProblemConfig(row, silent = false) {
    if (!contestId.value) return false
    if (!row?.relationId) {
      if (!silent) ElMessage.warning('缺少关联ID，无法更新配置')
      return false
    }
    if (!Number.isInteger(Number(row.problemOrder)) || Number(row.problemOrder) <= 0) {
      if (!silent) ElMessage.warning('题目顺序必须为正整数')
      return false
    }
    if (!Number.isInteger(Number(row.score)) || Number(row.score) < 0) {
      if (!silent) ElMessage.warning('分值必须为非负整数')
      return false
    }

    updatingProblemId.value = silent ? updatingProblemId.value : row.relationId
    try {
      const payload = {
        id: row.relationId,
        contestId: contestId.value,
        problemId: row.problemId,
        problemOrder: Number(row.problemOrder),
        score: Number(row.score),
        visible: Number(row.visible) === 0 ? 0 : 1
      }
      const res = await updateProblemContest(payload)
      if (!isResultSuccess(res)) {
        if (!silent) ElMessage.error(getResultMessage(res, '保存题目配置失败'))
        return false
      }
      if (!silent) {
        ElMessage.success('题目配置已保存')
        await loadProblemList()
      }
      return true
    } catch (e) {
      if (!silent) ElMessage.error(e?.message || '保存题目配置失败')
      return false
    } finally {
      if (!silent) {
        updatingProblemId.value = null
      }
    }
  }

  async function handleToggleVisible(row) {
    if (!row?.relationId) {
      ElMessage.warning('缺少关联ID，无法切换可见性')
      return
    }

    const targetVisible = Number(row.visible) === 1 ? 0 : 1
    togglingProblemId.value = row.relationId
    try {
      const res = await toggleProblemContestVisible(row.relationId, targetVisible)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '切换可见性失败'))
        return
      }
      row.visible = targetVisible
      ElMessage.success(targetVisible === 1 ? '已设为可见' : '已设为隐藏')
    } catch (e) {
      ElMessage.error(e?.message || '切换可见性失败')
    } finally {
      togglingProblemId.value = null
    }
  }

  // 移除是不可撤销的操作（关联行被删除，提交记录中的题目关系一并失效），
    // 因此先弹二次确认；用户取消时 catch 到的是 'cancel'/'close' 字符串，不能当错误弹。
  async function handleRemoveProblem(row) {
    if (!contestId.value) return
    if (!row?.relationId) {
      ElMessage.warning('缺少关联ID，无法移除')
      return
    }

    try {
      await ElMessageBox.confirm(
        `确认从比赛中移除题目 ${row.problemId ?? ''} 吗？`,
        '提示',
        {
          type: 'warning',
          confirmButtonText: '移除',
          cancelButtonText: '取消'
        }
      )

      removingProblemRelationId.value = row.relationId
      const res = await removeProblemContest(row.relationId)
      if (!isResultSuccess(res)) {
        ElMessage.error(getResultMessage(res, '移除题目失败'))
        return
      }
      ElMessage.success('移除题目成功')
      await loadProblemList()
    } catch (e) {
      if (e !== 'cancel' && e !== 'close') {
        ElMessage.error(e?.message || '移除题目失败')
      }
    } finally {
      removingProblemRelationId.value = null
    }
  }

  // 拖拽排序：先本地重排让 UI 立即响应，再串行持久化；
  // 任一题保存失败就用 backup 整体回滚，因为顺序是相对关系，部分成功会留下错乱的结果。
  async function handleReorder({ oldIndex, newIndex }) {
    if (oldIndex === newIndex) return

    const backup = problemList.value.map(item => ({ ...item }))
    // 本地重排
    const cloned = [...problemList.value]
    const moved = cloned.splice(oldIndex, 1)[0]
    cloned.splice(newIndex, 0, moved)
    problemList.value = cloned.map((item, idx) => ({ ...item, problemOrder: idx + 1 }))

    loadingProblems.value = true
    try {
      await saveProblemOrderBatch()
      ElMessage.success('题目顺序已更新')
      await loadProblemList()
    } catch (e) {
      problemList.value = backup
      ElMessage.error(e?.message || '保存题目顺序失败')
    } finally {
      loadingProblems.value = false
    }
  }

  // 后端没有"整体提交顺序数组"的接口，只能按当前列表顺序逐条 update；
  // 串行而非并行的原因是 order 之间存在隐含的相对次序，并发写可能乱序落库。
  async function saveProblemOrderBatch() {
    for (const item of problemList.value) {
      const ok = await handleSaveProblemConfig(item, true)
      if (!ok) throw new Error(`题目 ${item.problemId} 顺序保存失败`)
    }
  }

  return {
    // state
    problemList, loadingProblems, addingProblem,
    updatingProblemId, togglingProblemId, removingProblemRelationId,
    // actions
    clearList, loadProblemList, handleAddProblems, handleSaveProblemConfig,
    handleToggleVisible, handleRemoveProblem, handleReorder
  }
}
