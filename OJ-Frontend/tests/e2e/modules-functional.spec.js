import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { API_BASE, WEB_BASE, login, injectSession } from './fixtures/e2e_auth.js'

// Real backend and disposable seeded database; no route stubs.
const evidence = process.env.OJ_TEST_OUTPUT_DIR
test.skip(process.env.OJ_MODULE_SMOKE_ENABLED !== '1', 'Prepare the disposable Slidev/Meilisearch module fixtures explicitly')
function fixture(name) {
  if (!evidence) throw new Error('OJ_TEST_OUTPUT_DIR must point to the disposable module run')
  return JSON.parse(readFileSync(join(evidence, name), 'utf8'))
}
async function api(request, path, session, data) {
  const r = await request.fetch(API_BASE + path, {
    method: data === undefined ? 'GET' : 'POST',
    data,
    headers: { Authorization: 'Bearer ' + session.accessToken }
  })
  const body = await r.json()
  expect(body.code, path + ': ' + body.message).toBe(200)
  return body.data
}

test('community: publish in editor, reader likes, collects and comments', async ({ browser, request }) => {
  const author = await login('student01')
  const reader = await login('student02')
  const authorContext = await browser.newContext()
  const readerContext = await browser.newContext()
  try {
    await injectSession(authorContext, author)
    await injectSession(readerContext, reader)
    const title = 'browser-community-' + Date.now()
    const page = await authorContext.newPage()
    await page.goto(WEB_BASE + '/discussion/create', { waitUntil: 'networkidle' })
    await page.getByPlaceholder('输入文章标题...').fill(title)
    await page.getByPlaceholder('在这里开始撰写正文（支持 Markdown、代码块与 LaTex）...').fill('# Browser module body\n\nSynthetic discussion')
    const created = page.waitForResponse(r => r.url().endsWith('/api/post/add') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '发布文章', exact: true }).click()
    expect((await (await created).json()).code).toBe(200)
    const posts = await api(request, '/post/my?size=100', author)
    const id = posts.records.find(p => p.title === title)?.id
    expect(id).toBeTruthy()
    const readerPage = await readerContext.newPage()
    await readerPage.goto(WEB_BASE + '/post/' + id, { waitUntil: 'networkidle' })
    await expect(readerPage.getByRole('heading', { name: title, exact: true })).toBeVisible()
    await readerPage.getByRole('button', { name: '点赞 0', exact: true }).click()
    await expect(readerPage.getByRole('button', { name: '点赞 1', exact: true })).toBeVisible()
    await readerPage.getByRole('button', { name: '收藏', exact: true }).click()
    await expect(readerPage.getByRole('button', { name: '已收藏', exact: true })).toBeVisible()
    await readerPage.getByPlaceholder('友善评论，理性交流...').fill('browser-comment-marker')
    await readerPage.getByRole('button', { name: '发表评论', exact: true }).click()
    await expect(readerPage.getByText('browser-comment-marker', { exact: true })).toBeVisible()
    const detail = await api(request, '/post/' + id, reader)
    expect(detail.commentCount).toBe(1)
    expect(detail.likeCount).toBe(1)
    expect(detail.favoriteCount).toBe(1)
    await readerPage.screenshot({ path: join(evidence, 'community-browser.png'), fullPage: true })
  } finally {
    await authorContext.close()
    await readerContext.close()
  }
})

test('courseware: real Slidev renders inside the class page and changes slide', async ({ browser }, testInfo) => {
  const f = fixture('courseware-final.json').browser_fixture
  const context = await browser.newContext()
  const errors = []
  const failedAssets = []
  try {
    await injectSession(context, await login('student01'))
    const page = await context.newPage()
    page.on('pageerror', e => errors.push(e.message))
    page.on('response', r => { if (r.url().includes('/coursewares/') && r.status() >= 400) failedAssets.push(r.status() + ' ' + r.url()) })
    await page.goto(`${WEB_BASE}/study/${f.classId}/${f.coursewareId}`, { waitUntil: 'networkidle' })
    const frame = page.frameLocator('iframe.slidev-iframe')
    await expect(frame.getByRole('heading', { name: 'Module Slide One', exact: true })).toBeVisible()
    await frame.getByRole('button', { name: 'Go to next slide', exact: true }).click()
    await expect(frame.getByRole('heading', { name: 'Module Slide Two', exact: true })).toBeVisible()
    expect(failedAssets).toEqual([])
    await testInfo.attach('browser-observations', { body: JSON.stringify({ errors, failedAssets }), contentType: 'application/json' })
    await page.screenshot({ path: join(evidence, 'courseware-browser.png'), fullPage: true })
  } finally {
    await context.close()
  }
})

test('search: problem page uses the real search endpoint', async ({ page }) => {
  const f = fixture('search-after.json').browser_fixture
  await page.goto(WEB_BASE + '/problem', { waitUntil: 'networkidle' })
  const response = page.waitForResponse(r => r.url().includes('/api/problem/search?') && r.url().includes(encodeURIComponent(f.keyword)))
  await page.getByPlaceholder('算法、标题或题目编号').fill(f.keyword)
  await page.getByPlaceholder('算法、标题或题目编号').press('Enter')
  const body = await (await response).json()
  expect(body.code).toBe(200)
  await expect(page.getByText(f.title, { exact: true })).toBeVisible()
  await expect(page.locator('.table-card-wrapper .el-loading-mask')).toBeHidden()
  await expect(page.getByText(new RegExp(`共\\s*${body.data.total}\\s*道题目`))).toBeVisible()
  await page.screenshot({ path: join(evidence, 'search-browser.png'), fullPage: true })
})
