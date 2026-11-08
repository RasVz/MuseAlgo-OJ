// UI 交互验收使用确定性的 API 替身；真实 MySQL/权限/并发由后端 AiServiceAdminIT 覆盖。
// 若需连真实隔离后端，请运行 admin-ai-services.spec.js 并设置 AI_E2E_REAL=1 与测试配置。
import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const SERVICE_NAME = 'UI 教学主服务'
const UPSTREAM = 'https://api.example.com/v1/chat/completions'
// 本机内存紧张时整页渲染可达数秒，网络往返后的断言统一放宽到 20s（断言内容不变）。
const SLOW = { timeout: 20000 }

// 全流程含 6 次服务端往返与 3 次路由切换，慢机上 60s 默认超时不够。
test.describe.configure({ timeout: 180000 })

async function installAdminAuth(page, role = 3) {
  await page.addInitScript(({ roleValue }) => {
    localStorage.setItem('accessToken', 'ui-test-token')
    localStorage.setItem('refreshToken', 'ui-test-refresh')
    localStorage.setItem('user', JSON.stringify({
      id: 1,
      username: roleValue === 3 ? 'admin' : 'teacher',
      nickname: roleValue === 3 ? '管理员' : '教师',
      role: roleValue
    }))
  }, { roleValue: role })
}

async function installMockApi(page) {
  const now = new Date().toISOString()
  let services = []
  let nextId = 1
  let runtime = {
    mode: 'LEGACY',
    currentServiceId: null,
    currentServiceName: null,
    currentServiceModel: null,
    currentServiceVersion: null,
    currentServiceReady: false,
    runtimeVersion: 1,
    legacyModel: 'legacy-model'
  }

  const report = (service) => ({
    serviceId: service.id,
    configVersion: service.version,
    passed: true,
    testedAt: now,
    totalLatencyMs: 42,
    checks: [
      { kind: 'TEXT', success: true, skipped: false, latencyMs: 10, errorType: null, message: '普通文本调用通过' },
      { kind: 'STREAM', success: true, skipped: false, latencyMs: 12, errorType: null, message: '流式文本调用通过' },
      { kind: 'JSON', success: true, skipped: false, latencyMs: 20, errorType: null, message: 'JSON 输出通过' }
    ]
  })

  const runtimeVo = () => ({
    ...runtime,
    currentServiceId: runtime.mode === 'MANAGED' ? runtime.currentServiceId : null,
    currentServiceName: runtime.mode === 'MANAGED' ? runtime.currentServiceName : null,
    currentServiceModel: runtime.mode === 'MANAGED' ? runtime.currentServiceModel : null,
    currentServiceVersion: runtime.mode === 'MANAGED' ? runtime.currentServiceVersion : null
  })

  const activate = (service) => {
    services = services.map((item) => ({
      ...item,
      usageStatus: item.id === service.id ? 'CURRENT' : (item.enabled ? 'AVAILABLE' : 'DISABLED')
    }))
    runtime = {
      ...runtime,
      mode: 'MANAGED',
      currentServiceId: service.id,
      currentServiceName: service.name,
      currentServiceModel: service.model,
      currentServiceVersion: service.version,
      currentServiceReady: true,
      runtimeVersion: runtime.runtimeVersion + 1
    }
  }

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const json = (data) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: '操作成功', data })
    })
    const body = () => {
      try {
        return JSON.parse(request.postData() || '{}')
      } catch {
        return {}
      }
    }

    if (path === '/api/admin/ai/services' && method === 'GET') {
      return json(services)
    }
    if (path === '/api/admin/ai/services' && method === 'POST') {
      const payload = body()
      const service = {
        id: nextId++,
        name: payload.name,
        protocol: 'CHAT_COMPLETIONS',
        apiUrl: payload.apiUrl,
        model: payload.model,
        hasApiKey: Boolean(payload.apiKey),
        enabled: true,
        usageStatus: 'AVAILABLE',
        jsonMode: payload.jsonMode !== false,
        streamIncludeUsage: payload.streamIncludeUsage !== false,
        maxTokens: payload.maxTokens || 8192,
        remark: payload.remark || '',
        version: 1,
        lastTestVersion: null,
        lastTestAt: null,
        lastTestStatus: null,
        testState: 'UNTESTED',
        createdAt: now,
        updatedAt: now
      }
      services.push(service)
      return json(service)
    }

    const serviceMatch = path.match(/^\/api\/admin\/ai\/services\/(\d+)(?:\/(test|activate|enable|disable))?$/)
    if (serviceMatch) {
      const id = Number(serviceMatch[1])
      const action = serviceMatch[2]
      const service = services.find((item) => item.id === id)
      if (!service) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 404, message: 'AI 服务不存在', data: null })
        })
      }
      if (!action && method === 'PUT') {
        const payload = body()
        Object.assign(service, {
          name: payload.name,
          apiUrl: payload.apiUrl,
          model: payload.model,
          remark: payload.remark || '',
          jsonMode: payload.jsonMode !== false,
          streamIncludeUsage: payload.streamIncludeUsage !== false,
          maxTokens: payload.maxTokens || 8192,
          version: service.version + 1,
          lastTestVersion: service.version + 1,
          lastTestAt: now,
          lastTestStatus: 'PASSED',
          testState: 'PASSED',
          updatedAt: now
        })
        if (runtime.mode === 'MANAGED' && runtime.currentServiceId === id) {
          runtime.currentServiceModel = service.model
          runtime.currentServiceVersion = service.version
          runtime.runtimeVersion += 1
        }
        return json({ service, testReport: report(service), applied: true })
      }
      if (action === 'test' && method === 'POST') {
        service.lastTestVersion = service.version
        service.lastTestAt = now
        service.lastTestStatus = 'PASSED'
        service.testState = 'PASSED'
        return json(report(service))
      }
      if (action === 'activate' && method === 'POST') {
        activate(service)
        return json(runtimeVo())
      }
      if (action === 'enable' && method === 'POST') {
        service.enabled = true
        service.usageStatus = runtime.currentServiceId === id && runtime.mode === 'MANAGED' ? 'CURRENT' : 'AVAILABLE'
        return json(service)
      }
      if (action === 'disable' && method === 'POST') {
        service.enabled = false
        service.usageStatus = 'DISABLED'
        return json(service)
      }
      if (!action && method === 'DELETE') {
        services = services.filter((item) => item.id !== id)
        return json('服务已删除')
      }
    }

    if (path === '/api/admin/ai/runtime' && method === 'GET') {
      return json(runtimeVo())
    }
    if (path === '/api/admin/ai/runtime' && method === 'PUT') {
      const payload = body()
      runtime = {
        ...runtime,
        mode: payload.mode,
        currentServiceId: null,
        currentServiceName: null,
        currentServiceModel: null,
        currentServiceVersion: null,
        currentServiceReady: false,
        runtimeVersion: runtime.runtimeVersion + 1
      }
      services = services.map((item) => ({ ...item, usageStatus: item.enabled ? 'AVAILABLE' : 'DISABLED' }))
      return json(runtimeVo())
    }

    if (path === '/api/admin/ai/config') {
      return json({
        quotaEnabled: true,
        dailyTokens: 30000,
        exemptTeacherAdmin: true,
        contestGuardEnabled: true,
        contestGuardCacheSeconds: 60,
        model: runtime.currentServiceModel || runtime.legacyModel,
        maxTokens: 8192,
        jsonMode: true,
        runtimeMode: runtime.mode,
        serviceManagementPath: '/admin/ai-services'
      })
    }
    if (path === '/api/admin/ai/overview') {
      return json({ calls: 3, tokens: 300, failRate: 0, costNote: '费用以服务商账单为准' })
    }
    if (path === '/api/admin/ai/trend') {
      return json([])
    }
    if (path === '/api/admin/ai/logs') {
      return json({ records: [] })
    }
    if (path === '/api/admin/ai/quota-ranking') {
      return json([])
    }
    return json(null)
  })
}

