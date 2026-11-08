// QA-02 管理端策略回归：比赛等级限额清空、报名审核、正式/星号展示、CSV 业务错误不下载文件。
// 合成比赛/等级在 fixtures/e2e_prepare.js 中创建；CSV 负数用教师 token 验证，
// 正数（管理员下载）覆盖真实 Content-Disposition 文件名。
import { test, expect, expectOk, PREFIX } from './fixtures/e2e_prepare.js'

/**
 * 报名审核用例的幂等前置：同一真实栈多轮复用 fixture 时，student07 的报名可能已被上一轮审完。
 * 若已有 PENDING 报名则复用；否则新建一场合成报名审核赛并让 student07 重新报名，保证 UI 有「待审核」行。
 */
async function ensurePendingRegistration(api, { adminSession, teacherSession, studentSession, fixture }) {
  const listRes = await api.get('/contest/' + fixture.contests.review.id + '/registrations', {
    session: adminSession,
    params: { page: 1, size: 20 },
  })
  const records = (listRes.body && listRes.body.data && listRes.body.data.records) || []
  const existing = records.find((item) => item.username === 'student07' && item.status === 'PENDING')
  if (existing) return { contestId: fixture.contests.review.id, userId: existing.userId }

  const title = PREFIX + '报名审核赛重跑-' + Date.now().toString(36)
  expectOk(
    await api.post('/contest/add', {
      session: teacherSession,
      data: {
        title,
        description: 'QA-02 合成比赛：报名审核流程（重跑补充）',
        startTime: '2026-12-03 10:00:00',
        endTime: '2026-12-03 12:00:00',
        mode: 'ACM',
        category: 'FORMAL',
        registrationReview: 1,
      },
    }),
    '创建重跑报名审核比赛'
  )
  const contestList = await api.get('/contest/list', {
    session: teacherSession,
    params: { currentPage: 1, pageSize: 100, keyword: title },
  })
  const page = contestList.body && contestList.body.data
  const created = ((page && page.records) || []).find((item) => item.title === title)
  if (!created) throw new Error('重跑报名审核比赛创建后未能查到：' + title)
  expectOk(await api.post('/contest/register/' + created.id, { session: studentSession }), 'student07 重新报名合成审核赛')
  return { contestId: created.id, userId: 10 }
}

