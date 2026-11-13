import request from '@/utils/request'

// 获取题目列表
export function getProblemList(params) {
  return request({
    url: '/problem/list',
    method: 'get',
    params
  })
}

// 全文搜索（Meilisearch 召回，后端故障自动回退模糊查询）
export function searchProblems(params) {
  return request({
    url: '/problem/search',
    method: 'get',
    params
  })
}

export function getProblemFilterOptions() {
  return request({
    url: '/problem/filter-options',
    method: 'get'
  })
}

// 获取随机题目推荐（当前用户未做过）
export function getRandomProblemRecommendations(params) {
  return request({
    url: '/problem/recommendations/random',
    method: 'get',
    params
  })
}

// 获取题目详情
export function getProblemDetail(id) {
  return request({
    url: `/problem/${id}`,
    method: 'get'
  })
}

// 提交代码（userId 由调用方传入，API 层不再依赖 Store）
export function submitCode(data) {
  return request({
    url: '/submission/submit',
    method: 'post',
    data
  })
}

// === 新增管理接口 ===

// 创建题目
export function createProblem(data) {
  return request({
    url: '/problem/add',
    method: 'post',
    data
  })
}

// 更新题目
export function updateProblem(data) {
  return request({
    url: '/problem/update',
    method: 'post',
    data
  })
}

// 保存标准答案（AI 题库工具落库，仅教师/管理员）
export function saveProblemStd(problemId, code, explanation) {
  return request({
    url: '/problem/std/save',
    method: 'post',
    data: { problemId, code, explanation }
  })
}

// 读取标准答案（仅教师/管理员，学生接口不含此字段）
export function getProblemStd(problemId) {
  return request({
    url: `/problem/${problemId}/std`,
    method: 'get'
  })
}

// 清除标准答案（AI 题库工具，仅教师/管理员）
export function clearProblemStd(problemId) {
  return request({
    url: '/problem/std/clear',
    method: 'post',
    data: { problemId }
  })
}

// 标准答案「测试并保存」：现有测试数据全部通过才保存（仅教师/管理员）
export function testAndSaveStd(data) {
  return request({
    url: '/problem/std/test-and-save',
    method: 'post',
    data,
    timeout: 300000 // N 组逐组 Judge0 实测可能 1~2 分钟
  })
}

// 标准答案「清空测试用例并保存」：保存 std 同时清空该题全部测试用例
export function saveStdAndClearCases(data) {
  return request({
    url: '/problem/std/save-and-clear-cases',
    method: 'post',
    data
  })
}

// 删除单个测试用例（剩余用例自动重排并重算分值）
export function deleteProblemCase(caseId) {
  return request({
    url: `/problem/case/delete/${caseId}`,
    method: 'post'
  })
}

// 清空某题全部测试用例（文件与记录，计数归零）
export function clearProblemCases(problemId) {
  return request({
    url: '/problem/case/clear',
    method: 'post',
    data: { problemId }
  })
}

// 删除题目
export function deleteProblem(id) {
  return request({
    url: `/problem/delete/${id}`,
    method: 'post'
  })
}

// 题库管理全量列表（管理员）
export function getAdminProblemList(params) {
  return request({
    url: '/problem/admin/list',
    method: 'get',
    params
  })
}

// 获取某题目的所有测试用例
export function getProblemCases(problemId) {
  return request({
    url: `/problem/cases/${problemId}`,
    method: 'get'
  })
}

