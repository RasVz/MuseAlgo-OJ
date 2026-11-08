import { test, expect } from '@playwright/test'

test('wrong credentials show the login error without a session-expired prompt or refresh', async ({ page }) => {
  let refreshRequests = 0
  page.on('request', request => {
    if (request.url().includes('/api/auth/refresh')) refreshRequests += 1
  })
  await page.goto('/login')
  await page.getByPlaceholder('请输入账号').fill('student07')
  await page.getByPlaceholder('请输入密码').fill('invalid-password')
  const [response] = await Promise.all([
    page.waitForResponse(response => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST'),
    page.getByRole('button', { name: '立即登录' }).click()
  ])
  expect(response.status()).toBe(200)
  expect(await response.json()).toMatchObject({ code: 401, message: '用户名或密码错误' })
  await expect(page.getByText('用户名或密码错误', { exact: true })).toBeVisible()
  await expect(page.getByText('您的登录状态已失效，无法继续操作。是否立即前往登录？')).toHaveCount(0)
  await expect(page).toHaveURL(/\/login/)
  expect(refreshRequests).toBe(0)
})