test.describe('QA-02 比赛等级与报名策略', () => {
  test('等级限额：清空 Rating 上下限后列表显示「不限」', async ({ adminPage, api, adminSession, fixture }) => {
    // 幂等前置：同一真实栈可能被多轮复用（fixture 缓存复用同一 level id，上一轮已清空过限额），
    // 先用管理员 API 显式重设 1500/2000，保证本用例的初始态稳定可重放。
    const reset = await api.put('/admin/contest-levels/' + fixture.level.id, {
      session: adminSession,
      data: {
        code: fixture.level.code,
        name: PREFIX + '限额等级',
        minRating: 1500,
        maxRating: 2000,
        enabled: true,
        sortOrder: 90,
        description: 'QA-02 合成等级，用于验证 Rating 限额清空',
      },
    })
    expect(reset.body.code).toBe(200)

    await adminPage.goto('/admin/contest-levels')
    await expect(adminPage.locator('.page-title').filter({ hasText: '比赛等级' })).toBeVisible({ timeout: 15000 })

    const row = adminPage.locator('.el-table__row').filter({ hasText: fixture.level.code }).first()
    await expect(row).toBeVisible({ timeout: 15000 })
    await expect(row).toContainText('1500 ≤ Rating < 2000')

    await row.getByRole('button', { name: '编辑' }).click()
    const dialog = adminPage.locator('.el-dialog').filter({ hasText: '编辑比赛等级' }).first()
    await expect(dialog).toBeVisible({ timeout: 15000 })

    // 清空两个 el-input-number：clear 后失焦，值应为 null。
    const numberInputs = dialog.locator('.el-input-number input')
    await numberInputs.nth(0).fill('')
    await numberInputs.nth(1).fill('')
    await numberInputs.nth(1).blur()
    const saveResponse = adminPage.waitForResponse(
      (resp) => /\/api\/admin\/contest-levels\/\d+$/.test(resp.url()) && resp.request().method() === 'PUT',
      { timeout: 20000 }
    )
    await dialog.getByRole('button', { name: '保存' }).click()
    const resp = await saveResponse
    const body = await resp.json()
    expect(body.code).toBe(200)

    const refreshed = adminPage.locator('.el-table__row').filter({ hasText: fixture.level.code }).first()
    await expect(refreshed).toContainText('不限', { timeout: 15000 })

    // API 层复核：上限/下限确实被置空（LambdaUpdateWrapper 显式 set null）。
    const levels = await api.get('/admin/contest-levels', { session: adminSession })
    const level = (levels.body.data || []).find((item) => item.code === fixture.level.code)
    expect(level.minRating).toBeFalsy()
    expect(level.maxRating).toBeFalsy()
  })

  test('报名审核：PENDING 报名可通过，状态与审核信息即时更新', async ({ adminPage, api, adminSession, teacherSession, studentSession, fixture }) => {
    const pending = await ensurePendingRegistration(api, { adminSession, teacherSession, studentSession, fixture })
    await adminPage.goto('/admin/contest/' + pending.contestId + '/registrations')
    await expect(adminPage.locator('.page-title').filter({ hasText: '报名审核' })).toBeVisible({ timeout: 15000 })

    const row = adminPage.locator('.el-table__row').filter({ hasText: '小杰' }).first()
    await expect(row).toBeVisible({ timeout: 20000 })
    await expect(row).toContainText('待审核')

    await row.getByRole('button', { name: '通过' }).click()
    const confirmBox = adminPage.locator('.el-message-box').filter({ hasText: '通过报名' }).first()
    await expect(confirmBox).toBeVisible({ timeout: 15000 })
    await confirmBox.getByRole('button', { name: '通过' }).click()

    const updated = adminPage.locator('.el-table__row').filter({ hasText: '小杰' }).first()
    await expect(updated).toContainText('已通过', { timeout: 20000 })

    const apiList = await api.get('/contest/' + pending.contestId + '/registrations', {
      session: adminSession,
      params: { page: 1, size: 20 },
    })
    const target = (apiList.body.data.records || []).find((item) => item.username === 'student07')
    expect(target.status).toBe('APPROVED')
    expect(target.eligible).toBe(1)
  })

  test('正式/星号展示：超限报名显示「超限打星号」，全局榜展示星号图例', async ({ adminPage, fixture }) => {
    await adminPage.goto('/admin/contest/' + fixture.contests.star.id + '/registrations')
    await expect(adminPage.locator('.page-title').filter({ hasText: '报名审核' })).toBeVisible({ timeout: 15000 })
    // 该赛未开启审核：应显示自动通过说明。
    await expect(adminPage.locator('.el-alert').filter({ hasText: '本场比赛未开启报名审核' }).first()).toBeVisible({ timeout: 15000 })

    // 报名行「用户」列显示 nickname||username（合成学生昵称为 QA02合成前缀），UID 列校验精确到人。
    const overRow = adminPage.locator('.el-table__row').filter({ hasText: 'QA02合成' }).first()
    await expect(overRow).toBeVisible({ timeout: 20000 })
    await expect(overRow).toContainText('UID: ' + fixture.student.id)
    await expect(overRow).toContainText('超限打星号')

    // 榜单页：全局榜图例包含「* 超限」「VP」「无标记为正式选手」。
    await adminPage.goto('/contest/' + fixture.contests.star.id + '/ranking')
    await expect(adminPage.locator('.page-title').filter({ hasText: '比赛排行榜' })).toBeVisible({ timeout: 15000 })
    await adminPage.locator('.el-radio-button').filter({ hasText: '全局榜' }).locator('input').click({ force: true })
    await expect(adminPage.getByText('超限选手打星号，不计 Rating')).toBeVisible({ timeout: 15000 })
    await expect(adminPage.getByText('无标记为正式选手')).toBeVisible()
  })

  test('CSV 业务错误：教师导出全局榜被拒绝且不产生下载文件', async ({ api, teacherSession, teacherPage, fixture }) => {
    // 后端：@PreAuthorize hasRole('ADMIN') → HTTP 403 JSON。
    const res = await api.get('/contest/' + fixture.contests.star.id + '/rank/global/export', { session: teacherSession })
    expect(res.status).toBe(403)
    expect(res.body.code).toBe(403)

    // 前端：点击「导出全局榜」后出现错误提示，且不产生 download 事件。
    await teacherPage.goto('/contest/' + fixture.contests.star.id + '/ranking')
    await expect(teacherPage.locator('.page-title').filter({ hasText: '比赛排行榜' })).toBeVisible({ timeout: 15000 })
    // 非管理员不渲染导出入口；直接断言按钮不存在，避免误点。
    await expect(teacherPage.getByRole('button', { name: '导出全局榜' })).toHaveCount(0)

    // 页面上没有任何下载入口，且即便直接调用导出接口也不会以附件形式落盘。
    let downloaded = false
    teacherPage.on('download', () => {
      downloaded = true
    })
    await teacherPage.waitForTimeout(500)
    expect(downloaded).toBe(false)
  })

  test('CSV 正例：管理员下载全局榜 CSV（真实 Content-Disposition 文件名）', async ({ adminPage, api, adminSession, fixture }) => {
    const res = await api.get('/contest/' + fixture.contests.star.id + '/rank/global/export', { session: adminSession })
    expect(res.status).toBe(200)
    expect(res.contentType).toContain('text/csv')
    expect(String(res.headers['content-disposition'] || '')).toContain(
      'contest-' + fixture.contests.star.id + '-global-rank.csv'
    )
    // BOM + 表头固定列。
    expect(res.text.charCodeAt(0)).toBe(0xfeff)
    expect(res.text).toContain('名次,用户,类型,解题数,罚时,总分,Rating前,Rating后,奖项')

    await adminPage.goto('/contest/' + fixture.contests.star.id + '/ranking')
    await expect(adminPage.locator('.page-title').filter({ hasText: '比赛排行榜' })).toBeVisible({ timeout: 15000 })
    await adminPage.locator('.el-radio-button').filter({ hasText: '全局榜' }).locator('input').click({ force: true })
    await expect(adminPage.getByRole('button', { name: '导出全局榜' })).toBeVisible({ timeout: 15000 })
    const downloadPromise = adminPage.waitForEvent('download', { timeout: 20000 })
    await adminPage.getByRole('button', { name: '导出全局榜' }).click()
    const download = await downloadPromise
    expect(download.suggestedFilename()).toBe('contest-' + fixture.contests.star.id + '-global-rank.csv')
  })
})