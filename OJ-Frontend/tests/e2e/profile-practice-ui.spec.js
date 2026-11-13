import { test, expect } from '@playwright/test'
import { join } from 'node:path'
import { mkdirSync } from 'node:fs'
const output = process.env.OJ_TEST_OUTPUT_DIR || '/tmp/muse-profile-ui'
const image = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAd8AAAAASUVORK5CYII=',
  'base64',
)
const success = (data) => ({ code: 200, data })
function fixtures() {
  return {
    user: {
      id: 7,
      username: 'learner',
      nickname: '林间的学习者',
      motto: '把每一次练习，连成自己的成长轨迹。',
      school: '算法学习小组',
      role: 1,
      submitCount: 127,
      acceptedCount: 36,
      rating: 1450,
      followingCount: 12,
      followerCount: 8,
      likeCount: 16,
      coverUrl: '',
      coverPosition: 50,
      introduction:
        '## 你好，我是林间的学习者\n\n正在学习 **C++ 与算法**，喜欢把复杂的问题拆成容易理解的小步骤。\n\n- 最近在练习图论与动态规划\n- 希望每周认真完成一次复盘\n\n> 慢慢来，每一步都算数。',
    },
    practice: {
      range: 'all',
      periodStart: null,
      periodEnd: null,
      listLimit: 50,
      overview: { totalSubmissions: 127, solvedProblemCount: 36, acRate: 68.5, activeDays: 18 },
      dimensions: [
        { key: 'ALGORITHM', label: '算法', attempted: 21, passed: 16, mastery: 72 },
        { key: 'DATA_STRUCTURE', label: '数据结构', attempted: 12, passed: 8, mastery: 58 },
        { key: 'DP', label: '动态规划', attempted: 9, passed: 5, mastery: 41 },
        { key: 'MATH', label: '数学', attempted: 6, passed: 4, mastery: 47 },
        { key: 'GRAPH', label: '图论', attempted: 0, passed: 0, mastery: 0 },
        { key: 'STRING', label: '字符串', attempted: 4, passed: 3, mastery: 52 },
      ],
      tags: [
        { tagId: 1, tagName: '数组', passed: 12 },
        { tagId: 2, tagName: '二分查找', passed: 8 },
        { tagId: 3, tagName: '动态规划', passed: 5 },
        { tagId: 4, tagName: '字符串', passed: 3 },
      ],
      difficulty: Array.from({ length: 23 }, (_, i) => ({
        rating: 800 + i * 100,
        solved: i < 6 ? [8, 12, 6, 5, 3, 2][i] : 0,
      })),
      solvedProblems: [
        {
          problemId: 11,
          title: '灯塔信号换装：十进制转 K 进制',
          difficulty: 900,
          solvedAt: '2026-11-08T16:23:00',
        },
        {
          problemId: 12,
          title: '魔法森林探险：DFS 遍历',
          difficulty: 1100,
          solvedAt: '2026-11-09T11:18:00',
        },
      ],
      triedProblems: [
        {
          problemId: 13,
          title: '还需要一点耐心的动态规划',
          difficulty: 1600,
          lastAttemptAt: '2026-11-10T09:10:00',
        },
      ],
    },
    writes: [],
    uploads: 0,
    saveFailure: false,
    uploadFailure: false,
    private: false,
    practiceFailure: false,
    heatmapFailure: false,
  }
}
async function install(page, state, { self = true } = {}) {
  await page.addInitScript(
    ({ self }) => {
      localStorage.setItem('accessToken', 'synthetic-profile-token')
      localStorage.setItem(
        'user',
        JSON.stringify({ id: self ? 7 : 8, username: 'learner', role: 1 }),
      )
    },
    { self },
  )
  await page.route('**/uploads/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: image }),
  )
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    let body = success([])
    if (path === '/api/user/info/7')
      body = state.private
        ? { code: 403, errorCode: 'ACCESS_DENIED', message: '该用户未公开主页' }
        : success(state.user)
    else if (path === '/api/user/profile') {
      if (state.saveGate) await state.saveGate
      const patch = route.request().postDataJSON()
      state.writes.push(patch)
      if (state.saveFailure)
        body = { code: 500, errorCode: 'INTERNAL_ERROR', message: '合成保存失败' }
      else {
        Object.assign(state.user, patch)
        body = success(state.user)
      }
    } else if (path === '/api/file/upload') {
      if (state.uploadGate) await state.uploadGate
      state.uploads++
      body = state.uploadFailure
        ? { code: 500, errorCode: 'INTERNAL_ERROR', message: '合成上传失败' }
        : success('/uploads/profile-test.png')
    } else if (path.includes('/activity/heatmap/')) {
      const today = new Date()
      const date = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
      body = state.heatmapFailure
        ? { code: 503, errorCode: 'SERVICE_UNAVAILABLE', message: '合成日历失败' }
        : success({ total: 127, days: [{ date, count: 5 }] })
    } else if (path.includes('/recent-ac/'))
      body = success([
        {
          problemId: 12,
          title: '魔法森林探险：DFS 遍历',
          language: 'cpp',
          runTime: 47,
          createTime: '2026-11-09T11:18:00',
        },
      ])
    else if (path.includes('/rating/history/')) body = success([])
    else if (path === '/api/profile/practice/me') {
      if (state.practiceFailure)
        body = { code: 503, errorCode: 'SERVICE_UNAVAILABLE', message: '合成分析失败' }
      else
        body = success({
          ...state.practice,
          range: url.searchParams.get('range'),
          periodStart: '2026-10-12',
          periodEnd: '2026-11-10',
        })
    } else if (path.includes('unread')) body = success(0)
    await route.fulfill({ json: body })
  })
}
async function shot(page, name) {
  mkdirSync(output, { recursive: true })
  await page.screenshot({ path: join(output, name), fullPage: true, animations: 'disabled' })
}
async function noOverflow(page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
  expect(
    await page.locator('.main-content').evaluate((el) => el.scrollWidth <= el.clientWidth + 1),
  ).toBe(true)
}

