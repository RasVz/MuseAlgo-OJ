import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// 题面工作区：三属性版本、统一工具栏、无状态提示、差异大弹窗。
test.describe.configure({ timeout: 90000 })

const EVIDENCE = process.env.OJ_TEST_OUTPUT_DIR
  ? join(process.env.OJ_TEST_OUTPUT_DIR, 'problem-statement-studio')
  : join(process.cwd(), '..', '..', 'outputs', 'problem-format-workbench-20261113')
mkdirSync(EVIDENCE, { recursive: true })

const CONTENT_V3 = [
  '给定一个整数数组 $a$ 和一个目标值 $t$，请找出数组中和为 $t$ 的两个数，并返回它们的下标。',
  '',
  '## 数据范围',
  '',
  '- $1 \\le n \\le 10^5$'
].join('\n')

const CONTENT_V2 = '原英文题面：Given an array of integers, return indices of the two numbers.'

const problemDetail = {
  id: 12,
  title: '两数之和',
  content: CONTENT_V3,
  inputDesc: '第一行一个整数 $n$，第二行 $n$ 个整数，第三行目标值 $t$。',
  outputDesc: '输出两个下标（从 1 开始），用空格分隔。',
  hint: '注意同一元素不能重复使用。',
  timeLimit: 2000,
  memoryLimit: 256,
  difficulty: 1200,
  tags: '哈希表,数组',
  source: 'LeetCode',
  isPublic: 1,
  samples: [
    { input: '4\n2 7 11 15\n9', output: '1 2' },
    { input: '3\n3 2 4\n6', output: '2 3' }
  ]
}

const rawVersions = [
  { id: 303, versionNo: 3, status: 'LIVE', source: 'MANUAL', title: '两数之和', content: CONTENT_V3, timeLimit: 2000, memoryLimit: 256, baseVersionId: 302, issues: [], createTime: '2026-11-13T10:20:00', live: true },
  { id: 302, versionNo: 2, status: 'ARCHIVED', source: 'AI', title: 'Two Sum', content: CONTENT_V2, timeLimit: 1000, memoryLimit: 512, baseVersionId: 301, issues: [], createTime: '2026-11-12T09:00:00', live: false },
  { id: 301, versionNo: 1, status: 'ARCHIVED', source: 'INITIAL', title: '初始题面', content: CONTENT_V2, timeLimit: 1000, memoryLimit: 512, baseVersionId: null, issues: [], createTime: '2026-11-11T08:00:00', live: false }
]

let versions = []

function toDetail(version) {
  return {
    id: version.id,
    problemId: 12,
    versionNo: version.versionNo,
    status: version.status,
    source: version.source,
    title: version.title,
    content: version.content,
    inputDesc: '第一行一个整数 $n$，第二行 $n$ 个整数，第三行目标值 $t$。',
    outputDesc: '输出两个下标（从 1 开始），用空格分隔。',
    hint: '注意同一元素不能重复使用。',
    timeLimit: version.timeLimit,
    memoryLimit: version.memoryLimit,
    samples: problemDetail.samples,
    issues: version.issues,
    baseVersionId: version.baseVersionId,
    jobId: null,
    note: null,
    createdBy: 3,
    createTime: version.createTime
  }
}

function toVO(version) {
  return {
    id: version.id,
    problemId: 12,
    versionNo: version.versionNo,
    status: version.status,
    source: version.source,
    title: version.title,
    summary: String(version.content).slice(0, 40),
    timeLimit: version.timeLimit,
    memoryLimit: version.memoryLimit,
    sampleCount: problemDetail.samples.length,
    baseVersionId: version.baseVersionId,
    jobId: null,
    issueErrors: 0,
    issueWarnings: 0,
    createdBy: 3,
    createTime: version.createTime,
    live: version.live === true,
    drifted: false
  }
}

