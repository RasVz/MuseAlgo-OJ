import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { test, expect } from './fixtures/e2e_prepare.js'

test('报告预览、打印与 PNG 在隔离环境保留图表并阻止读取父页面', async ({ adminPage, fixture }) => {
  const jobId = fixture.report.jobId
  await adminPage.addInitScript(() => {
    window.__reportAttack = false
    window.__reportPrintCalled = false
    window.addEventListener('message', event => {
      if (event.data?.isolationPrintCalled) window.__reportPrintCalled = true
    })
  })
  await adminPage.route('**/api/profile/report/jobs/' + jobId, async route => {
    const response = await route.fetch()
    const body = await response.json()
    if (body.data?.html) {
      const injected = `<img src="https://invalid.example/x" onerror="try{parent.localStorage.getItem('accessToken');parent.__reportAttack=true}catch(e){}"><a id="unsafe-link" href="javascript:try{parent.__reportAttack=true}catch(e){}">测试链接</a><script>try{parent.localStorage.getItem('accessToken');parent.__reportAttack=true}catch(e){};window.addEventListener('beforeprint',function(){parent.postMessage({isolationPrintCalled:true},'*')})</script>`
      body.data.html = body.data.html.replace('</body>', injected + '</body>')
    }
    await route.fulfill({ response, json: body })
  })
  await adminPage.goto('/admin/reports?jobId=' + jobId)
  const reader = adminPage.locator('.reader-page')
  await expect(reader).toBeVisible({ timeout: 20000 })
  // 方案 B 的阅读页默认可能落在结构化视图；显式切到原始版保证权威 iframe 可见。
  await reader.getByText('A4 原始版', { exact: true }).click()
  const frame = reader.locator('iframe.report-iframe')
  await expect(frame).toHaveAttribute('sandbox', 'allow-scripts allow-modals')
  await expect(frame.contentFrame().locator('#__charts_ready__')).toHaveCSS('display', 'block', { timeout: 20000 })
  await expect(frame.contentFrame().locator('#radarChart svg')).toHaveCount(1)
  await frame.contentFrame().locator('#unsafe-link').click()
  expect(await adminPage.evaluate(() => window.__reportAttack)).toBe(false)
  await reader.getByRole('button', { name: '打印' }).click()
  await expect.poll(() => adminPage.evaluate(() => window.__reportPrintCalled)).toBe(true)
  const downloadPromise = adminPage.waitForEvent('download', { timeout: 30000 })
  await reader.getByRole('button', { name: '导出 PNG' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toMatch(/\.png$/)
  if (process.env.OJ_TEST_OUTPUT_DIR) await download.saveAs(join(process.env.OJ_TEST_OUTPUT_DIR, 'report-synthetic.png'))
  const bytes = await readFile(await download.path())
  expect(bytes.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a')
  expect(bytes.length).toBeGreaterThan(10000)
  const imageStats = await adminPage.evaluate(async (base64) => {
    const image = new Image()
    image.src = 'data:image/png;base64,' + base64
    await image.decode()
    const canvas = document.createElement('canvas')
    canvas.width = image.width; canvas.height = image.height
    const context = canvas.getContext('2d'); context.drawImage(image, 0, 0)
    const pixels = context.getImageData(0, Math.floor(image.height * 0.2), image.width, Math.floor(image.height * 0.6)).data
    let colored = 0
    for (let i = 0; i < pixels.length; i += 4) if (pixels[i] < 130 && pixels[i + 1] < 180 && pixels[i + 2] > 140) colored += 1
    return { width: image.width, height: image.height, colored }
  }, bytes.toString('base64'))
  expect(imageStats.width).toBe(1600)
  expect(imageStats.height).toBeGreaterThan(500)
  expect(imageStats.colored).toBeGreaterThan(100)
  expect(await adminPage.evaluate(() => window.__reportAttack)).toBe(false)
})

test('班级报告预览隔离不可信报告脚本', async ({ teacherPage, fixture }) => {
  const jobId = fixture.report.jobId
  await teacherPage.addInitScript(() => { window.__reportAttack = false })
  await teacherPage.goto('/admin/class/' + fixture.class.id)
  const row = teacherPage.locator('.el-table__row').filter({ hasText: fixture.student.username }).first()
  await expect(row).toBeVisible({ timeout: 20000 })
  await teacherPage.route('**/api/profile/report/jobs**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.endsWith('/profile/report/jobs')) {
      await route.fulfill({ status: 200, json: { code: 200, data: { id: jobId, status: 'GENERATED' } } })
    } else if (request.method() === 'GET' && path.endsWith('/profile/report/jobs/' + jobId)) {
      const response = await route.fetch()
      const body = await response.json()
      body.data.html = body.data.html.replace('</body>', '<script>try{parent.localStorage.getItem("accessToken");parent.__reportAttack=true}catch(e){}</script></body>')
      await route.fulfill({ response, json: body })
    } else await route.continue()
  })
  await row.getByRole('button', { name: '报告', exact: true }).click()
  const frame = teacherPage.locator('iframe[title="学生周报预览"]')
  await expect(frame).toBeVisible({ timeout: 20000 })
  await expect(frame).toHaveAttribute('sandbox', 'allow-scripts')
  await expect(frame.contentFrame().locator('#__charts_ready__')).toHaveCSS('display', 'block')
  // 班级页弹窗保留快速预览，同时提供通往全屏阅读页的入口（完整正文在独立页面）。
  await expect(teacherPage.getByRole('button', { name: '打开完整报告' })).toBeVisible()
  expect(await teacherPage.evaluate(() => window.__reportAttack)).toBe(false)
})
