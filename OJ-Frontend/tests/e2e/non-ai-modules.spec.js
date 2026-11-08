import { test, expect } from '@playwright/test'
import { join } from 'node:path'
import { API_BASE, WEB_BASE, login, injectSession } from './fixtures/e2e_auth.js'

test.skip(process.env.OJ_NON_AI_SMOKE_ENABLED !== '1', 'Requires an explicitly prepared disposable non-AI stack')
const evidence = process.env.OJ_TEST_OUTPUT_DIR
async function api(request, path, session, data, method) {
  const r = await request.fetch(API_BASE + path, {
    method: method || (data === undefined ? 'GET' : 'POST'), data,
    headers: { Authorization: 'Bearer ' + session.accessToken }
  })
  const body = await r.json()
  expect(body.code, path + ': ' + body.message).toBe(200)
  return body.data
}

test('objective exam: start, answer, grade and finish in browser', async ({ browser, request }) => {
  const admin = await login('admin')
  const id = await api(request, '/exam-paper/save', admin, {
    title: 'browser-exam-' + Date.now(), category: 'MOCK', durationMinutes: 10, totalScore: 10, isPublished: 1,
    questions: [{ questionType: 'JUDGE', statement: 'Browser synthetic true statement', answer: 'T', explanation: 'Synthetic explanation', score: 10 }]
  })
  const context = await browser.newContext()
  try {
    await injectSession(context, await login('student02'))
    const page = await context.newPage()
    await page.goto(WEB_BASE + '/training/' + id, { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '开始作答', exact: true }).click()
    await page.locator('.el-radio').filter({ hasText: '对' }).click()
    await page.getByRole('button', { name: '提交答案', exact: true }).click()
    await expect(page.getByText('✓ 正确', { exact: true })).toBeVisible()
    await expect(page.getByText('客观题得分：10 / 10')).toBeVisible()
    page.once('dialog', dialog => dialog.accept())
    await page.getByRole('button', { name: '交卷', exact: true }).click()
    await expect(page.getByText('已交卷', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '提交答案', exact: true })).toBeDisabled()
    await page.screenshot({ path: join(evidence, 'exam-browser.png'), fullPage: true })
  } finally { await context.close() }
})

test('report notification preference survives save and browser reload', async ({ browser, request }) => {
  const session = await login('student01')
  await api(request, '/user/preferences', session, { profilePublic: true, notifications: { reports: true } }, 'PUT')
  const context = await browser.newContext()
  try {
    await injectSession(context, session)
    const page = await context.newPage()
    await page.goto(WEB_BASE + '/user/setting', { waitUntil: 'networkidle' })
    await page.getByRole('tab', { name: '偏好设置' }).click()
    const toggle = page.locator('.el-form-item').filter({ hasText: '学习报告通知' }).getByRole('switch')
    await expect(toggle).toBeChecked()
    await page.locator('.el-form-item').filter({ hasText: '学习报告通知' }).locator('.el-switch').click()
    const response = page.waitForResponse(r => r.url().endsWith('/api/user/preferences') && r.request().method() === 'PUT')
    await page.getByRole('button', { name: '保存偏好', exact: true }).click()
    expect((await (await response).json()).code).toBe(200)
    expect((await api(request, '/user/preferences', session)).notifications.reports).toBe(false)
    await page.reload({ waitUntil: 'networkidle' })
    await page.getByRole('tab', { name: '偏好设置' }).click()
    await expect(toggle).not.toBeChecked()
    await page.screenshot({ path: join(evidence, 'notification-preference-browser.png'), fullPage: true })
  } finally { await context.close() }
})

test('community moderation notification is readable and can be marked read in browser', async ({ browser, request }) => {
  const author = await login('student01')
  const admin = await login('admin')
  const title = 'browser-moderation-' + Date.now()
  await api(request, '/post/add', author, { title, content: 'Synthetic moderation target', type: 0, privacy: 0, tags: '' })
  const posts = await api(request, '/post/my?size=100', author)
  const post = posts.records.find(p => p.title === title)
  expect(post).toBeTruthy()
  await api(request, '/community/report', await login('student02'), { postId: String(post.id), reason: 'OTHER' })
  const report = (await api(request, '/community/moderation/reports', admin)).find(r => r.postId === post.id)
  expect(report).toBeTruthy()
  await api(request, '/community/moderation/reports/' + report.id, admin, { hide: true })
  const notes = await api(request, '/notification/list?scope=all', author)
  const note = notes.find(n => n.targetId === post.id && n.type === 'MODERATION')
  expect(note?.isRead).toBe(false)
  const context = await browser.newContext()
  try {
    await injectSession(context, author)
    const page = await context.newPage()
    await page.goto(WEB_BASE + '/my/notifications', { waitUntil: 'networkidle' })
    const row = page.locator('.notification').filter({ hasText: '你的内容已被限制可见' }).first()
    await expect(row).toHaveClass(/unread/)
    await row.click()
    await expect.poll(async () => (await api(request, '/notification/list?scope=all', author)).find(n => n.id === note.id)?.isRead).toBe(true)
    await page.screenshot({ path: join(evidence, 'moderation-notification-browser.png'), fullPage: true })
  } finally { await context.close() }
})
