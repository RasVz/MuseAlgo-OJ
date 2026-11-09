import { test, expect } from '@playwright/test'
import { join } from 'node:path'
const evidence = process.env.OJ_TEST_OUTPUT_DIR

test.describe.configure({ timeout: 45000 })
async function setup(page, { contestSubmission = false, interrupted = false } = {}) {
  // 真实编辑器代码从本地依赖加载，浏览器验收不依赖公共 CDN。
  await page.route('**/monaco-editor@*/min/vs/**', route => {
    const asset = new URL(route.request().url()).pathname.split('/min/vs/')[1]
    return route.fulfill({ path: join(process.cwd(), 'node_modules/monaco-editor/min/vs', asset) })
  })
  const calls = []
  let history = []
  await page.addInitScript(() => {
    localStorage.setItem('accessToken', 'synthetic-teaching-token')
    localStorage.setItem('user', JSON.stringify({ id: 7, username: 'student', nickname: '学习者', role: 1 }))
  })
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data, message: '成功' }) })
    if (/\/api\/problem\/\d+$/.test(path)) return json({ id: Number(path.split('/').at(-1)), title: '求和教学案例', content: '输入两个整数，输出它们的和。', inputDesc: '两个整数', outputDesc: '和', hint: '绝对值不超过 100', timeLimit: 1000, memoryLimit: 256, samples: [{ input: '1 2', output: '3' }] })
    if (path === '/api/problem/draft/get' || path === '/api/problem/draft/latest') return json({ language: 'cpp', code: 'int main() { return 0; }' })
    if (path === '/api/problem/draft/save') return json(null)
    if (path.includes('/articles')) return json({ records: [] })
    if (path === '/api/ai/conversation') {
      calls.push({ path, method: request.method() })
      if (request.method() === 'DELETE') history = []
      return json(history)
    }
    if (path === '/api/ai/ask/stream' || path === '/api/ai/review') {
      const payload = request.postDataJSON(); calls.push({ path, payload })
      const text = '这是参考实现，关键修改是边界条件。\n\n```cpp\nint main() { return 0; }\n```'
      history.push({ role: 'user', content: payload.studentQuestion || '复盘' }, { role: 'assistant', content: text })
      const body = text.split('\n').map(line => `data: ${line}`).join('\n') + '\n\n' + (interrupted
        ? 'event: app-error\ndata: {"code":502,"errorCode":"AI_OUTPUT_LIMIT","message":"回复达到输出上限，尚未完成"}\n\n'
        : 'event: done\ndata: \n\n')
      return route.fulfill({ contentType: 'text/event-stream', body })
    }
    if (path === '/api/submission/detail') return json({ id: 10, problemId: 1, contestId: contestSubmission ? 9 : null, problemTitle: '求和教学案例', userId: 7, username: 'student', status: 1, code: 'int main() { return 0; }', language: 'cpp', score: 100, runTime: 1, runMemory: 1000, createTime: '2026-11-09T12:00:00' })
    if (path.includes('/user/preferences')) return json({ fontSize: 16 })
    if (path.includes('/user/info')) return json({ id: 7, username: 'student', nickname: '学习者', role: 1 })
    return json({ records: [], total: 0 })
  })
  return calls
}
async function openTutor(page) {
  await page.getByRole('button', { name: /AI 助教/ }).click()
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
  await expect.poll(async () => {
    const box = await page.locator('.ai-chat-drawer').boundingBox()
    return Math.abs(box.x + box.width - page.viewportSize().width)
  }).toBeLessThan(1)
}

test('ordinary practice can request full solution immediately, restore and clear its conversation', async ({ page }) => {
  const calls = await setup(page)
  await page.goto('/problem/1'); await openTutor(page)
  await page.getByRole('combobox', { name: '帮助方向' }).focus()
  await page.getByRole('combobox', { name: '帮助方向' }).press('ArrowDown')
  await page.getByRole('option', { name: '完整题解与代码', exact: true }).click()
  await page.getByRole('button', { name: '填入示例问题' }).click()
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.chat-history')).toContainText('这是参考实现')
  expect(calls.find(call => call.payload)?.payload.helpDirection).toBe('FULL_SOLUTION')
  expect(calls.find(call => call.payload)?.payload).not.toHaveProperty('hintLevel')
  await page.reload(); await openTutor(page)
  await expect(page.locator('.chat-history')).toContainText('这是参考实现')
  if (evidence) await page.screenshot({ path: join(evidence, 'practice-full-solution.png'), fullPage: true })
  await page.getByRole('button', { name: '清空本题对话' }).click()
  await expect(page.locator('.chat-history')).not.toContainText('这是参考实现')
  expect(calls.some(call => call.method === 'DELETE')).toBe(true)
})

