import request from '@/utils/request'

// AI 生成类接口耗时 30s~5min（含失败重试），必须覆盖全局 10s 默认超时
const AI_TIMEOUT = 300000

export function draftProblem(data) {
  return request({ url: '/ai/teacher/draft-problem', method: 'post', data, timeout: AI_TIMEOUT })
}

export function genTestcase(data) {
  return request({ url: '/ai/teacher/testcase', method: 'post', data, timeout: AI_TIMEOUT })
}

export function classInsight(data) {
  return request({ url: '/ai/teacher/class-insight', method: 'post', data, timeout: AI_TIMEOUT })
}

// ======================= 命题工作台（B） =======================

/** 完整题面生成：{knowledgePoint, difficulty, scenario, scale, extra, ipMode} */
export function wbStatement(data) {
  return request({ url: '/ai/teacher/workbench/statement', method: 'post', data, timeout: AI_TIMEOUT })
}

/** 标准答案 std 生成：{title, statement} */
export function wbStd(data) {
  return request({ url: '/ai/teacher/workbench/std', method: 'post', data, timeout: AI_TIMEOUT })
}

/** 测试数据生成：{title, statement, inputDesc, outputDesc, constraints, caseCount} */
export function wbTestdata(data) {
  return request({ url: '/ai/teacher/workbench/testdata', method: 'post', data, timeout: AI_TIMEOUT })
}

/** 测试数据生成器代码：{title, statement, inputDesc, outputDesc, constraints, caseCount} → {code, plan} */
export function wbGenerator(data) {
  return request({ url: '/ai/teacher/workbench/generator', method: 'post', data, timeout: AI_TIMEOUT })
}

/** 执行生成器并逐组用 std 实测（不落库）：{problemId, generatorCode} → {total, passed, failed, cases, failedReasons} */
export function generateAndVerifyCases(data) {
  return request({
    url: '/problem/case/generate-and-verify',
    method: 'post',
    data,
    timeout: 300000 // 30 组逐组 Judge0 实测可能 1~2 分钟
  })
}

/** 追加保存测试用例（按输入去重，不覆盖旧用例）：{problemId, cases} → {appended, skipped, totalCount} */
export function appendProblemCases(problemId, cases) {
  return request({
    url: '/problem/case/append',
    method: 'post',
    data: { problemId, cases: cases.map(({ input, output }) => ({ input, output })) }
  })
}

// ======================= 题面生成草稿（异步任务） =======================
// 生成本身在服务端跑，这些接口都只做受理与查询，因此用默认 10s 超时即可：
// 把一次 20~60s 的模型调用挂在 HTTP 上，正是旧实现超时丢结果的根因。

const DRAFT_BASE = '/ai/teacher/drafts'

/** 受理生成：{brief, variants} → DraftVO[] */
export function createDraft(data) {
  return request({ url: DRAFT_BASE, method: 'post', data })
}

/** 历史分页 / 方案组：{page, size, status, groupId} → {total, page, size, items} */
export function listDrafts(params) {
  return request({ url: DRAFT_BASE, method: 'get', params })
}

/** 详情：{draft, brief, content, issues, prompt, usage, errorCode} */
export function getDraft(id) {
  return request({ url: `${DRAFT_BASE}/${id}`, method: 'get' })
}

/** 取消：仅排队中可取消，已发出模型调用会返回 409 */
export function cancelDraft(id) {
  return request({ url: `${DRAFT_BASE}/${id}/cancel`, method: 'post' })
}

/** 重试：仅失败草稿，复用原简报重新入队 */
export function retryDraft(id) {
  return request({ url: `${DRAFT_BASE}/${id}/retry`, method: 'post' })
}

/** 删除：生成中也允许，模型调用不会中止，只丢弃结果 */
export function deleteDraft(id) {
  return request({ url: `${DRAFT_BASE}/${id}`, method: 'delete' })
}

/** 保存人工修改：返回归一化后的完整详情 */
export function updateDraftContent(id, data) {
  return request({ url: `${DRAFT_BASE}/${id}/content`, method: 'post', data })
}

/** 定向重写：{targetField, instruction} → 子草稿详情 */
export function rewriteDraft(id, data) {
  return request({ url: `${DRAFT_BASE}/${id}/rewrite`, method: 'post', data })
}

/** 落库为私有题目：{fields, timeLimit, memoryLimit, allowIssues, force} */
export function saveDraft(id, data) {
  return request({ url: `${DRAFT_BASE}/${id}/save`, method: 'post', data })
}

/** 提示词预览：{brief} → {system, user}，不调用模型 */
export function previewDraftPrompt(data) {
  return request({ url: `${DRAFT_BASE}/prompt-preview`, method: 'post', data })
}

/** 重复题提示：标题相近的历史题目 */
export function similarDrafts(id) {
  return request({ url: `${DRAFT_BASE}/${id}/similar`, method: 'get' })
}

// ======================= 题目落库 =======================

/** 批量保存测试用例（覆盖旧用例） */
export function saveProblemCases(problemId, cases) {
  return request({
    url: '/problem/case/batch',
    method: 'post',
    data: { problemId, cases: cases.map(({ input, output }) => ({ input, output })) }
  })
}
