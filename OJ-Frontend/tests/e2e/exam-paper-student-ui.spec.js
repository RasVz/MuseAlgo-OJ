import { test, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'

const WEB_BASE = process.env.OJ_TEST_WEB || 'http://127.0.0.1:15173'
const PAPER_ID = '501'
const QUESTION_ONE = 1001
const QUESTION_TWO = 1002

const paper = {
  id: Number(PAPER_ID),
  title: '合成训练试卷',
  description: '用于检查作答与恢复流程的合成数据。',
  category: 'MOCK',
  level: '练习组',
  durationMinutes: 10,
  totalScore: 10,
  problemCount: 0,
  isPublished: 1,
  problems: [],
  questions: [
    {
      id: QUESTION_ONE,
      questionType: 'JUDGE',
      statement: '合成判断题一：本题答案为对。',
      options: [],
      score: 5
    },
    {
      id: QUESTION_TWO,
      questionType: 'JUDGE',
      statement: '合成判断题二：本题答案为错。',
      options: [],
      score: 5
    }
  ]
}

const objectiveResults = (answers) => paper.questions.map((question) => {
  const myAnswer = answers.find((answer) => Number(answer.questionId) === question.id)?.answer ?? ''
  const correctAnswer = question.id === QUESTION_ONE ? 'T' : 'F'
  const correct = myAnswer === correctAnswer
  return {
    questionId: question.id,
    correct,
    score: correct ? question.score : 0,
    correctAnswer,
    myAnswer,
    explanation: '合成解析。'
  }
})

const makeAttempt = (overrides = {}) => ({
  id: 700,
  paperId: Number(PAPER_ID),
  status: 'RUNNING',
  remainingSeconds: 600,
  objectiveScore: 0,
  codingScore: 0,
  totalScore: 0,
  answers: [],
  results: [],
  ...overrides
})

async function installSyntheticApi(page, options = {}) {
  const state = {
    currentAttempt: options.attempt ? structuredClone(options.attempt) : null,
    nextAttemptId: (options.attempt?.id ?? 700) + 1,
    lookups: 0,
    expireOnNextLookup: false,
    failNextSubmit: false,
    listRequests: [],
    events: []
  }

  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-student-token')
    localStorage.setItem('refreshToken', 'synthetic-student-refresh')
    localStorage.setItem('user', JSON.stringify({ id: 41, username: 'synthetic-student', nickname: '合成学生', role: 0 }))
  })

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    const path = url.pathname.slice('/api'.length)
    const method = route.request().method()
    let data = null
    let status = 200
    let code = 200
    let message = 'ok'

    if (path === '/exam-paper/list' && method === 'GET') {
      const pageNumber = Number(url.searchParams.get('page') || 1)
      const size = Number(url.searchParams.get('size') || 12)
      const category = url.searchParams.get('category') || ''
      const keyword = url.searchParams.get('keyword') || ''
      state.listRequests.push({ page: pageNumber, size, category, keyword })
      const records = Array.from({ length: 25 }, (_, index) => ({
        id: index + 1,
        title: index === 23 ? 'CSP-J 目标训练卷' : `训练模拟卷 ${index + 1}`,
        category: index % 2 === 0 ? 'MOCK' : 'CSP_J',
        level: index % 2 === 0 ? '模拟' : 'CSP-J',
        problemCount: 2,
        totalScore: 100,
        durationMinutes: 120,
        description: '合成试卷简介'
      }))
      const filtered = records.filter((record) =>
        (!category || record.category === category) &&
        (!keyword || record.title.includes(keyword))
      )
      const start = (pageNumber - 1) * size
      data = {
        records: filtered.slice(start, start + size),
        total: filtered.length,
        current: pageNumber,
        size,
        pages: Math.ceil(filtered.length / size)
      }
    } else if (path === `/exam-paper/${PAPER_ID}` && method === 'GET') {
      data = paper
    } else if (path === `/exam-paper/${PAPER_ID}/attempt` && method === 'GET') {
      state.lookups += 1
      if (state.expireOnNextLookup) {
        state.expireOnNextLookup = false
        state.currentAttempt = {
          ...state.currentAttempt,
          status: 'TIMEOUT',
          remainingSeconds: 0,
          objectiveScore: state.currentAttempt?.objectiveScore ?? 5,
          totalScore: state.currentAttempt?.totalScore ?? 5
        }
      }
      data = state.currentAttempt
    } else if (path === `/exam-paper/${PAPER_ID}/attempt/start` && method === 'POST') {
      if (state.currentAttempt?.status === 'RUNNING') {
        data = state.currentAttempt
      } else {
        state.currentAttempt = makeAttempt({ id: state.nextAttemptId++ })
        data = state.currentAttempt
      }
    } else if (path === `/exam-paper/${PAPER_ID}/submit-answers` && method === 'POST') {
      state.events.push('submit')
      if (state.failNextSubmit) {
        state.failNextSubmit = false
        code = 500
        message = 'synthetic answer save failure'
      } else {
        const answers = route.request().postDataJSON()?.answers ?? []
        const results = objectiveResults(answers)
        const objectiveScore = results.reduce((sum, result) => sum + result.score, 0)
        state.currentAttempt = {
          ...state.currentAttempt,
          answers,
          results,
          objectiveScore,
          totalScore: objectiveScore
        }
        data = results
      }
    } else if (path === `/exam-paper/${PAPER_ID}/attempt/finish` && method === 'POST') {
      state.events.push('finish')
      state.currentAttempt = {
        ...state.currentAttempt,
        status: 'SUBMITTED',
        remainingSeconds: 0,
        submitTime: '2026-11-13T12:00:00',
        objectiveScore: state.currentAttempt?.objectiveScore ?? 0,
        totalScore: state.currentAttempt?.objectiveScore ?? 0
      }
      data = state.currentAttempt
    }

    await route.fulfill({
      status,
      contentType: 'application/json',
      body: JSON.stringify({ code, message, data })
    })
  })

  return state
}

