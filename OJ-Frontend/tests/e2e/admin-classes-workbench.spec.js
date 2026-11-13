import { test, expect } from '@playwright/test'
import { join } from 'node:path'

const rows = Array.from({ length: 48 }, (_, index) => ({
  id: index + 1,
  name: index < 24 ? '算法入门 ' + (index + 1) : '进阶训练 ' + (index + 1),
  school: index % 4 ? '睿思教学实验室' : null,
  teacherId: index % 5 ? 7 : 99,
  teacherName: index % 5 ? '林老师' : null,
  studentCount: index % 3 ? 16 + index : 0,
  description: index % 2 ? '每周练习与课堂讲解，循序掌握算法思维。\n请按教师安排完成训练。' : null,
  createTime: '2026-11-13 12:30:00',
}))
rows[47].name = '进阶训练：从基础思维到竞赛综合实践的完整班级课程与专项训练计划'

async function fixture(page, { role = 3, dark = false } = {}) {
  const state = { requests: [], fail: false, delay: false, empty: false, description: null }
  await page.addInitScript(
    ({ role, dark }) => {
      localStorage.setItem('accessToken', 'synthetic-classes-token')
      localStorage.setItem('refreshToken', 'synthetic-refresh')
      localStorage.setItem('user', JSON.stringify({ id: 1, username: 'synthetic', role }))
      localStorage.setItem('oj-dark-mode', dark ? '1' : '0')
    },
    { role, dark },
  )
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    const ok = (data) => route.fulfill({ json: { code: 200, message: '成功', data } })
    if (url.pathname === '/api/admin/classes/list') {
      const params = Object.fromEntries(url.searchParams)
      state.requests.push(params)
      if (state.delay && params.keyword === '算法')
        await new Promise((resolve) => setTimeout(resolve, 650))
      if (state.fail)
        return route.fulfill({
          status: 503,
          json: { code: 503, errorCode: 'DEPENDENCY_UNAVAILABLE', message: '测试服务暂时不可用' },
        })
      let records = state.empty
        ? []
        : rows.filter((row) => !params.keyword || row.name.includes(params.keyword))
      if (params.memberStatus === 'empty') records = records.filter((row) => !row.studentCount)
      if (params.memberStatus === 'hasMembers') records = records.filter((row) => row.studentCount)
      records.sort(
        params.sort === 'oldest'
          ? (a, b) => a.id - b.id
          : params.sort === 'name'
            ? (a, b) => a.name.localeCompare(b.name) || a.id - b.id
            : (a, b) => b.id - a.id,
      )
      const total = records.length
      const current = Number(params.page || 1)
      const size = Number(params.size || 20)
      records = records
        .slice((current - 1) * size, current * size)
        .map((row) => ({ ...row, description: state.description ?? row.description }))
      return ok({ records, total, current, size })
    }
    if (/^\/api\/class\/\d+$/.test(url.pathname))
      return ok(rows.find((row) => String(row.id) === url.pathname.split('/').pop()))
    if (url.pathname === '/api/class/members') return ok({ records: [], total: 0 })
    if (url.pathname.includes('/list') || url.pathname.includes('/my')) return ok([])
    return ok({})
  })
  return state
}

const table = (page) => page.locator('.class-table')
async function loaded(page) {
  await expect(table(page).locator('.class-name').first()).toBeVisible({ timeout: 20000 })
}
async function search(page, value) {
  await page.getByLabel('查找班级', { exact: true }).fill(value)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
}
async function choose(page, id, label) {
  await page
    .locator('.filter-field')
    .filter({ has: page.locator(id) })
    .locator('.el-select')
    .click()
  await page.getByRole('option', { name: label, exact: true }).click()
}
test.beforeEach(async ({ page }) => {
  page.setDefaultTimeout(20000)
})

