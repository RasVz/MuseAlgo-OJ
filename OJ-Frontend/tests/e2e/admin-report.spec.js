// QA-02 学习周报管理端回归：报告生成、历史、姓名快照、阅读页预览与下载入口、重试负例、跨班 403。
// 数据全部来自 fixtures/e2e_prepare.js 的合成班级/合成学生（QA02合成前缀），
// 截图与 trace 由 playwright.config.js 统一落到 OJ_TEST_OUTPUT_DIR 私有证据目录。
//
// 方案 B 后：/admin/reports?jobId= 深链直接进入全屏阅读页（不再是详情弹窗），
// 工作区按 Tab 分区；生成入口在「生成任务」Tab，历史列表在「历史报告」Tab。
import { test, expect } from './fixtures/e2e_prepare.js'

/** 通过 ?jobId= 深链打开全屏阅读页，等待报告任务元信息渲染。 */
async function openReaderForJob(page, fixture) {
  await page.goto('/admin/reports?jobId=' + fixture.report.jobId)
  const reader = page.locator('.reader-page')
  await expect(reader).toBeVisible({ timeout: 20000 })
  await expect(reader.locator('.title-meta').first()).toContainText(fixture.report.periodStart, { timeout: 20000 })
  return reader
}

/** 工作区顶部的班级上下文下拉。option 由 teleport 渲染到 body 的 .el-select__popper 内。 */
async function selectClass(page, optionText) {
  const select = page.locator('.context-bar .el-select').first()
  await expect(select).toBeVisible({ timeout: 15000 })
  const wrapper = select.locator('.el-select__wrapper')
  await expect(wrapper).not.toHaveClass(/is-disabled/, { timeout: 20000 })
  await wrapper.click()
  const option = page.locator('.el-select__popper:visible .el-select-dropdown__item').filter({ hasText: optionText }).first()
  await expect(option).toBeVisible({ timeout: 15000 })
  await option.click()
}

/** 生成任务 Tab 内的学生选择弹窗：打开、按文本定位行、点击确定。 */
async function selectStudent(page, optionText) {
  const item = page.locator('.form-item').filter({ has: page.locator('span.form-label', { hasText: '学生' }) }).first()
  const trigger = item.locator('.picker-trigger')
  await expect(trigger).toBeVisible({ timeout: 15000 })
  await expect(trigger).not.toHaveClass(/is-disabled/, { timeout: 20000 })
  await trigger.click()
  const dialog = page.locator('.student-picker-dialog')
  await expect(dialog).toBeVisible({ timeout: 15000 })
  const row = dialog.locator('.el-table__row').filter({ hasText: optionText }).first()
  await expect(row).toBeVisible({ timeout: 15000 })
  await row.click()
  await dialog.getByRole('button', { name: /确定/ }).click()
  await expect(dialog).toBeHidden({ timeout: 15000 })
}