const answerOption = (page, questionIndex, label) =>
  page.locator('.boolean-group').nth(questionIndex).getByText(label, { exact: true })

test('training library keeps search, category and server pagination in the URL', async ({ page }) => {
  const api = await installSyntheticApi(page)
  await page.goto(`${WEB_BASE}/training?page=2`, { waitUntil: 'networkidle' })
  await expect(page.locator('.paper-card')).toHaveCount(12)
  await expect(page).toHaveURL(/page=2/)
  expect(api.listRequests.at(-1)).toMatchObject({ page: 2, size: 12 })

  await page.locator('.category-filter').getByText('CSP-J', { exact: true }).click()
  await expect(page).toHaveURL(/category=CSP_J/)
  await expect(page).not.toHaveURL(/page=2/)
  expect(api.listRequests.at(-1)).toMatchObject({ page: 1, category: 'CSP_J' })

  const search = page.getByRole('textbox', { name: '搜索试卷标题' })
  await search.fill('目标')
  await search.press('Enter')
  await expect(page).toHaveURL(/keyword=%E7%9B%AE%E6%A0%87/)
  await expect(page.getByRole('link', { name: /CSP-J 目标训练卷/ })).toBeVisible()
  expect(api.listRequests.at(-1)).toMatchObject({ page: 1, category: 'CSP_J', keyword: '目标' })
})

