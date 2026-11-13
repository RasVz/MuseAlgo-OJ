// Opt-in local persistence check using a disposable session; credentials never enter this repository.
import { test, expect } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
const sessionFile = process.env.OJ_PROFILE_SESSION_FILE

test('local profile edits and image uploads survive a real server readback', async ({
  page,
  request,
}) => {
  test.skip(!sessionFile, 'Requires a disposable local QA session')
  const session = JSON.parse(readFileSync(sessionFile, 'utf8'))
  const id = session.user.id,
    uploads = []
  await page.addInitScript((session) => {
    localStorage.setItem('accessToken', session.accessToken)
    localStorage.setItem('refreshToken', session.refreshToken)
    localStorage.setItem('user', JSON.stringify(session.user))
  }, session)
  page.on('response', async (response) => {
    if (new URL(response.url()).pathname === '/api/file/upload') {
      const result = await response.json().catch(() => null)
      if (result?.code === 200) uploads.push(result.data)
    }
  })
  const png = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAd8AAAAASUVORK5CYII=',
    'base64',
  )
  try {
    await page.goto(`/user/${id}`)
    await page.getByRole('button', { name: '更换封面' }).click()
    await page
      .getByLabel('上传主页封面')
      .setInputFiles({ name: 'qa-cover.png', mimeType: 'image/png', buffer: png })
    await expect(page.locator('.cover-preview img')).toBeVisible()
    await page.getByLabel('封面垂直位置').fill('33')
    await page.getByRole('button', { name: '保存封面' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.reload()
    await expect(page.locator('.user-banner > img')).toHaveCSS('object-position', '50% 33%')
    await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
    await page
      .getByLabel('Markdown个人介绍')
      .fill('# 真实保存验收\n\n**这是合成账号的介绍**\n\n- 支持图片\n- 保存后刷新仍然存在')
    await page.getByRole('button', { name: '上传图片', exact: true }).click()
    await page
      .getByLabel('个人介绍图片')
      .setInputFiles({ name: 'qa-intro.png', mimeType: 'image/png', buffer: png })
    await expect.poll(() => page.getByLabel('Markdown个人介绍').inputValue()).toContain('/uploads/')
    await page.getByText('预览', { exact: true }).click()
    await expect(page.locator('.editor-preview img')).toBeVisible()
    await page.getByRole('button', { name: '保存介绍', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await page.reload()
    await expect(page.locator('.intro-content h1')).toHaveText('真实保存验收')
    await expect(page.locator('.intro-content img')).toBeVisible()
    const result = await request.get(`/api/user/info/${id}`)
    const body = await result.json()
    expect(body.code).toBe(200)
    expect(body.data.coverPosition).toBe(33)
    expect(body.data.introduction).toContain('![图片]')
    await page.goto('/user/practice')
    await expect(page.locator('.overview-grid')).toBeVisible()
    await expect(page.getByText('暂无相关练习记录')).toHaveCount(4)
  } finally {
    writeFileSync(
      join(process.env.OJ_TEST_OUTPUT_DIR || '/tmp', 'real-uploaded-images.json'),
      JSON.stringify(uploads),
    )
  }
})
