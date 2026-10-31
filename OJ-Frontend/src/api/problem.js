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

// 批量格式化题目（AI 整理并直接保存，不预览；ipMode=true 时同一次调用完成 IP 改名）
export function formatProblems(problemIds, ipMode = false) {
  return request({
    url: '/problem/format',
    method: 'post',
    data: { problemIds, ipMode },
    timeout: 1800000
  })
}

// 单题格式化预览（AI 整理但未保存；ipMode=true 时同一次调用完成 IP 改名）
export function previewFormatProblem(problemId, ipMode = false) {
  return request({
    url: '/problem/format/preview',
    method: 'post',
    data: { problemIds: [problemId], ipMode },
    timeout: 1800000
  })
}

// 应用单题格式化预览结果
export function applyFormatProblem(problemId, formatted) {
  return request({
    url: '/problem/format/apply',
    method: 'post',
    data: { problemId, formatted }
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
export function runCodeApi(data) {
  return request({
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
