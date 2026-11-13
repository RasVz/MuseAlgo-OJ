import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
const output = process.env.OJ_TEST_OUTPUT_DIR || '/tmp/training-exam'
mkdirSync(output, { recursive: true })
const paper = (id = 1) => ({ id, title: `模拟训练卷 ${id}`, category: 'MOCK', level: '入门', totalScore: 100, durationMinutes: 120, isPublished: 1, problemCount: 2, hasAttempts: false,
  problems: [{ problemId: 11, title: '简单加法', score: 80, problemOrder: 1, difficulty: 800 }],
  questions: [{ id: 21, questionType: 'JUDGE', statement: '一加一等于二。', answer: 'T', options: [], score: 20, explanation: '成立' }] })
async function fixture(page, state = {}) {
  state.requests = []; state.saves = []
  await page.addInitScript(role => { localStorage.setItem('accessToken', 'synthetic-admin'); localStorage.setItem('user', JSON.stringify({ id: 3, username: 'synthetic', role })) }, state.role ?? 3)
  await page.route('**/api/**', async (route) => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    if (!path.startsWith("/api/")) return route.continue()
    state.requests.push({ path, params: Object.fromEntries(url.searchParams) })
    let data = []
    if (path === '/api/user/info') data = { id: 3, username: 'synthetic', role: state.role ?? 3 }
    else if (path === '/api/exam-paper/list') data = { records: [paper(Number(url.searchParams.get('page') || 1))], total: 42 }
    else if (/\/exam-paper\/\d+\/stats$/.test(path)) data = { attemptCount: 2, totalScore: 100, avgObjective: 10, avgCoding: 40, avgTotal: 50, questionStats: [{ questionOrder: 1, statement: '一加一等于二', correctCount: 1, attemptCount: 2, correctRate: 50 }], distribution: [{ bucket: '60-69', count: 1 }], gespDistribution: [] }
    else if (/\/exam-paper\/\d+\/attempts$/.test(path)) data = { total: 41, records: [{ id: 1, username: `明细第${url.searchParams.get('page')}页`, status: 'SUBMITTED', totalScore: 60 }] }
    else if (path === '/api/exam-paper/save') { state.saves.push(req.postDataJSON()); if (state.saveGate) await state.saveGate; data = 1 }
    else if (/\/exam-paper\/\d+$/.test(path)) data = { ...paper(), ...state.detail }
    else if (path === '/api/problem/list') data = { records: [{ id: 12, title: '第二道编程题' }], total: 1 }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  return state
}
const title = (page) => page.getByPlaceholder('如：GESP 2级 2024-09 真题')
for (const role of [1, 2]) {
  test(`角色 ${role} 不能通过直接输入地址进入试卷管理`, async ({ page }) => {
    const state = await fixture(page, { role })
    for (const path of ['/admin/exam-paper', '/admin/exam-paper/create', '/admin/exam-paper/edit/1']) {
      await page.goto(path)
      await expect(page).toHaveURL(/\/admin$/)
    }
    expect(state.requests.filter(request => request.path.startsWith('/api/exam-paper'))).toEqual([])
  })
}
test('管理列表搜索分类与后续页使用服务端参数', async ({ page }) => {
  const state = await fixture(page); await page.goto('/admin/exam-paper')
  await expect(page.getByText('模拟训练卷 1', { exact: true })).toBeVisible()
  await page.locator('.pagination-box .btn-next').click()
  await expect(page.getByText('模拟训练卷 2', { exact: true })).toBeVisible()
  const input = page.locator('.search-input input'); await input.fill('GESP'); await input.press('Enter')
  await expect.poll(() => state.requests.filter(r => r.path.endsWith('/list')).at(-1)?.params).toMatchObject({ page: '1', keyword: 'GESP' })
  await page.locator('.category-select').click(); await page.getByRole('option', { name: 'CSP-J', exact: true }).click()
  await expect.poll(() => state.requests.filter(r => r.path === '/api/exam-paper/list').at(-1)?.params.category).toBe('CSP_J')
})
test('成绩明细真分页并展示得分率', async ({ page }) => {
  await fixture(page); await page.goto('/admin/exam-paper'); await page.getByRole('button', { name: '成绩', exact: true }).click()
  const dialog = page.getByRole('dialog'); await expect(dialog.getByText('明细第1页', { exact: true })).toBeVisible()
  await expect(dialog.locator('.el-table').first().getByText('50%')).toBeVisible(); await expect(dialog.getByText('60-69%')).toBeVisible()
  await dialog.locator('.attempt-pagination .btn-next').click(); await expect(dialog.getByText('明细第2页', { exact: true })).toBeVisible()
})
test('判断题批量录入按文档格式保存答案与分值', async ({ page }) => {
  const state = await fixture(page); await page.goto('/admin/exam-paper/create'); await title(page).fill('新建训练卷')
  await page.getByRole('button', { name: '批量粘贴录入' }).click(); const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox').fill('JUDGE|一加一等于二|对|100'); await dialog.getByRole('button', { name: '导入到题目列表' }).click()
  await expect(page.locator('.answer-input input')).toHaveValue('T'); await page.getByRole('button', { name: '保存试卷', exact: true }).click()
  await expect.poll(() => state.saves.length).toBe(1); expect(state.saves[0].questions[0]).toMatchObject({ questionType: 'JUDGE', answer: 'T', score: 100 })
})
test('批量非法行阻止整批导入', async ({ page }) => {
  await fixture(page); await page.goto('/admin/exam-paper/create'); await page.getByRole('button', { name: '批量粘贴录入' }).click()
  const dialog = page.getByRole('dialog'); await dialog.getByRole('textbox').fill('JUDGE|正确行|T|5\nJUDGE|错误行|A|5')
  await dialog.getByRole('button', { name: '导入到题目列表' }).click(); await expect(dialog.getByText(/第 2 行/)).toBeVisible(); await expect(page.locator('.question-card')).toHaveCount(0)
})
test('历史卷冻结结构且元信息保存保留旧分值', async ({ page }) => {
  const state = await fixture(page, { detail: { hasAttempts: true, problems: [{ problemId: 11, title: '旧卷编程题', score: 0, problemOrder: 1 }] } })
  await page.goto('/admin/exam-paper/edit/1'); await expect(title(page)).toHaveValue('模拟训练卷 1')
  await expect(page.locator('.problem-score input')).toBeDisabled(); await title(page).fill('旧卷新标题')
  await page.getByRole('button', { name: '保存试卷', exact: true }).click(); await expect.poll(() => state.saves.length).toBe(1); expect(state.saves[0].problems[0].score).toBe(0)
})
test('未保存离开可以取消并继续编辑', async ({ page }) => {
  await fixture(page); await page.goto('/admin/exam-paper/create'); await title(page).fill('暂未保存'); await page.getByRole('button', { name: '返回', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '离开编辑器' })).toBeVisible(); await page.getByRole('button', { name: '继续编辑' }).click(); await expect(title(page)).toHaveValue('暂未保存')
})
for (const [name, width, dark] of [1024, 1366, 1440, 1920].flatMap(width => [false, true].map(dark => [`desktop-${width}-${dark ? 'dark' : 'light'}`, width, dark]))) {
  test(`管理与编辑布局 ${name}`, async ({ page }) => {
    await fixture(page); await page.setViewportSize({ width, height: 960 })
    await page.addInitScript((value) => { if (value) document.addEventListener('DOMContentLoaded', () => document.documentElement.classList.add('dark')) }, dark)
    const errors = []; page.on('pageerror', (e) => errors.push(e.message)); await page.goto('/admin/exam-paper')
    await expect(page.getByText('模拟训练卷 1', { exact: true })).toBeVisible(); await page.screenshot({ path: join(output, `admin-${name}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    await page.goto('/admin/exam-paper/edit/1'); await expect(page.locator('.q-statement textarea')).toHaveValue('一加一等于二。')
    if (dark) await expect(page.locator('html')).toHaveClass(/dark/)
    const saveButton = await page.getByRole('button', { name: '保存试卷', exact: true }).boundingBox()
    expect(saveButton.x + saveButton.width).toBeLessThanOrEqual(width)
    await expect(page.locator('.admin-side')).toBeVisible()
    await expect(page.getByRole('button', { name: '返回主站', exact: true })).toBeVisible()
    await page.screenshot({ path: join(output, `editor-${name}.png`), fullPage: true }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true); expect(errors).toEqual([])
  })
}

test('保存期间锁定编辑内容，防止未提交改动随关窗丢失', async ({ page }) => {
  let release
  const state = await fixture(page, { saveGate: new Promise(resolve => { release = resolve }) })
  await page.goto('/admin/exam-paper/edit/1')
  await expect(title(page)).toHaveValue('模拟训练卷 1')
  await title(page).fill('准备保存')
  await page.getByRole('button', { name: '保存试卷', exact: true }).click()
  await expect.poll(() => state.saves.length).toBe(1)
  await expect(page.locator('.section-card[inert]')).toHaveCount(2)
  await expect(page.getByRole('button', { name: '返回', exact: true })).toBeDisabled()
  release()
  await expect(page.getByText('保存成功', { exact: true })).toBeVisible()
})