async function setup(page) {
  versions = rawVersions.map((v) => ({ ...v }))
  const state = { composeBodies: [], nextVersionId: 400, aiVersionAdded: false }

  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-studio-token')
    localStorage.setItem('refreshToken', 'synthetic-refresh')
    localStorage.setItem('user', JSON.stringify({ id: 3, username: 'admin', nickname: '管理员', role: 3 }))
  })

  await page.route((url) => new URL(url).pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const json = (data, message = '成功') =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message }) })

    if (path === '/api/user/info') return json({ id: 3, username: 'admin', nickname: '管理员', role: 3 })
    if (path === '/api/problem/12' && method === 'GET') return json(problemDetail)
    if (path === '/api/problem/format/jobs/active') return json(null)
    if (path === '/api/problem/12/statement/versions' && method === 'GET') return json(versions.map(toVO))

    if (path === '/api/problem/12/statement/versions/compose' && method === 'POST') {
      const body = request.postDataJSON()
      state.composeBodies.push(body)
      let saved
      if (body.mode === 'SAVE') {
        const target = versions.find((v) => v.id === body.baseVersionId)
        Object.assign(target, {
          title: body.formatted.title,
          content: body.formatted.content,
          timeLimit: body.formatted.timeLimitMs,
          memoryLimit: body.formatted.memoryLimitMb,
          status: 'LIVE',
          live: true
        })
        versions.forEach((v) => { if (v !== target) { v.live = false; if (v.status === 'LIVE') v.status = 'ARCHIVED' } })
        saved = target
      } else {
        saved = {
          id: state.nextVersionId,
          versionNo: Math.max(...versions.map((v) => v.versionNo)) + 1,
          status: 'LIVE',
          source: body.source,
          title: body.formatted.title,
          content: body.formatted.content,
          timeLimit: body.formatted.timeLimitMs,
          memoryLimit: body.formatted.memoryLimitMb,
          baseVersionId: body.baseVersionId,
          issues: [],
          createTime: '2026-11-13T12:00:00',
          live: true
        }
        versions.forEach((v) => { v.live = false; if (v.status === 'LIVE') v.status = 'ARCHIVED' })
        versions = [saved, ...versions]
        state.nextVersionId += 1
      }
      return json(toVO(saved), '保存成功')
    }

    if (path === '/api/problem/12/statement/versions/optimize' && method === 'POST') {
      return json({ id: 91, status: 'PENDING', total: 1, processed: 0, items: [{ problemId: 12, status: 'PENDING' }] })
    }
    if (path === '/api/problem/format/jobs/91' && method === 'GET') {
      if (!state.aiVersionAdded) {
        state.aiVersionAdded = true
        versions = [{
          id: 401,
          versionNo: Math.max(...versions.map((v) => v.versionNo)) + 1,
          status: 'ARCHIVED',
          source: 'AI',
          title: 'AI 整理结果',
          content: `${CONTENT_V3}\n\n## 复杂度\n\n$O(n)$。`,
          timeLimit: 2000,
          memoryLimit: 256,
          baseVersionId: 303,
          issues: [],
          createTime: '2026-11-13T13:00:00',
          live: false
        }, ...versions]
      }
      return json({
        id: 91,
        status: 'DONE',
        total: 1,
        processed: 1,
        applied: 0,
        review: 1,
        failed: 0,
        skipped: 0,
        message: '完成',
        items: [{ problemId: 12, status: 'REVIEW', resultVersionId: 401, baseVersionId: 303 }]
      })
    }

    const detailMatch = path.match(/^\/api\/problem\/12\/statement\/versions\/(\d+)$/)
    if (detailMatch && method === 'GET') {
      const version = versions.find((v) => v.id === Number(detailMatch[1]))
      return json(toDetail(version || versions[0]))
    }

    return json({ records: [], total: 0 })
  })

  return state
}

async function openStudio(page) {
  await page.goto('/admin/problem/edit/12')
  await expect(page.locator('.st')).toBeVisible({ timeout: 20000 })
  await expect(page.locator('.st-block').first()).toBeVisible({ timeout: 20000 })
}

