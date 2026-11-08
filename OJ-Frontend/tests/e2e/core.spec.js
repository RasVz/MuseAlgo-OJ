import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'

async function login(page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill('student07')
  await page.getByPlaceholder('请输入密码').fill('123456')
  const [response] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST'),
    page.getByRole('button', { name: '立即登录' }).click()
  ])
  expect((await response.json()).code).toBe(200)
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15000 })
}

test('student signs in, opens problem and reads the real accepted result', async ({ page }) => {
  const evidence = JSON.parse(readFileSync(process.env.OJ_TEST_EVIDENCE, 'utf8'))
  await login(page)
  await page.goto('/problem/1')
  await expect(page.getByText('两数之和', { exact: false }).first()).toBeVisible()
  await page.goto(`/submission/${evidence.submissions[0].id}`)
  await expect(page.getByText('通过 (Accepted)', { exact: true })).toBeVisible()
  await expect(page.locator('.value.score')).toHaveText('100')
})

test('student can find the assigned homework and its completed progress', async ({ page }) => {
  const evidence = JSON.parse(readFileSync(process.env.OJ_TEST_EVIDENCE, 'utf8'))
  await login(page)
  await page.goto(`/problem/list/${evidence.homework_id}`)
  await expect(page.getByText(`${evidence.class_name}-homework`, { exact: false }).first()).toBeVisible()
  await expect(page.getByText('班级作业', { exact: true })).toBeVisible()
  await expect(page.getByRole('progressbar')).toContainText('1')
  await page.getByText('题目 (1)', { exact: true }).click()
  await expect(page.getByText('两数之和', { exact: false }).first()).toBeVisible()
})

test('student opens the real practice analysis page and switches the time range', async ({ page }) => {
  await login(page)

  // 从用户下拉进入练习分析；等真实聚合接口返回后再对账，不写死任何统计数字。
  await page.locator('.user-dropdown-link').click()
  const [overviewResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().includes('/api/profile/practice/me') && response.request().method() === 'GET'
    ),
    page.getByRole('menuitem', { name: '练习分析' }).click()
  ])
  const overviewBody = await overviewResponse.json()
  expect(overviewBody.code).toBe(200)
  expect(overviewBody.data.range).toBe('all')

  await expect(page.getByText('练习分析', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('AC 率', { exact: false }).first()).toBeVisible()
  await expect(
    page.locator('.stat-card').filter({ hasText: '提交总数' }).locator('.value')
  ).toHaveText(String(overviewBody.data.overview.totalSubmissions))

  // 原型页的假题号不应再出现。
  await expect(page.getByText('P1000', { exact: false })).toHaveCount(0)
  await expect(page.getByText('CF1A', { exact: false })).toHaveCount(0)

  // 切换到近 30 天：必须重新请求（range=30d），并仍展示四格指标。
  const [monthResponse] = await Promise.all([
    page.waitForResponse(response =>
      response.url().includes('/api/profile/practice/me') && response.url().includes('range=30d')
    ),
    page.getByText('近 30 天', { exact: true }).click()
  ])
  const monthBody = await monthResponse.json()
  expect(monthBody.code).toBe(200)
  expect(monthBody.data.range).toBe('30d')
  await expect(page.locator('.stat-card')).toHaveCount(4)
  await expect(
    page.locator('.stat-card').filter({ hasText: '提交总数' }).locator('.value')
  ).toHaveText(String(monthBody.data.overview.totalSubmissions))
})
