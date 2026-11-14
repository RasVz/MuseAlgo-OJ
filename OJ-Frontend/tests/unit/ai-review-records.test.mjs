import { test } from 'node:test'
import assert from 'node:assert/strict'
import { aiReviewListLocation, aiReviewRelation, aiReviewRequestKey, aiReviewStatus, isAiReviewActive } from '../../src/utils/aiReview.js'

test('review status metadata distinguishes active and terminal records', () => {
  assert.equal(isAiReviewActive('QUEUED'), true)
  assert.equal(isAiReviewActive('running'), true)
  assert.equal(isAiReviewActive('CANCELLING'), true)
  assert.equal(isAiReviewActive('SUCCEEDED'), false)
  assert.deepEqual(aiReviewStatus('FAILED'), { label: '生成失败', type: 'danger' })
  assert.deepEqual(aiReviewStatus('CANCELLED'), { label: '已停止', type: 'info' })
})

test('review relation labels preserve followup and retry history semantics', () => {
  assert.equal(aiReviewRelation('INITIAL'), '首次复盘')
  assert.equal(aiReviewRelation('FOLLOW_UP'), '追问')
  assert.equal(aiReviewRelation('RETRY'), '失败重试')
})

test('each explicit creation gets a unique idempotency key', () => {
  const first = aiReviewRequestKey()
  const second = aiReviewRequestKey()
  assert.ok(first.length > 8)
  assert.notEqual(first, second)
})

test('returning to the records restores filters and pagination from its own route', () => {
  assert.deepEqual(aiReviewListLocation('/my/ai-reviews?keyword=P100&status=SUCCEEDED&page=2&pageSize=20', 10), {
    name: 'AiReviewRecords', query: { keyword: 'P100', status: 'SUCCEEDED', page: '2', pageSize: '20' }
  })
  assert.deepEqual(aiReviewListLocation('/my/ai-reviews', 10), { name: 'AiReviewRecords', query: {} })
  assert.deepEqual(aiReviewListLocation('https://external.example/my/ai-reviews?page=2', 10), {
    name: 'AiReviewRecords', query: { submissionId: '10' }
  })
})
