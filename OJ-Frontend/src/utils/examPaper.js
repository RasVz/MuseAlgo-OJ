const normalizeQuestionAnswer = (question, value) => {
  if (question?.questionType === 'MULTIPLE') {
    if (Array.isArray(value)) return value.map((item) => String(item).trim()).filter(Boolean)
    if (value == null || value === '') return []
    const text = String(value).trim()
    return text.includes(',') || text.includes('，')
      ? text.split(/[,，]/).map((item) => item.trim()).filter(Boolean)
      : [...text]
  }
  return value == null ? '' : String(value)
}

export const serializeQuestionAnswer = (question, value) => {
  const normalized = normalizeQuestionAnswer(question, value)
  return Array.isArray(normalized) ? [...normalized].sort().join('') : normalized
}

export const createInitialExamAnswers = (questions = []) => Object.fromEntries(
  questions.map((question) => [question.id, question.questionType === 'MULTIPLE' ? [] : ''])
)

export const buildExamAnswerState = ({ questions = [], attempt = null, draft = null } = {}) => {
  const state = createInitialExamAnswers(questions)
  const questionById = new Map(questions.map((question) => [String(question.id), question]))

  // Judged answers also carry myAnswer, so older servers can still resume before
  // the dedicated answers field is available.
  for (const result of attempt?.results ?? []) {
    const question = questionById.get(String(result.questionId))
    if (question && result.myAnswer != null) state[question.id] = normalizeQuestionAnswer(question, result.myAnswer)
  }
  for (const answer of attempt?.answers ?? []) {
    const question = questionById.get(String(answer.questionId))
    if (question && answer.answer != null) state[question.id] = normalizeQuestionAnswer(question, answer.answer)
  }
  // The current device's draft is the newest unsent work and takes precedence.
  for (const answer of draft?.answers ?? []) {
    const question = questionById.get(String(answer.questionId))
    if (question) state[question.id] = normalizeQuestionAnswer(question, answer.answer)
  }
  return state
}

export const buildExamAnswersPayload = (questions = [], answers = {}) => ({
  answers: questions.map((question) => ({
    questionId: question.id,
    answer: serializeQuestionAnswer(question, answers[question.id])
  }))
})

export const getAnsweredQuestionIds = (questions = [], answers = {}) => questions
  .filter((question) => {
    const value = answers[question.id]
    return Array.isArray(value) ? value.length > 0 : String(value ?? '').trim().length > 0
  })
  .map((question) => question.id)

export const isExamAnswerUnsubmitted = (question, currentAnswer, savedAnswer) =>
  serializeQuestionAnswer(question, currentAnswer) !== serializeQuestionAnswer(question, savedAnswer)

export const createExamDraftStorage = ({ userId, paperId, attemptId, storage } = {}) => {
  const key = `exam-draft:${userId}:${paperId}:${attemptId}`
  const getStorage = () => {
    try {
      return storage ?? globalThis.localStorage
    } catch {
      return null
    }
  }

  return {
    key,
    load() {
      try {
        const raw = getStorage()?.getItem(key)
        if (!raw) return null
        const value = JSON.parse(raw)
        return value && Array.isArray(value.answers) ? value : null
      } catch {
        return null
      }
    },
    save(answers) {
      try {
        const normalized = Object.entries(answers ?? {}).map(([questionId, answer]) => ({ questionId, answer }))
        getStorage()?.setItem(key, JSON.stringify({ answers: normalized, savedAt: Date.now() }))
        return true
      } catch {
        return false
      }
    },
    clear() {
      try {
        getStorage()?.removeItem(key)
        return true
      } catch {
        return false
      }
    }
  }
}

export const examAttemptDeadline = (remainingSeconds, now = Date.now()) =>
  now + Math.max(0, Number(remainingSeconds) || 0) * 1000

export const remainingExamSeconds = (deadline, now = Date.now()) =>
  Math.max(0, Math.ceil((Number(deadline) - now) / 1000))