async function screenshot(page, name) {
  if (process.env.OJ_TEST_OUTPUT_DIR) {
    // 等确认框关闭动画结束再截图，避免把正在消失的弹窗拍进证据图。
    await page.locator('.el-message-box').first().waitFor({ state: 'detached', timeout: 5000 }).catch(() => {})
    // 点击行内操作会让表格横向滚动到右侧，截图前复位，保证能看到服务名称列。
    await page.evaluate(() => {
      document.querySelectorAll('.el-scrollbar__wrap, .el-table__body-wrapper')
        .forEach((el) => { el.scrollLeft = 0 })
    })
    await page.waitForTimeout(300)
    await page.screenshot({ path: join(process.env.OJ_TEST_OUTPUT_DIR, name), fullPage: true })
  }
}

test('管理员完成添加、测试、切换、改 Key 与停用流程（UI 替身）', async ({ page }) => {
  await installAdminAuth(page, 3)
  await installMockApi(page)

  await page.goto('/admin')
  // 菜单入口存在即验收点；导航本身用确定性 URL，避免慢机上点击与挂载竞态。
  await expect(page.getByRole('menuitem', { name: 'AI 服务', exact: true })).toBeVisible(SLOW)
  await page.goto('/admin/ai-services')
  await expect(page).toHaveURL(/\/admin\/ai-services$/, { timeout: 15000 })
  await expect(page.locator('.page-title').filter({ hasText: 'AI 服务' })).toBeVisible(SLOW)

  await page.getByRole('button', { name: '添加服务' }).click()
  const drawer = page.locator('.el-drawer').filter({ hasText: '添加 AI 服务' })
  await expect(drawer).toBeVisible(SLOW)
  await drawer.getByPlaceholder('例如：教学主服务').fill(SERVICE_NAME)
  await drawer.getByPlaceholder('https://api.example.com/v1/chat/completions').fill(UPSTREAM)
  await drawer.getByPlaceholder('服务商要求的精确模型 ID').fill('ui-model')
  await drawer.getByPlaceholder('输入服务商 API Key').fill('ui-test-key')
  await drawer.getByRole('button', { name: '保存服务', exact: true }).click()
  await expect(drawer).toBeHidden(SLOW)

  const row = page.locator('.el-table__row').filter({ hasText: SERVICE_NAME }).first()
  await expect(row).toBeVisible(SLOW)
  await expect(row).toContainText('未测试', SLOW)
  await expect(row).toContainText('已配置', SLOW)

  await row.getByRole('button', { name: '测试连接' }).click()
  const report = page.locator('.el-dialog').filter({ hasText: '连接测试结果' }).first()
  await expect(report).toBeVisible(SLOW)
  await expect(report).toContainText('全部必测项通过', SLOW)
  await expect(report).toContainText('普通文本', SLOW)
  await expect(report).toContainText('流式文本', SLOW)
  await expect(report).toContainText('JSON 输出', SLOW)
  await report.getByRole('button', { name: '知道了' }).click()
  await expect(report).toBeHidden(SLOW)
  await expect(row).toContainText('通过', SLOW)

  await row.getByRole('button', { name: '设为当前' }).click()
  await page.getByRole('button', { name: '确认切换' }).click()
  await expect(page.locator('.runtime-value')).toContainText('后台服务', SLOW)
  await expect(page.locator('.runtime-value')).toContainText(SERVICE_NAME, SLOW)
  await expect(row).toContainText('当前使用', SLOW)
  await screenshot(page, 'ai-services-ui-managed.png')

  await page.goto('/admin/ai')
  await expect(page).toHaveURL(/\/admin\/ai$/, { timeout: 15000 })
  await expect(page.getByText('当前由后台服务提供 AI')).toBeVisible(SLOW)
  const modelInput = page.locator('.el-form-item').filter({ hasText: '模型' }).locator('input').first()
  await expect(modelInput).toBeDisabled()
  await page.goto('/admin/ai-services')
  await expect(page).toHaveURL(/\/admin\/ai-services$/, { timeout: 15000 })
  await expect(page.locator('.page-title').filter({ hasText: 'AI 服务' })).toBeVisible(SLOW)

  const currentRow = page.locator('.el-table__row').filter({ hasText: SERVICE_NAME }).first()
  await currentRow.getByRole('button', { name: '编辑', exact: true }).click()
  const editDrawer = page.locator('.el-drawer').filter({ hasText: '编辑 AI 服务' })
  await expect(editDrawer).toBeVisible(SLOW)
  await expect(editDrawer.getByPlaceholder('留空表示保留现有密钥')).toHaveValue('')
  await editDrawer.getByPlaceholder('留空表示保留现有密钥').fill('ui-test-key-rotated')
  await editDrawer.getByPlaceholder('服务商要求的精确模型 ID').fill('ui-model-v2')
  await editDrawer.getByRole('button', { name: '测试并保存应用', exact: true }).click()
  await expect(page.getByText('候选配置已测试通过并应用')).toBeVisible(SLOW)
  // 保存当前服务会同时弹出候选测试报告，需关闭后才能继续操作页面。
  await expect(report).toBeVisible(SLOW)
  await report.getByRole('button', { name: '知道了' }).click()
  await expect(report).toBeHidden(SLOW)
  await expect(editDrawer).toBeHidden(SLOW)
  await expect(currentRow).toContainText('ui-model-v2', SLOW)
  await expect(currentRow).toContainText('v2', SLOW)

  await page.getByRole('button', { name: '停用 AI', exact: true }).click()
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(page.locator('.runtime-value')).toContainText('已停用', SLOW)
  await expect(page.getByText('不发送任何模型请求')).toBeVisible(SLOW)
  await screenshot(page, 'ai-services-ui-off.png')

  await page.getByRole('button', { name: '恢复旧环境配置' }).click()
  await page.getByRole('button', { name: '确认恢复' }).click()
  await expect(page.locator('.runtime-value')).toContainText('旧环境', SLOW)
})

test('教师直接输入 AI 服务地址被前端守卫挡回管理中心', async ({ page }) => {
  await installAdminAuth(page, 2)
  await installMockApi(page)
  await page.goto('/admin/ai-services')
  await expect(page).toHaveURL(/\/admin$/, { timeout: 15000 })
})