test('contest problem hides AI in reading and editor modes without loading conversation', async ({ page }) => {
  const calls = await setup(page)
  await page.goto('/problem/1?contestId=9')
  await expect(page.getByRole('button', { name: '提交答案', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /AI 助教/ })).toHaveCount(0)
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  await expect(page.getByRole('button', { name: '返回题目', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /AI 助教/ })).toHaveCount(0)
  await expect(page.locator('.ai-chat-drawer')).toHaveCount(0)
  expect(calls).toHaveLength(0)
  if (evidence) await page.screenshot({ path: join(evidence, 'contest-editor-no-ai.png'), fullPage: true })
})

test('actual contest submission hides AI even without a contest URL parameter', async ({ page }) => {
  await setup(page, { contestSubmission: true }); await page.goto('/submission/10')
  await expect(page.getByText('提交者', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /AI 复盘/ })).toHaveCount(0)
  await expect(page.locator('.review-dialog')).toHaveCount(0)
})

test('AC practice submission supports review and followup with preferences', async ({ page }) => {
  const calls = await setup(page); await page.goto('/submission/10')
  await page.getByRole('button', { name: /AI 复盘/ }).click()
  await expect(page.locator('.review-content')).toContainText('这是参考实现')
  await page.getByRole('textbox', { name: '继续提问' }).fill('请解释刚才提到的边界条件')
  await page.getByRole('button', { name: '发送追问' }).click()
  await expect.poll(() => calls.filter(call => call.path === '/api/ai/review').length).toBe(2)
  expect(calls.filter(call => call.path === '/api/ai/review')[1].payload.previousAnswer).toContain('这是参考实现')
})

test('sending after editing includes latest code and language, preserving draft across modes', async ({ page }) => {
  const calls = await setup(page); await page.goto('/problem/1')
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  const lines = page.locator('.monaco-editor .view-lines').first()
  await expect(lines).toContainText('return', { timeout: 20000 })
  await lines.click()
  await page.keyboard.press('Meta+A')
  await page.keyboard.type('int main() { return 123; }')
  await expect(lines).toContainText('123')
  await page.getByRole('button', { name: /AI 助教/ }).click()
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
  await page.getByRole('textbox', { name: '你的问题' }).fill('检查最新代码')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.payload).length).toBe(1)
  expect(calls.find(call => call.payload).payload.studentCode).toContain('return 123')
  expect(calls.find(call => call.payload).payload.language).toBe('cpp')
})

test('truncated stream preserves partial answer and exposes retry', async ({ page }) => {
  await setup(page, { interrupted: true }); await page.goto('/problem/1'); await openTutor(page)
  await page.getByRole('textbox', { name: '你的问题' }).fill('分析代码')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.chat-history')).toContainText('回复未完成，请重试')
  await expect(page.getByRole('button', { name: '重试上次提问' })).toBeVisible()
})

test('mobile tutor controls fit the viewport and help remains available', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 }); await setup(page)
  await page.goto('/problem/1'); await openTutor(page)
  await expect(page.getByRole('combobox', { name: '帮助方向' })).toBeVisible()
  const box = await page.locator('.ai-chat-drawer').boundingBox()
  expect(box.width).toBeLessThanOrEqual(391)
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(391)
  if (evidence) await page.screenshot({ path: join(evidence, 'mobile-tutor.png'), fullPage: true })
})


test('reading mode uses the actual language of the most recently saved draft', async ({ page }) => {
  const calls = await setup(page)
  await page.route('**/api/problem/draft/latest*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'java', code: 'class Main { public static void main(String[] args) {} }' } }) }))
  await page.goto('/problem/1'); await openTutor(page)
  await page.getByRole('textbox', { name: '你的问题' }).fill('解释当前代码')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.payload).length).toBe(1)
  expect(calls.find(call => call.payload).payload.language).toBe('java')
  expect(calls.find(call => call.payload).payload.studentCode).toContain('class Main')
})


