// QA-02 受控重判回归：预览 → 原因 → 执行 → 详情。
// 真实 Judge0 判题在真实栈窗口内可用；若发布构建未启用 judge profile，
// 执行接口会返回业务错误，此时用例降级为「预览+校验」并标记 skip 原因。
import { test, expect } from './fixtures/e2e_prepare.js'

// 真实 Judge0 在整机负载下处理 5 条提交可超过 1 分钟；等待只用于保证后续 spec 读到终态，
// 不是产品 SLA，因此留足 3 分钟，避免把慢判题误报为功能失败。
const SETTLE_TIMEOUT_MS = 180000
const TERMINAL_OPERATION_STATUSES = ['COMPLETED', 'FAILED', 'PARTIAL']

/**
 * 等待重判操作与其影响的所有提交回到终态。
 *
 * 背景：批量/单提交重判会把 submission 重置为 status=0 并异步判题。core.spec.js 随后会读取
 * evidence.submissions[0]（api-smoke 的 AC 提交 504）断言「通过 (Accepted)」，若此时该提交仍在
 * 判题中就会读到「等待判题 (Pending)」。gate P5 曾因此出现 1 条伪失败（重判窗口 07:45:28~07:45:49，
 * core 于 07:45:44 读取）。这里在重判用例结束前显式等待，保证后续 spec 看到的是终态数据；
 * 超时则抛错明确失败（不静默跳过），避免把真实判题异常误当成通过。
 */