test('answer draft wins on refresh, server answers resume without a draft, and a new attempt starts clean', async ({ page }) => {
  const serverAnswers = [{ questionId: QUESTION_ONE, answer: 'T' }]
  const state = await installSyntheticApi(page, {
    attempt: makeAttempt({
      id: 801,
      answers: serverAnswers,
      results: objectiveResults(serverAnswers),
      objectiveScore: 5,
      totalScore: 5
    })
  })
  await page.goto(`${WEB_BASE}/training/${PAPER_ID}`, { waitUntil: 'networkidle' })
  await expect(answerOption(page, 0, '对')).toHaveClass(/is-checked/)

  await answerOption(page, 0, '错').click()
  await page.reload({ waitUntil: 'networkidle' })
  await expect(answerOption(page, 0, '错')).toHaveClass(/is-checked/)

  await page.evaluate(() => localStorage.removeItem('exam-draft:41:501:801'))
  await page.reload({ waitUntil: 'networkidle' })
  await expect(answerOption(page, 0, '对')).toHaveClass(/is-checked/)

  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确认交卷', exact: true }).click()
  await page.getByRole('button', { name: '再次作答', exact: true }).click()
  await expect(answerOption(page, 0, '对')).not.toHaveClass(/is-checked/)
  await expect(answerOption(page, 0, '错')).not.toHaveClass(/is-checked/)
  expect(state.currentAttempt.id).toBe(802)
  expect(state.currentAttempt.answers).toEqual([])
})

test('manual finish submits current choices before finishing and does not finish after submit failure', async ({ page }) => {
  const state = await installSyntheticApi(page)
  await page.goto(`${WEB_BASE}/training/${PAPER_ID}`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '开始作答', exact: true }).click()
  await answerOption(page, 0, '对').click()
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确认交卷', exact: true }).click()
  await expect(page.getByText('本次作答已交卷')).toBeVisible()
  expect(state.events).toEqual(['submit', 'finish'])

  await page.getByRole('button', { name: '再次作答', exact: true }).click()
  await answerOption(page, 0, '错').click()
  state.failNextSubmit = true
  await page.getByRole('button', { name: '交卷', exact: true }).click()
  await page.getByRole('button', { name: '确认交卷', exact: true }).click()
  await expect(page.getByText('答案提交失败，试卷仍未交卷。请检查网络后重试')).toBeVisible()
  expect(state.events).toEqual(['submit', 'finish', 'submit'])
  expect(state.currentAttempt.status).toBe('RUNNING')
})

test('timeout preserves local edits and explains they were not counted', async ({ page }) => {
  const savedAnswers = [{ questionId: QUESTION_ONE, answer: 'T' }]
  const state = await installSyntheticApi(page, {
    attempt: makeAttempt({ id: 901, remainingSeconds: 60, answers: savedAnswers, results: objectiveResults(savedAnswers), objectiveScore: 5, totalScore: 5 })
  })
  await page.goto(`${WEB_BASE}/training/${PAPER_ID}`, { waitUntil: 'networkidle' })
  await answerOption(page, 0, '错').click()
  state.expireOnNextLookup = true

  await page.evaluate(() => {
    const originalNow = Date.now
    Date.now = () => originalNow() + 65_000
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await expect(page.getByText('时间已到，设备上暂存的未提交修改未计入本次成绩。')).toBeVisible()
  await expect(page.getByText('本次分数按超时前已提交并判分的答案统计；未提交修改仍显示在答题卡中，方便你核对。')).toBeVisible()
  await expect(answerOption(page, 0, '错')).toHaveClass(/is-checked/)
  await expect(page.getByRole('button', { name: '第 1 题，已作答' })).toHaveClass(/nav-unsubmitted/)
  expect(state.events).toEqual([])
})

test('mobile workbench remains readable in light and dark themes and saves screenshots', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installSyntheticApi(page, { attempt: makeAttempt({ id: 1001 }) })
  await page.goto(`${WEB_BASE}/training/${PAPER_ID}`, { waitUntil: 'networkidle' })
  await expect(page.getByRole('heading', { name: '合成训练试卷' })).toBeVisible()

  for (const theme of ['light', 'dark']) {
    await page.evaluate((value) => document.documentElement.classList.toggle('dark', value === 'dark'), theme)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
    const screenshot = await page.screenshot({ fullPage: true })
    await testInfo.attach(`training-exam-mobile-${theme}.png`, { body: screenshot, contentType: 'image/png' })
    if (process.env.OJ_TEST_OUTPUT_DIR) {
      const path = join(process.env.OJ_TEST_OUTPUT_DIR, `training-exam-mobile-${theme}.png`)
      await mkdir(dirname(path), { recursive: true })
      await page.screenshot({ path, fullPage: true })
    }
  }
})
