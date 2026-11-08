import { test, expect } from './fixtures/e2e_auth.js'

test('学生不能直接进入学习周报中心，教师和管理员仍可进入', async ({ studentPage, teacherPage, adminPage }) => {
  await studentPage.goto('/admin/reports')
  await expect(studentPage).not.toHaveURL(/\/admin\/reports/)
  await expect(studentPage.locator('.page-title').filter({ hasText: '学习周报' })).toHaveCount(0)
  for (const page of [teacherPage, adminPage]) {
    await page.goto('/admin/reports')
    await expect(page).toHaveURL(/\/admin\/reports/)
    await expect(page.locator('.page-title').filter({ hasText: '学习周报' })).toBeVisible()
  }
})

test('历史列表快速翻页时，较晚返回的旧页不能覆盖当前页', async ({ adminPage }) => {
  let releaseOld
  let oldRequestStarted
  const oldStarted = new Promise(resolve => { oldRequestStarted = resolve })
  const release = new Promise(resolve => { releaseOld = resolve })
  let oldResponseFinished
  const oldFinished = new Promise(resolve => { oldResponseFinished = resolve })
  await adminPage.route(url => url.pathname === '/api/profile/report/jobs', async route => {
    const page = Number(new URL(route.request().url()).searchParams.get('page') || '1')
    if (page === 2) {
      oldRequestStarted()
      await release
    }
    await route.fulfill({ json: { code: 200, message: 'ok', data: {
      total: 30, records: [{ id: 9000 + page, studentId: 9000 + page,
        studentName: `第${page}页合成数据`, status: 'GENERATED', attempts: 1,
        periodStart: '2026-01-05', periodEnd: '2026-01-11' }]
    } } })
    if (page === 2) oldResponseFinished()
  })
  try {
    await adminPage.goto('/admin/reports')
    await expect(adminPage.getByText('第1页合成数据')).toBeVisible({ timeout: 20000 })
    const next = adminPage.locator('.pager .btn-next')
    await next.click()
    await oldStarted
    await next.click()
    await expect(adminPage.getByText('第3页合成数据')).toBeVisible()
    const oldResponse = adminPage.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/profile/report/jobs' && url.searchParams.get('page') === '2'
    })
    releaseOld()
    await (await oldResponse).finished()
    await oldFinished
    await adminPage.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    await expect(adminPage.getByText('第3页合成数据')).toBeVisible()
    await expect(adminPage.getByText('第2页合成数据')).toHaveCount(0)
  } finally {
    releaseOld()
  }
})
