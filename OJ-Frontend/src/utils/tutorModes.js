export const AUTO_MODE = 'AUTO'

export const TUTOR_MODES = [
  {
    value: 'READ_PROBLEM',
    label: '读懂题目',
    description: '解释题意与输入输出，带你手推样例。',
    question: '请帮我读懂题目，并带我推一遍样例。'
  },
  {
    value: 'GET_HINT',
    label: '给我提示',
    description: '给一个关键切入点，不直接展开完整解法。',
    question: '我还没有思路，请只给我一个关键提示，不要直接给完整解法。'
  },
  {
    value: 'CHECK_APPROACH',
    label: '检查思路',
    description: '检查正确性和复杂度，指出漏洞与修补方向。',
    question: '请结合我的代码检查思路是否成立，复杂度是否满足要求。'
  },
  {
    value: 'DEBUG_CODE',
    label: '排查代码',
    description: '定位错误原因，优先给出最小修改。',
    question: '请帮我定位代码错误，尽量保留原有思路和实现。'
  },
  {
    value: 'FULL_SOLUTION',
    label: '完整讲解',
    description: '讲解推导、正确性、复杂度和参考代码。',
    question: '请完整讲解本题，给出正确性说明、复杂度和完整参考代码。'
  }
]

export const getTutorMode = value => TUTOR_MODES.find(item => item.value === value)

export const appendTutorPrompt = (text, prompt) => {
  const current = typeof text === 'string' ? text.trimEnd() : ''
  const addition = typeof prompt === 'string' ? prompt.trim() : ''
  if (!addition || current.includes(addition)) return current
  if (!current) return addition
  return `${current}${/[。！？.!?；;：:，,、\s]$/.test(current) ? '' : ' '}${addition}`
}
export const isContestScene = contestId => contestId !== undefined && contestId !== null && contestId !== ''
export const canReviewSubmission = (detail, contestId) => Boolean(detail?.code) &&
  [1, 2, 3, 4, 5].includes(Number(detail?.status)) && !isContestScene(contestId) && !isContestScene(detail?.contestId)
