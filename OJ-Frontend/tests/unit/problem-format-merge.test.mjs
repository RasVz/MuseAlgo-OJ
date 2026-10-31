import test from 'node:test'
import assert from 'node:assert/strict'

import {
  changedFormattedFields,
  describeFieldChanges,
  mergeFormattedFields,
  normalizeSamples,
  pickFormattedFields
} from '../../src/views/admin/problemFormatMerge.js'

function baseForm() {
  return {
    id: 7,
    title: '旧标题',
    content: '旧正文',
    inputDesc: '',
    outputDesc: '',
    hint: '',
    timeLimit: 1000,
    memoryLimit: 512,
    samples: [{ input: '1', output: '1' }],
    // 非格式化字段：这些值代表用户尚未保存的修改
    difficulty: 1600,
    source: 'NOIP',
    tags: '图论,最短路',
    isPublic: 0
  }
}

test('格式化结果只覆盖它负责的字段，其他未保存修改保留', () => {
  const form = baseForm()
  const detail = {
    title: '新标题',
    content: '新正文',
    inputDesc: '输入格式',
    outputDesc: '输出格式',
    hint: '提示',
    timeLimit: 2000,
    memoryLimit: 256,
    samples: [{ input: '2', output: '2' }],
    difficulty: 800,
    source: 'Other',
    tags: 'dp',
    isPublic: 1
  }

  mergeFormattedFields(form, detail)

  assert.equal(form.title, '新标题')
  assert.equal(form.content, '新正文')
  assert.equal(form.timeLimit, 2000)
  assert.deepEqual(form.samples, [{ input: '2', output: '2' }])
  // 非格式化字段不受影响
  assert.equal(form.difficulty, 1600)
  assert.equal(form.source, 'NOIP')
  assert.equal(form.tags, '图论,最短路')
  assert.equal(form.isPublic, 0)
})

test('样例归一化丢弃非法结构并补空串', () => {
  assert.deepEqual(normalizeSamples(null), [])
  assert.deepEqual(normalizeSamples('oops'), [])
  assert.deepEqual(normalizeSamples([{}, { input: 'a' }, { output: 'b' }]), [
    { input: '', output: '' },
    { input: 'a', output: '' },
    { input: '', output: 'b' }
  ])
})

test('pickFormattedFields 对缺失详情给出安全默认值', () => {
  assert.deepEqual(pickFormattedFields(null), {
    title: '',
    content: '',
    inputDesc: '',
    outputDesc: '',
    hint: '',
    timeLimit: 1000,
    memoryLimit: 512,
    samples: []
  })
})

test('能识别格式化字段上的未保存修改，忽略非格式化字段', () => {
  const form = baseForm()
  const baseline = pickFormattedFields({
    title: '旧标题', content: '旧正文', inputDesc: '', outputDesc: '', hint: '',
    timeLimit: 1000, memoryLimit: 512, samples: [{ input: '1', output: '1' }]
  })
  assert.deepEqual(changedFormattedFields(form, baseline), [])

  form.difficulty = 2000 // 非格式化字段变动不算
  assert.deepEqual(changedFormattedFields(form, baseline), [])

  form.title = '手改标题'
  form.samples = [{ input: '9', output: '9' }]
  assert.deepEqual(changedFormattedFields(form, baseline), ['title', 'samples'])
  assert.equal(describeFieldChanges(['title', 'samples']), '题目名称、样例数据')
  assert.equal(describeFieldChanges([]), '')
})

test('基线缺失时不误报未保存修改', () => {
  assert.deepEqual(changedFormattedFields(baseForm(), null), [])
  assert.deepEqual(changedFormattedFields(null, {}), [])
})
