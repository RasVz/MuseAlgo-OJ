import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import { request, test, expect } from '@playwright/test'
import {
  API_BASE,
  createApi,
  injectSession,
  login
} from './fixtures/e2e_auth.js'

const UPSTREAM = process.env.AI_TEST_UPSTREAM || 'http://host.docker.internal:18082/v1/chat/completions'
const INITIAL_KEY = 'sk-e2e-fixture'
const ROTATED_KEY = 'sk-e2e-fixture-rotated'
const TIMEOUT = 20000
const RESPONSE_TIMEOUT = 30000

async function withPageRequest(page, method, path, action) {
  const [response] = await Promise.all([
    page.waitForResponse((candidate) => {
      const url = new URL(candidate.url())
      return url.pathname === `/api${path}` && candidate.request().method() === method
    }, { timeout: RESPONSE_TIMEOUT }),
    action()
  ])
  const body = await response.json()
  expect(response.status()).toBe(200)
  expect(body.code).toBe(200)
  return body.data
}

function serviceRow(page, name) {
  return page.locator('.el-table__row').filter({ hasText: name })
}

async function readData(api, method, path, session, options = {}) {
  const response = await api.call(method, path, { ...options, session })
  if (response.status !== 200 || response.body?.code !== 200) {
    throw new Error(`${method} ${path} failed: HTTP ${response.status}, code=${response.body?.code}`)
  }
  return response.body.data
}

async function cleanupRun(api, session, serviceName, knownServiceId, offRuntimeVersion) {
  if (!api || !session) return

  try {
    let services = await readData(api, 'GET', '/admin/ai/services', session)
    let target = services.find((item) => item.id === knownServiceId)
      || services.find((item) => item.name === serviceName)
    if (!target) return

    let runtime = await readData(api, 'GET', '/admin/ai/runtime', session)
    const isOurManagedService = runtime.mode === 'MANAGED' && runtime.currentServiceId === target.id
    const isOurOffState = runtime.mode === 'OFF'
      && offRuntimeVersion !== null
      && runtime.runtimeVersion === offRuntimeVersion

    // Restore only a runtime state owned by this run. A different active service or a
    // later runtime version belongs to another actor and must be left untouched.
    if (isOurManagedService || isOurOffState) {
      const restored = await api.put('/admin/ai/runtime', {
        session,
        data: { mode: 'LEGACY', version: runtime.runtimeVersion }
      })
      if (restored.status !== 200 || restored.body?.code !== 200) {
        throw new Error(`restore LEGACY failed: HTTP ${restored.status}, code=${restored.body?.code}`)
      }
      runtime = restored.body.data
    }

    if (runtime.mode === 'MANAGED' && runtime.currentServiceId === target.id) {
      throw new Error('run service is still current; skipped deletion to preserve runtime integrity')
    }

    // Refresh the row so cleanup always uses the latest optimistic-lock version.
    services = await readData(api, 'GET', '/admin/ai/services', session)
    target = services.find((item) => item.id === target.id)
    if (!target) return
    const deleted = await api.del(`/admin/ai/services/${target.id}`, {
      session,
      data: { version: target.version }
    })
    if (deleted.status !== 200 || deleted.body?.code !== 200) {
      throw new Error(`delete run service failed: HTTP ${deleted.status}, code=${deleted.body?.code}`)
    }
  } catch (error) {
    // Do not mask the browser assertion that caused teardown; keep cleanup evidence visible.
    console.error('[AI E2E cleanup]', error)
  }
}

