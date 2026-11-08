// QA-02 结算链式重放回归。
// 生产默认 contest.replay.enabled=false：默认只验证「开关关闭」路径。
// 真实栈窗口内按 Lead 批准，用测试专用 env（OJ_SETTLEMENT_REPLAY_ENABLED=true，仅本次进程注入，
// 不改 application.yml 默认值）启动后端，并设置 OJ_TEST_REPLAY_ENABLED=1 启用执行/恢复全链路用例。
import { test, expect } from './fixtures/e2e_prepare.js'

const REPLAY_ENABLED = process.env.OJ_TEST_REPLAY_ENABLED === '1'

/** 历史卡片里按 header 文本定位，避免和「链范围」表格的行混淆。 */
function historyCard(page) {
  return page.locator('.el-card').filter({ hasText: '最近 50 次重放（备份仅管理员可读）' }).first()
}

test.describe('QA-02 结算链式重放', () => {
  test('预览状态与未确认执行被拒绝（开关关闭时同时验证维护开关告警）', async ({ adminPage, api, adminSession, fixture }) => {
    const contestId = fixture.replay.contestId
    await adminPage.goto('/admin/contest/' + contestId + '/settlement-replay')
    await expect(adminPage.locator('.page-title').filter({ hasText: '结算链式重放' })).toBeVisible({ timeout: 15000 })

    const previewCard = adminPage.locator('.el-descriptions').filter({ hasText: '起点比赛' }).first()
    await expect(previewCard).toBeVisible({ timeout: 20000 })
    await expect(previewCard).toContainText('是否可执行')
    // 「是否可执行」文本本身同时含「是」「否」，必须对该项的值标签做精确断言。
    const executableTag = previewCard.locator('.el-tag').first()

    if (REPLAY_ENABLED) {
      // 测试专用 enabled=true 窗口：目标比赛（fixture 动态解析并固化的 seed 比赛 2）应可执行、无阻断告警。
      await expect(executableTag).toHaveText('是')
      await expect(adminPage.locator('.warn-list')).toHaveCount(0)
      await expect(adminPage.getByRole('button', { name: '执行链式重放' })).toBeDisabled()
    } else {
      await expect(executableTag).toHaveText('否')
      const warn = adminPage.locator('.warn-list').first()
      await expect(warn).toBeVisible({ timeout: 15000 })
      await expect(warn).toContainText('链式重放维护开关未开启')
      await expect(adminPage.getByRole('button', { name: '执行链式重放' })).toBeDisabled()
    }

    // 后端拒绝：即使绕过前端直接调用，确认串非法也必须返回业务错误且不落重放记录。
    const historyBefore = await api.get('/admin/settlement-replay/history', { session: adminSession })
    const beforeIds = ((historyBefore.body && historyBefore.body.data) || []).map((row) => row.id)
    const res = await api.post('/admin/settlement-replay/' + contestId, {
      session: adminSession,
      data: { reason: 'QA02 合成：验证确认串非法时执行被拒绝', confirm: 'QA02-INVALID' },
    })
    expect(res.status).toBe(200)
    expect(res.body.code).not.toBe(200)
    if (!REPLAY_ENABLED) {
      // 维护开关未开启抛 SETTLEMENT_REPLAY_DISABLED(409)：返回具体业务码与文案，不走全局兜底。
      expect(res.body.code).toBe(409)
      expect(String(res.body.message || '')).toContain('链式重放维护开关未开启')
    }
    const historyAfter = await api.get('/admin/settlement-replay/history', { session: adminSession })
    const afterIds = ((historyAfter.body && historyAfter.body.data) || []).map((row) => row.id)
    expect(afterIds.length).toBe(beforeIds.length)
  })

  test('确认串机制：与预览不一致时执行按钮保持禁用', async ({ adminPage, fixture }) => {
    test.skip(!REPLAY_ENABLED, '生产默认 contest.replay.enabled=false；确认串可执行态只在测试用 enabled=true 窗口内验证')
    await adminPage.goto('/admin/contest/' + fixture.replay.contestId + '/settlement-replay')
    const previewCard = adminPage.locator('.el-descriptions').filter({ hasText: '起点比赛' }).first()
    await expect(previewCard).toBeVisible({ timeout: 20000 })
    await expect(previewCard.locator('.el-tag').first()).toHaveText('是')

    await adminPage.getByPlaceholder('必填，写入重放审计记录').fill('QA02 合成：确认串校验')
    await adminPage.locator('input[placeholder^="REPLAY:"]').fill('WRONG-CONFIRM')
    await expect(adminPage.getByRole('button', { name: '执行链式重放' })).toBeDisabled()
  })

  test('执行 → 历史备份下载 → 恢复（含重复恢复被拒绝）', async ({ adminPage, api, adminSession, fixture }) => {
    test.skip(!REPLAY_ENABLED, '执行与恢复会重写历史榜单，仅在 Lead 批准的测试用 enabled=true 窗口内运行')

    const contestId = fixture.replay.contestId
    await adminPage.goto('/admin/contest/' + contestId + '/settlement-replay')
    const previewCard = adminPage.locator('.el-descriptions').filter({ hasText: '起点比赛' }).first()
    await expect(previewCard).toBeVisible({ timeout: 20000 })
    await expect(previewCard.locator('.el-tag').first()).toHaveText('是')

    const confirmInput = adminPage.locator('input[placeholder^="REPLAY:"]')
    const expectedConfirm = await confirmInput.getAttribute('placeholder')
    expect(expectedConfirm).toMatch(/^REPLAY:\d+:[0-9a-f]{64}$/)

    await adminPage.getByPlaceholder('必填，写入重放审计记录').fill('QA02 合成：链式重放执行回归')
    await confirmInput.fill(expectedConfirm)
    await adminPage.getByRole('button', { name: '执行链式重放' }).click()
    const confirmBox = adminPage.locator('.el-message-box').filter({ hasText: '确认链式重放' }).first()
    await expect(confirmBox).toBeVisible({ timeout: 15000 })
    const execResponse = adminPage.waitForResponse(
      (resp) => /\/api\/admin\/settlement-replay\/\d+$/.test(resp.url()) && resp.request().method() === 'POST',
      { timeout: 60000 }
    )
    await confirmBox.getByRole('button', { name: '确认执行' }).click()
    const resp = await execResponse
    const body = await resp.json()
    expect(body.code).toBe(200)
    expect(body.data && body.data.contestCount).toBeGreaterThanOrEqual(1)
    // 备注：页面 result alert 在 loadPreview() 里立即被清空（result.value = null），
    // 经源码核对与 run2 实测该 alert 不可观测（已作为产品 UI 缺陷上报 Lead）；
    // 此处断言用户可见的 ElMessage 成功提示 + 下方历史记录落库，替代 alert 文案断言。
    await expect(adminPage.locator('.el-message').filter({ hasText: '链式重放完成' }).first()).toBeVisible({ timeout: 30000 })

    // 历史列表出现新记录（COMPLETED），可下载备份 JSON（只落浏览器私有下载目录）。
    const card = historyCard(adminPage)
    const historyRow = card.locator('.el-table__row').first()
    await expect(historyRow).toBeVisible({ timeout: 20000 })
    await expect(historyRow).toContainText('COMPLETED')
    const downloadPromise = adminPage.waitForEvent('download', { timeout: 20000 })
    await historyRow.getByRole('button', { name: '保存备份' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toMatch(/^settlement-replay-\d+\.json$/)

    // 恢复：预览可执行 → 填恢复原因 → 填恢复确认串 → 恢复完成。
    await historyRow.getByRole('button', { name: '预览恢复' }).click()
    const reasonPrompt = adminPage.locator('.el-message-box').filter({ hasText: '恢复预览' }).first()
    await expect(reasonPrompt).toBeVisible({ timeout: 20000 })
    await reasonPrompt.locator('input').fill('QA02 合成：恢复回归')
    await reasonPrompt.getByRole('button', { name: '确定' }).click()

    const confirmPrompt = adminPage.locator('.el-message-box').filter({ hasText: '确认恢复' }).first()
    await expect(confirmPrompt).toBeVisible({ timeout: 20000 })
    const promptText = await confirmPrompt.innerText()
    const restoreConfirm = (promptText.match(/请输入确认串：\s*(\S+)/) || [])[1]
    expect(restoreConfirm).toMatch(/^RESTORE:\d+:[0-9a-f]{64}$/)
    await confirmPrompt.locator('input').fill(restoreConfirm)
    await confirmPrompt.getByRole('button', { name: '确定' }).click()
    await expect(adminPage.locator('.el-message').filter({ hasText: '恢复完成' }).first()).toBeVisible({ timeout: 30000 })
    // 恢复后该记录状态变为 RESTORED，「预览恢复」入口消失。
    const restoredRow = card.locator('.el-table__row').first()
    await expect(restoredRow).toContainText('RESTORED', { timeout: 20000 })
    await expect(restoredRow.getByRole('button', { name: '预览恢复' })).toHaveCount(0)

    // 恢复拒绝后续覆盖：对已恢复的同一条重放再次预览恢复必须被后端拒绝（不允许重复恢复）。
    const history = await api.get('/admin/settlement-replay/history', { session: adminSession })
    const restored = ((history.body && history.body.data) || []).find((row) => row.status === 'RESTORED')
    expect(restored && restored.id).toBeTruthy()
    const again = await api.get('/admin/settlement-replay/history/' + restored.id + '/restore-preview', {
      session: adminSession,
    })
    expect(again.status).toBe(200)
    expect(again.body.code).not.toBe(200)
  })

  test('历史卡片：管理员可读重放记录列表（备份仅管理员可读）', async ({ adminPage, api, adminSession, otherTeacherSession, fixture }) => {
    const history = await api.get('/admin/settlement-replay/history', { session: adminSession })
    expect(history.status).toBe(200)
    expect(history.body.code).toBe(200)
    expect(Array.isArray(history.body.data)).toBe(true)

    // 非管理员读取同一接口必须被拒绝（注解式权限 → HTTP 403「无权访问该资源」）。
    const denied = await api.get('/admin/settlement-replay/history', { session: otherTeacherSession })
    expect(denied.status).toBe(403)
    expect(denied.body.code).toBe(403)

    await adminPage.goto('/admin/contest/' + fixture.replay.contestId + '/settlement-replay')
    await expect(adminPage.getByText('最近 50 次重放（备份仅管理员可读）')).toBeVisible({ timeout: 20000 })
  })
})