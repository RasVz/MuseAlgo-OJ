// 报告通知管理端回归：教师 403、管理员列表、短原因 400、唯一一次重投 200、二次 409。
// 失败意图无法通过正常 API 稳定制造，因此在隔离测试栈中把合成报告的真实 READY 意图
// 置为“预算已耗尽”的 FAILED，再验证管理端与后端的一次性重投契约。不触碰业务库。
import { test, expect, expectOk } from './fixtures/e2e_prepare.js'
import { sql } from './fixtures/e2e_db.js'

const PERIOD_LIST = { periodStart: '2023-11-06', periodEnd: '2023-11-12' }
const PERIOD_REDRIVE = { periodStart: '2023-11-20', periodEnd: '2023-11-26' }

async function waitGenerated(api, session, jobId) {
  for (let i = 0; i < 40; i += 1) {
    const job = expectOk(await api.get('/profile/report/jobs/' + jobId, { session }), '查询合成报告任务')
    if (job.status === 'GENERATED') return job
    if (job.status === 'FAILED') throw new Error('合成报告未生成：' + job.errorMessage)
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('合成报告 20 秒内未生成')
}

async function createFailedReadyIntent(api, teacherSession, fixture, period) {
  const job = expectOk(
    await api.post('/profile/report/jobs', {
      session: teacherSession,
      params: { studentId: fixture.student.id, classId: fixture.class.id, ...period },
    }),
    '创建合成报告任务'
  )
  await waitGenerated(api, teacherSession, job.id)
  const intentId = Number(
    sql(
      "SELECT id FROM report_notification_intent WHERE job_id=" +
        job.id +
        " AND event_kind='REPORT_READY' ORDER BY id DESC LIMIT 1"
    )
  )
  if (!intentId) throw new Error('合成报告未产生 READY 通知意图')
  sql(
    'UPDATE report_notification_intent SET status=\'FAILED\', attempts=5, max_attempts=5, redrive_round=0, ' +
      'notification_id=NULL, delivered_at=NULL, dispatch_owner=NULL, dispatch_lease_until=NULL, ' +
      'last_error_code=\'NOTICE_DELIVERY_UNAVAILABLE\', next_attempt_at=NULL, redrive_admin_id=NULL, ' +
      'redriven_at=NULL, redrive_reason=NULL, updated_at=CURRENT_TIMESTAMP(3) WHERE id=' +
      intentId
  )
  return { jobId: job.id, intentId }
}

async function openQueueForJob(page, jobId) {
  await page.goto('/admin/report-notifications')
  await expect(page.locator('.page-title').filter({ hasText: '报告通知队列' })).toBeVisible({ timeout: 15000 })
  await page.getByPlaceholder('报告任务 ID').fill(String(jobId))
  await page.getByRole('button', { name: '查询' }).click()
  const row = page.locator('.el-table__row').filter({ hasText: String(jobId) }).first()
  await expect(row).toBeVisible({ timeout: 15000 })
  return row
}

test.describe('报告通知管理端', () => {
  test('教师访问通知队列返回 403，直接输入 URL 被重定向', async ({ teacherSession, api, teacherPage }) => {
    const res = await api.get('/admin/report-notification-intents', { session: teacherSession })
    expect(res.status).toBe(403)
    expect(res.body.code).toBe(403)

    await teacherPage.goto('/admin/report-notifications')
    await expect(teacherPage).toHaveURL(/\/admin\/?$/, { timeout: 15000 })
    await expect(teacherPage.locator('.page-title').filter({ hasText: '报告通知队列' })).toHaveCount(0)
  })

  test('管理员按任务筛选看到失败意图，短原因被 UI 与 API 双重拒绝', async ({
    adminPage,
    adminSession,
    teacherSession,
    api,
    fixture,
  }) => {
    const { jobId, intentId } = await createFailedReadyIntent(api, teacherSession, fixture, PERIOD_LIST)
    const row = await openQueueForJob(adminPage, jobId)
    await expect(row).toContainText('投递失败')
    await expect(row).toContainText('NOTICE_DELIVERY_UNAVAILABLE')

    await row.getByRole('button', { name: '重投一次' }).click()
    const dialog = adminPage.locator('.el-dialog').filter({ hasText: '重投报告通知' }).first()
    await expect(dialog).toBeVisible({ timeout: 15000 })
    await expect(dialog.getByText('意图 ID')).toBeVisible()
    await expect(dialog).toContainText(String(intentId))

    const textarea = dialog.getByPlaceholder('必填 10-200 字，写入持久化重投审计；不要填写学生姓名或报告正文')
    await textarea.fill('太短了')
    await expect(dialog.getByRole('button', { name: '确认重投' })).toBeDisabled()
    await expect(dialog.getByText('当前 3 字，需 10-200 字')).toBeVisible()

    const shortReason = await api.post('/admin/report-notification-intents/' + intentId + '/redrive', {
      session: adminSession,
      data: { reason: '太短了' },
    })
    expect(shortReason.body.code).toBe(400)
    expect(String(shortReason.body.message || '')).toContain('10-200')

    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toBeHidden({ timeout: 15000 })
  })

  test('管理员重投 200 且只允许一次，二次请求 409 并留下审计', async ({
    adminPage,
    adminSession,
    teacherSession,
    api,
    fixture,
  }) => {
    const { jobId, intentId } = await createFailedReadyIntent(api, teacherSession, fixture, PERIOD_REDRIVE)
    const row = await openQueueForJob(adminPage, jobId)
    await row.getByRole('button', { name: '重投一次' }).click()

    const dialog = adminPage.locator('.el-dialog').filter({ hasText: '重投报告通知' }).first()
    await expect(dialog).toBeVisible({ timeout: 15000 })
    const reason = '合成通知重投演练：验证一次性预算、审计与二次拒绝。'
    await dialog
      .getByPlaceholder('必填 10-200 字，写入持久化重投审计；不要填写学生姓名或报告正文')
      .fill(reason)
    const redriveResponse = adminPage.waitForResponse(
      (resp) =>
        resp.url().includes('/admin/report-notification-intents/' + intentId + '/redrive') &&
        resp.request().method() === 'POST'
    )
    await dialog.getByRole('button', { name: '确认重投' }).click()
    const first = await redriveResponse
    const firstBody = await first.json()
    expect(firstBody.code).toBe(200)
    await expect(adminPage.locator('.el-message').filter({ hasText: '已重新排队' }).first()).toBeVisible({
      timeout: 15000,
    })
    await expect(dialog).toBeHidden({ timeout: 15000 })

    const audit = sql(
      'SELECT CONCAT(redrive_round, \':\', max_attempts, \':\', IF(redrive_admin_id IS NULL, 0, 1), \':\', ' +
        'COALESCE(redrive_reason, \'\')) FROM report_notification_intent WHERE id=' +
        intentId
    )
    expect(audit.startsWith('1:10:1:')).toBe(true)
    expect(audit).toContain(reason)

    const second = await api.post('/admin/report-notification-intents/' + intentId + '/redrive', {
      session: adminSession,
      data: { reason },
    })
    expect(second.body.code).toBe(409)
    expect(String(second.body.message || '')).toMatch(/重投|状态已变化/)

    await expect(adminPage.locator('.el-table__row').filter({ hasText: String(jobId) }).first()).toContainText(
      '已用重投',
      { timeout: 15000 }
    )
  })
})
