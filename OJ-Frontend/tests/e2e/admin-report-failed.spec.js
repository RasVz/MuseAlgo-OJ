// 报告 FAILED 分支 UI 回归：失败状态/错误码、终态停止轮询、重试后重新生成并再次停止轮询。
// 为了稳定复现失败终态，测试栈内直接构造一个已合成的 PENDING 任务（next_attempt_at 在未来），
// 打开详情后将其置为 FAILED；这是测试夹具状态，不修改产品代码或业务库。
import { test, expect } from './fixtures/e2e_prepare.js'
import { sql } from './fixtures/e2e_db.js'

const PERIOD = { periodStart: '2023-11-13', periodEnd: '2023-11-19' }

function insertPendingJob(fixture) {
  const teacherId = Number(sql("SELECT id FROM user WHERE username='teacher01'"))
  const name = fixture.student.nickname.replace(/'/g, "''")
  return Number(
    sql(
      'INSERT INTO report_job(student_id, student_name, class_id, period_start, period_end, status, attempts, ' +
        'requester_id, active_requester_id, generation_no, terminal_revision, next_attempt_at, create_time, update_time) ' +
        'VALUES (' +
        fixture.student.id +
        ", '" +
        name +
        "', " +
        fixture.class.id +
        ", '" +
        PERIOD.periodStart +
        "', '" +
        PERIOD.periodEnd +
        "', 'PENDING', 0, " +
        teacherId +
        ', ' +
        teacherId +
        ', 0, 0, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)); ' +
        'SELECT LAST_INSERT_ID();'
    )
  )
}

function forceFailed(jobId) {
  sql(
    "UPDATE report_job SET status='FAILED', attempts=1, last_error_code='GENERATOR_UNAVAILABLE', " +
      "error_message='报告生成暂时失败。', html_content=NULL, finished_at=CURRENT_TIMESTAMP(3), " +
      'terminal_revision=terminal_revision+1, next_attempt_at=NULL, lease_owner=NULL, lease_until=NULL, ' +
      'heartbeat_at=NULL, update_time=CURRENT_TIMESTAMP(3) WHERE id=' +
      jobId +
      " AND status='PENDING'"
  )
}

function detailRequestCount(page, jobId) {
  const path = '/profile/report/jobs/' + jobId
  let count = 0
  page.on('request', (request) => {
    if (request.method() !== 'GET') return
    try {
      if (new URL(request.url()).pathname.endsWith(path)) count += 1
    } catch {
      // 忽略非 HTTP 请求
    }
  })
  return () => count
}

test.describe('报告 FAILED 分支', () => {
  test('关闭详情时取消在途请求，迟到结果不能重新开始轮询', async ({ adminPage, fixture }) => {
    const jobId = fixture.report.jobId
    let requests = 0
    let release
    const blocked = new Promise(resolve => { release = resolve })
    let entered
    const intercepted = new Promise(resolve => { entered = resolve })
    await adminPage.route('**/api/profile/report/jobs/' + jobId, async route => {
      requests += 1
      entered()
      await blocked
      try {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
          code: 200, data: { id: jobId, status: 'PENDING' }
        }) })
      } catch {
        // The browser may already have aborted the request when the dialog closed.
      }
    })
    await adminPage.goto('/admin/reports?jobId=' + jobId)
    await intercepted
    const dialog = adminPage.locator('.el-dialog').filter({ hasText: '学习周报详情' }).first()
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    release()
    await expect(dialog).toBeHidden()
    await adminPage.waitForTimeout(5000)
    expect(requests).toBe(1)
    await expect(adminPage.locator('.el-message').filter({ hasText: '网络连接异常' })).toHaveCount(0)
  })
  test('失败状态展示错误码，轮询在终态停止；重试成功后再次停止', async ({ adminPage, fixture }) => {
    const jobId = insertPendingJob(fixture)
    const requests = detailRequestCount(adminPage, jobId)

    await adminPage.goto('/admin/reports?jobId=' + jobId)
    const dialog = adminPage.locator('.el-dialog').filter({ hasText: '学习周报详情' }).first()
    await expect(dialog).toBeVisible({ timeout: 20000 })
    await expect(dialog.getByText('排队中', { exact: true }).first()).toBeVisible({ timeout: 15000 })

    forceFailed(jobId)
    await expect(dialog.getByText('失败', { exact: true }).first()).toBeVisible({ timeout: 15000 })
    await expect(dialog).toContainText('错误码：GENERATOR_UNAVAILABLE')
    await expect(dialog).toContainText('报告生成暂时失败。')

    // 终态后不应再排下一轮详情轮询；给已在途请求留出 1.5 秒再取基线。
    await adminPage.waitForTimeout(1500)
    const afterFailure = requests()
    await adminPage.waitForTimeout(5000)
    expect(requests()).toBe(afterFailure)

    const retry = dialog.getByRole('button', { name: '重试生成' }).first()
    await expect(retry).toBeVisible({ timeout: 15000 })
    await retry.click()

    await expect(dialog.getByText('已生成', { exact: true }).first()).toBeVisible({ timeout: 60000 })
    await expect(dialog.locator('iframe.report-iframe')).toBeVisible({ timeout: 20000 })
    await expect(dialog).not.toContainText('错误码：GENERATOR_UNAVAILABLE')

    await adminPage.waitForTimeout(1500)
    const afterGenerated = requests()
    await adminPage.waitForTimeout(5000)
    expect(requests()).toBe(afterGenerated)
    expect(afterGenerated).toBeGreaterThan(afterFailure)

    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toBeHidden({ timeout: 15000 })
  })
})