test('统一工具栏、三种版本属性、无状态提示', async ({ page }) => {
  await setup(page)
  await openStudio(page)

  const toolbar = page.locator('.st-toolbar')
  await expect(toolbar.getByRole('button', { name: '返回题库' })).toBeVisible()
  await expect(toolbar.getByRole('button', { name: '保存修改' })).toBeVisible()
  await expect(toolbar.getByRole('button', { name: '另存为新版本' })).toBeVisible()
  await expect(toolbar.getByRole('button', { name: '放弃修改' })).toBeVisible()
  await expect(toolbar.getByRole('button', { name: '优化这一版' })).toBeVisible()

  // 截图里那些提示全部不再出现
  await expect(page.locator('.st-state')).toHaveCount(0)
  await expect(page.locator('.st-banner')).toHaveCount(0)
  await expect(page.locator('.st-issues')).toHaveCount(0)
  await expect(page.locator('.st-chip')).toHaveCount(0)

  await expect(page.locator('.st-block')).toHaveCount(7)
  await expect(page.locator('.st-block').first().getByRole('button', { name: '恢复' })).toHaveCount(0)
  await expect(page.locator('.st-block').first().getByRole('button', { name: '编辑' })).toBeVisible()
  await expect(page.locator('.st-block').first().getByRole('button', { name: '差异' })).toBeVisible()

  await expect(page.locator('.st-rail-card')).toHaveCount(3)
  await expect(page.locator('.st-rail-card', { hasText: '人工编辑' }).first()).toBeVisible()
  await expect(page.locator('.st-rail-card', { hasText: 'AI 整理' }).first()).toBeVisible()
  await expect(page.locator('.st-rail-card', { hasText: '初始题面' }).first()).toBeVisible()
  await expect(page.locator('.st-rail-card', { hasText: '当前' }).first()).toBeVisible()

  await page.waitForTimeout(700)
  await page.screenshot({ path: join(EVIDENCE, '31-studio-clean.png') })
})

test('就地编辑、差异大弹窗、另存为新版本', async ({ page }) => {
  const state = await setup(page)
  await openStudio(page)

  const contentBlock = page.locator('.st-block').nth(1)
  await contentBlock.getByRole('button', { name: '编辑' }).click()
  await contentBlock.locator('textarea').fill(`${CONTENT_V3}\n\n补充：数组元素互不相同。`)
  await contentBlock.getByRole('button', { name: '完成' }).click()

  await contentBlock.getByRole('button', { name: '差异' }).click()
  const dialog = page.locator('.el-dialog').last()
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.st-diff-pane')).toHaveCount(2)
  await expect(dialog.locator('.st-diff-line.is-add').first()).toBeVisible()
  await page.waitForTimeout(500)
  await page.screenshot({ path: join(EVIDENCE, '32-studio-diff-dialog.png') })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.locator('.st-toolbar').getByRole('button', { name: '另存为新版本' }).click()
  await expect(page.locator('.st-rail-card')).toHaveCount(4)

  const last = state.composeBodies.at(-1)
  expect(last.mode).toBe('SAVE_AS_NEW')
  expect(last.source).toBe('MANUAL')
  expect(last.formatted.content).toContain('数组元素互不相同')
  await page.screenshot({ path: join(EVIDENCE, '33-studio-save-as-new.png') })
})

test('保存修改是就地覆盖，优化生成 AI 整理版本', async ({ page }) => {
  const state = await setup(page)
  await openStudio(page)

  const titleBlock = page.locator('.st-block').first()
  await titleBlock.getByRole('button', { name: '编辑' }).click()
  await titleBlock.locator('textarea').fill('两数之和（修订）')
  await titleBlock.getByRole('button', { name: '完成' }).click()

  await page.locator('.st-toolbar').getByRole('button', { name: '保存修改' }).click()
  await expect(page.locator('.st-rail-card')).toHaveCount(3)
  const saveBody = state.composeBodies.at(-1)
  expect(saveBody.mode).toBe('SAVE')
  expect(saveBody.baseVersionId).toBe(303)

  await page.locator('.st-toolbar').getByRole('button', { name: '优化这一版' }).click()
  await expect(page.locator('.st-rail-card')).toHaveCount(4, { timeout: 20000 })
  await expect(page.locator('.st-rail-card', { hasText: 'AI 整理' }).first()).toBeVisible()
  await expect(page.locator('.st-banner')).toHaveCount(0)
  await page.waitForTimeout(600)
  await page.screenshot({ path: join(EVIDENCE, '34-studio-ai-version.png') })
})
