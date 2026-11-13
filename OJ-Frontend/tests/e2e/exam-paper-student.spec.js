import { test, expect } from '@playwright/test'
import { API_BASE, WEB_BASE, injectSession, login } from './fixtures/e2e_auth.js'

test.skip(process.env.OJ_NON_AI_SMOKE_ENABLED !== '1', 'Requires an explicitly prepared disposable non-AI stack')

async function api(request, path, session, data, method) {
  const response = await request.fetch(API_BASE + path, {
    method: method || (data === undefined ? 'GET' : 'POST'),
    data,
    headers: { Authorization: `Bearer ${session.accessToken}` }
  })
  const body = await response.json()
  expect(body.code, `${path}: ${body.message}`).toBe(200)
  return body.data
}

test('training exam preserves a local draft on refresh and guides answer progress', async ({ browser, request }) => {
  const admin = await login(process.env.OJ_EXAM_TEST_ADMIN_USER || 'admin', process.env.OJ_EXAM_TEST_PASSWORD || '123456')
  const paperId = await api(request, '/exam-paper/save', admin, {
    title: `student-exam-${Date.now()}`,
    category: 'MOCK',
    durationMinutes: 10,
    totalScore: 10,
    isPublished: 1,
    questions: [
      { questionType: 'JUDGE', statement: 'Synthetic statement one', answer: 'T', explanation: 'Synthetic explanation', score: 5 },
      { questionType: 'JUDGE', statement: 'Synthetic statement two', answer: 'F', score: 5 }
    ]
  })

  const context = await browser.newContext()
  try {
    await injectSession(context, await login(process.env.OJ_EXAM_TEST_STUDENT_USER || 'student02', process.env.OJ_EXAM_TEST_PASSWORD || '123456'))
    const page = await context.newPage()
    await page.goto(`${WEB_BASE}/training/${paperId}`, { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '开始作答', exact: true }).click()

    await page.locator('.boolean-group').nth(0).getByText('对', { exact: true }).click()
    await expect(page.getByRole('button', { name: '第 1 题，已作答' })).toBeVisible()
    await expect(page.locator('.progress-copy')).toContainText('1 / 2')
    await expect(page.getByText(/本设备暂存不计分/).first()).toBeVisible()

    await page.reload({ waitUntil: 'networkidle' })
    await expect(page.locator('.boolean-group').nth(0).locator('.el-radio').filter({ hasText: '对' })).toHaveClass(/is-checked/)
    await expect(page.getByRole('button', { name: '第 1 题，已作答' })).toBeVisible()

    if (process.env.OJ_TEST_OUTPUT_DIR) await page.screenshot({ path: process.env.OJ_TEST_OUTPUT_DIR + '/real-exam-running.png', fullPage: true })
    await page.getByRole('button', { name: '第 2 题，未作答' }).click()
    await page.locator('.boolean-group').nth(1).getByText('错', { exact: true }).click()
    await page.getByRole('button', { name: '提交客观题', exact: true }).click()
    await expect(page.getByText('最近判分 10 / 10 分')).toBeVisible()

    await page.getByRole('button', { name: '交卷', exact: true }).click()
    await page.getByRole('button', { name: '确认交卷', exact: true }).click()
    await expect(page.getByText('本次作答已交卷')).toBeVisible()
  } finally {
    await context.close()
  }
})