test.describe('QA-02 学习周报', () => {
  test('历史报告 Tab 展示合成班级的周报任务与状态标签', async ({ adminPage, fixture }) => {
    await adminPage.goto('/admin/reports?tab=history')
    await expect(adminPage.locator('.page-title').filter({ hasText: '学习周报' })).toBeVisible({ timeout: 15000 })
    await selectClass(adminPage, fixture.class.name)

    // 按周期过滤，保证多轮运行后仍锁定 fixture 生成的 2024-12 任务。
    const row = adminPage
      .locator('.el-table__row')
      .filter({ hasText: '#' + fixture.report.studentId })
      .filter({ hasText: fixture.report.periodStart })
      .first()
    await expect(row).toBeVisible({ timeout: 20000 })
    // 姓名快照：生成后改过昵称，历史行仍应显示生成时的快照名（精确匹配，避免前缀包含假阳性）。
    await expect(row.locator('.student-cell span').first()).toHaveText(fixture.report.snapshotName)
    await expect(row).toContainText(fixture.report.periodStart)
    await expect(row).toContainText(fixture.report.periodEnd)
    await expect(row).toContainText('已生成')
  })

  test('阅读页：姓名快照、周期、预览 iframe 与下载入口', async ({ adminPage, fixture }) => {
    const reader = await openReaderForJob(adminPage, fixture)

    // 姓名快照断言点：标题使用报告任务落库时的 studentName 快照（不是当前昵称）。
    await expect(reader.locator('.title-text').first()).toContainText(fixture.report.snapshotName)
    await expect(reader.locator('.title-meta').first()).toContainText(fixture.report.periodEnd)

    // 新生成的报告带结构化数据节点，阅读页默认「网页阅读」；显式切到 A4 原始版验证权威 iframe。
    await reader.getByText('A4 原始版', { exact: true }).click()
    const iframe = reader.locator('iframe.report-iframe')
    await expect(iframe).toBeVisible()
    await expect(iframe).toHaveAttribute('title', '学习周报预览')

    // 下载入口：另存为 HTML 触发真实浏览器下载事件，文件名带周期。
    const downloadPromise = adminPage.waitForEvent('download', { timeout: 20000 })
    await reader.getByRole('button', { name: '另存为 HTML' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toContain(fixture.report.periodStart)
    expect(download.suggestedFilename()).toContain(fixture.report.periodEnd)
    expect(download.suggestedFilename().endsWith('.html')).toBe(true)

    // 导出 PNG 入口存在（html2canvas 实际渲染结果在本环境记为弱断言，见交付说明）。
    await expect(reader.getByRole('button', { name: '导出 PNG' })).toBeVisible()
    await expect(reader.getByRole('button', { name: '打印' })).toBeVisible()
  })

  test('生成入口：真实生成一份合成学生周报并在任务卡片轮询到完成', async ({ adminPage, fixture }) => {
    await adminPage.goto('/admin/reports?tab=generate')
    await expect(adminPage.locator('.page-title').filter({ hasText: '学习周报' })).toBeVisible({ timeout: 15000 })

    // 显式选中合成班级（下拉 option label 为 `${name}（#${id}）`）。
    await selectClass(adminPage, fixture.class.name)
    await expect(adminPage.locator('.field-hint').first()).toContainText('名学生', { timeout: 15000 })
    await selectStudent(adminPage, fixture.student.username)

    // 周期用页面默认的「上周一~上周日」（真实日期），走真实 POST /profile/report/jobs 生成；
    // fixture 里已生成的 2024-12 周期任务用于历史/快照断言，这里生成的是另一个周期，不会互相覆盖。
    const startInput = adminPage.getByPlaceholder('开始日期').first()
    await expect(startInput).not.toHaveValue('', { timeout: 15000 })

    const generateResponse = adminPage.waitForResponse(
      (resp) => resp.url().includes('/api/profile/report/jobs') && resp.request().method() === 'POST',
      { timeout: 60000 }
    )
    await adminPage.getByRole('button', { name: '生成学习周报' }).click()
    const resp = await generateResponse
    const body = await resp.json()
    expect(body.code).toBe(200)
    const created = body.data
    expect(created && created.id).toBeTruthy()
    // 新周期不存在既有 job，POST 必须 enqueue-only：可以是 PENDING，也可能是 worker 抢先完成。
    expect(['PENDING', 'GENERATING', 'GENERATED']).toContain(created.status)
    // 新周期报告的快照名取自生成瞬间的昵称：fixture 生成 2024-12 报告后改过昵称，
    // 因此这里应为改名后的昵称（remark > nickname > username，合成学生无班级备注）。
    expect(created.studentName).toBe(fixture.report.renamedNickname)

    const toast = adminPage.locator('.el-message').filter({ hasText: /学习周报已生成|报告已受理/ }).first()
    await expect(toast).toBeVisible({ timeout: 30000 })
    const taskCard = adminPage.locator('.task-card')
    await expect(taskCard).toBeVisible({ timeout: 30000 })
    // 任务卡片每 2 秒轮询当前 job；等待后台 worker 把 PENDING 推进到 GENERATED。
    await expect(taskCard.getByText('已生成', { exact: true }).first()).toBeVisible({ timeout: 60000 })
    await expect(taskCard).toContainText(fixture.report.renamedNickname)
    await expect(taskCard).toContainText(String(created.periodStart))
  })

  test('重试幂等：已生成任务 retry 返回当前成功（FAILED 分支未验证）', async ({ adminPage, api, adminSession, fixture }) => {
    // 契约规定 PENDING/GENERATING/GENERATED 的 retry 返回当前任务；只有 FAILED 且
    // canRetry=false/预算耗尽才返回稳定错误码。正常数据无法制造 FAILED，正向失败重试标记未验证。
    const res = await api.post('/profile/report/jobs/' + fixture.report.jobId + '/retry', { session: adminSession })
    expect(res.status).toBe(200)
    expect(res.body.code).toBe(200)
    expect(res.body.data.status).toBe('GENERATED')

    // 页面：已生成任务行不出现「重试」按钮（只有 FAILED 且 canRetry=true 才渲染）。
    await adminPage.goto('/admin/reports?tab=history')
    await expect(adminPage.locator('.page-title').filter({ hasText: '学习周报' })).toBeVisible({ timeout: 15000 })
    await selectClass(adminPage, fixture.class.name)
    const row = adminPage.locator('.el-table__row').filter({ hasText: '#' + fixture.report.studentId }).first()
    await expect(row).toBeVisible({ timeout: 20000 })
    await expect(row.getByRole('button', { name: '重试' })).toHaveCount(0)
  })

  test('跨班 403：他班教师打开报告中心时看不到本班数据', async ({ otherTeacherPage, api, otherTeacherSession, fixture }) => {
    // teacher02 名下没有班级：页面不会发起历史查询，因此先做接口层跨班 403 断言，
    // 再断言页面呈现「当前账号没有可用班级」且不出现合成班级/学生数据。
    const denied = await api.get('/profile/report/jobs', {
      session: otherTeacherSession,
      params: { classId: fixture.class.id, page: 1, size: 10 },
    })
    expect(denied.status).toBe(200)
    expect(denied.body.code).toBe(403)
    expect(String(denied.body.message || '')).toContain('无权限操作该班级报告')

    const detailDenied = await api.get('/profile/report/jobs/' + fixture.report.jobId, { session: otherTeacherSession })
    expect(detailDenied.body.code).toBe(403)

    await otherTeacherPage.goto('/admin/reports')
    await expect(otherTeacherPage.locator('.page-title').filter({ hasText: '学习周报' })).toBeVisible({ timeout: 15000 })
    await expect(otherTeacherPage.locator('.el-alert').filter({ hasText: '当前账号没有可用班级' }).first()).toBeVisible({
      timeout: 15000,
    })
    await expect(otherTeacherPage.getByText(fixture.class.name)).toHaveCount(0)
    await expect(otherTeacherPage.getByText(fixture.report.snapshotName)).toHaveCount(0)
  })
})