test.describe('真实后端 AI 服务管理', () => {
  test.skip(process.env.AI_E2E_REAL !== '1', '需要 AI_E2E_REAL=1 和隔离后端配置')
  test.describe.configure({ timeout: 240000 })

  test('管理员添加、测试、切换、改 Key 并停用 AI', async ({ page }) => {
    const runId = randomUUID().replaceAll('-', '').slice(0, 16)
    const serviceName = `E2E AI ${runId}`
    let adminSession
    let apiContext
    let api
    let serviceId = null
    let offRuntimeVersion = null

    try {
      adminSession = await login('admin')
      await injectSession(page.context(), adminSession)
      apiContext = await request.newContext({ baseURL: API_BASE, timeout: RESPONSE_TIMEOUT })
      api = createApi(apiContext)

      const initialRuntime = await readData(api, 'GET', '/admin/ai/runtime', adminSession)
      expect(initialRuntime.mode).toBe('LEGACY')
      expect(initialRuntime.currentServiceId).toBeNull()

      await page.goto('/admin')
      await expect(page.getByRole('menuitem', { name: 'AI 服务', exact: true })).toBeVisible({ timeout: TIMEOUT })
      await page.getByRole('menuitem', { name: 'AI 服务', exact: true }).click()
      await expect(page).toHaveURL(/\/admin\/ai-services$/, { timeout: TIMEOUT })
      await expect(page.locator('.page-title').filter({ hasText: 'AI 服务' })).toBeVisible({ timeout: TIMEOUT })

      await page.getByRole('button', { name: '添加服务' }).click()
      const drawer = page.locator('.el-drawer').filter({ hasText: '添加 AI 服务' })
      await expect(drawer).toBeVisible({ timeout: TIMEOUT })
      await drawer.getByPlaceholder('例如：教学主服务').fill(serviceName)
      await drawer.getByPlaceholder('https://api.example.com/v1/chat/completions').fill(UPSTREAM)
      await drawer.getByPlaceholder('服务商要求的精确模型 ID').fill('e2e-model')
      await drawer.getByPlaceholder('输入服务商 API Key').fill(INITIAL_KEY)

      const created = await withPageRequest(page, 'POST', '/admin/ai/services',
        () => drawer.getByRole('button', { name: '保存服务', exact: true }).click())
      serviceId = created.id
      expect(created.version).toBe(1)
      expect(created.hasApiKey).toBe(true)
      await expect(drawer).toBeHidden({ timeout: TIMEOUT })

      const row = serviceRow(page, serviceName)
      await expect(row).toHaveCount(1)
      await expect(row).toContainText('未测试', { timeout: TIMEOUT })
      await expect(row).toContainText('已配置')

      const tested = await withPageRequest(page, 'POST', `/admin/ai/services/${serviceId}/test`,
        () => row.getByRole('button', { name: '测试连接' }).click())
      expect(tested.passed).toBe(true)
      await test.info().attach('connection-test-report', {
        body: JSON.stringify(tested, null, 2),
        contentType: 'application/json'
      })
      if (process.env.AI_TEST_MIN_PROBE_MS) {
        expect(tested.totalLatencyMs).toBeGreaterThanOrEqual(Number(process.env.AI_TEST_MIN_PROBE_MS))
      }
      const report = page.locator('.el-dialog').filter({ hasText: '连接测试结果' })
      await expect(report).toBeVisible({ timeout: RESPONSE_TIMEOUT })
      await expect(report).toContainText('全部必测项通过', { timeout: RESPONSE_TIMEOUT })
      await expect(report).toContainText('普通文本')
      await expect(report).toContainText('流式文本')
      await expect(report).toContainText('JSON 输出')
      await report.getByRole('button', { name: '知道了' }).click()
      await expect(report).toBeHidden({ timeout: TIMEOUT })
      await expect(row).toContainText('通过', { timeout: TIMEOUT })

      const activate = await withPageRequest(page, 'POST', `/admin/ai/services/${serviceId}/activate`,
        async () => {
          await row.getByRole('button', { name: '设为当前' }).click()
          await page.getByRole('button', { name: '确认切换' }).click()
        })
      expect(activate.mode).toBe('MANAGED')
      expect(activate.currentServiceId).toBe(serviceId)
      await expect(page.locator('.runtime-value')).toContainText('后台服务', { timeout: TIMEOUT })
      await expect(page.locator('.runtime-value')).toContainText(serviceName)
      await expect(row).toContainText('当前使用')
      await expect(row.getByText(serviceName, { exact: true })).toBeInViewport()
      await screenshot(page, 'ai-services-managed.png')

      // SPA navigation keeps the admin shell mounted and exercises the actual legacy page.
      await page.getByRole('menuitem', { name: 'AI 管理', exact: true }).click()
      await expect(page).toHaveURL(/\/admin\/ai$/, { timeout: TIMEOUT })
      await expect(page.getByText('当前由后台服务提供 AI')).toBeVisible({ timeout: TIMEOUT })
      await expect(page.locator('.el-alert').filter({ hasText: '当前由后台服务提供 AI' }))
        .toContainText('模型、输出上限和 JSON 模式来自当前服务')
      const modelInput = page.locator('.el-form-item').filter({ hasText: '模型' }).locator('input').first()
      await expect(modelInput).toBeDisabled()

      await page.getByRole('menuitem', { name: 'AI 服务', exact: true }).click()
      await expect(page).toHaveURL(/\/admin\/ai-services$/, { timeout: TIMEOUT })
      const currentRow = serviceRow(page, serviceName)
      await expect(currentRow).toHaveCount(1)
      await currentRow.getByRole('button', { name: '编辑', exact: true }).click()
      const editDrawer = page.locator('.el-drawer').filter({ hasText: '编辑 AI 服务' })
      await expect(editDrawer).toBeVisible({ timeout: TIMEOUT })
      const keyInput = editDrawer.getByPlaceholder('留空表示保留现有密钥')
      await expect(keyInput).toHaveValue('')
      await keyInput.fill(ROTATED_KEY)
      await editDrawer.getByPlaceholder('服务商要求的精确模型 ID').fill('e2e-model-v2')

      const update = await withPageRequest(page, 'PUT', `/admin/ai/services/${serviceId}`,
        () => editDrawer.getByRole('button', { name: '测试并保存应用', exact: true }).click())
      expect(update.applied).toBe(true)
      expect(update.service.version).toBe(2)
      expect(update.service.model).toBe('e2e-model-v2')
      expect(update.service.hasApiKey).toBe(true)
      await expect(page.getByText('候选配置已测试通过并应用')).toBeVisible({ timeout: RESPONSE_TIMEOUT })

      // Editing the current service also opens a test report; close it before the next action.
      const updateReport = page.locator('.el-dialog').filter({ hasText: '连接测试结果' })
      await expect(updateReport).toBeVisible({ timeout: RESPONSE_TIMEOUT })
      await updateReport.getByRole('button', { name: '知道了' }).click()
      await expect(updateReport).toBeHidden({ timeout: TIMEOUT })
      await expect(editDrawer).toBeHidden({ timeout: TIMEOUT })
      await expect(currentRow).toContainText('e2e-model-v2', { timeout: TIMEOUT })
      await expect(currentRow).toContainText('v2')

      const updatedServices = await readData(api, 'GET', '/admin/ai/services', adminSession)
      const updated = updatedServices.find((item) => item.id === serviceId)
      expect(updated).toMatchObject({
        model: 'e2e-model-v2',
        version: 2,
        lastTestVersion: 2,
        testState: 'PASSED',
        hasApiKey: true
      })

      await page.getByRole('button', { name: '停用 AI', exact: true }).click()
      const off = await withPageRequest(page, 'PUT', '/admin/ai/runtime',
        () => page.getByRole('button', { name: '确认停用' }).click())
      offRuntimeVersion = off.runtimeVersion
      expect(off.mode).toBe('OFF')
      expect(off.currentServiceId).toBeNull()
      await expect(page.locator('.runtime-value')).toContainText('已停用', { timeout: TIMEOUT })
      await expect(page.getByText('不发送任何模型请求')).toBeVisible()
      await screenshot(page, 'ai-services-off.png')

      const legacy = await withPageRequest(page, 'PUT', '/admin/ai/runtime',
        async () => {
          await page.getByRole('button', { name: '恢复旧环境配置' }).click()
          await page.getByRole('button', { name: '确认恢复' }).click()
        })
      expect(legacy.mode).toBe('LEGACY')
      expect(legacy.currentServiceId).toBeNull()
      offRuntimeVersion = null
      await expect(page.locator('.runtime-value')).toContainText('旧环境', { timeout: TIMEOUT })
    } finally {
      await cleanupRun(api, adminSession, serviceName, serviceId, offRuntimeVersion)
      await apiContext?.dispose()
    }
  })

  test('教师登录后被页面守卫拦截，直接读写管理接口均返回 403', async ({ page }) => {
    const teacherSession = await login('teacher01')
    await injectSession(page.context(), teacherSession)
    const apiContext = await request.newContext({ baseURL: API_BASE, timeout: RESPONSE_TIMEOUT })
    const api = createApi(apiContext)

    try {
      await page.goto('/admin/ai-services')
      await expect(page).toHaveURL(/\/admin$/, { timeout: TIMEOUT })
      await expect(page.locator('.page-title').filter({ hasText: '管理中心' })).toBeVisible({ timeout: TIMEOUT })

      const read = await api.get('/admin/ai/services', { session: teacherSession })
      expect(read.status).toBe(403)
      expect(read.body?.code).toBe(403)

      const write = await api.post('/admin/ai/services', {
        session: teacherSession,
        data: {
          name: `forbidden-e2e-${randomUUID()}`,
          protocol: 'CHAT_COMPLETIONS',
          apiUrl: UPSTREAM,
          model: 'e2e-forbidden-model',
          apiKey: INITIAL_KEY,
          jsonMode: true,
          streamIncludeUsage: true,
          maxTokens: 128
        }
      })
      expect(write.status).toBe(403)
      expect(write.body?.code).toBe(403)
    } finally {
      await apiContext.dispose()
    }
  })
})

async function screenshot(page, name) {
  await expect(page.locator('.el-message-box:visible')).toHaveCount(0, { timeout: TIMEOUT })
  const output = process.env.OJ_TEST_OUTPUT_DIR
  if (output) {
    await page.screenshot({ path: join(output, name), fullPage: true })
  }
}
