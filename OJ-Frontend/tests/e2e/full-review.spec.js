import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { injectSession } from './fixtures/e2e_auth.js'

const enabled = process.env.OJ_FULL_REVIEW_ENABLED === '1'
const evidence = process.env.OJ_TEST_OUTPUT_DIR
const session = () => JSON.parse(readFileSync(join(evidence, 'sessions.json'), 'utf8')).student01
const detail = id => ({ id, problemId: 1, problemTitle: `synthetic ${id}`, userId: 3, status: 2,
  code: `// synthetic submission ${id}`, language: 'cpp', score: 0, runTime: 1, runMemory: 1024, judgePhase: 'FINISHED' })
async function openReview(page, context, contentType, body, status = 200) {
  await injectSession(context, session())
  await page.route('**/api/submission/detail?*', route => route.fulfill({ json: { code: 200, data: detail(91001) } }))
  await page.route('**/api/ai/review', route => route.fulfill({ status, contentType, body }))
  await page.goto('/submission/91001')
  await page.getByRole('button', { name: '🤖 AI 复盘' }).click()
}

test.describe('full repository review regressions', () => {
  test.skip(!enabled, 'Requires the isolated full-review runtime')
  for (const [name, contentType, body, expected] of [
    ['JSON handshake', 'application/json', '{"code":403,"errorCode":"ACCESS_DENIED","message":"无权复盘合成提交"}', '无权复盘合成提交'],
    ['app-error', 'text/event-stream', 'data: partial synthetic review\n\nevent: app-error\ndata: {"code":503,"errorCode":"AI_UPSTREAM_FAILED","message":"合成复盘中断"}\n\n', '合成复盘中断'],
    ['early EOF', 'text/event-stream', 'data: partial synthetic review\n\n', 'AI 回复中断，请重试']
  ]) {
    test(`review ${name} preserves failure and incomplete state`, async ({ page, context }) => {
      await openReview(page, context, contentType, body)
      await expect(page.getByText('回复未完成，请重试')).toBeVisible()
      await expect(page.locator('.el-message--error')).toHaveCount(1)
      await expect(page.locator('.el-message--error')).toContainText(expected)
      await expect(page.locator('.review-content')).not.toContainText('"errorCode"')
    })
  }
  test('review done retains whitespace and completes successfully', async ({ page, context }) => {
    await openReview(page, context, 'text/event-stream', 'data:   completed synthetic review\n\nevent: done\ndata: \n\n')
    await expect(page.locator('.review-content')).toContainText('completed synthetic review')
    await expect(page.getByText('回复未完成，请重试')).toHaveCount(0)
    await expect(page.locator('.el-message--error')).toHaveCount(0)
  })
  test('failed contest submission request never falls back to all submissions', async ({ page, context }) => {
    await injectSession(context, session())
    let global = 0, primary = 0
    await page.route('**/api/submission/list?*', route => { global++; return route.fulfill({ json: { code: 200, data: { records: [{ id: 99000 }], total: 1 } } }) })
    await page.route('**/api/contest/1/submission/list?*', route => { primary++; return route.fulfill({ json: { code: 503, errorCode: 'DEPENDENCY_UNAVAILABLE', message: '合成比赛记录不可用' } }) })
    await page.goto('/contest/1/submission')
    await expect(page.locator('.el-message--error')).toHaveCount(1)
    await expect(page.locator('.el-message--error')).toContainText('合成比赛记录不可用')
    expect(global).toBe(0)
    expect(primary).toBe(1)
    await expect(page.getByText('99000')).toHaveCount(0)
  })
  test('private profile releases the loading overlay', async ({ page, context }) => {
    await injectSession(context, session())
    await page.route('**/api/user/info/2', route => route.fulfill({ json: { code: 403, errorCode: 'ACCESS_DENIED', message: '该用户未公开主页' } }))
    await page.goto('/user/2')
    await expect(page.locator('.profile-dashboard').getByText('该用户未公开主页')).toBeVisible()
    await expect(page.locator('.profile-dashboard > .el-loading-mask')).toHaveCount(0)
  })
  test('settings shared error produces one useful toast', async ({ page, context }) => {
    await injectSession(context, session())
    await page.route('**/api/user/info/' + session().user.id, route => route.fulfill({ json: { code: 503, errorCode: 'DEPENDENCY_UNAVAILABLE', message: '合成资料读取故障' } }))
    await page.goto('/user/setting')
    await expect(page.locator('.el-message--error')).toHaveCount(1)
    await expect(page.locator('.el-message--error')).toContainText('合成资料读取故障')
  })
  test('notification outage stays inline without an empty-success state or duplicate toast', async ({ page, context }) => {
    await injectSession(context, session())
    await page.route('**/api/notification/list?*', route => route.fulfill({ json: { code: 503, errorCode: 'DEPENDENCY_UNAVAILABLE', message: '合成通知读取故障' } }))
    await page.goto('/my/notifications')
    await expect(page.getByRole('alert')).toContainText('合成通知读取故障')
    await expect(page.getByText('暂时没有新通知')).toHaveCount(0)
    await expect(page.locator('.el-message--error')).toHaveCount(0)
  })

  test('failed notification scope switch clears old actionable notifications', async ({ page, context }) => {
    await injectSession(context, session())
    await page.route('**/api/notification/list?*', route => {
      const today = new URL(route.request().url()).searchParams.get('scope') === 'today'
      return route.fulfill({ json: today
        ? { code: 503, errorCode: 'DEPENDENCY_UNAVAILABLE', message: '合成今日通知故障' }
        : { code: 200, data: [{ id: 91001, title: 'synthetic old notification', type: 'LIKE', isRead: 0 }] } })
    })
    await page.goto('/my/notifications')
    await expect(page.getByText('synthetic old notification')).toBeVisible()
    await page.getByRole('button', { name: '今日任务', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('合成今日通知故障')
    await expect(page.getByText('synthetic old notification')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '全部标为已读' })).toBeDisabled()
  })
  test('saving list order disables further pinning and dragging', async ({ page, context }) => {
    const admin = JSON.parse(readFileSync(join(evidence, 'sessions.json'), 'utf8')).admin
    await injectSession(context, admin)
    const records = [{ id: 91001, title: 'synthetic list one' }, { id: 91002, title: 'synthetic list two' }]
    await page.route('**/api/problem-list/list?*', route => route.fulfill({ json: { code: 200, data: { records, total: 2 } } }))
    await page.route('**/api/problem-list/stats?*', route => route.fulfill({ json: { code: 200, data: {} } }))
    let pending, writes = 0
    await page.route('**/api/problem-list/update-sort', route => { pending = route; writes++ })
    await page.goto('/admin/problem-lists')
    await expect(page.getByText('synthetic list two')).toBeVisible()
    await page.locator('.pin-btn').click()
    await expect(page.getByText('正在保存排序…')).toBeVisible()
    await expect(page.locator('.pin-btn')).toHaveCount(0)
    await expect(page.locator('.drag-handle')).toHaveCount(0)
    await expect.poll(() => writes).toBe(1)
    await pending.fulfill({ json: { code: 200, data: null } })
    await expect(page.getByText('正在保存排序…')).toHaveCount(0)
    await expect(page.locator('.list-row').first()).toContainText('synthetic list two')
    await expect(page.locator('.pin-btn')).toHaveCount(1)
  })

})
