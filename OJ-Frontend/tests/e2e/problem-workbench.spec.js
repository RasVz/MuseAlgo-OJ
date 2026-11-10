import { test, expect } from '@playwright/test'
import { join } from 'node:path'

test.describe.configure({ timeout: 45000 })

const problem = {
  id: 1,
  title: '布局验证题',
  content: '输入两个整数，输出它们的和。',
  inputDesc: '两个整数',
  outputDesc: '和',
  hint: '注意多组样例',
  timeLimit: 1000,
  memoryLimit: 256,
  samples: [
    { input: '1 2', output: '3' },
    { input: '10 20', output: '30' }
  ]
}

async function setup(page) {
  await page.route('**/monaco-editor@*/min/vs/**', route => {
    const asset = new URL(route.request().url()).pathname.split('/min/vs/')[1]
    return route.fulfill({ path: join(process.cwd(), 'node_modules/monaco-editor/min/vs', asset) })
  })
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-layout-token')
    localStorage.setItem('user', JSON.stringify({ id: 7, username: 'student', nickname: '学习者', role: 1 }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message: '成功' }) })
    if (/\/api\/problem\/\d+$/.test(path)) return json(problem)
    if (path === '/api/problem/draft/get' || path === '/api/problem/draft/latest') {
      return json({ language: 'cpp', code: 'int main() { return 0; }' })
    }
    if (path === '/api/problem/run') {
      const run = request.postDataJSON()
      const stdout = run.input === '1 2' ? '3' : run.input === '9 9' ? '18' : ''
      return json({ runId: 'layout-run', status: 3, raw_stdout: stdout, stdout, time: '0.01', truncated: false })
    }
    if (path === '/api/problem/draft/save') return json(null)
    if (path.includes('/articles')) return json({ records: [] })
    if (path.includes('/user/preferences')) return json({ fontSize: 16 })
    if (path.includes('/user/info')) return json({ id: 7, username: 'student', nickname: '学习者', role: 1 })
    return json({ records: [], total: 0 })
  })
}

async function enterIde(page) {
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  await expect(page.locator('.monaco-editor .view-lines').first()).toContainText('return', { timeout: 20000 })
  await expect(page.locator('.test-case-panel')).toBeVisible()
}

async function dragHandle(page, name, delta, axis = 'x') {
  const handle = page.getByRole('separator', { name })
  const box = await handle.boundingBox()
  const startX = box.x + box.width / 2
  const startY = box.y + Math.min(40, box.height / 2)
  await page.mouse.move(startX, startY)
  await page.mouse.down()
  if (axis === 'y') await page.mouse.move(startX, startY + delta, { steps: 6 })
  else await page.mouse.move(startX + delta, startY, { steps: 6 })
  await page.mouse.up()
}

test('statement samples stay complete and read-only in both modes; editor width is draggable', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await setup(page)
  await page.goto('/problem/1')

  const sampleRegion = page.getByRole('region', { name: '样例' })
  await expect(sampleRegion.getByRole('article')).toHaveCount(2)
  await expect(sampleRegion.getByRole('button', { name: '复制样例 1 的输入' })).toBeVisible()
  await expect(page.getByRole('button', { name: /新增样例/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '编辑样例', exact: true })).toHaveCount(0)

  await enterIde(page)
  const ideSampleRegion = page.getByRole('region', { name: '样例' })
  await expect(ideSampleRegion.getByRole('article')).toHaveCount(2)
  await expect(ideSampleRegion).toContainText('10 20')

  const statement = page.locator('.statement-pane')
  const editor = page.locator('.editor-pane')
  const before = { statement: (await statement.boundingBox()).width, editor: (await editor.boundingBox()).width }
  await dragHandle(page, '题面宽度', 100)
  const after = { statement: (await statement.boundingBox()).width, editor: (await editor.boundingBox()).width }
  expect(after.statement - before.statement).toBeGreaterThan(85)
  expect(before.editor - after.editor).toBeGreaterThan(85)

  const handle = page.getByRole('separator', { name: '题面宽度' })
  await handle.focus()
  const keyboardBefore = (await statement.boundingBox()).width
  await handle.press('ArrowLeft')
  const keyboardAfter = (await statement.boundingBox()).width
  expect(keyboardBefore - keyboardAfter).toBeGreaterThan(10)
})

