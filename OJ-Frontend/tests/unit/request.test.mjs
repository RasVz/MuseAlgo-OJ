import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

function interceptor() {
  const events = []
  const refreshes = []
  let success
  let failure
  const service = { interceptors: { request: { use() {} }, response: { use(fn, reject) { success = fn; failure = reject } } } }
  const source = readFileSync(new URL('../../src/utils/request.js', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '').replace('export default service', '')
  vm.runInNewContext(source, {
    axios: { create: () => service, isCancel: () => false, post: (...args) => { refreshes.push(args); return Promise.reject(new Error('unexpected refresh')) } },
    useUserStore: () => ({ getRefreshToken: () => 'existing-refresh-token' }),
    authBus: { emit: (...args) => events.push(args) }, trackApiFail() {}, console
  })
  return { success, failure, events, refreshes }
}

test('HTTP 200 JSON errors in Blob downloads follow the common business-error path', async () => {
  for (const code of [400, 401, 403, 500]) {
    const { success, events } = interceptor()
    const blob = new Blob([JSON.stringify({ code, message: '下载被拒绝' })], { type: 'application/json' })
    await assert.rejects(success({ data: blob, config: { responseType: 'blob' }, headers: {} }), /下载被拒绝/)
    assert.equal(events[0][0], code === 401 ? 'auth:fail' : 'request:error')
  }
})

test('login authentication failures reach the page without a session-expired event', async () => {
  const { success, failure, events, refreshes } = interceptor()
  const config = { url: '/auth/login', authFailureHandled: true }
  await assert.rejects(success({ data: { code: 401, message: '用户名或密码错误' }, config }), /用户名或密码错误/)
  await assert.rejects(failure({ config, response: { status: 401, data: { message: '用户名或密码错误' } } }), /用户名或密码错误/)
  assert.equal(events.length, 0)
  assert.equal(refreshes.length, 0)
})

test('a regular business 401 still signals an expired session', async () => {
  const { success, events } = interceptor()
  await assert.rejects(success({ data: { code: 401, message: '请重新登录' }, config: { url: '/profile' } }), /请重新登录/)
  assert.deepEqual(events, [['auth:fail']])
})

test('CSV Blob remains intact and normal JSON responses retain their contract', async () => {
  const { success, events } = interceptor()
  const csv = new Blob(['用户,成绩\n学生,100'], { type: 'text/csv' })
  assert.equal(await success({ data: csv, config: { responseType: 'blob' }, headers: {} }), csv)
  const payload = { code: 200, data: [1, 2] }
  assert.equal(await success({ data: payload, config: {}, headers: {} }), payload)
  assert.equal(events.length, 0)
})