test('editor restores actual draft after loading a different preferred language', async ({ page }) => {
  const calls = await setup(page)
  await page.route('**/api/**/preferences*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'java', fontSize: 16 } }) }))
  await page.route('**/api/problem/draft/latest*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'python', code: 'print(42)' } }) }))
  await page.goto('/problem/1')
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  await expect(page.locator('.monaco-editor .view-lines').first()).toContainText('print', { timeout: 20000 })
  await page.getByRole('button', { name: /AI 助教/ }).click()
  await page.getByRole('textbox', { name: '你的问题' }).fill('解释当前草稿')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.payload).length).toBe(1)
  expect(calls.find(call => call.payload).payload.language).toBe('python')
  expect(calls.find(call => call.payload).payload.studentCode).toBe('print(42)')
})


test('leaving editor during draft loading does not poison the reading-mode code snapshot', async ({ page }) => {
  const calls = await setup(page)
  await page.route('**/api/**/preferences*', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'java' } }) }))
  let requests = 0
  let release
  const held = new Promise(resolve => { release = resolve })
  await page.route('**/api/problem/draft/latest*', async route => {
    if (++requests === 1) await held
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'java', code: 'class Main { }' } }) }).catch(() => {})
  })
  await page.goto('/problem/1')
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  await expect.poll(() => requests).toBe(1)
  await page.getByRole('button', { name: '返回题目', exact: true }).click()
  await openTutor(page)
  await page.getByRole('textbox', { name: '你的问题' }).fill('解释当前代码')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.payload).length).toBe(1)
  expect(requests).toBe(2)
  expect(calls.find(call => call.payload).payload.studentCode).toBe('class Main { }')
  release()
})

test('stopping a pending answer ignores late response and allows a new question', async ({ page }) => {
  await setup(page)
  let release
  let requests = 0
  const held = new Promise(resolve => { release = resolve })
  await page.route('**/api/ai/ask/stream', async route => {
    const first = ++requests === 1
    if (first) await held
    await route.fulfill({ contentType: 'text/event-stream', body: `data: ${first ? '迟到的旧回答' : '新的回答'}\n\nevent: done\ndata: \n\n` }).catch(() => {})
  })
  await page.goto('/problem/1'); await openTutor(page)
  await page.getByRole('textbox', { name: '你的问题' }).fill('第一个问题')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => requests).toBe(1)
  await page.getByRole('button', { name: '停止生成' }).click()
  await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled()
  release()
  await page.getByRole('textbox', { name: '你的问题' }).fill('新的问题')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect(page.locator('.chat-history')).toContainText('新的回答')
  await expect(page.locator('.chat-history')).not.toContainText('迟到的旧回答')
})


test('leaving during a language switch loads the selected language before asking', async ({ page }) => {
  const calls = await setup(page)
  let requests = 0
  let release
  const held = new Promise(resolve => { release = resolve })
  await page.route('**/api/problem/draft/get*', async route => {
    if (++requests === 1) await held
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: { language: 'python', code: 'print(99)' } }) }).catch(() => {})
  })
  await page.goto('/problem/1')
  await page.getByRole('button', { name: '提交答案', exact: true }).click()
  await expect(page.locator('.monaco-editor .view-lines').first()).toContainText('return', { timeout: 20000 })
  const language = page.getByRole('combobox', { name: '编程语言' })
  await language.focus(); await language.press('ArrowDown')
  await page.getByRole('option', { name: 'Python 3', exact: true }).click()
  await expect.poll(() => requests).toBe(1)
  await page.getByRole('button', { name: '返回题目', exact: true }).click()
  await openTutor(page)
  await page.getByRole('textbox', { name: '你的问题' }).fill('解释当前代码')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.payload).length).toBe(1)
  expect(calls.find(call => call.payload).payload.language).toBe('python')
  expect(calls.find(call => call.payload).payload.studentCode).toBe('print(99)')
  release()
})