test('self-test docks right as a third column, collapses to a rail and restores its size', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await setup(page)
  await page.goto('/problem/1')
  await enterIde(page)

  const panel = page.locator('.test-pane')
  const bottomBefore = (await panel.boundingBox()).height
  await dragHandle(page, '自测高度', -40, 'y')
  const bottomAfter = (await panel.boundingBox()).height
  expect(bottomAfter - bottomBefore).toBeGreaterThan(25)

  await page.getByRole('button', { name: '停靠右侧', exact: true }).click()
  await expect(page.getByRole('button', { name: '停靠底部', exact: true })).toBeVisible()
  await expect(page.getByRole('separator', { name: '自测宽度' })).toBeVisible()

  const editor = page.locator('.editor-pane')
  const editorBox = await editor.boundingBox()
  const panelBox = await panel.boundingBox()
  expect(panelBox.x).toBeGreaterThan(editorBox.x + editorBox.width - 2)

  const before = { editor: editorBox.width, panel: panelBox.width }
  await dragHandle(page, '自测宽度', -30)
  const after = { editor: (await editor.boundingBox()).width, panel: (await panel.boundingBox()).width }
  expect(after.panel - before.panel).toBeGreaterThan(20)
  expect(before.editor - after.editor).toBeGreaterThan(20)

  await page.getByRole('button', { name: '收起自测面板' }).click()
  await expect(page.locator('.test-case-panel')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '展开自测面板' })).toBeVisible()
  const collapsedEditor = (await editor.boundingBox()).width
  expect(collapsedEditor).toBeGreaterThan(after.editor)

  await page.getByRole('button', { name: '自测', exact: true }).click()
  await expect(page.locator('.test-case-panel')).toBeVisible()
  expect(Math.abs((await panel.boundingBox()).width - after.panel)).toBeLessThan(3)

  await page.reload()
  await enterIde(page)
  await expect(page.getByRole('button', { name: '停靠底部', exact: true })).toBeVisible()
  await expect(page.locator('.test-case-panel')).toBeVisible()
})

test('official input is read-only; new samples edit inline with four-row fields and header delete', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await setup(page)
  await page.goto('/problem/1')
  await enterIde(page)

  const panel = page.locator('.test-case-panel')
  const statementSample = page.getByRole('region', { name: '样例' }).getByRole('article').first()

  await panel.getByRole('tab', { name: /样例 1/ }).click()
  const officialInput = panel.getByLabel('输入', { exact: true })
  await expect(officialInput).toHaveValue('1 2')
  await expect(officialInput).toHaveAttribute('readonly', '')
  await expect(panel.getByRole('button', { name: '删除样例', exact: true })).toHaveCount(0)
  await expect(panel.getByRole('button', { name: '恢复官方', exact: true })).toHaveCount(0)
  await expect(panel.getByText('官方样例的修改只影响本页试跑，不改题面与题库。')).toHaveCount(0)
  const officialOutput = panel.getByLabel('输出', { exact: true })
  await expect(officialOutput).toHaveValue('')
  await expect(officialOutput).toHaveAttribute('readonly', '')
  await expect(panel.locator('.el-switch')).toHaveCount(0)
  await expect(statementSample).toContainText('1 2')

  await panel.getByRole('button', { name: /新增样例/ }).click()
  await expect(panel.locator('.el-dialog')).toHaveCount(0)
  await expect(panel.getByRole('tab', { name: /样例 3/ })).toHaveCount(1)
  const input = panel.getByLabel('输入', { exact: true })
  const output = panel.getByLabel('输出', { exact: true })
  await expect(input).toBeFocused()
  await expect(input).toHaveValue('')
  await expect(input).toHaveAttribute('rows', '4')
  await expect(output).toHaveAttribute('rows', '4')
  await expect(output).toHaveAttribute('readonly', '')
  await input.fill('9 9')
  await expect(output).toHaveValue('')
  await expect(panel.locator('.el-switch')).toHaveCount(0)
  const deleteButton = panel.getByRole('button', { name: '删除样例', exact: true })
  const dockButton = panel.getByRole('button', { name: '停靠右侧', exact: true })
  await expect(deleteButton).toBeVisible()
  const deleteBox = await deleteButton.boundingBox()
  const dockBox = await dockButton.boundingBox()
  expect(deleteBox.x + deleteBox.width).toBeLessThanOrEqual(dockBox.x)

  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(panel.locator('.case-result')).toContainText('运行完成')
  await expect(output).toHaveValue('18')
  await deleteButton.click()
  await expect(panel.getByRole('tab', { name: /样例 3/ })).toHaveCount(0)
  await expect(panel.getByRole('tab', { name: /样例 2/ })).toHaveCount(1)
  await expect(page.getByRole('button', { name: '编辑样例', exact: true })).toHaveCount(0)
})
