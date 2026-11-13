import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// 题面优化工作台的合成检查。入口已统一为「题面版本页 → 候选版本 → 审阅」，
// 因此这里先搭一个只有单个候选版本的版本页，再验证工作台本身。
test.describe.configure({ timeout: 90000 })

const EVIDENCE = process.env.OJ_TEST_OUTPUT_DIR
  ? join(process.env.OJ_TEST_OUTPUT_DIR, 'problem-format-workbench')
  : join(process.cwd(), '..', '..', 'outputs', 'problem-format-workbench-20261113')
mkdirSync(EVIDENCE, { recursive: true })

const baseHash = 'a'.repeat(64)

const sourceProblem = {
  title: 'Two Sum',
  content: [
    'Given an array of integers, return indices of the two numbers such that they add up to a specific target.',
    '',
    'Time Limit: 1000ms',
    '',
    'Input',
    'The first line contains an integer $n$ ($1 \\le n \\le 10^5$).',
    '',
    'Output',
    'Print two indices.',
    '',
    'Sample Input',
    '4',
    '2 7 11 15',
    '9'
  ].join('\n'),
  inputDesc: '',
  outputDesc: '',
  hint: '',
  timeLimitMs: 1000,
  memoryLimitMb: 512,
  samples: [{ input: '4\n2 7 11 15\n9', output: '1 2' }]
}

const formatted = {
  title: '两数之和',
  content: [
    '给定一个整数数组 $a$ 和一个目标值 $t$，请找出数组中和为 $t$ 的两个数，并返回它们的下标。',
    '',
    '## 数据范围',
    '',
    '- 数组长度满足 $1 \\le n \\le 10^5$',
    '- 每个元素满足 $-10^9 \\le a_i \\le 10^9$',
    '',
    '## 提示',
    '',
    '可以用哈希表把查找从 $O(n)$ 降到 $O(1)$，整体复杂度 $O(n)$。'
  ].join('\n'),
  inputDesc: '第一行一个整数 $n$，第二行 $n$ 个整数，第三行目标值 $t$。',
  outputDesc: '输出两个下标（从 1 开始），用空格分隔。',
  hint: '注意同一元素不能重复使用。',
  timeLimitMs: 2000,
  memoryLimitMb: 256,
  samples: [
    { input: '4\n2 7 11 15\n9', output: '1 2' },
    { input: '3\n3 2 4\n6', output: '2 3' }
  ]
}

const baseIssues = [
  { code: 'SAMPLE_CONTENT_CHANGED', field: 'samples', severity: 'WARN', message: '第 2 组样例与原文不一致，请确认是修复了错乱还是被改写' },
  { code: 'IO_SECTION_LEFT', field: 'content', severity: 'WARN', message: '正文仍含「输入/输出/样例」章节标题，这些内容应拆到输入描述、输出描述与样例' },
  { code: 'MEMORY_LIMIT_RANGE', field: 'memoryLimit', severity: 'WARN', message: '内存 2048MB 超出常见范围 16~1024MB' }
]

const candidateVersion = {
  id: 301,
  problemId: 12,
  versionNo: 2,
  status: 'CANDIDATE',
  source: 'AI',
  title: formatted.title,
  summary: '给定一个整数数组 a 和一个目标值 t……',
  timeLimit: 2000,
  memoryLimit: 256,
  sampleCount: 2,
  baseVersionId: 300,
  jobId: 91,
  issueErrors: 0,
  issueWarnings: 3,
  createdBy: 3,
  createTime: '2026-11-13T11:00:00',
  live: false,
  drifted: false
}

const liveVersion = {
  ...candidateVersion,
  id: 300,
  versionNo: 1,
  status: 'LIVE',
  source: 'INITIAL',
  live: true,
  issueWarnings: 0
}

const problems = [
  { id: 12, title: 'Two Sum', difficulty: 1200, isPublic: true, testCaseCount: 12, totalCount: 30, passCount: 18 },
  { id: 13, title: 'Binary Search', difficulty: 1400, isPublic: false, testCaseCount: 8, totalCount: 12, passCount: 5 }
]

const problemDetail = {
  id: 12,
  title: 'Two Sum',
  content: sourceProblem.content,
  inputDesc: '',
  outputDesc: '',
  hint: '',
  timeLimit: 1000,
  memoryLimit: 512,
  difficulty: 1200,
  tags: '',
  isPublic: 1,
  samples: sourceProblem.samples
}

