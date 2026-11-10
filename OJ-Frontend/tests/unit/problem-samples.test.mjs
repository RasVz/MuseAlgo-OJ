import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeProblemSamples } from '../../src/utils/problemSamples.js'

test('structured samples keep order, whitespace and empty-output distinction', () => {
  const samples = normalizeProblemSamples({
    samples: [
      { input: '1 2\n', output: '3' },
      { input: '  4 5  ', output: '' }
    ]
  })
  assert.deepEqual(samples, [
    { input: '1 2\n', output: '3', hasOutput: true },
    { input: '  4 5  ', output: '', hasOutput: true }
  ])
})

test('legacy single sample is used when samples are absent', () => {
  assert.deepEqual(normalizeProblemSamples({ inputSample: '1', outputSample: '2' }), [
    { input: '1', output: '2', hasOutput: true }
  ])
  assert.deepEqual(normalizeProblemSamples({ inputSample: '1' }), [
    { input: '1', output: '', hasOutput: false }
  ])
})

test('missing or malformed samples produce an empty list', () => {
  assert.deepEqual(normalizeProblemSamples(null), [])
  assert.deepEqual(normalizeProblemSamples({ samples: [null, 'x'] }), [])
})
