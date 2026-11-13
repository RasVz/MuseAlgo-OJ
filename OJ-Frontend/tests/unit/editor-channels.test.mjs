import test from 'node:test'
import assert from 'node:assert/strict'

import { buildPayload, resolveChannel } from '../../src/views/editor/channels.js'

const baseForm = {
  id: null,
  title: '标题',
  category: 'algorithm',
  tags: ['动态规划', 'C++'],
  summary: '摘要',
  content: '# 正文',
  problemId: null,
  privacy: 0,
  relatedProblemIds: []
}

test('文章载荷：type=2、标签用逗号拼接、保留关联练习', () => {
  const { payload } = buildPayload({
    channel: 'article',
    form: { ...baseForm, relatedProblemIds: [12, 34], privacy: 1 }
  })
  assert.equal(payload.type, 2)
  assert.equal(payload.category, 'algorithm')
  assert.equal(payload.tags, '动态规划,C++')
  assert.equal(payload.privacy, 1)
  assert.deepEqual(payload.relatedProblemIds, [12, 34])
  assert.equal(payload.problemId, null)
})

test('讨论载荷：type=0、丢弃题目关联', () => {
  const { payload } = buildPayload({
    channel: 'discussion',
    form: { ...baseForm, category: 'experience', problemId: 9, relatedProblemIds: [1] }
  })
  assert.equal(payload.type, 0)
  assert.equal(payload.category, 'experience')
  assert.equal(payload.problemId, null)
  assert.deepEqual(payload.relatedProblemIds, [])
})

test('题解必须关联题目', () => {
  const missing = buildPayload({ channel: 'solution', form: { ...baseForm, category: 'solution' } })
  assert.match(missing.error, /关联题目/)

  const { payload } = buildPayload({ channel: 'solution', form: { ...baseForm, category: 'solution', problemId: 1001 } })
  assert.equal(payload.type, 1)
  assert.equal(payload.category, 'solution')
  assert.equal(payload.problemId, 1001)
})

test('分类不在频道白名单时回落到默认分类', () => {
  const { payload } = buildPayload({ channel: 'article', form: { ...baseForm, category: 'question' } })
  assert.equal(payload.category, 'algorithm')
})

test('标题、摘要与标签数量校验', () => {
  assert.match(buildPayload({ channel: 'article', form: { ...baseForm, title: '  ' } }).error, /标题/)
  assert.match(buildPayload({ channel: 'article', form: { ...baseForm, title: 'a'.repeat(129) } }).error, /128/)
  assert.match(buildPayload({ channel: 'article', form: { ...baseForm, summary: 'a'.repeat(257) } }).error, /256/)
  assert.match(buildPayload({ channel: 'article', form: { ...baseForm, tags: Array.from({ length: 9 }, (_, i) => `t${i}`) } }).error, /8/)
  assert.match(buildPayload({ channel: 'article', form: { ...baseForm, content: '   ' } }).error, /正文/)
})

test('频道判定覆盖历史数据', () => {
  assert.equal(resolveChannel(2, 'algorithm'), 'article')
  assert.equal(resolveChannel(1, 'solution'), 'solution')
  assert.equal(resolveChannel(0, 'question'), 'discussion')
  // 历史文章可能写成 type=0 + 文章分类
  assert.equal(resolveChannel(0, 'life'), 'article')
  assert.equal(resolveChannel(3, 'question'), 'class')
})
