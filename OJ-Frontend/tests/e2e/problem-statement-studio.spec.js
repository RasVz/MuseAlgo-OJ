import { test, expect } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

// 题面工作区（文档就地编辑 + 版本轨）的合成检查。
// 入口只有一个：编辑页的「题面」Tab。AI 与手改共用同一个编辑区，保存才产生版本。
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
  { id: 302, versionNo: 2, status: 'ARCHIVED', source: 'AI_APPLIED', title: 'Two Sum', content: CONTENT_V2, timeLimit: 1000, memoryLimit: 512, baseVersionId: 301, issues: [], createTime: '2026-11-12T09:00:00', live: false },
  { id: 301, versionNo: 1, status: 'CANDIDATE', source: 'AI', title: '两数之和（候选）', content: `${CONTENT_V3}\n\n## 提示\n\n用哈希表把查找降到 $O(1)$。`, timeLimit: 2000, memoryLimit: 256, baseVersionId: 302, issues: [{ code: 'SAMPLE_CONTENT_CHANGED', field: 'samples', severity: 'WARN', message: '第 2 组样例与原文不一致' }], createTime: '2026-11-13T11:00:00', live: false }
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
    issueWarnings: version.issues?.length || 0,
    createdBy: 3,
    createTime: version.createTime,
    live: version.live === true,
    drifted: false
  }
}

async function setup(page) {
  versions = rawVersions.map((v) => ({ ...v }))
  const state = { composeBody: null, nextVersionId: 304 }

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
      state.composeBody = request.postDataJSON()
      const created = {
        id: state.nextVersionId,
        versionNo: Math.max(...versions.map((v) => v.versionNo)) + 1,
        status: 'LIVE',
        source: state.composeBody.source,
        title: state.composeBody.formatted.title,
        content: state.composeBody.formatted.content,
        timeLimit: state.composeBody.formatted.timeLimitMs,
        memoryLimit: state.composeBody.formatted.memoryLimitMb,
        baseVersionId: state.composeBody.baseVersionId,
        issues: [],
        createTime: '2026-11-13T12:00:00',
        live: true
      }
      versions = [created, ...versions.map((v) => ({ ...v, live: false, status: v.status === 'LIVE' ? 'ARCHIVED' : v.status }))]
      state.nextVersionId += 1
      return json(toVO(created), '保存成功')
    }

    if (path === '/api/problem/12/statement/versions/optimize' && method === 'POST') {
      return json({ id: 91, status: 'PENDING', total: 1, processed: 0, applied: 0, review: 1, failed: 0, skipped: 0, items: [{ problemId: 12, status: 'PENDING' }] })
    }
    if (path === '/api/problem/format/jobs/91' && method === 'GET') {
      return json({
        id: 91,
        status: 'DONE',
        total: 1,
        processed: 1,
        applied: 0,
        review: 1,
        failed: 0,
        skipped: 0,
        message: '成功 0 题，待审 1 题',
        items: [{ problemId: 12, status: 'REVIEW', resultVersionId: 301, baseVersionId: 303 }]
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
  await expect(page.locator('.st')).toBeVisible()
  await expect(page.locator('.st-block').first()).toBeVisible()
}

test('题面工作区：文档区块、元数据行与版本轨', async ({ page }) => {
  await setup(page)
  await openStudio(page)

  await expect(page.locator('.st-block')).toHaveCount(7)
  await expect(page.locator('.st-block-label').first()).toHaveText('题名')
  await expect(page.locator('.st-state')).toContainText('已保存为 v3')
  await expect(page.locator('.st-rail-card')).toHaveCount(3)
  await expect(page.locator('.meta-row')).toContainText('难度分')

  await page.waitForTimeout(800)
  await page.screenshot({ path: join(EVIDENCE, '21-studio-overview.png') })
})

test('题面工作区：就地编辑后保存为新版本', async ({ page }) => {
  const state = await setup(page)
  await openStudio(page)

  const contentBlock = page.locator('.st-block').nth(1)
  await contentBlock.getByRole('button', { name: '编辑' }).click()
  const textarea = contentBlock.locator('textarea')
  await expect(textarea).toBeVisible()
  await textarea.fill(`${CONTENT_V3}\n\n补充一行：数组元素互不相同。`)
  await contentBlock.getByRole('button', { name: '完成' }).click()

  await expect(contentBlock.locator('.st-chip')).toHaveText('已改')
  await expect(page.locator('.save-btn')).toHaveText('保存为新版本')

  await page.waitForTimeout(400)
  await page.screenshot({ path: join(EVIDENCE, '22-studio-editing.png') })

  await page.locator('.save-btn').click()
  await expect(page.locator('.st-state')).toContainText('已保存为 v4')

  expect(state.composeBody).not.toBeNull()
  expect(state.composeBody.source).toBe('MANUAL')
  expect(state.composeBody.baseVersionId).toBe(303)
  expect(state.composeBody.formatted.content).toContain('数组元素互不相同')
  await expect(page.locator('.st-rail-card')).toHaveCount(4)
})

test('题面工作区：切换历史版本与 AI 草稿', async ({ page }) => {
  await setup(page)
  await openStudio(page)

  await page.locator('.st-rail-card', { hasText: 'v2' }).click()
  await expect(page.locator('.st-state')).toContainText('已保存为 v2')
  await expect(page.locator('.st-render').nth(1)).toContainText('Given an array of integers')

  await page.waitForTimeout(600)
  await page.screenshot({ path: join(EVIDENCE, '23-studio-version-switch.png') })

  await page.getByRole('button', { name: '优化这一版' }).click()
  await page.getByRole('button', { name: '开始生成' }).click()

  await expect(page.locator('.st-banner')).toContainText('AI 草稿', { timeout: 20000 })
  await expect(page.locator('.st-state')).toContainText('AI 草稿未保存')
  await page.waitForTimeout(800)
  await page.screenshot({ path: join(EVIDENCE, '24-studio-ai-draft.png') })
})
