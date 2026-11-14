import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const CONTEST_ID = 77
const PROBLEM_A = 201
const PROBLEM_B = 202

async function installApi(page, options = {}) {
  const state = {
    mode: options.mode || 'ACM',
    status: options.status || 'RUNNING',
    submitted: false,
    hasLastSubmission: options.hasLastSubmission ?? false,
    registrationStatus: options.registrationStatus ?? 'APPROVED',
    registrationReviewRequired: options.registrationReviewRequired ?? false,
    canSubmit: options.canSubmit ?? (options.status || 'RUNNING') === 'RUNNING'
  }
  await page.route('**/monaco-editor@*/min/vs/**', route => {
    const asset = new URL(route.request().url()).pathname.split('/min/vs/')[1]
    return route.fulfill({ path: join(process.cwd(), 'node_modules/monaco-editor/min/vs', asset) })
  })
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-contest-token')
    localStorage.setItem('user', JSON.stringify({ id: 41, username: 'student', nickname: '合成学生', role: 0 }))
    localStorage.setItem(`musealgo:scoped-draft:contest:77:${201}:cpp`, JSON.stringify({
      code: 'int main() { return 0; }',
      language: 'cpp',
      updatedAt: Date.now()
    }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname.slice('/api'.length)
    const json = data => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data, message: '成功' })
    })

    if (path === `/contest/${CONTEST_ID}`) {
      return json({
        id: CONTEST_ID,
        title: '合成比赛',
        mode: state.mode,
        startTime: new Date(Date.now() - 600000).toISOString(),
        endTime: new Date(Date.now() + 3600000).toISOString(),
        registrationReview: state.registrationReviewRequired ? 1 : 0,
        visible: 1
      })
    }
    if (path === `/contest/${CONTEST_ID}/problem/list`) {
      return json([
        { problemId: PROBLEM_A, title: 'A + B', problemOrder: 1, score: 100, status: null },
        { problemId: PROBLEM_B, title: '求和', problemOrder: 2, score: 100, status: null }
      ])
    }
    if (path === `/contest/${CONTEST_ID}/registration/mine`) {
      return json({
        registered: state.registrationStatus != null,
        status: state.registrationStatus,
        registrationReviewRequired: state.registrationReviewRequired
      })
    }
    if (path === `/contest/${CONTEST_ID}/answer/paper`) {
      if (state.status === 'NOT_STARTED') {
        return json({
          contestId: CONTEST_ID,
          title: '合成比赛',
          mode: state.mode,
          status: 'NOT_STARTED',
          remainingSeconds: 600,
          canSubmit: false,
          canSelfTest: false,
          canFinish: false,
          resultVisibility: 'FULL',
          registrationStatus: null,
          registrationReviewRequired: false,
          problems: []
        })
      }
      return json({
        contestId: CONTEST_ID,
        title: '合成比赛',
        mode: state.mode,
        status: state.status,
        serverTime: new Date().toISOString(),
        startTime: new Date(Date.now() - 600000).toISOString(),
        endTime: new Date(Date.now() + 3600000).toISOString(),
        deadline: new Date(Date.now() + 3600000).toISOString(),
        remainingSeconds: 3600,
        canSubmit: state.canSubmit,
        canSelfTest: true,
        canFinish: false,
        resultVisibility: state.status === 'ENDED' ? 'FULL' : (state.mode === 'OI' ? 'SUBMITTED_ONLY' : 'VERDICT_TIME_MEMORY'),
        registrationStatus: state.registrationStatus,
        registrationReviewRequired: state.registrationReviewRequired,
        problems: [
          { problemId: PROBLEM_A, order: 1, title: 'A + B', score: 100, myStatus: state.submitted || state.hasLastSubmission ? 'ATTEMPTED' : 'UNTOUCHED', pending: false, lastSubmissionId: state.hasLastSubmission ? 9101 : null },
          { problemId: PROBLEM_B, order: 2, title: '求和', score: 100, myStatus: 'UNTOUCHED', pending: false, lastSubmissionId: null }
        ]
      })
    }
    if (path === `/contest/${CONTEST_ID}/answer/problem/${PROBLEM_A}`) {
      return json({
        contestId: CONTEST_ID,
        problemId: PROBLEM_A,
        order: 1,
        title: 'A + B',
        content: '输入两个整数，输出它们的和。',
        inputDesc: '两个整数',
        outputDesc: '和',
        samples: [{ input: '1 2', output: '3' }],
        timeLimit: 1000,
        memoryLimit: 256,
        score: 100,
        myStatus: state.submitted ? 'ATTEMPTED' : 'UNTOUCHED',
        canSubmit: state.canSubmit
      })
    }
    if (path === `/contest/${CONTEST_ID}/answer/problem/${PROBLEM_B}`) {
      return json({
        contestId: CONTEST_ID,
        problemId: PROBLEM_B,
        order: 2,
        title: '求和',
        content: '求区间和。',
        inputDesc: '两个整数',
        outputDesc: '和',
        samples: [{ input: '1 2', output: '3' }],
        timeLimit: 1000,
        memoryLimit: 256,
        score: 100,
        myStatus: 'UNTOUCHED',
        canSubmit: state.canSubmit
      })
    }
    if (path === '/submission/submit' && request.method() === 'POST') {
      state.submitted = true
      return json({
        id: 9101,
        problemId: PROBLEM_A,
        contestId: CONTEST_ID,
        status: 2,
        judgePhase: 'FINISHED',
        runTime: 120,
        runMemory: 2048,
        errorMessage: '答案错误'
      })
    }
    if (path === '/submission/detail') {
      return json({
        id: 9101,
        problemId: PROBLEM_A,
        status: 2,
        judgePhase: 'FINISHED',
        runTime: 120,
        runMemory: 2048,
        errorMessage: '答案错误'
      })
    }
    if (path.includes('/user/preferences')) return json({ fontSize: 16 })
    if (path.includes('/user/info')) return json({ id: 41, username: 'student', nickname: '合成学生', role: 0 })
    return json({ records: [], total: 0 })
  })
  return state
}