test('search from a later page resets page and returns after manage navigation', async ({
  page,
}) => {
  const state = await fixture(page)
  await page.goto('/admin/classes?page=2')
  await loaded(page)
  expect(state.requests).toHaveLength(1)
  await search(page, '算法')
  await expect(page).toHaveURL(/keyword=.*$/)
  await expect.poll(() => state.requests.at(-1)?.page).toBe('1')
  await loaded(page)
  await expect(table(page).locator('.class-name').first()).toHaveText('算法入门 24')
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page).toHaveURL(/page=2/)
  await loaded(page)
  await table(page).getByRole('button', { name: '进入管理' }).first().click()
  await expect(page).toHaveURL(/\/admin\/class\/\d+/)
  await page.goBack()
  await expect(page).toHaveURL(/keyword=.*page=2/)
  await loaded(page)
  await expect(page.getByLabel('查找班级', { exact: true })).toHaveValue('算法')
  await expect(table(page).locator('.class-name').first()).toHaveText('算法入门 4')
})

test('member filters, sorting and size operate through the server query', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/admin/classes?page=2')
  await loaded(page)
  await choose(page, '#class-members', '暂无学生')
  await loaded(page)
  expect(state.requests.at(-1)).toMatchObject({ page: '1', memberStatus: 'empty' })
  await expect(table(page).locator('.member-count').first()).toHaveText('0 人')
  await choose(page, '#class-sort', '最早创建')
  await expect(table(page).locator('.class-name').first()).toHaveText('算法入门 1')
  await page.locator('.page-size').click()
  await page.getByRole('option', { name: '10 个 / 页', exact: true }).click()
  await expect.poll(() => state.requests.at(-1)?.size).toBe('10')
  await expect(table(page).locator('.class-name')).toHaveCount(10)
  await page.reload()
  await loaded(page)
  expect(state.requests.at(-1)).toMatchObject({
    page: '1',
    size: '10',
    memberStatus: 'empty',
    sort: 'oldest',
  })
})

test('a delayed previous search cannot overwrite the latest result', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/admin/classes')
  await loaded(page)
  state.delay = true
  await search(page, '算法')
  await expect.poll(() => state.requests.at(-1)?.keyword).toBe('算法')
  await search(page, '进阶')
  await loaded(page)
  await expect(table(page).locator('.class-name').first()).toContainText('进阶')
  // Wait for the intentionally delayed handler to resolve, then recheck visible state.
  await expect.poll(() => state.requests.filter((item) => item.keyword).length).toBe(2)
  await page.waitForTimeout(750)
  await expect(table(page).locator('.class-name').first()).toContainText('进阶')
  await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0)
})

test('failure preserves the last successful results and retry recovers', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/admin/classes')
  await loaded(page)
  state.fail = true
  await page.getByRole('button', { name: '刷新列表' }).click()
  await expect(page.getByRole('alert')).toContainText('保留上次加载的结果')
  await expect(table(page).locator('.class-name')).toHaveCount(20)
  state.fail = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await loaded(page)
})

test('initial failure, no results and empty directory have different recovery states', async ({
  page,
}) => {
  const state = await fixture(page)
  state.fail = true
  await page.goto('/admin/classes')
  await expect(page.getByRole('alert')).toContainText('班级列表加载失败')
  await expect(page.getByRole('heading', { name: '还没有班级' })).toHaveCount(0)
  state.fail = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await loaded(page)
  await search(page, '不存在')
  await expect(page.getByRole('heading', { name: '没有找到匹配的班级' })).toBeVisible()
  await page.getByRole('button', { name: '重置筛选', exact: true }).first().click()
  await loaded(page)
  state.empty = true
  await page.getByRole('button', { name: '刷新列表' }).click()
  await expect(page.getByRole('heading', { name: '还没有班级' })).toBeVisible()
})

test('preview is local, refreshes its snapshot and closes on new query', async ({ page }) => {
  const state = await fixture(page)
  const detailRequests = []
  page.on('request', (request) => {
    if (/\/api\/class\/\d+$/.test(request.url())) detailRequests.push(request.url())
  })
  await page.goto('/admin/classes')
  await loaded(page)
  await table(page).locator('.class-name').first().focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog', { name: '班级预览' })).toBeVisible()
  await expect(page.locator('.preview-description')).toContainText('每周练习')
  expect(detailRequests).toHaveLength(0)
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  state.description = '刷新后的班级简介'
  await page.getByRole('button', { name: '刷新列表' }).click()
  await loaded(page)
  await table(page).getByRole('button', { name: '预览', exact: true }).first().click()
  await expect(page.locator('.preview-description')).toContainText('刷新后的班级简介')
  await page.keyboard.press('Escape')
  await search(page, '算法')
  await loaded(page)
  await expect(page.getByRole('dialog', { name: '班级预览' })).toHaveCount(0)
})