// 上传测试用例
export function uploadProblemCases(data) {
  return request({
    url: '/problem/upload-cases',
    method: 'post',
    data: data,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

// 导入 Free Problem Set (FPS) XML 题库文件
export function importFpsProblemSet(data) {
  return request({
    url: '/problem/import/fps',
    method: 'post',
    data,
    timeout: 1800000,
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

// ---- 题面版本：记录、审阅、启用 ----

export function listStatementVersions(problemId) {
  return request({
    url: `/problem/${problemId}/statement/versions`,
    method: 'get'
  })
}

export function getStatementVersion(problemId, versionId) {
  return request({
    url: `/problem/${problemId}/statement/versions/${versionId}`,
    method: 'get'
  })
}

// 候选版本的审阅数据：原文取基线版本，不触发 AI 调用
export function getStatementVersionProposal(problemId, versionId) {
  return request({
    url: `/problem/${problemId}/statement/versions/${versionId}/proposal`,
    method: 'get'
  })
}

// 基于指定版本发起优化（异步单题任务）
export function optimizeStatementVersion(problemId, baseVersionId, ipMode = false) {
  return request({
    url: `/problem/${problemId}/statement/versions/optimize`,
    method: 'post',
    data: { baseVersionId, ipMode }
  })
}

export function recordCurrentStatement(problemId) {
  return request({
    url: `/problem/${problemId}/statement/versions/current`,
    method: 'post'
  })
}

export function activateStatementVersion(problemId, versionId) {
  return request({
    url: `/problem/${problemId}/statement/versions/${versionId}/activate`,
    method: 'post'
  })
}

export function applyStatementVersion(problemId, versionId, { fields, formatted }) {
  return request({
    url: `/problem/${problemId}/statement/versions/${versionId}/apply`,
    method: 'post',
    data: { fields, formatted }
  })
}

export function discardStatementVersion(problemId, versionId) {
  return request({
    url: `/problem/${problemId}/statement/versions/${versionId}`,
    method: 'delete'
  })
}

// 创建批量题面优化任务（异步）：HTTP 只受理，进度与逐项结果靠轮询
export function createFormatJob(problemIds, { ipMode = false, autoApply = true } = {}) {
  return request({
    url: '/problem/format/jobs',
    method: 'post',
    data: { problemIds, ipMode, autoApply }
  })
}

export function getFormatJob(jobId, config = {}) {
  return request({
    url: `/problem/format/jobs/${jobId}`,
    method: 'get',
    ...config
  })
}

// 当前登录人正在跑的任务：面板关闭后重新进入靠它接回进度
export function getActiveFormatJob(config = {}) {
  return request({
    url: '/problem/format/jobs/active',
    method: 'get',
    ...config
  })
}

// 读取任务内单题提案：不重新调用 AI，直接返回任务生成时保存的快照
export function getFormatJobItem(jobId, problemId) {
  return request({
    url: `/problem/format/jobs/${jobId}/items/${problemId}`,
    method: 'get'
  })
}

export function cancelFormatJob(jobId) {
  return request({
    url: `/problem/format/jobs/${jobId}/cancel`,
    method: 'post'
  })
}

export function retryFormatJob(jobId) {
  return request({
    url: `/problem/format/jobs/${jobId}/retry`,
    method: 'post'
  })
}

// 单题优化预览（AI 整理但未保存；ipMode=true 时同一次调用完成 IP 改名）
// 服务端单次调用最长 150s，这里留出余量但不设 30 分钟那种无意义的等待
export function previewFormatProblem(problemId, ipMode = false) {
  return request({
    url: '/problem/format/preview',
    method: 'post',
    data: { problemIds: [problemId], ipMode },
    timeout: 300000
  })
}

// 应用整理结果：支持字段白名单与原文哈希校验
export function applyFormatProblem(problemId, { baseHash, fields, formatted }) {
  return request({
    url: '/problem/format/apply',
    method: 'post',
    data: { problemId, baseHash, fields, formatted }
  })
}

// 获取代码草稿
export function getDraftCode(problemId, language) {
  return request({
    url: '/problem/draft/get',
    method: 'get',
    params: { problemId, language }
  })
}

// 保存代码草稿
export function saveDraftCode(data) {
  return request({
    url: '/problem/draft/save',
    method: 'post',
    data: data
  })
}

// 在线运行代码 (不保存，只测试)
export function runCodeApi(data, signal) {
  return request({
    signal,
    url: '/problem/run', // 这里对应你后端的 @PostMapping("/run") 接口
    method: 'post',
    data: data,
    timeout: 60000
  })
}

// 获取我创建的题目列表（管理端/格式化工具共用）
// 对应后端: /problem/my-created/list
export function getMyCreatedProblemList(params) {
  return request({
    url: '/problem/my-created/list',
    method: 'get',
    params
  })
}

/** 当前用户本题最近保存的草稿，连同实际语言返回。 */
export function getLatestDraftCode(problemId) {
  return request({ url: '/problem/draft/latest', method: 'get', params: { problemId } })
}