const jobItems = [
  { problemId: 12, title: 'Two Sum', status: 'APPLIED', message: '已自动应用：title、content', attempts: 1, issueErrors: 0, issueWarnings: 2, hasProposal: true, appliedFields: ['title', 'content'] },
  { problemId: 13, title: 'Binary Search', status: 'REVIEW', message: '样例存在风险，等待人工审阅', attempts: 1, issueErrors: 0, issueWarnings: 1, hasProposal: true, appliedFields: [] },
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

/**
 * 拦截后端接口。
 * 注意用 pathname 判定，不能用 `**／api/**`：那会把源码模块 src/api/problem.js 也拦成 JSON。
 */
async function setup(page, { issues = baseIssues, proposalOverrides = {} } = {}) {
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-format-token')
    localStorage.setItem('refreshToken', 'synthetic-refresh')
    localStorage.setItem('user', JSON.stringify({ id: 3, username: 'admin', nickname: '管理员', role: 3 }))
  })
  const proposal = {
    problemId: 12,
    title: formatted.title,
    success: true,
    message: '整理完成',
    formatted,
    source: sourceProblem,
    issues,
    baseHash,
    ...proposalOverrides
  }
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const json = (data, message = '成功') =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message }) })

    if (path === '/api/user/info') return json({ id: 3, username: 'admin', nickname: '管理员', role: 3 })
    if (path === '/api/problem/12' && method === 'GET') return json(problemDetail)
    if (path === '/api/problem/admin/list') return json({ records: problems, total: problems.length })
    if (path === '/api/problem/format/jobs/active') return json(null)
    if (path === '/api/problem/format/jobs' && method === 'POST') return json(job('PENDING', { processed: 0 }))
    if (path === '/api/problem/12/statement/versions' && method === 'GET') return json([candidateVersion, liveVersion])
    if (path === '/api/problem/12/statement/versions/301/proposal') return json(proposal)
    if (path === '/api/problem/12/statement/versions/300/proposal') {
      return json({ ...proposal, formatted: { ...formatted, title: 'Two Sum' } })
    }
    if (path === '/api/problem/12/statement/versions/301' && method === 'GET') {
      return json({
        ...candidateVersion,
        content: formatted.content,
        inputDesc: formatted.inputDesc,
        outputDesc: formatted.outputDesc,
        hint: formatted.hint,
        samples: formatted.samples,
        issues,
        note: null
      })
    }
    if (path === '/api/problem/12/statement/versions/300' && method === 'GET') {
      return json({
        ...liveVersion,
        content: sourceProblem.content,
        inputDesc: '',
        outputDesc: '',
        hint: '',
        samples: sourceProblem.samples,
        issues: [],
        note: null
      })
    }
    if (path === '/api/problem/format/jobs/91' && method === 'GET') return json(job('RUNNING'))
    if (path === '/api/problem/format/apply') return json({ problemId: 12, appliedFields: ['title', 'content'], baseHash: 'b'.repeat(64) })
    return json({ records: [], total: 0 })
  })
}

async function openWorkbench(page) {
  await page.goto('/admin/problem/edit/12')
  await page.locator('.sv-item', { hasText: '待审' }).click()
  await page.getByRole('button', { name: '审阅并应用' }).click()
  const drawer = page.locator('.format-workbench')
  await expect(drawer).toBeVisible()
  return drawer
}

test('单题工作台：字段导航、渲染对照与质量提示', async ({ page }) => {
  await setup(page)
  const drawer = await openWorkbench(page)

  await expect(drawer.locator('.fw-title')).toContainText('两数之和')
  await expect(drawer.locator('.fw-nav-item')).toHaveCount(8)

  await drawer.locator('.fw-nav-item', { hasText: '题目正文' }).click()
  await expect(drawer.locator('.fw-issue').first()).toBeVisible()
  await expect(drawer.locator('.fw-compare')).toBeVisible()
  await expect(drawer.locator('.fw-pane')).toHaveCount(2)

  await page.waitForTimeout(1200) // MathJax 渲染
  await page.screenshot({ path: join(EVIDENCE, '01-workbench-compare.png') })

  await drawer.getByText('源码', { exact: true }).click()
  await expect(drawer.locator('.fw-diff')).toBeVisible()
  await expect(drawer.locator('.fw-diff-row.del').first()).toBeVisible()
  await page.screenshot({ path: join(EVIDENCE, '02-workbench-source-diff.png') })

  await drawer.locator('.fw-nav-item', { hasText: '样例' }).click()
  await expect(drawer.locator('.fw-sample').first()).toBeVisible()
  await expect(drawer.locator('.fw-sample').nth(1).getByText('新增')).toBeVisible()
  await page.screenshot({ path: join(EVIDENCE, '03-workbench-samples.png') })
})

test('单题工作台：阻断问题默认不勾选，强行勾选后禁止应用', async ({ page }) => {
  await setup(page, {
    issues: [...baseIssues, { code: 'SAMPLES_LOST', field: 'samples', severity: 'ERROR', message: '原文有 1 组样例，整理结果一组都没有' }]
  })
  const drawer = await openWorkbench(page)

  await expect(drawer.locator('.fw-dot.is-error')).toBeVisible()
  await expect(drawer.getByText(/项因阻断问题未勾选/)).toBeVisible()

  const applyButton = drawer.getByRole('button', { name: /应用/ })
  await expect(applyButton).toBeEnabled()

  const samplesCheckbox = drawer.locator('.fw-nav-item', { hasText: '样例' }).locator('.el-checkbox')
  await samplesCheckbox.click()
  await expect(applyButton).toBeDisabled()
  await page.screenshot({ path: join(EVIDENCE, '04-workbench-blocking.png') })
})

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

test('暗色主题下工作台保持可读', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/problem/edit/12')
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await page.locator('.sv-item', { hasText: '待审' }).click()
  await page.getByRole('button', { name: '审阅并应用' }).click()
  await expect(page.locator('.format-workbench')).toBeVisible()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.waitForTimeout(1200)
  await page.screenshot({ path: join(EVIDENCE, '07-workbench-dark.png') })
})
