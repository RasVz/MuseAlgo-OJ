import test from 'node:test'
import assert from 'node:assert/strict'
import { getPaperTotals, nextOptionKey, parseBatchQuestions, validatePaperQuestions } from '../../src/utils/examPaperEditor.js'

test('batch import parses compact judge rows without treating score as its answer', () => {
  const result = parseBatchQuestions('JUDGE|数组下标从 0 开始|对|5\nSINGLE|1+1=?|A.1;B.2|B|3')
  assert.deepEqual(result.errors, [])
  assert.deepEqual(result.questions.map(({ questionType, answer, score }) => ({ questionType, answer, score })), [
    { questionType: 'JUDGE', answer: 'T', score: 5 },
    { questionType: 'SINGLE', answer: 'B', score: 3 }
  ])
})

test('batch import is atomic and reports malformed rows with their line number', () => {
  const result = parseBatchQuestions('JUDGE|题干|错|5\nSINGLE|题干|A.选项;A.重复|A|5')
  assert.deepEqual(result.questions, [])
  assert.equal(result.errors.length, 1)
  assert.match(result.errors[0], /第 2 行/)
})

test('judge batch rows accept the documented blank-option form and reject empty scores or trailing separators', () => {
  assert.equal(parseBatchQuestions('JUDGE|题干||错|5').questions[0].answer, 'F')
  for (const row of ['JUDGE|题干|对|', 'JUDGE|题干|对|5|', 'JUDGE|题干|选项|对|5']) {
    assert.equal(parseBatchQuestions(row).questions.length, 0, row)
  }
})

test('next option key uses the first unused letter after deleting a middle option', () => {
  assert.equal(nextOptionKey([{ key: 'A' }, { key: 'C' }]), 'B')
  assert.equal(nextOptionKey([{ key: 'a' }, { key: 'B' }]), 'C')
})

test('paper totals include coding problem scores and objective question scores', () => {
  assert.deepEqual(getPaperTotals([{ score: 40 }, { score: 20 }], [{ score: 5 }, { score: 5 }]), {
    problemCount: 2, questionCount: 2, problemScore: 60, objectiveScore: 10
  })
})

test('question validation rejects empty statements, duplicate option keys and mismatched answers', () => {
  const errors = validatePaperQuestions([
    { questionType: 'SINGLE', statement: '', options: [{ key: 'A', text: 'one' }, { key: 'A', text: 'two' }], answer: 'A', score: 5 },
    { questionType: 'SINGLE', statement: 'valid stem', options: [{ key: 'A', text: 'one' }, { key: 'B', text: 'two' }], answer: 'C', score: 5 }
  ])
  assert.equal(errors.length, 3)
})

test('question validation sees empty option text instead of silently dropping it', () => {
  const errors = validatePaperQuestions([{ questionType: 'SINGLE', statement: '题干', options: [{ key: 'A', text: '选项' }, { key: 'B', text: '' }], answer: 'A', score: 5 }])
  assert.equal(errors.length, 1)
  assert.match(errors[0], /选项至少两项/)
})
