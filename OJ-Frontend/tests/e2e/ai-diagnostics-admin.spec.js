import { test, expect } from '@playwright/test'
import { join } from 'node:path'

test('administrator observes trial usage and configures diagnosis and a public official reference', async ({ page }) => {
  await page.addInitScript(() => { localStorage.setItem('accessToken','synthetic-admin');localStorage.setItem('user',JSON.stringify({id:1,username:'admin',role:3})) })
  let settings = { maxCases:1,waitSeconds:12 }, selectedPostId = '', statsDays = []
  const saved = []
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const json = data => route.fulfill({contentType:'application/json',body:JSON.stringify({code:200,data})})
    if (path === '/api/admin/ai/diagnostics') { statsDays.push(Number(url.searchParams.get('days')));return json({ metrics:{'run.manual':8,'run.auto':2,'reference.hit':3,'diagnosis.counterexamples':1},ranking:[{userId:'7',runs:10}] }) }
    if (path === '/api/admin/ai/diagnostics/settings') {
      if (request.method() === 'PUT') { settings = request.postDataJSON(); saved.push(settings) }
      return json(settings)
    }
    if (path === '/api/admin/ai/diagnostics/reference') {
      if (request.method() === 'PUT') { selectedPostId = String(request.postDataJSON().postId || '');saved.push(request.postDataJSON());return json(null) }
      return json({problemId:1,available:true,selectedPostId,postId:12,title:'公开官方题解',originalChars:9000,truncated:true,candidates:[{id:12,title:'公开官方题解'}]})
    }
    if (path.endsWith('/trend') || path.endsWith('/quota-ranking')) return json([])
    if (path.endsWith('/logs')) return json({records:[],total:0})
    if (path.includes('/user/info')) return json({id:1,username:'admin',role:3})
    return json({})
  })
  await page.goto('/admin/ai')
  await expect(page.getByText('教学诊断与试跑',{exact:true})).toBeVisible()
  await expect(page.locator('.metrics')).toContainText('8')
  await page.getByRole('button',{name:'保存诊断预算',exact:true}).click()
  await expect.poll(() => saved.length).toBe(1)
  expect(saved[0]).toEqual({maxCases:1,waitSeconds:12})
  await page.getByRole('textbox',{name:'查询参考题解的题目 ID'}).fill('1')
  await page.getByRole('button',{name:'查询参考题解',exact:true}).click()
  await expect(page.getByRole('status')).toContainText('头尾节选')
  await page.getByRole('combobox',{name:'指定参考题解'}).focus();await page.getByRole('combobox',{name:'指定参考题解'}).press('ArrowDown')
  await page.getByRole('option',{name:'12 · 公开官方题解',exact:true}).click()
  await page.getByRole('button',{name:'保存题解选择',exact:true}).click()
  await expect.poll(() => saved.length).toBe(2)
  expect(saved[1]).toEqual({problemId:1,postId:12})
  await page.getByText('近 30 天',{exact:true}).click()
  await expect.poll(() => statsDays.includes(30)).toBe(true)
  if (process.env.OJ_TEST_OUTPUT_DIR) await page.screenshot({path:join(process.env.OJ_TEST_OUTPUT_DIR,'admin-diagnostics-reference.png'),fullPage:true})
})
