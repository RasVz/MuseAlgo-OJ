// Runs only through ReviewRecordsIT against its synthetic, disposable MySQL fixture.
import { chromium, expect } from '@playwright/test'
import process from 'node:process'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const port = process.env.OJ_REVIEW_HTTP_PORT
if (!port || !process.env.OJ_REVIEW_WEB) throw new Error('Requires the isolated ReviewRecordsIT runner')
const evidence = process.env.OJ_TEST_OUTPUT_DIR
if (evidence) mkdirSync(evidence, { recursive: true })
const browser = await chromium.launch({ headless: true, args: ['--disable-gpu', '--disable-dev-shm-usage'] })
const calls = []
let activePage
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 850 } })
  activePage = page
  const errors = []
  page.on('pageerror', error => { errors.push(error.message); console.log('PAGE_ERROR', error.message) })
  page.on('console', message => { if (message.type() === 'error') console.log('BROWSER_ERROR', message.text()) })
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-review-browser')
    localStorage.setItem('user', JSON.stringify({ id: 7, nickname: '测试学习者', username: 'synthetic', role: 1 }))
  })
  await page.route('**/api/**', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (url.pathname.startsWith('/api/ai/reviews')) {
      if (req.method() === 'POST') calls.push(req.postDataJSON())
      const response = await route.fetch({ url: `http://127.0.0.1:${port}${url.pathname.replace('/api', '')}${url.search}`,
        headers: { ...req.headers(), 'X-Review-Test-User': '7' } })
      return route.fulfill({ response })
    }
    let data = { records: [], total: 0 }
    if (url.pathname === '/api/submission/detail') data = { id: 1, userId: 7, problemId: 100, problemTitle: '合成题目',
      status: 1, score: 100, runTime: 1, runMemory: 1024, language: 'cpp', code: 'int main(){}', createTime: '2026-11-15T01:00:00' }
    if (url.pathname.includes('/user/info')) data = { id: 7, nickname: '测试学习者', role: 1 }
    if (url.pathname.includes('count')) data = 0
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: '成功', data }) })
  })
  await page.goto(`${process.env.OJ_REVIEW_WEB}/submission/1`)
  await page.getByRole('button', { name: /AI 复盘/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  expect(calls).toHaveLength(0)
  await page.getByRole('button', { name: /AI 复盘/ }).click()
  await page.getByRole('button', { name: '确认并开始' }).click()
  await expect(page).toHaveURL(/ai\/reviews\/\d+/)
  await expect(page.locator('.review-answer')).toContainText('分析结果', { timeout: 15000 })
  const originalUrl = page.url()
  await page.reload()
  await expect(page.locator('.review-answer')).toContainText('分析结果')
  expect(calls).toHaveLength(1)
  await page.getByRole('button', { name: '重新分析', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('历史提交 #1')
  await page.getByRole('button', { name: '确认并开始' }).click()
  await expect(page).not.toHaveURL(originalUrl)
  await expect(page.locator('.review-answer')).toContainText('分析结果', { timeout: 15000 })
  expect(calls[1].relationType).toBe('REANALYZE')
  expect(calls[1].parentReviewId == null).toBe(true)
  await expect(page).not.toHaveURL(originalUrl)
  await page.getByRole('button', { name: '继续追问', exact: true }).click()
  await page.getByRole('textbox', { name: '继续追问', exact: true }).fill('解释复杂度')
  const reanalyzedUrl = page.url()
  await page.getByRole('button', { name: '发送追问', exact: true }).click()
  await expect(page).not.toHaveURL(reanalyzedUrl)
  await expect(page.locator('.review-answer')).toContainText('分析结果', { timeout: 15000 })
  expect(calls).toHaveLength(3)
  expect(calls[2].relationType).toBe('FOLLOW_UP')
  for (const width of [1280, 1024]) {
    await page.setViewportSize({ width, height: 850 })
    for (const theme of ['light', 'dark']) {
      await page.evaluate(theme => document.documentElement.classList.toggle('dark', theme === 'dark'), theme)
      await expect(page.locator('h1')).toContainText('AI 代码复盘')
      if (evidence) await page.screenshot({ path: join(evidence, `detail-real-${width}-${theme}.png`), fullPage: true })
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    }
  }
  await page.locator('.user-dropdown-link').click()
  await expect(page.getByRole('menuitem', { name: '通知中心', exact: true })).toHaveCount(0)
  await page.getByRole('menuitem', { name: '复盘记录', exact: true }).click()
  await expect(page.getByText('共 3 条复盘记录')).toBeVisible()
  await expect(page.locator('button[title="通知中心"]')).toHaveCount(1)
  await page.goto(`${process.env.OJ_REVIEW_WEB}/my/ai-reviews?keyword=P100&status=SUCCEEDED&pageSize=10`)
  await expect(page.getByText('共 3 条复盘记录')).toBeVisible()
  await page.getByRole('button', { name: '查看', exact: true }).first().click()
  await expect(page.locator('h1')).toContainText('AI 代码复盘')
  await page.getByRole('button', { name: '‹ 复盘记录', exact: true }).click()
  await expect(page).toHaveURL(/keyword=P100/)
  await expect(page).toHaveURL(/status=SUCCEEDED/)
  await expect(page).toHaveURL(/pageSize=10/)
  await expect(page.getByText('共 3 条复盘记录')).toBeVisible()
  if (evidence) await page.screenshot({ path: join(evidence, 'records-real-1024-dark.png'), fullPage: true })
  expect(errors).toEqual([])
  console.log('Real HTTP/MySQL browser flow passed: cancel confirmation, create, reload, reanalyze, followup, records, bell, desktop themes.')
} catch (error) {
  if (activePage) {
    console.log(await activePage.locator('body').innerText())
    if (evidence) await activePage.screenshot({ path: join(evidence, 'real-browser-failure.png'), fullPage: true })
  }
  throw error
} finally { await browser.close() }
