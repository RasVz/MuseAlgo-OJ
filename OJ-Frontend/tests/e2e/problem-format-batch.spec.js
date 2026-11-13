import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// 批量优化的任务面板：创建选项、进度、逐项状态。
// 单题审阅已经并入编辑页的题面工作区，这里只覆盖批量链路本身。
test.describe.configure({ timeout: 90000 })

const EVIDENCE = process.env.OJ_TEST_OUTPUT_DIR
  ? join(process.env.OJ_TEST_OUTPUT_DIR, 'problem-format-batch')
  : join(process.cwd(), '..', '..', 'outputs', 'problem-format-workbench-20261113')
mkdirSync(EVIDENCE, { recursive: true })

const problems = [
  { id: 12, title: 'Two Sum', difficulty: 1200, isPublic: true, testCaseCount: 12, totalCount: 30, passCount: 18 },
  { id: 13, title: 'Binary Search', difficulty: 1400, isPublic: false, testCaseCount: 8, totalCount: 12, passCount: 5 }
]

const jobItems = [
  { problemId: 12, title: 'Two Sum', status: 'APPLIED', message: '已自动应用：title、content', attempts: 1, issueErrors: 0, issueWarnings: 2, hasProposal: true, appliedFields: ['title', 'content'] },
  { problemId: 13, title: 'Binary Search', status: 'REVIEW', message: '样例存在风险，等待人工审阅', attempts: 1, issueErrors: 0, issueWarnings: 1, hasProposal: true, appliedFields: [], resultVersionId: 305 },
  { problemId: 14, title: 'Graph Coloring', status: 'FAILED', message: 'AI 服务暂不可用，请稍后重试', errorCode: 'AI_UNAVAILABLE', attempts: 2, issueErrors: 3, issueWarnings: 0, hasProposal: false, appliedFields: [] },
  { problemId: 15, title: 'Shortest Path', status: 'RUNNING', message: null, attempts: 1, issueErrors: 0, issueWarnings: 0, hasProposal: false, appliedFields: [] }
]

function job(status, extra = {}) {
  return {
    id: 91,
    status,
    ipMode: false,
    autoApply: true,
    total: 4,
    processed: 3,
    applied: 1,
    review: 1,
    failed: 1,
    skipped: 0,
    cancelRequested: false,
    message: status === 'DONE' ? '成功 1 题，待审 1 题，失败 1 题，跳过 0 题' : null,
    createTime: '2026-11-13T00:10:00',
    finishTime: status === 'DONE' ? '2026-11-13T00:12:00' : null,
    items: jobItems,
    ...extra
  }
}

async function setup(page) {
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-batch-token')
    localStorage.setItem('refreshToken', 'synthetic-refresh')
    localStorage.setItem('user', JSON.stringify({ id: 3, username: 'admin', nickname: '管理员', role: 3 }))
  })
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const json = (data, message = '成功') =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message }) })

    if (path === '/api/user/info') return json({ id: 3, username: 'admin', nickname: '管理员', role: 3 })
    if (path === '/api/problem/admin/list') return json({ records: problems, total: problems.length })
    if (path === '/api/problem/format/jobs/active') return json(null)
    if (path === '/api/problem/format/jobs' && method === 'POST') return json(job('PENDING', { processed: 0 }))
    if (path === '/api/problem/format/jobs/91' && method === 'GET') return json(job('RUNNING'))
    return json({ records: [], total: 0 })
  })
}

test('批量任务面板：创建选项、进度与逐项状态', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/problem')
  await page.locator('.el-table__header .el-checkbox').first().click()
  await page.getByRole('button', { name: /批量优化/ }).click()

  const panel = page.locator('.format-job-panel')
  await expect(panel).toBeVisible()
  await expect(panel.getByText('题面优化任务')).toBeVisible()
  await expect(panel.getByText('无阻断问题时自动应用')).toBeVisible()
  await page.waitForTimeout(500)
  await page.screenshot({ path: join(EVIDENCE, '05-job-setup.png') })

  await panel.getByRole('button', { name: '开始整理' }).click()
  await expect(panel.locator('.fj-progress-text')).toContainText('3 / 4')
  await expect(panel.locator('.fj-item')).toHaveCount(4)
  await expect(panel.getByText('待审阅').first()).toBeVisible()
  await page.screenshot({ path: join(EVIDENCE, '06-job-progress.png') })
})
