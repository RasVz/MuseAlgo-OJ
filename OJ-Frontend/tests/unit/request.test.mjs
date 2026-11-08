import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import * as errorTools from '../../src/utils/apiError.js'

function interceptor(options = {}) {
  const events = []
  const refreshes = []
  let success
  let failure
  let token = options.token || ''
  let refreshToken = 'existing-refresh-token'
  const service = config => options.replay ? options.replay(config, { success, failure }) : Promise.resolve({ config })
  service.interceptors = { request: { use() {} }, response: { use(fn, reject) { success = fn; failure = reject } } }
  const source = readFileSync(new URL('../../src/utils/request.js', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '').replace('export default service', '')
  vm.runInNewContext(source, {
    ...errorTools,
    axios: { create: () => service, isCancel: () => false, post: (...args) => { refreshes.push(args); return options.refresh ? options.refresh(...args) : Promise.reject(new Error('unexpected refresh')) } },
    useUserStore: () => ({ getAccessToken: () => token, getRefreshToken: () => refreshToken, setAccessToken: value => { token = value } }),
    authBus: { emit: (...args) => events.push(args) }, trackApiFail() {}, console
  })
  return { success, failure, events, refreshes, getToken: () => token,
    switchSession: value => { token = value; refreshToken = 'different-session-refresh' } }
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

test('a late expired response reuses the token already refreshed by another request', async () => {
  const { failure, refreshes } = interceptor({
    token: 'expired-access',
    refresh: async () => ({ status: 200, data: { code: 200, data: 'new-access' } }),
    replay: config => Promise.resolve(config)
  })
  const expired = url => {
    const config = { url, headers: { Authorization: 'Bearer expired-access' } }
    return { config, response: { status: 401, data: { code: 401, errorCode: 'AUTH_TOKEN_EXPIRED' } } }
  }
  const first = await failure(expired('/first'))
  const late = await failure(expired('/late'))
  assert.equal(refreshes.length, 1)
  assert.equal(first.headers.Authorization, 'Bearer new-access')
  assert.equal(late.headers.Authorization, 'Bearer new-access')
})

test('an old response is not replayed under another signed-in account', async () => {
  let replays = 0
  const { failure, refreshes, events, switchSession, getToken } = interceptor({
    token: 'account-a-access', replay: () => { replays++; return Promise.resolve({ code: 200 }) }
  })
  switchSession('account-b-access')
  await assert.rejects(failure(expired({ url: '/private-write', headers: { Authorization: 'Bearer account-a-access' } })),
    error => error.errorCode === 'AUTH_SESSION_CHANGED')
  assert.equal(replays, 0)
  assert.equal(refreshes.length, 0)
  assert.equal(getToken(), 'account-b-access')
  assert.deepEqual(events, [])
})

test('an anonymous request is not replayed under a session created while it was in flight', async () => {
  let replays = 0
  const { failure, refreshes, events, switchSession } = interceptor({
    replay: () => { replays++; return Promise.resolve({ code: 200 }) }
  })
  switchSession('new-account-access')
  await assert.rejects(failure({ config: { url: '/private-write', headers: {} }, response: { status: 401, data: { code: 401 } } }),
    error => error.errorCode === 'AUTH_SESSION_CHANGED')
  assert.equal(replays, 0)
  assert.equal(refreshes.length, 0)
  assert.deepEqual(events, [])
})

test('an in-flight refresh cannot overwrite a new account session', async () => {
  let resolveRefresh
  const pending = new Promise(resolve => { resolveRefresh = resolve })
  const { failure, switchSession, getToken, events } = interceptor({ token: 'account-a-access', refresh: () => pending })
  const result = failure(expired({ url: '/private-write', headers: { Authorization: 'Bearer account-a-access' } }))
  switchSession('account-b-access')
  resolveRefresh({ status: 200, data: { code: 200, data: 'account-a-refreshed' } })
  await assert.rejects(result, error => error.errorCode === 'AUTH_SESSION_CHANGED')
  assert.equal(getToken(), 'account-b-access')
  assert.deepEqual(events, [])
})

test('CSV Blob remains intact and normal JSON responses retain their contract', async () => {
  const { success, events } = interceptor()
  const csv = new Blob(['用户,成绩\n学生,100'], { type: 'text/csv' })
  assert.equal(await success({ data: csv, config: { responseType: 'blob' }, headers: {} }), csv)
  const payload = { code: 200, data: [1, 2] }
  assert.equal(await success({ data: payload, config: {}, headers: {} }), payload)
  assert.equal(events.length, 0)
})

test('real HTTP errors preserve the safe backend reason and trace', async () => {
  const { failure, events } = interceptor()
  const body = { code: 409, errorCode: 'CONTEST_NOT_ENDED', message: '比赛尚未结束', traceId: 'trace-1' }
  await assert.rejects(failure({ config: { url: '/contest' }, response: { status: 409, data: body, headers: {} }, message: 'Request failed with status code 409' }), error => {
    assert.equal(error.message, '比赛尚未结束')
    assert.equal(error.errorCode, 'CONTEST_NOT_ENDED')
    assert.equal(error.traceId, 'trace-1')
    assert.equal(error.response.status, 409)
    return true
  })
  assert.deepEqual(events, [['request:error', '比赛尚未结束']])
})

function expired(config) {
  return { config, response: { status: 401, data: { code: 401, errorCode: 'AUTH_TOKEN_EXPIRED', message: '凭据过期' }, headers: {} } }
}

test('concurrent expired requests share one refresh and replay with the Result.data token', async () => {
  let resolveRefresh
  let started
  const refreshing = new Promise(resolve => { resolveRefresh = resolve })
  const ready = new Promise(resolve => { started = resolve })
  const seen = []
  const { failure, refreshes, events } = interceptor({
    refresh: () => { started(); return refreshing },
    replay: config => { seen.push(config); return Promise.resolve({ code: 200 }) }
  })
  const requests = [failure(expired({ url: '/a' })), failure(expired({ url: '/b' }))]
  await ready
  resolveRefresh({ status: 200, data: { code: 200, data: 'new-access-token' } })
  await Promise.all(requests)
  assert.equal(refreshes.length, 1)
  assert.equal(seen.length, 2)
  assert.ok(seen.every(config => config.headers.Authorization === 'Bearer new-access-token' && config._retry))
  assert.deepEqual(events, [])
})

test('a replayed 401 terminates rather than refreshing again', async () => {
  const { failure, refreshes, events } = interceptor({
    refresh: () => Promise.resolve({ data: { code: 200, data: 'new-access-token' } }),
    replay: (config, callbacks) => callbacks.failure(expired(config))
  })
  await assert.rejects(failure(expired({ url: '/a' })), /凭据过期/)
  assert.equal(refreshes.length, 1)
  assert.deepEqual(events, [['auth:fail']])
})

test('refresh dependency failures reject every waiter without clearing their session or repeating the toast', async () => {
  let rejectRefresh
  let started
  const pending = new Promise((_, reject) => { rejectRefresh = reject })
  const ready = new Promise(resolve => { started = resolve })
  const { failure, refreshes, events } = interceptor({ refresh: () => { started(); return pending } })
  const requests = [failure(expired({ url: '/a' })), failure(expired({ url: '/b' }))]
  const settled = Promise.allSettled(requests)
  await ready
  rejectRefresh({ response: { status: 503, data: { code: 503, errorCode: 'AUTH_DEPENDENCY_UNAVAILABLE', message: '认证暂不可用' } } })
  const results = await settled
  assert.ok(results.every(result => result.status === 'rejected' && result.reason.errorCode === 'AUTH_DEPENDENCY_UNAVAILABLE'))
  assert.equal(refreshes.length, 1)
  assert.deepEqual(events, [['request:error', '认证暂不可用']])
})

test('silent 403 and HTTP Blob errors retain structured metadata', async () => {
  const { failure, events } = interceptor()
  await assert.rejects(failure({ config: { url: '/download', responseType: 'blob', silent: true }, response: {
    status: 403, data: new Blob([JSON.stringify({ code: 403, errorCode: 'ACCESS_DENIED', message: '无权下载' })], { type: 'application/json' }), headers: { 'x-trace-id': 'trace-download' }
  } }), error => error.errorCode === 'ACCESS_DENIED' && error.traceId === 'trace-download')
  assert.deepEqual(events, [])
})
