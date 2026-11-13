import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createLocalTestCases } from '../../src/utils/localTestCases.js'
const fixture = () => createLocalTestCases([{ id: 1, input: '1 2', output: '3' }])

test('official sample input is immutable, has no expected value and stays undeletable', () => {
  const cases = fixture()
  const id = cases.items[0].id
  assert.equal(cases.items[0].input, '1 2')
  assert.equal('expectedOutput' in cases.items[0], false)
  assert.throws(() => cases.patch(id, { input: '9 9' }), /官方样例的输入不可修改/)
  assert.equal(cases.items[0].input, '1 2')
  assert.equal(cases.context().expectedOutput, null)
  assert.equal(cases.context().hasExpected, false)
  assert.throws(() => cases.remove(id), /官方样例不可删除/)
})

test('new samples start empty and custom samples can be edited and removed', () => {
  const cases = fixture()
  const id = cases.add()
  assert.equal(cases.items.length, 2)
  assert.equal(cases.selectedId, id)
  assert.equal(cases.items[1].input, '')
  cases.patch(id, { input: '2 3' })
  assert.equal(cases.items[1].input, '2 3')
  cases.remove(id)
  assert.equal(cases.items.length, 1)
})

test('same-version runs expose actual output and sample/code changes invalidate it', () => {
  const cases = fixture()
  const id = cases.add({ input: '1 2' })
  cases.record(id, { code: 'code', language: 'cpp' }, { runId: 'r1', status: 3, raw_stdout: '3' })
  assert.equal(cases.result(id, { code: 'code', language: 'cpp' }).actual, '3')
  assert.equal(cases.result(id, { code: 'changed', language: 'cpp' }), null)
  cases.record(id, { code: 'code', language: 'cpp' }, { runId: 'r1', status: 3, raw_stdout: '3' })
  assert.equal(cases.result(id, { code: 'code', language: 'java' }), null)
  cases.record(id, { code: 'code', language: 'cpp' }, { runId: 'r1', status: 3, raw_stdout: '3' })
  cases.patch(id, { input: '1 3' })
  assert.equal(cases.result(id, { code: 'code', language: 'cpp' }), null)
})

test('editing an AI-verified sample downgrades it to custom', () => {
  const cases = fixture()
  cases.addCounterexample({
    confirmedFailure: true,
    input: '0',
    expectedOutput: '1',
    expectationSource: 'REFERENCE_PROGRAM',
    runId: 'x'
  })
  const aiCase = cases.items.find(item => item.source === 'AI_VERIFIED')
  assert.ok(aiCase)
  cases.patch(aiCase.id, { input: '0 0' })
  assert.equal(aiCase.source, 'CUSTOM')
  assert.match(aiCase.note, /已手动编辑/)
})

test('verified counterexamples auto-append once and unverified candidates never do', () => {
  const cases = fixture()
  assert.equal(cases.addCounterexample({ confirmedFailure: false, input: '0' }), null)
  cases.addCounterexample({
    confirmedFailure: true,
    input: '0',
    expectedOutput: '1',
    expectationSource: 'REFERENCE_PROGRAM',
    runId: 'x'
  })
  cases.addCounterexample({
    confirmedFailure: true,
    input: '0',
    expectedOutput: '1',
    expectationSource: 'REFERENCE_PROGRAM',
    runId: 'x'
  })
  assert.equal(cases.items.length, 2)
  assert.equal(cases.items[1].source, 'AI_VERIFIED')
})

test('fresh page has no previously added temporary samples', () => {
  const first = fixture()
  first.add({ input: 'local' })
  assert.equal(fixture().items.length, 1)
})