test('profile cover upload, focus, cancel, save and restore are isolated updates', async ({
  page,
}) => {
  const state = fixtures()
  await install(page, state)
  await page.goto('/user/7')
  await expect(page.getByRole('heading', { name: '个人介绍', exact: true })).toBeVisible()
  await shot(page, 'profile-preview.png')
  await page.getByRole('button', { name: '更换封面' }).click()
  await page
    .getByLabel('上传主页封面')
    .setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: image })
  await expect(page.locator('.cover-preview img')).toBeVisible()
  await page.getByLabel('封面垂直位置').fill('25')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(state.writes).toHaveLength(0)
  await page.getByRole('button', { name: '更换封面' }).click()
  await page
    .getByLabel('上传主页封面')
    .setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: image })
  await expect(page.locator('.cover-preview img')).toBeVisible()
  await page.getByLabel('封面垂直位置').fill('30')
  await page.getByRole('button', { name: '保存封面' }).click()
  await expect(page.locator('.user-banner > img')).toBeVisible()
  expect(state.writes.at(-1)).toEqual({ coverUrl: '/uploads/profile-test.png', coverPosition: 30 })
  await page.reload()
  await expect(page.locator('.user-banner > img')).toHaveCSS('object-position', '50% 30%')
  await page.getByRole('button', { name: '更换封面' }).click()
  await page.getByRole('button', { name: '恢复默认', exact: true }).click()
  await page.getByRole('button', { name: '保存封面' }).click()
  await expect(page.locator('.user-banner > img')).toHaveCount(0)
  expect(state.user.introduction).toContain('你好')
})
test('Markdown editing uploads images, previews safely, persists and cancels without writes', async ({
  page,
}) => {
  const state = fixtures()
  await install(page, state)
  await page.goto('/user/7')
  await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
  await page
    .getByLabel('Markdown个人介绍')
    .fill('# 我的新介绍\n\n**继续学习**\n\n<script>window.profileInjected=true</script>')
  await page.getByRole('button', { name: '上传图片', exact: true }).click()
  await page
    .getByLabel('个人介绍图片')
    .setInputFiles({ name: 'note.png', mimeType: 'image/png', buffer: image })
  await expect
    .poll(() => page.getByLabel('Markdown个人介绍').inputValue())
    .toContain('/uploads/profile-test.png')
  await page.getByText('预览', { exact: true }).click()
  await expect(page.locator('.editor-preview h1')).toHaveText('我的新介绍')
  await expect(page.locator('.editor-preview img')).toBeVisible()
  await expect(page.locator('.editor-preview script')).toHaveCount(0)
  await page.getByRole('button', { name: '保存介绍', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(Object.keys(state.writes.at(-1))).toEqual(['introduction'])
  await page.reload()
  await expect(page.locator('.intro-content h1')).toHaveText('我的新介绍')
  await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
  await page.getByLabel('Markdown个人介绍').fill('未保存')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.locator('.intro-content h1')).toHaveText('我的新介绍')
})
test('failed cover uploads and failed introduction saves preserve committed content', async ({
  page,
}) => {
  const state = fixtures()
  await install(page, state)
  await page.goto('/user/7')
  state.uploadFailure = true
  await page.getByRole('button', { name: '更换封面' }).click()
  await page
    .getByLabel('上传主页封面')
    .setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: image })
  await expect(page.locator('.cover-error')).toBeVisible()
  expect(state.writes).toHaveLength(0)
  await page.getByRole('button', { name: '取消', exact: true }).click()
  state.saveFailure = true
  await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
  await page.getByLabel('Markdown个人介绍').fill('不会保存的内容')
  await page.getByRole('button', { name: '保存介绍', exact: true }).click()
  await expect(page.locator('.save-error')).toHaveText('合成保存失败')
  expect(state.user.introduction).toContain('你好')
})
test('private and visitor pages expose no edit controls or empty introduction', async ({
  page,
}) => {
  const state = fixtures()
  state.user.introduction = ''
  await install(page, state, { self: false })
  await page.goto('/user/7')
  await expect(page.getByRole('button', { name: '+ 关注' })).toBeVisible()
  await expect(page.getByRole('button', { name: '更换封面' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '个人介绍', exact: true })).toHaveCount(0)
  state.private = true
  await page.reload()
  await expect(page.getByText('该用户未公开主页，登录后可查看', { exact: true })).toBeVisible()
  await expect(page.locator('.profile-main')).toHaveCount(0)
})
test('practice range, complete difficulty, chronological records and failure retry', async ({
  page,
}) => {
  const state = fixtures()
  await install(page, state)
  state.practice.dimensions[0].mastery = 100
  await page.goto('/user/practice')
  await expect(page.getByRole('heading', { name: '标签覆盖', exact: true })).toBeVisible()
  const outerPoint = (await page.locator('.radar-grid').last().getAttribute('points')).split(' ')[0]
  const firstDot = page.locator('.radar-dot').first()
  expect(`${await firstDot.getAttribute('cx')},${await firstDot.getAttribute('cy')}`).toBe(
    outerPoint,
  )
  await expect(page.locator('.bar-col')).toHaveCount(7)
  await page.getByRole('button', { name: '完整区间', exact: true }).click()
  await expect(page.locator('.bar-col')).toHaveCount(23)
  expect(
    await page
      .locator('.bar')
      .last()
      .evaluate((el) => el.getBoundingClientRect().height),
  ).toBe(0)
  await expect(page.locator('.problem-name').first()).toContainText('魔法森林')
  await page.getByText('尝试未通过', { exact: true }).click()
  await expect(page.locator('.problem-action')).toHaveText('继续练习 ↗')
  await page.getByText('近 30 天', { exact: true }).click()
  await expect(page.locator('.practice-page-header p')).toHaveText('2026.10.12 — 2026.11.10')
  state.practiceFailure = true
  await page.getByText('全部', { exact: true }).click()
  await expect(page.getByText('练习分析加载失败', { exact: true })).toBeVisible()
  await expect(page.locator('.overview-grid')).toHaveCount(0)
  state.practiceFailure = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.locator('.overview-grid')).toBeVisible()
})
test('both pages fit desktop and mobile, including long Markdown and dark theme', async ({
  page,
}) => {
  const state = fixtures()
  state.user.introduction +=
    '\n\n' +
    Array(12).fill('这是较长的个人介绍，用来检查阅读与展开。').join('\n\n') +
    '\n\n```cpp\n' +
    'very_long_identifier_'.repeat(50) +
    '\n```'
  await install(page, state)
  for (const [width, height] of [
    [1440, 900],
    [1366, 768],
    [1024, 768],
    [768, 900],
    [390, 844],
  ]) {
    await page.setViewportSize({ width, height })
    await page.goto('/user/7')
    await expect(page.locator('.profile-main')).toBeVisible()
    await noOverflow(page)
    await expect(page.getByRole('button', { name: '展开全文', exact: true })).toBeVisible()
    if (width === 1440) await shot(page, 'profile-desktop.png')
    if (width === 390) await shot(page, 'profile-mobile.png')
    await page.goto('/user/practice')
    await expect(page.locator('.overview-grid')).toBeVisible()
    await noOverflow(page)
    if (width === 1440) await shot(page, 'practice-desktop.png')
    if (width === 390) await shot(page, 'practice-mobile.png')
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/user/7')
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await noOverflow(page)
  await expect(page.locator('.intro-card')).toHaveCSS('background-color', 'rgb(22, 27, 34)')
  await shot(page, 'profile-dark.png')
  await page.goto('/user/practice')
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await expect(page.locator('.overview-grid')).toBeVisible()
  await expect(page.locator('.ability-card')).toHaveCSS('background-color', 'rgb(22, 27, 34)')
  await shot(page, 'practice-dark.png')
})
test('empty practice and failed heatmap remain meaningful distinct states', async ({ page }) => {
  const state = fixtures()
  state.practice.overview = { totalSubmissions: 0, solvedProblemCount: 0, acRate: 0, activeDays: 0 }
  state.practice.dimensions = []
  state.practice.tags = []
  state.practice.difficulty = []
  state.practice.solvedProblems = []
  state.practice.triedProblems = []
  await install(page, state)
  await page.goto('/user/practice')
  await expect(page.getByText('暂无相关练习记录')).toHaveCount(4)
  state.heatmapFailure = true
  await page.goto('/user/7')
  await expect(page.getByText('活跃日历加载失败，请重试', { exact: false })).toBeVisible()
  await expect(page.locator('.heatmap-grid')).toHaveCount(0)
})

test('pending upload and save lock editing and restore the insertion cursor', async ({ page }) => {
  const state = fixtures()
  await install(page, state)
  await page.goto('/user/7')
  await page.getByRole('button', { name: '编辑介绍', exact: true }).click()
  await page.getByLabel('Markdown个人介绍').fill('这一段要保存')
  let releaseUpload
  state.uploadGate = new Promise((resolve) => {
    releaseUpload = resolve
  })
  await page.getByRole('button', { name: '上传图片', exact: true }).click()
  await page
    .getByLabel('个人介绍图片')
    .setInputFiles({ name: 'note.png', mimeType: 'image/png', buffer: image })
  await expect(page.getByLabel('Markdown个人介绍')).toBeDisabled()
  await expect(page.getByRole('button', { name: '保存介绍', exact: true })).toBeDisabled()
  releaseUpload()
  await expect(page.getByLabel('Markdown个人介绍')).toBeEnabled()
  await expect(page.getByLabel('Markdown个人介绍')).toBeFocused()
  const submitted = await page.getByLabel('Markdown个人介绍').inputValue()
  let releaseSave
  state.saveGate = new Promise((resolve) => {
    releaseSave = resolve
  })
  await page.getByRole('button', { name: '保存介绍', exact: true }).click()
  await expect(page.getByLabel('Markdown个人介绍')).toBeDisabled()
  await expect(page.getByRole('button', { name: '上传图片', exact: true })).toBeDisabled()
  releaseSave()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(state.user.introduction).toBe(submitted)
})
