import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { AUTO_MODE } from '../../src/utils/tutorModes.js'

test('question typed while the code snapshot loads is preserved after sending the previous question', async () => {
  let resolve
  const pending = new Promise(done => { resolve = done })
  const source = readFileSync(new URL('../../src/views/problem/components/AiAssistant.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const ask = async')
  const end = source.indexOf('const onEnter', start)
  const sent = []
  const context = { sending: { value: false }, state: { value: { busy: false, ready: true } }, question: { value: '第一个问题' },
    diagnosisStatus: { value: '' }, loadQuota() {}, identity: { value: '7:1' }, snapshotGeneration: 0, helpMode: { value: AUTO_MODE },
    props: { visible: true, problem: { id: 1 }, getStudentContext: () => pending },
    conversation: { ask: async payload => { sent.push(payload) } }, ElMessage: { info() {} }, showApiError() {} }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.run = ask', context)
  const request = context.run()
  context.question.value = '下一条尚未发送的问题'
  resolve({ language: 'java', code: 'class Main {}' })
  await request
  assert.equal(sent[0].studentQuestion, '第一个问题')
  assert.equal(sent[0].helpMode, 'AUTO')
  assert.equal(context.question.value, '下一条尚未发送的问题')
})