async function waitRejudgeSettled(api, adminSession, operationId) {
  const deadline = Date.now() + SETTLE_TIMEOUT_MS
  let last = null
  while (Date.now() < deadline) {
    const res = await api.get('/admin/rejudge/operations/' + operationId, { session: adminSession })
    expect(res.status).toBe(200)
    expect(res.body.code).toBe(200)
    last = res.body.data
    if (!last) throw new Error('重判操作 #' + operationId + ' 详情为空')
    const items = last.items || []
    const openItems = items.filter((item) => item.status === 'PENDING' || item.status === 'RUNNING')
    if (TERMINAL_OPERATION_STATUSES.includes(last.status) && openItems.length === 0) {
      const stillPending = []
      for (const item of items) {
        const detail = await api.get('/submission/detail', { session: adminSession, params: { id: item.submissionId } })
        expect(detail.status).toBe(200)
        expect(detail.body.code).toBe(200)
        if (Number(detail.body.data.status) === 0) stillPending.push(item.submissionId)
      }
      if (stillPending.length === 0) return last
    }
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  const summary = last
    ? { status: last.status, succeeded: last.succeededItems, failed: last.failedItems, total: last.totalItems }
    : null
  throw new Error(
    '重判操作 #' + operationId + ' 未在 ' + SETTLE_TIMEOUT_MS + 'ms 内回到终态，后续 spec 可能读到判题中数据：' +
      JSON.stringify(summary)
  )
}

test.describe('QA-02 受控重判', () => {
  test('批量预览：PROBLEM 范围返回匹配数、可重判数与样例提交 ID', async ({ adminPage }) => {
    await adminPage.goto('/admin/rejudge')
    await expect(adminPage.locator('.page-title').filter({ hasText: '受控重判' })).toBeVisible({ timeout: 15000 })
    await adminPage.getByRole('tab', { name: '范围化批量' }).click()

    await adminPage.getByPlaceholder('题目 ID').first().fill('1')
    const previewResponse = adminPage.waitForResponse(
      (resp) => resp.url().includes('/api/admin/rejudge/batch/preview') && resp.request().method() === 'POST',
      { timeout: 30000 }
    )
    await adminPage.getByRole('button', { name: 'dry-run 预览' }).click()
    const resp = await previewResponse
    const body = await resp.json()
    expect(body.code).toBe(200)
    expect(body.data.scopeType).toBe('PROBLEM')
    expect(Number(body.data.matched)).toBeGreaterThanOrEqual(1)

    const previewCard = adminPage.locator('.el-descriptions').filter({ hasText: '可重判' }).first()
    await expect(previewCard).toBeVisible({ timeout: 15000 })
    await expect(previewCard).toContainText('上限')
    await expect(adminPage.getByRole('button', { name: '按预览范围执行' })).toBeVisible()

    // 执行按钮的禁用态与可重判数联动：eligible<=0 时必须禁用。
    if (Number(body.data.eligible) <= 0) {
      await expect(adminPage.getByRole('button', { name: '按预览范围执行' })).toBeDisabled()
    }
  })

  test('批量执行：填写原因 → 确认框 → 生成操作记录 → 详情可见', async ({ adminPage, api, adminSession }) => {
    await adminPage.goto('/admin/rejudge')
    await expect(adminPage.locator('.page-title').filter({ hasText: '受控重判' })).toBeVisible({ timeout: 15000 })
    await adminPage.getByRole('tab', { name: '范围化批量' }).click()
    await adminPage.getByPlaceholder('题目 ID').first().fill('1')

    // 可重判数从预览接口响应体读取（Element Plus descriptions 的 label 与 content 是分开的
    // 单元格，从 DOM 文本解析容易只拿到 label「可重判」而误判为 0）。
    const previewResponse = adminPage.waitForResponse(
      (resp) => resp.url().includes('/api/admin/rejudge/batch/preview') && resp.request().method() === 'POST',
      { timeout: 30000 }
    )
    await adminPage.getByRole('button', { name: 'dry-run 预览' }).click()
    const previewBody = await (await previewResponse).json()
    expect(previewBody.code).toBe(200)
    const eligibleCount = Number(previewBody.data.eligible || 0)
    expect(eligibleCount).toBeGreaterThan(0)
    await expect(adminPage.locator('.el-descriptions').filter({ hasText: '可重判' }).first()).toBeVisible({ timeout: 20000 })

    await adminPage.getByPlaceholder('预览可空；执行时必填').first().fill('QA02 合成重判：批量重判预览范围')
    await adminPage.getByRole('button', { name: '按预览范围执行' }).click()
    const confirmBox = adminPage.locator('.el-message-box').filter({ hasText: '确认执行批量重判' }).first()
    await expect(confirmBox).toBeVisible({ timeout: 15000 })
    const execResponse = adminPage.waitForResponse(
      (resp) => resp.url().includes('/api/admin/rejudge/batch') && !resp.url().includes('/preview') && resp.request().method() === 'POST',
      { timeout: 30000 }
    )
    await confirmBox.getByRole('button', { name: '确定' }).click()
    const resp = await execResponse
    const body = await resp.json()
    expect(body.code).toBe(200)
    expect(body.data && body.data.id).toBeTruthy()

    // 执行成功后自动切到操作记录 tab，并出现新记录。
    await expect(adminPage.getByRole('tab', { name: '操作记录' })).toHaveAttribute('aria-selected', 'true', { timeout: 15000 })
    const operationRow = adminPage.locator('.el-table__row').filter({ hasText: String(body.data.id) }).first()
    await expect(operationRow).toBeVisible({ timeout: 20000 })
    await operationRow.getByRole('button', { name: '详情' }).click()
    const detail = adminPage.locator('.el-dialog').filter({ hasText: '重判操作详情' }).first()
    await expect(detail).toBeVisible({ timeout: 15000 })
    await expect(detail).toContainText('QA02 合成重判')
    await expect(detail).toContainText('操作 ID')

    // 关键：批量重判会把命中提交重置为 QUEUED 并异步判题。必须等本操作与其提交全部回到终态，
    // 否则后续 core.spec.js 读取 api-smoke 的 AC 提交时会看到「等待判题 (Pending)」（gate P5 竞态）。
    await waitRejudgeSettled(api, adminSession, body.data.id)
  })

  test('单提交重判：原因必填校验 + 提交生成操作记录', async ({ adminPage, api, adminSession, fixture }) => {
    await adminPage.goto('/admin/rejudge')
    await expect(adminPage.locator('.page-title').filter({ hasText: '受控重判' })).toBeVisible({ timeout: 15000 })

    // 前端校验：只填 ID 不填原因必须被拦截（不发请求）。
    await adminPage.getByPlaceholder('例如 12345').first().fill(String(fixture.rejudge.submissionId))
    await adminPage.getByRole('button', { name: '提交重判' }).click()
    await expect(adminPage.locator('.el-message').filter({ hasText: '请填写重判原因' }).first()).toBeVisible({ timeout: 10000 })

    await adminPage.getByPlaceholder('必填，写入审计与重判操作记录').first().fill('QA02 合成单提交重判')
    await adminPage.getByRole('button', { name: '提交重判' }).click()
    const confirmBox = adminPage.locator('.el-message-box').filter({ hasText: '确认重判' }).first()
    await expect(confirmBox).toBeVisible({ timeout: 15000 })
    const rejudgeResponse = adminPage.waitForResponse(
      (resp) => /\/api\/admin\/rejudge\/submissions\/\d+$/.test(resp.url()) && resp.request().method() === 'POST',
      { timeout: 30000 }
    )
    await confirmBox.getByRole('button', { name: '确定' }).click()
    const resp = await rejudgeResponse
    const body = await resp.json()
    if (body.code !== 200) {
      // 判题服务不可用时记录后端真实文案，用例标记 skip 并在交付说明中标注。
      test.skip(true, '单提交重判后端返回 code=' + body.code + ' message=' + (body.message || ''))
    }
    expect(body.data && body.data.id).toBeTruthy()
    await expect(adminPage.getByRole('tab', { name: '操作记录' })).toHaveAttribute('aria-selected', 'true', { timeout: 20000 })

    // 单提交重判同样会重置该提交；等它重新判完，避免污染后续 core.spec.js。
    await waitRejudgeSettled(api, adminSession, body.data.id)
  })

  test('操作记录列表：分页与详情字段齐全', async ({ adminPage }) => {
    await adminPage.goto('/admin/rejudge')
    await adminPage.getByRole('tab', { name: '操作记录' }).click()
    await expect(adminPage.locator('.el-table').first()).toBeVisible({ timeout: 15000 })
    const rows = adminPage.locator('.el-table__row')
    await expect(rows.first()).toBeVisible({ timeout: 20000 })
    await expect(adminPage.locator('.el-table').first()).toContainText('范围')
    await expect(adminPage.locator('.el-table').first()).toContainText('进度')
    await rows.first().getByRole('button', { name: '详情' }).click()
    const detail = adminPage.locator('.el-dialog').filter({ hasText: '重判操作详情' }).first()
    await expect(detail).toBeVisible({ timeout: 15000 })
    await expect(detail).toContainText('状态')
    await expect(detail).toContainText('原因')
  })
})