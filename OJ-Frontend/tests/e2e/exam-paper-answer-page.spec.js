import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const PAPER_ID = 501
const ATTEMPT_ID = 700
const OBJECTIVE_ID = 1001
const CODING_ID = 2001

const paper = {
  id: PAPER_ID,
  title: '合成整卷答题',
  description: '用于验证整卷答题页。',
  category: 'MOCK',
  level: '模拟',
  durationMinutes: 10,
  totalScore: 55,
  problemCount: 1,
  isPublished: 1,
  problems: [],
  questions: []
}

const attempt = {
  id: ATTEMPT_ID,
  paperId: PAPER_ID,
  status: 'RUNNING',
  remainingSeconds: 600,
  objectiveScore: 0,
  codingScore: 0,
  totalScore: 0,
  answers: [],
  results: []
}

async function installApi(page, options = {}) {
  const state = {
    codingSolved: false,
    submitted: false,
    attemptStatus: options.attemptStatus || 'RUNNING',
    remainingSeconds: options.remainingSeconds ?? 600,
    gradingPending: options.gradingPending ?? false,
    finishPending: options.finishPending ?? false
  }
  await page.route('**/monaco-editor@*/min/vs/**', route => {
    const asset = new URL(route.request().url()).pathname.split('/min/vs/')[1]
    return route.fulfill({ path: join(process.cwd(), 'node_modules/monaco-editor/min/vs', asset) })
  })
  await page.addInitScript(id => {
    localStorage.setItem('accessToken', 'synthetic-answer-token')
    localStorage.setItem('user', JSON.stringify({ id: 41, username: 'student', nickname: '合成学生', role: 0 }))
    localStorage.setItem(`musealgo:scoped-draft:paper:${id}:${2001}:cpp`, JSON.stringify({
      code: 'int main() { return 0; }',
      language: 'cpp',
      updatedAt: Date.now()
    }))
  }, ATTEMPT_ID)
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname.slice('/api'.length)
    const json = data => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, data, message: '成功' })
    })

    if (path === `/exam-paper/${PAPER_ID}` && request.method() === 'GET') return json(paper)
    if (path === `/exam-paper/${PAPER_ID}/attempt` && request.method() === 'GET') {
      return json({
        ...attempt,
        status: state.attemptStatus,
        remainingSeconds: state.remainingSeconds,
        gradingPending: state.gradingPending,
        objectiveScore: state.submitted ? 5 : 0,
        codingScore: state.codingSolved ? 50 : 0,
        totalScore: (state.submitted ? 5 : 0) + (state.codingSolved ? 50 : 0)
      })
    }
    if (path === `/exam-paper/${PAPER_ID}/attempt/finish` && request.method() === 'POST') {
      if (state.finishPending) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            code: 409,
            errorCode: 'EXAM_ATTEMPT_GRADING_PENDING',
            message: '编程题仍在判题，请等待判题完成后交卷'
          })
        })
      }
      const expired = state.remainingSeconds <= 0
      state.attemptStatus = expired ? 'TIMEOUT' : 'SUBMITTED'
      state.remainingSeconds = 0
      return json({
        ...attempt,
        status: state.attemptStatus,
        remainingSeconds: 0,
        objectiveScore: state.submitted ? 5 : 0,
        codingScore: state.codingSolved ? 50 : 0,
        totalScore: (state.submitted ? 5 : 0) + (state.codingSolved ? 50 : 0)
      })
    }
    if (path === `/exam-paper/${PAPER_ID}/attempt/${ATTEMPT_ID}/answer/paper`) {
      return json({
        paperId: PAPER_ID,
        attemptId: ATTEMPT_ID,
        title: paper.title,
        description: paper.description,
        status: state.attemptStatus,
        serverTime: new Date().toISOString(),
        deadline: new Date(Date.now() + Math.max(0, state.remainingSeconds) * 1000).toISOString(),
        remainingSeconds: state.remainingSeconds,
        gradingPending: state.gradingPending,
        objectiveScore: state.submitted ? 5 : 0,
        codingScore: state.codingSolved ? 50 : 0,
        totalScore: (state.submitted ? 5 : 0) + (state.codingSolved ? 50 : 0),
        canSubmit: state.attemptStatus === 'RUNNING' && state.remainingSeconds > 0,
        canSelfTest: state.attemptStatus === 'RUNNING' && state.remainingSeconds > 0,
        objectiveQuestions: [{
          id: OBJECTIVE_ID,
          questionType: 'JUDGE',
          statement: '合成判断题：本题答案为对。',
          options: [],
          score: 5,
          questionOrder: 1,
          myAnswer: state.submitted ? 'T' : '',
          correct: state.submitted ? true : null,
          gainedScore: state.submitted ? 5 : 0
        }],
        codingProblems: [{
          problemId: CODING_ID,
          problemOrder: 1,
          title: 'A + B',
          score: 50,
          myStatus: state.codingSolved ? 'SOLVED' : 'UNTOUCHED',
          myBestScore: state.codingSolved ? 100 : 0,
          attemptCount: state.codingSolved ? 1 : 0,
          pending: false
        }]
      })
    }
    if (path === `/exam-paper/${PAPER_ID}/attempt/${ATTEMPT_ID}/answer/problem/${CODING_ID}`) {
      return json({
        problemId: CODING_ID,
        title: 'A + B',
        content: '输入两个整数，输出它们的和。',
        inputDesc: '两个整数',
        outputDesc: '和',
        samples: [{ input: '1 2', output: '3' }],
        timeLimit: 1000,
        memoryLimit: 256,
        score: 50,
        myStatus: state.codingSolved ? 'SOLVED' : 'UNTOUCHED',
        myBestScore: state.codingSolved ? 100 : 0,
        canSubmit: state.attemptStatus === 'RUNNING' && state.remainingSeconds > 0
      })
    }
    if (path === `/exam-paper/${PAPER_ID}/submit-answers` && request.method() === 'POST') {
      state.submitted = true
      return json([{ questionId: OBJECTIVE_ID, correct: true, score: 5, myAnswer: 'T' }])
    }
    if (path === '/submission/submit' && request.method() === 'POST') {
      state.codingSolved = true
      return json({
        id: 9001,
        problemId: CODING_ID,
        examAttemptId: ATTEMPT_ID,
        status: 1,
        judgePhase: 'FINISHED',
        score: 100,
        runTime: 10,
        runMemory: 1024,
        errorMessage: null
      })
    }
    if (path === '/submission/detail') {
      state.codingSolved = true
      return json({
        id: 9001,
        problemId: CODING_ID,
        status: 1,
        judgePhase: 'FINISHED',
        score: 100,
        runTime: 10,
        runMemory: 1024,
        errorMessage: null
      })
    }
    if (path.includes('/user/preferences')) return json({ fontSize: 16 })
    if (path.includes('/user/info')) return json({ id: 41, username: 'student', nickname: '合成学生', role: 0 })
    return json({ records: [], total: 0 })
  })
  return state
}

