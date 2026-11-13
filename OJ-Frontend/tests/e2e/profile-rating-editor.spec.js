import { test, expect } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
const output = process.env.OJ_TEST_OUTPUT_DIR || '/tmp/muse-rating-editor'
async function install(page) {
  const state = { history: [], ratingFailure: false }
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-profile-token')
    localStorage.setItem('user', JSON.stringify({ id: 7, username: 'learner', role: 1 }))
  })
  await page.route('**/uploads/**', (route) =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1800"><rect width="1000" height="1800" fill="#eff6ff"/></svg>',
    }),
  )
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    let body = { code: 200, data: [] }
    if (path === '/api/user/info/7')
      body.data = {
        id: 7,
        username: 'learner',
        nickname: '学习者',
        createTime: '2026-09-01T10:30:00',
        rating: 1200,
        submitCount: 0,
        acceptedCount: 0,
        introduction: '## 我的介绍\n\n继续学习。',
      }
    else if (path === '/api/user/rating/history/7')
      body = state.ratingFailure
        ? { code: 503, errorCode: 'SERVICE_UNAVAILABLE', message: '合成积分读取失败' }
        : { code: 200, data: state.history }
    else if (path.includes('/activity/heatmap/')) body.data = { total: 0, days: [] }
    else if (path.includes('unread')) body.data = 0
    await route.fulfill({ json: body })
  })
  return state
}
async function shot(page, name) {
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: join(output, name), animations: 'disabled' })
}

test('registration point exists without contests and grows into real contest history', async ({
  page,
}) => {
  const state = await install(page)
  await page.goto('/user/7')
  const start = page.getByRole('img', { name: '注册起点，积分1200', exact: true })
  await expect(start).toBeVisible()
  await expect(page.locator('.chart-dot')).toHaveCount(1)
  await expect(page.locator('.rating-caption')).toContainText('2026-09-01')
  await expect(page.locator('.rating-empty')).toHaveCount(0)
  const url = page.url()
  await start.focus()
  await page.keyboard.press('Enter')
  expect(page.url()).toBe(url)
  await expect(page.locator('.my-tooltip')).toContainText('注册起点')
  await expect(page.locator('.my-tooltip')).not.toContainText('排名')
  await page
    .locator('.chart-card')
    .screenshot({ path: join(output, 'registration-rating.png'), animations: 'disabled' })
  state.history = [
    {
      contestId: 2,
      contestName: '第一场比赛',
      rating: 1160,
      ratingChange: -40,
      date: '2026-09-10T10:00:00',
      rank: 8,
    },
    {
      contestId: 3,
      contestName: '第二场比赛',
      rating: 1230,
      ratingChange: 70,
      date: '2026-09-17T10:00:00',
      rank: 3,
    },
  ]
  await page.reload()
  await expect(page.locator('.chart-dot')).toHaveCount(3)
  await expect(page.locator('.max-rating')).toContainText('1230')
  await expect(page.getByRole('link', { name: '第一场比赛，积分1160', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '第一场比赛，积分1160', exact: true }).focus()
  await expect(page.locator('.my-tooltip')).toContainText('-40')
  await expect(page.locator('.my-tooltip')).toContainText('排名: 8')
})
test('rating dependency failure remains an error rather than a registration fallback', async ({
  page,
}) => {
  const state = await install(page)
  state.ratingFailure = true
  await page.goto('/user/7')
  await expect(page.getByText('积分趋势加载失败', { exact: false })).toBeVisible()
  await expect(page.locator('.chart-card')).toHaveCount(0)
  state.ratingFailure = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('img', { name: '注册起点，积分1200', exact: true })).toBeVisible()
})
for (const [width, height] of [
  [1440, 900],
  [390, 844],
]) {
  test(`introduction dialog keeps its size and buttons fixed at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await install(page)
    await page.goto('/user/7')
    await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '编辑个人介绍', exact: true })).toHaveCSS(
      'transform',
      'none',
    )
    const dialog = page.locator('.introduction-dialog')
    const expectedWidth = width > 680 ? 1120 : width - 24
    await expect
      .poll(async () => Math.round((await dialog.boundingBox()).width))
      .toBe(expectedWidth)
    const save = page.getByRole('button', { name: '保存介绍', exact: true })
    const geometry = async () => ({
      dialog: await dialog.boundingBox(),
      button: await save.boundingBox(),
    })
    const before = await geometry()
    expect(before.dialog.y).toBeGreaterThanOrEqual(0)
    expect(before.dialog.y + before.dialog.height).toBeLessThanOrEqual(height + 1)
    async function stable() {
      const now = await geometry()
      for (const key of ['x', 'y', 'width', 'height'])
        expect(Math.abs(now.dialog[key] - before.dialog[key])).toBeLessThan(1)
      expect(Math.abs(now.button.y - before.button.y)).toBeLessThan(1)
      expect(now.button.y + now.button.height).toBeLessThanOrEqual(height)
    }
    await page.getByText('预览', { exact: true }).click()
    await stable()
    await page.getByText('编辑', { exact: true }).click()
    const long =
      '# 长介绍\n\n' +
      Array(60).fill('一段较长的学习介绍。').join('\n\n') +
      '\n\n![长图片](/uploads/long.png)\n\n```cpp\n' +
      'long_identifier_'.repeat(100) +
      '\n```'
    await page.getByLabel('Markdown个人介绍').fill(long)
    await page.getByText('预览', { exact: true }).click()
    await stable()
    expect(
      await page.locator('.editor-preview').evaluate((el) => el.scrollHeight > el.clientHeight),
    ).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await shot(page, `introduction-preview-${width}.png`)
    await page.getByText('编辑', { exact: true }).click()
    await stable()
    await page.getByLabel('Markdown个人介绍').fill('')
    await page.getByText('预览', { exact: true }).click()
    await stable()
    await page.getByText('编辑', { exact: true }).click()
    await stable()
    await shot(page, `introduction-editor-${width}.png`)
    writeFileSync(join(output, `dialog-geometry-${width}.json`), JSON.stringify(before, null, 2))
  })
}
