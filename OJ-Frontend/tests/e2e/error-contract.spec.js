import { test, expect } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { injectSession } from './fixtures/e2e_auth.js'

const evidence = process.env.OJ_TEST_OUTPUT_DIR
const enabled = process.env.OJ_ERROR_CONTRACT_ENABLED === '1'
const load = name => JSON.parse(readFileSync(join(evidence, name), 'utf8'))

async function dependency(action, service) {
  const command = { action, service, id: Date.now() }
  writeFileSync(join(evidence, 'runtime-command.json'), JSON.stringify(command))
  await expect.poll(() => {
    try { return load('runtime-command-result.json').id } catch { return 0 }
  }, { timeout: 30000 }).toBe(command.id)
}

test.describe('local error contract on the production frontend build', () => {
  test.skip(!enabled, 'Requires the disposable synthetic error-contract stack')

  test('wrong password shows one useful message and keeps the login page', async ({ page }) => {
    await page.goto('/login')
    await page.getByPlaceholder('请输入账号').fill('student01')
    await page.getByPlaceholder('请输入密码').fill('synthetic-wrong-password')
    const response = page.waitForResponse(r => r.url().endsWith('/api/auth/login'))
    await page.getByRole('button', { name: '立即登录' }).click()
    expect((await (await response).json()).errorCode).toBe('AUTH_INVALID_CREDENTIALS')
    await expect(page.locator('.el-message--error')).toHaveCount(1)
    await expect(page.locator('.el-message--error')).toContainText('用户名或密码错误')
    await expect(page).toHaveURL(/\/login$/)
  })

  test('expired access is refreshed once and real requests are replayed', async ({ page, context }) => {
    await injectSession(context, load('expired-session.json'))
    let refreshes = 0
    page.on('request', request => { if (request.url().endsWith('/api/auth/refresh')) refreshes++ })
    await page.goto('/problem/1')
    await expect(page.getByRole('button', { name: 'AI 助教' })).toBeVisible()
    await expect.poll(() => refreshes).toBe(1)
    await expect.poll(() => page.evaluate(() => localStorage.getItem('accessToken')))
      .not.toBe(load('expired-session.json').accessToken)
    await expect(page.locator('.el-message--error')).toHaveCount(0)
    await expect(page).toHaveURL(/\/problem\/1$/)
  })

  test('missing problem shows the backend reason once', async ({ page, context }) => {
    await injectSession(context, load('sessions.json').student01)
    await page.goto('/problem/999999')
    await expect(page.locator('.el-message--error')).toHaveCount(1)
    await expect(page.locator('.el-message--error')).toContainText('题目不存在')
  })

  test('an interrupted local SSE keeps partial text and does not unlock the next hint', async ({ page, context }) => {
    await injectSession(context, load('sessions.json').student01)
    // Only the stream is a fixture. Page, authentication, problem and hint state use the real backend.
    await page.route('**/api/ai/ask/stream', route => route.fulfill({
      status: 200, contentType: 'text/event-stream', body: 'data: synthetic partial answer\n\n'
    }))
    await page.goto('/problem/1')
    await page.getByRole('button', { name: 'AI 助教' }).click()
    await page.getByRole('button', { name: 'L1 思路方向' }).click()
    await page.getByPlaceholder('告诉教练你的思路，或描述哪里卡住了...').fill('synthetic question')
    await page.locator('.send-btn').click()
    await expect(page.locator('.chat-container')).toContainText('synthetic partial answer')
    await expect(page.locator('.chat-container')).toContainText('回复未完成，请重试')
    await expect(page.getByRole('button', { name: 'L2 关键观察' })).toBeDisabled()
    await expect(page.locator('.el-message--error')).toContainText('AI 回复中断，请重试')
  })

  test('a local app-error event keeps the answer incomplete even if done follows', async ({ page, context }) => {
    await injectSession(context, load('sessions.json').student01)
    await page.route('**/api/ai/ask/stream', route => route.fulfill({
      status: 200, contentType: 'text/event-stream',
      body: 'data: synthetic event answer\n\nevent: app-error\ndata: {"code":503,"errorCode":"AI_UPSTREAM_FAILED","message":"合成上游暂不可用"}\n\nevent: done\ndata: {}\n\n'
    }))
    await page.goto('/problem/1')
    await page.getByRole('button', { name: 'AI 助教' }).click()
    await page.getByRole('button', { name: 'L1 思路方向' }).click()
    await page.getByPlaceholder('告诉教练你的思路，或描述哪里卡住了...').fill('synthetic event question')
    await page.locator('.send-btn').click()
    await expect(page.locator('.chat-container')).toContainText('synthetic event answer')
    await expect(page.locator('.chat-container')).toContainText('回复未完成，请重试')
    await expect(page.getByRole('button', { name: 'L2 关键观察' })).toBeDisabled()
    await expect(page.locator('.el-message--error')).toContainText('合成上游暂不可用')
  })

  test('the real disabled AI backend returns an error without unlocking a hint', async ({ page, context }) => {
    await injectSession(context, load('sessions.json').student01)
    await page.goto('/problem/1')
    await page.getByRole('button', { name: 'AI 助教' }).click()
    await page.getByRole('button', { name: 'L1 思路方向' }).click()
    await page.getByPlaceholder('告诉教练你的思路，或描述哪里卡住了...').fill('synthetic backend question')
    const response = page.waitForResponse(r => r.url().endsWith('/api/ai/ask/stream'))
    await page.locator('.send-btn').click()
    expect((await response).headers()['content-type']).toContain('text/event-stream')
    await expect(page.locator('.el-message--error')).toContainText('AI 服务暂时不可用')
    await expect(page.getByRole('button', { name: 'L2 关键观察' })).toBeDisabled()
  })

  for (const service of ['redis', 'mysql']) {
    test(`${service} loss preserves the session without refresh loops and recovers`, async ({ page, context }) => {
      const session = load('sessions.json').student01
      await injectSession(context, session)
      let refreshes = 0
      page.on('request', request => { if (request.url().endsWith('/api/auth/refresh')) refreshes++ })
      try {
        await dependency('stop', service)
        const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/problem/1'))
        await page.goto('/problem/1')
        const response = await responsePromise
        expect(response.status()).toBe(503)
        const body = await response.json()
        expect(body.code).toBe(503)
        expect(body.errorCode).toBe('AUTH_DEPENDENCY_UNAVAILABLE')
        expect(body.traceId).toBe(response.headers()['x-trace-id'])
        if (service === 'mysql') {
          const publicResponse = await page.request.get('/api/problem/1')
          const publicBody = await publicResponse.json()
          expect(publicResponse.status()).toBe(200)
          expect(publicBody.code).toBe(503)
          expect(publicBody.errorCode).toBe('DEPENDENCY_UNAVAILABLE')
          expect(publicBody.traceId).toBe(publicResponse.headers()['x-trace-id'])
          writeFileSync(join(evidence, 'mysql-public-fault.json'), JSON.stringify(publicBody))
        }
        await expect(page.locator('.el-message--error').first()).toContainText('暂时无法验证登录状态')
        expect(await page.evaluate(() => localStorage.getItem('accessToken'))).toBe(session.accessToken)
        expect(await page.evaluate(() => localStorage.getItem('refreshToken'))).toBe(session.refreshToken)
        expect(refreshes).toBe(0)
        await expect(page).toHaveURL(/\/problem\/1$/)
        writeFileSync(join(evidence, `${service}-browser-fault.json`), JSON.stringify({ body, refreshes, sessionRetained: true }))
      } finally { await dependency('start', service) }
      await expect.poll(async () => {
        const response = await page.request.get('/api/problem/1', { headers: { Authorization: `Bearer ${session.accessToken}` } })
        return (await response.json()).code
      }, { timeout: 30000 }).toBe(200)
    })
  }
})
