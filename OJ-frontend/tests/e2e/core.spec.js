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
