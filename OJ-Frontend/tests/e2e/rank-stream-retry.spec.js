import { test, expect } from './fixtures/e2e_prepare.js'

test('榜单高频通知合并请求，隐藏期间暂停，恢复后只刷新一次', async ({ adminPage, fixture }) => {
  const id = fixture.contests.star.id
  await adminPage.addInitScript(() => {
    window.__hiddenForTest = false
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => window.__hiddenForTest })
    window.__streamConnections = 0
    const realFetch = window.fetch
    window.fetch = (url, options) => {
      if (!String(url).includes('/ranking/stream')) return realFetch(url, options)
      window.__streamConnections += 1
      const encoder = new TextEncoder()
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) {
          window.__rankEmit = n => { for (let i = 0; i < n; i++) controller.enqueue(encoder.encode('event: ranking-invalidated\ndata: official\n\n')) }
          controller.enqueue(encoder.encode('event: connected\ndata: ok\n\n'))
          options.signal.addEventListener('abort', () => { try { controller.close() } catch { /* already closed */ } })
        }
      }), { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    }
  })
  await adminPage.route('**/api/contest/' + id, async route => {
    const response = await route.fetch()
    const body = await response.json()
    body.data.startTime = '2026-01-01T00:00:00'
    body.data.endTime = '2099-01-01T00:00:00'
    body.data.mode = 'ACM'
    await route.fulfill({ response, json: body })
  })
  let requests = 0
  await adminPage.route('**/api/contest/' + id + '/rank/list*', async route => {
    requests += 1
    await route.fulfill({ status: 200, json: { code: 200, data: [] } })
  })
  await adminPage.goto('/contest/' + id + '/ranking')
  await expect.poll(() => adminPage.evaluate(() => window.__streamConnections)).toBe(1)
  await expect.poll(() => requests).toBe(2)
  await adminPage.evaluate(() => window.__rankEmit(10))
  await expect.poll(() => requests).toBe(3)
  await adminPage.waitForTimeout(500)
  expect(requests).toBe(3)
  await adminPage.evaluate(() => { window.__hiddenForTest = true; document.dispatchEvent(new Event('visibilitychange')) })
  await adminPage.waitForTimeout(400)
  expect(requests).toBe(3)
  expect(await adminPage.evaluate(() => window.__streamConnections)).toBe(1)
  await adminPage.evaluate(() => { window.__hiddenForTest = false; document.dispatchEvent(new Event('visibilitychange')) })
  await expect.poll(() => requests).toBe(4)
  await expect.poll(() => adminPage.evaluate(() => window.__streamConnections)).toBe(2)
  await adminPage.waitForTimeout(500)
  expect(requests).toBe(4)
})

test('榜单断线退避后恢复，只补一次权威读取', async ({ adminPage, fixture }) => {
  const id = fixture.contests.star.id
  await adminPage.addInitScript(() => {
    window.__networkDown = true
    window.__streamCalls = 0
    const realFetch = window.fetch
    window.fetch = (url, options) => {
      if (!String(url).includes('/ranking/stream')) return realFetch(url, options)
      window.__streamCalls += 1
      if (window.__networkDown) return Promise.resolve(new Response('', { status: 503 }))
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('event: connected\ndata: ok\n\n'))
          options.signal.addEventListener('abort', () => { try { controller.close() } catch { /* already closed */ } })
        }
      }), { status: 200, headers: { 'content-type': 'text/event-stream' } }))
    }
  })
  await adminPage.route('**/api/contest/' + id, async route => {
    const response = await route.fetch(); const body = await response.json()
    body.data.startTime = '2020-01-01T00:00:00'; body.data.endTime = '2099-01-01T00:00:00'
    body.data.mode = 'ACM'
    await route.fulfill({ response, json: body })
  })
  let reads = 0
  await adminPage.route('**/api/contest/' + id + '/rank/list*', async route => {
    reads += 1
    await route.fulfill({ status: 200, json: { code: 200, data: [] } })
  })
  await adminPage.clock.install()
  await adminPage.goto('/contest/' + id + '/ranking')
  await expect.poll(() => adminPage.evaluate(() => window.__streamCalls)).toBe(1)
  await expect(adminPage.getByText('实时连接中断，正在重连（第 1 次）。')).toBeVisible()
  expect(reads).toBe(1)
  await adminPage.clock.fastForward(1500)
  await expect.poll(() => adminPage.evaluate(() => window.__streamCalls)).toBe(2)
  await expect(adminPage.getByText('实时连接中断，正在重连（第 2 次）。')).toBeVisible()
  await adminPage.clock.fastForward(3000)
  await expect.poll(() => adminPage.evaluate(() => window.__streamCalls)).toBe(3)
  await expect(adminPage.getByText('实时连接中断，正在重连（第 3 次）。')).toBeVisible()
  expect(reads).toBe(1)
  await adminPage.evaluate(() => { window.__networkDown = false })
  await adminPage.clock.fastForward(6000)
  await expect.poll(() => adminPage.evaluate(() => window.__streamCalls)).toBe(4)
  await expect(adminPage.getByText('实时连接中断，正在重连（第 3 次）。')).not.toBeVisible()
  await adminPage.clock.fastForward(150)
  await expect.poll(() => reads).toBe(2)
  await adminPage.clock.fastForward(30000)
  expect(reads).toBe(2)
})