test('invalid shared parameters normalize once and an obsolete page falls back', async ({
  page,
}) => {
  const state = await fixture(page)
  await page.goto('/admin/classes?page=-2&size=999&sort=unsafe&memberStatus=no&keyword=a&keyword=b')
  await loaded(page)
  await expect(page).toHaveURL(/\/admin\/classes$/)
  expect(state.requests).toHaveLength(1)
  await page.goto('/admin/classes?page=999')
  await loaded(page)
  await expect(page).toHaveURL(/page=3/)
  await expect(table(page).locator('.class-name')).toHaveCount(8)
})

test('teacher cannot open the administrator directory by URL', async ({ page }) => {
  const state = await fixture(page, { role: 2 })
  await page.goto('/admin/classes')
  await expect(page).toHaveURL(/\/admin$/)
  expect(state.requests).toHaveLength(0)
})

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
  { width: 1366, height: 768 },
  { width: 1920, height: 1080 },
]) {
  for (const dark of [false, true]) {
    test('layout ' + viewport.width + ' ' + (dark ? 'dark' : 'light'), async ({ page }) => {
      const errors = []
      page.on('pageerror', (error) => errors.push(error.message))
      await fixture(page, { dark })
      await page.setViewportSize(viewport)
      await page.goto('/admin/classes')
      await expect(
        page.locator('.class-table .class-name').first(),
      ).toBeVisible()
      await expect(page.getByRole('heading', { name: '全部班级', exact: true })).toBeVisible()
      const bounds = await page
        .locator('.admin-classes')
        .evaluate((node) => ({
          right: node.getBoundingClientRect().right,
          viewport: window.innerWidth,
          width: document.documentElement.scrollWidth,
        }))
      expect(bounds.right).toBeLessThanOrEqual(bounds.viewport + 1)
      expect(bounds.width).toBeLessThanOrEqual(bounds.viewport)
      await expect(page.locator('.admin-side')).toBeVisible()
      await expect(page.getByRole('button', { name: '返回主站', exact: true })).toBeVisible()
      await page.locator('.class-table').getByRole('button', { name: '预览', exact: true }).first().click()
      await expect(page.getByRole('dialog', { name: '班级预览' })).toBeVisible()
      await page.getByRole('button', { name: '关闭', exact: true }).click()
      await expect(page.getByRole('dialog', { name: '班级预览' })).not.toBeVisible()
      await page.locator('.admin-main').evaluate((node) => { node.scrollTop = 0 })
      if (process.env.OJ_TEST_OUTPUT_DIR)
        await page.screenshot({
          path: join(
            process.env.OJ_TEST_OUTPUT_DIR,
            'classes-' + viewport.width + '-' + (dark ? 'dark' : 'light') + '.png',
          ),
          fullPage: true,
        })
      expect(errors).toEqual([])
    })
  }
}

test('desktop navigation supports keyboard activation and stays visible after resizing', async ({ page }) => {
  await fixture(page)
  await page.setViewportSize({ width: 1366, height: 768 })
  await page.goto('/admin/classes')
  const brand = page.getByRole('button', { name: '回到管理中心首页', exact: true })
  await brand.focus()
  await expect(brand).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/admin$/)
  await page.goto('/admin/classes')
  await page.setViewportSize({ width: 1024, height: 768 })
  await expect(page.locator('.admin-side')).toBeVisible()
  await expect(page.locator('.admin-side')).not.toHaveAttribute('inert')
  const back = page.getByRole('button', { name: '返回主站', exact: true })
  await back.focus()
  await expect(back).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/$/)
})
