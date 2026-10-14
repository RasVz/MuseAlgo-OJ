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

/** 班级 × 题目的 Hint Ladder 解锁情况：{classId, problemId} → [{userId, username, level}] */
export function getHintLevels(classId, problemId) {
  return request({
    url: '/ai/teacher/hint/levels',
    method: 'get',
    params: { classId, problemId }
  })
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
