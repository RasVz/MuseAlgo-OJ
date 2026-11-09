import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createLocalTestCases } from '../../src/utils/localTestCases.js'
const fixture = () => createLocalTestCases([{ id: 1, input: '1 2', output: '3' }])
test('official samples stay read-only while temporary samples append and can be removed', () => {
  const cases=fixture(); const id=cases.add({ input:'', expectedOutput:'', hasExpected:true })
  assert.equal(cases.items.length,2);assert.equal(cases.items[0].source,'OFFICIAL')
  assert.throws(()=>cases.edit(cases.items[0].id,{ input:'overwrite' }))
  assert.equal(cases.items[1].hasExpected,true);assert.equal(cases.items[1].expectedOutput,'')
  cases.remove(id);assert.equal(cases.items.length,1)
})
test('same-version runs reuse results; code, language, input and expected changes invalidate display', () => {
  const cases=fixture();const id=cases.items[0].id
  cases.record(id,{code:'code',language:'cpp'},{ runId:'r1',status:3,raw_stdout:'3' })
  assert.equal(cases.result(id,{code:'code',language:'cpp'}).runId,'r1')
  assert.equal(cases.result(id,{code:'changed',language:'cpp'}),null)
  assert.equal(cases.result(id,{code:'code',language:'java'}),null)
})
test('verified counterexamples auto-append once, unverified candidates never do', () => {
  const cases=fixture();assert.equal(cases.addCounterexample({confirmedFailure:false,input:'0'}),null)
  cases.addCounterexample({confirmedFailure:true,input:'0',expectedOutput:'1',expectationSource:'REFERENCE_PROGRAM',runId:'x'})
  cases.addCounterexample({confirmedFailure:true,input:'0',expectedOutput:'1',expectationSource:'REFERENCE_PROGRAM',runId:'x'})
  assert.equal(cases.items.length,2);assert.equal(cases.items[1].source,'AI_VERIFIED')
})
test('fresh page has no previously added temporary samples', () => {
  const first=fixture();first.add({input:'local'});assert.equal(fixture().items.length,1)
})
