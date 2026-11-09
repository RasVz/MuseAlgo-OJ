export const HELP_DIRECTIONS = [
  { value: 'AUTO', label: '自由提问', description: '直接描述你的问题，助教会结合当前代码回答。', question: '' },
  { value: 'READ_PROBLEM', label: '读懂题目', description: '解释题意、输入输出和数据范围，手推公开样例。', question: '请帮我读懂题目，并带我推一遍样例。' },
  { value: 'GET_STARTED', label: '找切入点', description: '从题目特征建立观察，找到可以继续推进的方向。', question: '我还没有思路，请帮我找一个切入点。' },
  { value: 'CHECK_APPROACH', label: '检查我的思路', description: '检查正确性与复杂度，有漏洞时提供反例和修补方向。', question: '请结合我的代码检查思路是否成立，复杂度是否满足要求。' },
  { value: 'DEBUG_CODE', label: '定位代码错误', description: '定位具体错误，解释原因，优先给最小修改和验证例。', question: '请帮我定位代码错误，尽量保留原有思路和实现。' },
  { value: 'EXPLAIN_CODE', label: '解释代码与知识点', description: '解释变量、执行过程、语法或算法概念。', question: '请结合当前代码解释关键逻辑和涉及的知识点。' },
  { value: 'TEST_CASES', label: '构造样例与反例', description: '自拟输入、预期输出与推导，检验边界或思路漏洞。', question: '请构造边界样例或反例，给出预期输出并解释原因。' },
  { value: 'OPTIMIZE', label: '优化我的代码', description: '分析瓶颈与复杂度，说明优化依据和必要改动。', question: '请分析当前代码的复杂度和瓶颈，并给出有依据的优化。' },
  { value: 'FULL_SOLUTION', label: '完整题解与代码', description: '完整推导、正确性说明、复杂度与参考实现，直接可用。', question: '请完整讲解本题，给出正确性说明、复杂度和完整参考代码。' }
]
export const defaultPreferences = () => ({ helpDirection: 'AUTO', answerStyle: 'DIRECT', detailLevel: 'CONCISE' })
export const isContestScene = contestId => contestId !== undefined && contestId !== null && contestId !== ''
export const canReviewSubmission = (detail, contestId) => Boolean(detail?.code) &&
  [1, 2, 3, 4, 5].includes(Number(detail?.status)) && !isContestScene(contestId) && !isContestScene(detail?.contestId)