test('contest problem list entry opens the whole-contest answer page', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installApi(page)
  await page.goto(`/contest/${CONTEST_ID}/problem`, { waitUntil: 'networkidle' })

  await page.getByRole('button', { name: '进入答题' }).click()
  await expect(page).toHaveURL(new RegExp(`/contest/${CONTEST_ID}/answer\\?q=coding-${PROBLEM_A}`))
  await expect(page.getByText('输入两个整数，输出它们的和。')).toBeVisible()
})

test('ACM contest submission shows verdict, time and memory without a score', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const state = await installApi(page, { mode: 'ACM' })
  await page.goto(`/contest/${CONTEST_ID}/answer?q=coding-${PROBLEM_A}`, { waitUntil: 'networkidle' })

  await expect(page.getByText('输入两个整数，输出它们的和。')).toBeVisible()
  await page.getByRole('button', { name: '提交代码' }).click()

  await expect(page.locator('.result-strip strong').filter({ hasText: '答案错误' })).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('耗时 120 ms')).toBeVisible()
  await expect(page.getByText('内存 2048 KB')).toBeVisible()
  await expect(page.getByText(/得分/)).toHaveCount(0)
  await expect(page.getByRole('button', { name: /题目 A，已尝试/ }).first()).toBeVisible({ timeout: 20000 })
  expect(state.submitted).toBe(true)
})

test('OI contest submission hides the verdict until the contest ends', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installApi(page, { mode: 'OI' })
  await page.goto(`/contest/${CONTEST_ID}/answer?q=coding-${PROBLEM_A}`, { waitUntil: 'networkidle' })

  await page.getByRole('button', { name: '提交代码' }).click()
  await expect(page.getByText('判题结果将在赛后公布')).toBeVisible({ timeout: 20000 })
  await expect(page.getByText('答案错误')).toHaveCount(0)
  await expect(page.getByText('耗时 120 ms')).toHaveCount(0)
})

test('not-started contest only shows the countdown and no problems', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installApi(page, { status: 'NOT_STARTED', canSubmit: false })
  await page.goto(`/contest/${CONTEST_ID}/answer`, { waitUntil: 'networkidle' })

  await expect(page.getByText('比赛尚未开始，开始后可进入答题')).toBeVisible()
  await expect(page.getByRole('button', { name: '答题卡' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /题目 A/ })).toHaveCount(0)
})

test('ended contest restores my latest submission for review', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await installApi(page, { status: 'ENDED', canSubmit: false, hasLastSubmission: true })
  await page.goto(`/contest/${CONTEST_ID}/answer?q=coding-${PROBLEM_A}`, { waitUntil: 'networkidle' })

  await expect(page.locator('.result-strip strong').filter({ hasText: '答案错误' })).toBeVisible()
  await expect(page.getByText('耗时 120 ms')).toBeVisible()
  await expect(page.getByText('内存 2048 KB')).toBeVisible()
})

test('contest answer page fits four desktop widths in light and dark themes', async ({ page }) => {
  await installApi(page)
  for (const width of [1920, 1440, 1280, 1024]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(`/contest/${CONTEST_ID}/answer?q=coding-${PROBLEM_A}`, { waitUntil: 'networkidle' })
    await expect(page.locator('.workspace-topbar')).toBeVisible()

    const lightOverflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(lightOverflow).toBeLessThanOrEqual(1)

    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await expect(page.locator('.workspace-topbar')).toBeVisible()
    const darkOverflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(darkOverflow).toBeLessThanOrEqual(1)
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
  }
})