test('虚拟参赛永久错误停止自动重试并允许手动重试', async ({ adminPage, fixture }) => {
  const id = fixture.contests.star.id
  await adminPage.route('**/api/contest/' + id, async route => {
    const response = await route.fetch(); const body = await response.json()
    body.data.startTime = '2020-01-01T00:00:00'; body.data.endTime = '2021-01-01T00:00:00'
    await route.fulfill({ response, json: body })
  })
  let calls = 0
  await adminPage.route('**/api/contest/' + id + '/vp', async route => {
    calls += 1
    await route.fulfill(calls === 1
      ? { status: 200, json: { code: 200, data: { status: 'SETTLING' } } }
      : { status: 403, json: { code: 403, message: 'FORBIDDEN' } })
  })
  await adminPage.goto('/contest/' + id + '/ranking')
  await expect(adminPage.getByText('已交卷，正在等待判题和正式榜结算。', { exact: false })).toBeVisible()
  await expect(adminPage.getByText('成绩刷新失败，请检查权限后重试。')).toBeVisible({ timeout: 10000 })
  await adminPage.waitForTimeout(5000)
  expect(calls).toBe(2)
  await adminPage.getByRole('button', { name: '重新尝试' }).click()
  await expect.poll(() => calls).toBe(3)
})

test('虚拟参赛首次读取失败也显示手动重试入口', async ({ adminPage, fixture }) => {
  const id = fixture.contests.star.id
  await adminPage.route('**/api/contest/' + id, async route => {
    const response = await route.fetch(); const body = await response.json()
    body.data.startTime = '2020-01-01T00:00:00'; body.data.endTime = '2021-01-01T00:00:00'
    await route.fulfill({ response, json: body })
  })
  let calls = 0
  await adminPage.route('**/api/contest/' + id + '/vp', async route => {
    calls += 1
    await route.fulfill({ status: 403, json: { code: 403, message: 'FORBIDDEN' } })
  })
  await adminPage.goto('/contest/' + id + '/ranking')
  await expect(adminPage.getByText('成绩刷新失败，请检查权限后重试。')).toBeVisible()
  expect(calls).toBe(1)
  await adminPage.getByRole('button', { name: '重新尝试' }).click()
  await expect.poll(() => calls).toBe(2)
})

test('虚拟参赛瞬时错误递增退避并在达到上限后停止', async ({ adminPage, fixture }) => {
  const id = fixture.contests.star.id
  await adminPage.route('**/api/contest/' + id, async route => {
    const response = await route.fetch(); const body = await response.json()
    body.data.startTime = '2020-01-01T00:00:00'; body.data.endTime = '2021-01-01T00:00:00'
    await route.fulfill({ response, json: body })
  })
  let calls = 0
  await adminPage.route('**/api/contest/' + id + '/vp', async route => {
    calls += 1
    await route.fulfill(calls === 1
      ? { status: 200, json: { code: 200, data: { status: 'SETTLING' } } }
      : { status: 503, json: { code: 503, message: 'UNAVAILABLE' } })
  })
  await adminPage.clock.install()
  await adminPage.goto('/contest/' + id + '/ranking')
  await expect(adminPage.getByText('已交卷，正在等待判题和正式榜结算。', { exact: false })).toBeVisible()
  for (const [index, delay] of [3000, 3000, 6000, 12000, 24000, 30000].entries()) {
    await adminPage.clock.fastForward(delay)
    await expect.poll(() => calls).toBe(index + 2)
  }
  await expect(adminPage.getByText('多次刷新失败，已暂停自动刷新。')).toBeVisible()
  await adminPage.clock.fastForward(120000)
  expect(calls).toBe(7)
})
