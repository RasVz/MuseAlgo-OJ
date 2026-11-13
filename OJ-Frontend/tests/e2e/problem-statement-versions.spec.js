import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// 题面版本页的合成检查：版本时间线、版本详情、候选审阅入口、异步优化完成后的候选落地。
// 全部走 route 拦截，不写真实数据库、不调用真实模型。
test.describe.configure({ timeout: 90000 })

const EVIDENCE = process.env.OJ_TEST_OUTPUT_DIR
  ? join(process.env.OJ_TEST_OUTPUT_DIR, 'problem-statement-versions')
  : join(process.cwd(), '..', '..', 'outputs', 'problem-format-workbench-20261113')
mkdirSync(EVIDENCE, { recursive: true })

const LONG_TEXT = [
  '给定一个整数数组 $a$ 和一个目标值 $t$，请找出数组中和为 $t$ 的两个数，并返回它们的下标。',
  '',
  '## 数据范围',
  '',
  '- $1 \\le n \\le 10^5$',
  '- $-10^9 \\le a_i \\le 10^9$'
].join('\n')

const baseVersion = {
  id: 102,
  problemId: 12,
  versionNo: 2,
  status: 'ARCHIVED',
  source: 'MANUAL',
  title: 'Two Sum',
  summary: 'Given an array of integers, return indices of the two numbers.',
  timeLimit: 1000,
  memoryLimit: 512,
  sampleCount: 1,
  baseVersionId: null,
  jobId: null,
  issueErrors: 0,
  issueWarnings: 0,
  createdBy: 3,
  createTime: '2026-11-12T09:00:00',
  live: false,
  drifted: false
}

let versions = []

function resetVersions() {
  versions = [
    {
      ...baseVersion,
      id: 103,
      versionNo: 3,
      status: 'LIVE',
      source: 'AI_APPLIED',
      title: '两数之和',
      summary: '给定一个整数数组 a 和一个目标值 t……',
      timeLimit: 2000,
      memoryLimit: 256,
      sampleCount: 2,
      baseVersionId: 102,
      createTime: '2026-11-13T10:00:00',
      live: true,
      issueWarnings: 1
    },
    { ...baseVersion },
    {
      ...baseVersion,
      id: 101,
      versionNo: 1,
      status: 'CANDIDATE',
      source: 'AI',
      title: '两数之和（候选）',
      timeLimit: 1000,
      memoryLimit: 512,
      sampleCount: 2,
      baseVersionId: 102,
      jobId: 91,
      createTime: '2026-11-13T11:00:00',
      issueWarnings: 2
    }
  ]
}

const problemDetail = {
  id: 12,
  title: '两数之和',
  content: LONG_TEXT,
  inputDesc: '第一行一个整数 $n$。',
  outputDesc: '输出两个下标。',
  hint: '注意同一元素不能重复使用。',
  timeLimit: 2000,
  memoryLimit: 256,
  difficulty: 1200,
  tags: '',
  isPublic: 1,
  samples: [
    { input: '4\n2 7 11 15\n9', output: '1 2' },
    { input: '3\n3 2 4\n6', output: '2 3' }
  ]
}

const issues = [
  { code: 'SAMPLE_CONTENT_CHANGED', field: 'samples', severity: 'WARN', message: '第 2 组样例与原文不一致，请确认是修复了错乱还是被改写' },
  { code: 'MEMORY_LIMIT_RANGE', field: 'memoryLimit', severity: 'WARN', message: '内存 2048MB 超出常见范围 16~1024MB' }
]

function detailOf(version) {
  return {
    id: version.id,
    problemId: 12,
    versionNo: version.versionNo,
    status: version.status,
    source: version.source,
    title: version.title,
    content: version.status === 'CANDIDATE' ? LONG_TEXT : '原英文题面内容。',
    inputDesc: '第一行一个整数 $n$，第二行 $n$ 个整数，第三行目标值 $t$。',
    outputDesc: '输出两个下标（从 1 开始），用空格分隔。',
    hint: '注意同一元素不能重复使用。',
    timeLimit: version.timeLimit,
    memoryLimit: version.memoryLimit,
    samples: [
      { input: '4\n2 7 11 15\n9', output: '1 2' },
      { input: '3\n3 2 4\n6', output: '2 3' }
    ],
    issues: version.status === 'CANDIDATE' ? issues : [],
    baseVersionId: version.baseVersionId,
    jobId: version.jobId,
    note: null,
    createdBy: 3,
    createTime: version.createTime
  }
}

const proposal = {
  problemId: 12,
  title: '两数之和（候选）',
  success: true,
  message: '整理完成',
  formatted: {
    title: '两数之和（候选）',
    content: LONG_TEXT,
    inputDesc: '第一行一个整数 $n$，第二行 $n$ 个整数，第三行目标值 $t$。',
    outputDesc: '输出两个下标（从 1 开始），用空格分隔。',
    hint: '注意同一元素不能重复使用。',
    timeLimitMs: 2000,
    memoryLimitMb: 256,
    samples: [
      { input: '4\n2 7 11 15\n9', output: '1 2' },
      { input: '3\n3 2 4\n6', output: '2 3' }
    ]
  },
  source: {
    title: 'Two Sum',
    content: 'Given an array of integers, return indices of the two numbers.\n\nTime Limit: 1000ms',
    inputDesc: '',
    outputDesc: '',
    hint: '',
    timeLimitMs: 1000,
    memoryLimitMb: 512,
    samples: [{ input: '4\n2 7 11 15\n9', output: '1 2' }]
  },
  issues,
  baseHash: 'a'.repeat(64)
}

