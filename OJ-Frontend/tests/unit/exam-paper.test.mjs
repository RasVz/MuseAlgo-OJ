import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildExamAnswerState,
  buildExamAnswersPayload,
  createExamDraftStorage,
  createInitialExamAnswers,
  examAttemptDeadline,
  getAnsweredQuestionIds,
  isExamAnswerUnsubmitted,
  remainingExamSeconds
} from '../../src/utils/examPaper.js'

const questions = [
  { id: 11, questionType: 'SINGLE' },
  { id: 12, questionType: 'MULTIPLE' },
  { id: 13, questionType: 'JUDGE' }
]

test('initializes answer values for each question type', () => {
  assert.deepEqual(createInitialExamAnswers(questions), { 11: '', 12: [], 13: '' })
})

test('restores the local draft over submitted server answers and grading results', () => {
  const state = buildExamAnswerState({
    questions,
    attempt: {
      answers: [{ questionId: 11, answer: 'A' }, { questionId: 12, answer: 'AC' }],
      results: [{ questionId: 13, myAnswer: 'T' }]
    },
    draft: { answers: [{ questionId: 11, answer: 'B' }, { questionId: 12, answer: 'BC' }, { questionId: 999, answer: 'x' }] }
  })
  assert.deepEqual(state, { 11: 'B', 12: ['B', 'C'], 13: 'T' })
})

test('builds grading payload and answered progress consistently', () => {
  const answers = { 11: 'A', 12: ['C', 'A'], 13: '' }
  assert.deepEqual(buildExamAnswersPayload(questions, answers), {
    answers: [
      { questionId: 11, answer: 'A' },
      { questionId: 12, answer: 'AC' },
      { questionId: 13, answer: '' }
    ]
  })
  assert.deepEqual(getAnsweredQuestionIds(questions, answers), [11, 12])
  assert.equal(isExamAnswerUnsubmitted(questions[1], ['A', 'C'], 'CA'), false)
  assert.equal(isExamAnswerUnsubmitted(questions[1], ['A', 'B'], 'AC'), true)
})

test('isolates saved drafts by user, paper and attempt and tolerates storage failures', () => {
  const values = new Map()
  const storage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  }
  const draft = createExamDraftStorage({ userId: 7, paperId: 20, attemptId: 30, storage })
  assert.equal(draft.key, 'exam-draft:7:20:30')
  assert.equal(draft.save({ 11: 'B', 12: ['A'] }), true)
  assert.deepEqual(draft.load().answers, [
    { questionId: '11', answer: 'B' },
    { questionId: '12', answer: ['A'] }
  ])
  assert.equal(draft.clear(), true)
  assert.equal(draft.load(), null)

  const unavailable = createExamDraftStorage({ userId: 7, paperId: 20, attemptId: 31, storage: {
    getItem() { throw new Error('blocked') },
    setItem() { throw new Error('blocked') },
    removeItem() { throw new Error('blocked') }
  } })
  assert.equal(unavailable.load(), null)
  assert.equal(unavailable.save({ 11: 'A' }), false)
  assert.equal(unavailable.clear(), false)
})

test('computes a deadline from wall time so timer display catches up after throttling', () => {
  const deadline = examAttemptDeadline(120, 10_000)
  assert.equal(deadline, 130_000)
  assert.equal(remainingExamSeconds(deadline, 10_500), 120)
  assert.equal(remainingExamSeconds(deadline, 129_100), 1)
  assert.equal(remainingExamSeconds(deadline, 130_100), 0)
})

test('does not claim a saved draft when local storage is absent', () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: undefined })
  try { assert.equal(createExamDraftStorage({ userId: 7, paperId: 20, attemptId: 30 }).save({ 11: 'A' }), false) }
  finally {
    if (previous) Object.defineProperty(globalThis, 'localStorage', previous)
    else delete globalThis.localStorage
  }
})