test('running paper redirects to the whole-paper answer page and submits objective and coding answers', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const state = await installApi(page)
  await page.goto(`/training/${PAPER_ID}`, { waitUntil: 'networkidle' })

  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}/answer\\?attemptId=${ATTEMPT_ID}`))
  await expect(page.getByText('合成整卷答题')).toBeVisible()
  await expect(page.getByText('合成判断题：本题答案为对。')).toBeVisible()

  await page.locator('.option-group .el-radio').filter({ hasText: '对' }).click()
  await page.getByRole('button', { name: '提交客观题' }).click()
  await expect(page.getByText(/客观题已判分/)).toBeVisible()
  expect(state.submitted).toBe(true)

  await page.getByRole('button', { name: /编程题 1/ }).click()
  await expect(page.getByText('输入两个整数，输出它们的和。')).toBeVisible()
  await expect(page.locator('.monaco-editor .view-lines').first()).toContainText('return', { timeout: 20000 })
  await page.getByRole('button', { name: '提交代码' }).click()
  await expect(page.getByText('通过')).toBeVisible({ timeout: 20000 })
  await expect(page.getByRole('button', { name: /编程题 1，已通过/ }).first()).toBeVisible()
})

test('objective draft restores and manual finish returns to the result page', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const state = await installApi(page)
  await page.addInitScript(() => {
    localStorage.setItem('exam-draft:41:501:700', JSON.stringify({
      answers: [{ questionId: 1001, answer: 'T' }],
      savedAt: Date.now()
    }))
  })
  await page.goto(`/training/${PAPER_ID}`, { waitUntil: 'networkidle' })

  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}/answer\\?attemptId=${ATTEMPT_ID}`))
  await expect(page.locator('.option-group .el-radio').filter({ hasText: '对' })).toHaveClass(/is-checked/)

  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确定交卷', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}$`))
  await expect(page.getByText('本次作答已交卷')).toBeVisible()
  expect(state.attemptStatus).toBe('SUBMITTED')
})

test('pending judgement keeps the attempt open until judging finishes', async ({ page }) => {
  const state = await installApi(page, { finishPending: true })
  await page.goto(`/training/${PAPER_ID}`, { waitUntil: 'networkidle' })
  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}/answer\\?attemptId=${ATTEMPT_ID}`))

  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确定交卷', exact: true }).click()
  await expect(page.getByText(/编程题仍在判题/)).toBeVisible()
  expect(state.attemptStatus).toBe('RUNNING')

  state.finishPending = false
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确定交卷', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}$`))
  await expect(page.getByText('本次作答已交卷')).toBeVisible()
})

test('expired attempt finishes automatically and returns to the timeout result', async ({ page }) => {
  const state = await installApi(page, { remainingSeconds: 0 })
  await page.goto(`/training/${PAPER_ID}`, { waitUntil: 'networkidle' })

  await expect(page).toHaveURL(new RegExp(`/training/${PAPER_ID}$`), { timeout: 15000 })
  await expect(page.locator('.el-tag__content').filter({ hasText: '时间到，已自动交卷' })).toBeVisible()
  expect(state.attemptStatus).toBe('TIMEOUT')
})