async function setup(page) {
  resetVersions()
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-version-token')
    localStorage.setItem('refreshToken', 'synthetic-refresh')
    localStorage.setItem('user', JSON.stringify({ id: 3, username: 'admin', nickname: '管理员', role: 3 }))
  })
  let jobDone = false
  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    const json = (data, message = '成功') =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message }) })

    if (path === '/api/user/info') return json({ id: 3, username: 'admin', nickname: '管理员', role: 3 })
    if (path === '/api/problem/12' && method === 'GET') return json(problemDetail)
    if (path === '/api/problem/format/jobs/active') return jobDone ? json(null) : json(null)
    if (path === '/api/problem/12/statement/versions' && method === 'GET') return json(versions)
    if (path === '/api/problem/12/statement/versions/optimize' && method === 'POST') {
      return json({
        id: 91,
        status: 'PENDING',
        total: 1,
        processed: 0,
        applied: 0,
        review: 0,
        failed: 0,
        skipped: 0,
        ipMode: false,
        autoApply: false,
        items: [{ problemId: 12, title: '两数之和', status: 'PENDING', baseVersionId: 103, resultVersionId: null }]
      })
    }
    if (path === '/api/problem/format/jobs/91' && method === 'GET') {
      jobDone = true
      if (!versions.some((version) => version.id === 104)) {
        versions = [{
          ...baseVersion,
          id: 104,
          versionNo: 4,
          status: 'CANDIDATE',
          source: 'AI',
          title: '两数之和 v4',
          baseVersionId: 103,
          jobId: 91,
          createTime: '2026-11-13T12:00:00',
          issueWarnings: 1
        }, ...versions]
      }
      return json({
        id: 91,
        status: 'DONE',
        total: 1,
        processed: 1,
        applied: 0,
        review: 1,
        failed: 0,
        skipped: 0,
        ipMode: false,
        autoApply: false,
        message: '成功 0 题，待审 1 题，失败 0 题，跳过 0 题',
        items: [{ problemId: 12, title: '两数之和 v4', status: 'REVIEW', baseVersionId: 103, resultVersionId: 104 }]
      })
    }
    if (/^\/api\/problem\/12\/statement\/versions\/\d+\/proposal$/.test(path)) return json(proposal)
    if (/^\/api\/problem\/12\/statement\/versions\/\d+$/.test(path) && method === 'GET') {
      const id = Number(path.split('/').pop())
      const version = versions.find((item) => item.id === id)
      return json(detailOf(version))
    }
    if (/^\/api\/problem\/12\/statement\/versions\/\d+\/apply$/.test(path)) {
      return json({ ...versions[0], status: 'LIVE', source: 'AI_APPLIED' })
    }
    if (path === '/api/problem/12/statement/versions/current') return json(versions[0])
    return json(null)
  })
}

test('版本页：时间线、版本详情与候选审阅', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/problem/edit/12')
  await expect(page.locator('.sv-item')).toHaveCount(3)
  await expect(page.locator('.hero-title')).toContainText('编辑题目 #12')
  await expect(page.locator('.sv-item', { hasText: '当前' }).first()).toBeVisible()
  await expect(page.locator('.sv-item', { hasText: '待审' }).first()).toBeVisible()
  await page.waitForTimeout(800)
  await page.screenshot({ path: join(EVIDENCE, '11-versions-live.png') })

  await page.locator('.sv-item', { hasText: 'v2' }).first().click()
  await expect(page.locator('.sv-detail-title')).toContainText('v2')
  await expect(page.locator('.sv-detail')).toContainText('原英文题面内容')
  await page.screenshot({ path: join(EVIDENCE, '12-versions-archived.png') })

  await page.locator('.sv-item', { hasText: 'v1' }).first().click()
  await expect(page.locator('.sv-detail-title')).toContainText('候选')
  await page.getByRole('button', { name: '审阅并应用' }).click()
  const drawer = page.locator('.format-workbench')
  await expect(drawer).toBeVisible()
  await expect(drawer.locator('.fw-compare')).toBeVisible()
  // 问题清单按字段展示：样例字段上有 2 条提醒
  await drawer.locator('.fw-nav-item', { hasText: '样例' }).click()
  await expect(drawer.locator('.fw-issue').first()).toBeVisible()
  await page.waitForTimeout(1000)
  await page.screenshot({ path: join(EVIDENCE, '13-version-review.png') })
})

test('版本页：异步优化后自动生成候选并打开审阅', async ({ page }) => {
  await setup(page)
  await page.goto('/admin/problem/edit/12')
  await expect(page.locator('.sv-item')).toHaveCount(3)

  await page.getByRole('button', { name: /基于所选版本优化/ }).click()
  await page.getByRole('button', { name: '开始整理' }).click()

  await expect(page.locator('.sv-running')).toBeVisible()
  await expect(page.locator('.sv-item')).toHaveCount(4, { timeout: 15000 })
  // 完成后自动打开工作台，且原文来自基线版本
  const drawer = page.locator('.format-workbench')
  await expect(drawer).toBeVisible({ timeout: 15000 })
  await expect(drawer.locator('.fw-nav-item')).toHaveCount(8)
  await page.waitForTimeout(1000)
  await page.screenshot({ path: join(EVIDENCE, '14-version-async-job.png') })
})
