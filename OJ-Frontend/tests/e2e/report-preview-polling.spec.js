import { test, expect } from './fixtures/e2e_prepare.js'

async function openPendingReport(page, fixture, responseCode) {
  await page.goto('/admin/class/' + fixture.class.id)
  const row = page.locator('.el-table__row').filter({ hasText: fixture.student.username }).first()
  await expect(row).toBeVisible({ timeout: 20000 })
  const id = fixture.report.jobId
  let count = 0
  await page.route('**/api/profile/report/jobs**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.endsWith('/profile/report/jobs')) {
      await route.fulfill({ status: 200, json: { code: 200, data: { id, status: 'PENDING' } } })
    } else if (request.method() === 'GET' && path.endsWith('/profile/report/jobs/' + id)) {
      count += 1
      await route.fulfill({ status: 409, json: { code: 409, message: responseCode } })
    } else {
      await route.continue()
    }
  })
  await page.clock.install()
  await row.getByRole('button', { name: '报告', exact: true }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '学习周报' }).last()
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('正在排队')
  return { dialog, count: () => count }
}

test('REPORT_NOT_READY 使用递增退避，达到上限后允许手动继续', async ({ teacherPage, fixture }) => {
  const { dialog, count } = await openPendingReport(teacherPage, fixture, 'REPORT_NOT_READY')
  for (const [i, delay] of [2000, 2000, 4000, 8000, 16000, 32000].entries()) {
    await teacherPage.clock.fastForward(delay)
    await expect.poll(count).toBe(i + 1)
  }
  await expect(dialog).toContainText('自动刷新已暂停')
  await teacherPage.clock.fastForward(120000)
  expect(count()).toBe(6)
  await dialog.getByRole('button', { name: '继续刷新' }).click()
  await expect.poll(count).toBe(7)
})

test('其他 409 停止自动重试', async ({ teacherPage, fixture }) => {
  const { dialog, count } = await openPendingReport(teacherPage, fixture, 'OTHER_CONFLICT')
  await teacherPage.clock.fastForward(2000)
  await expect.poll(count).toBe(1)
  await expect(dialog).toContainText('OTHER_CONFLICT')
  await teacherPage.clock.fastForward(120000)
  expect(count()).toBe(1)
})

test('离开班级页面时取消在途请求，迟到响应不产生新轮询', async ({ teacherPage, fixture }) => {
  const { count } = await openPendingReport(teacherPage, fixture, 'REPORT_NOT_READY')
  let release
  const blocked = new Promise(resolve => { release = resolve })
  let entered
  const intercepted = new Promise(resolve => { entered = resolve })
  let pendingCalls = 0
  await teacherPage.route('**/api/profile/report/jobs/' + fixture.report.jobId, async route => {
    pendingCalls += 1
    entered()
    await blocked
    try {
      await route.fulfill({ status: 200, json: { code: 200, data: { id: fixture.report.jobId, status: 'PENDING' } } })
    } catch {
      // Navigation may have already canceled the request.
    }
  })
  await teacherPage.clock.fastForward(2000)
  await intercepted
  await teacherPage.goto('/admin')
  release()
  await teacherPage.clock.fastForward(120000)
  expect(pendingCalls).toBe(1)
  expect(count()).toBe(0)
})
