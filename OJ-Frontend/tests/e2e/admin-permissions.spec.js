// QA-02 权限矩阵：匿名 / 学生 / 教师 / 他班教师 / 管理员 × 关键端点。
// 断言依据后端 SecurityConfig：匿名 → HTTP 401 body.code=401「请先登录」；
// 已登录但无权限 → HTTP 403 body.code=403「无权访问该资源」；管理员 → HTTP 200 code=200。
// 同时验证非管理员直接访问 /admin/* 页面时由后端 403 阻断，页面呈现错误而不是数据。
import { test, expect, login } from './fixtures/e2e_prepare.js'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

test.describe('QA-02 权限矩阵', () => {
  test('匿名访问受保护端点返回 401 且不泄露数据', async ({ api }) => {
    const adminTrends = await api.get('/admin/trends', { params: { days: 7 } })
    expect(adminTrends.status).toBe(401)
    expect(adminTrends.body.code).toBe(401)
    expect(String(adminTrends.body.message || '')).toContain('请先登录')

    const rejudgeOps = await api.get('/admin/rejudge/operations', { params: { page: 1, size: 20 } })
    expect(rejudgeOps.status).toBe(401)
    expect(rejudgeOps.body.code).toBe(401)

    const reportJobs = await api.get('/profile/report/jobs', { params: { classId: 1, page: 1, size: 10 } })
    expect(reportJobs.status).toBe(401)
    expect(reportJobs.body.code).toBe(401)

    // 公开白名单端点匿名可读：确认权限断言不是「所有接口都 401」的假阳性。
    const publicContestList = await api.get('/contest/list', { params: { currentPage: 1, pageSize: 5 } })
    expect(publicContestList.status).toBe(200)
    expect(publicContestList.body.code).toBe(200)
  })

  test('学生访问管理端接口返回 403 且前端页面显示错误', async ({ studentSession, api, studentPage }) => {
    const trends = await api.get('/admin/trends', { session: studentSession, params: { days: 7 } })
    expect(trends.status).toBe(403)
    expect(trends.body.code).toBe(403)
    expect(String(trends.body.message || '')).toContain('无权访问该资源')

    const ops = await api.get('/admin/rejudge/operations', { session: studentSession, params: { page: 1, size: 20 } })
    expect(ops.status).toBe(403)
    expect(ops.body.code).toBe(403)

    // 页面层：注入学生登录态后直接访问 /admin/rejudge，不得出现操作记录数据。
    await studentPage.goto('/admin/rejudge')
    await expect(studentPage.locator('.page-title').filter({ hasText: '受控重判' })).toBeVisible({ timeout: 15000 })
    await expect(studentPage.locator('.el-table__row')).toHaveCount(0)
  })

  test('教师访问仅管理员端点返回 403，本班报告接口可用', async ({ teacherSession, api, fixture }) => {
    const trends = await api.get('/admin/trends', { session: teacherSession, params: { days: 7 } })
    expect(trends.status).toBe(403)
    expect(trends.body.code).toBe(403)

    const ops = await api.get('/admin/rejudge/operations', { session: teacherSession, params: { page: 1, size: 20 } })
    expect(ops.status).toBe(403)

    const globalExport = await api.get('/contest/1/rank/global/export', { session: teacherSession })
    expect(globalExport.status).toBe(403)

    // 本班报告接口：教师对自己班级有权限（HTTP 200 code=200）。
    const ownClass = await api.get('/profile/report/jobs', {
      session: teacherSession,
      params: { classId: fixture.class.id, page: 1, size: 10 },
    })
    expect(ownClass.status).toBe(200)
    expect(ownClass.body.code).toBe(200)
  })

  test('他班教师访问本班报告接口返回业务 403', async ({ otherTeacherSession, api, fixture }) => {
    const res = await api.get('/profile/report/jobs', {
      session: otherTeacherSession,
      params: { classId: fixture.class.id, page: 1, size: 10 },
    })
    expect(res.status).toBe(200)
    expect(res.body.code).toBe(403)
    expect(String(res.body.message || '')).toContain('无权限操作该班级报告')

    const job = await api.get('/profile/report/jobs/' + fixture.report.jobId, { session: otherTeacherSession })
    expect(job.status).toBe(200)
    expect(job.body.code).toBe(403)

    // 他班教师也无权管理该比赛的报名（比赛作者是 teacher01）。
    const registrations = await api.get('/contest/' + fixture.contests.review.id + '/registrations', {
      session: otherTeacherSession,
      params: { page: 1, size: 20 },
    })
    const body = registrations.body || {}
    expect([200, 403]).toContain(registrations.status)
    if (registrations.status === 200) {
      expect(body.code).toBe(403)
      expect(String(body.message || '')).toContain('无权管理该比赛报名')
    } else {
      expect(body.code).toBe(403)
    }
  })

  test('管理员访问管理端点全部 200', async ({ adminSession, api }) => {
    const trends = await api.get('/admin/trends', { session: adminSession, params: { days: 7 } })
    expect(trends.status).toBe(200)
    expect(trends.body.code).toBe(200)

    const ops = await api.get('/admin/rejudge/operations', { session: adminSession, params: { page: 1, size: 20 } })
    expect(ops.status).toBe(200)
    expect(ops.body.code).toBe(200)

    const levels = await api.get('/admin/contest-levels', { session: adminSession })
    expect(levels.status).toBe(200)
    expect(levels.body.code).toBe(200)

    const globalExport = await api.get('/contest/1/rank/global/export', { session: adminSession })
    expect(globalExport.status).toBe(200)
  })

  test('无 token 的伪造请求被拒绝（不信任本地存储）', async ({ api }) => {
    const forged = await api.call('GET', '/admin/trends', {
      headers: Object.assign({ Authorization: 'Bearer qa02-forged-token' }, JSON_HEADERS),
    })
    expect(forged.status).toBe(401)
    expect(forged.body.code).toBe(401)
    // login 辅助本身不应接受错误口令（防止缓存把失败当成功复用）。
    await expect(login('qa02_not_exists_user', 'wrong-password')).rejects.toThrow(/登录失败/)
  })
})