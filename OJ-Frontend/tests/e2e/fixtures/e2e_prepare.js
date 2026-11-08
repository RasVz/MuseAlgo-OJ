// QA-02 合成数据准备：全部通过真实后端 API 创建，命名统一带 QA02 合成前缀，
// 不写库、不改迁移、不新增依赖。测试只读取这里返回的 ID/名称，
// 断言按 ID/名称定位，不依赖 seed 行序，也不依赖 user.rating 的默认值。
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { test as authTest, expect, login, API_BASE } from './e2e_auth.js'

export const PREFIX = 'QA02合成'
export const PASSWORD = '123456'
export const REPORT_PERIOD = { periodStart: '2024-12-16', periodEnd: '2024-12-22' }

const CACHE_FILE = join(process.env.OJ_TEST_OUTPUT_DIR || tmpdir(), 'qa02-fixture.json')

export function readEvidence() {
  const file = process.env.OJ_TEST_EVIDENCE || join(process.env.OJ_TEST_OUTPUT_DIR || '.', 'api-smoke.json')
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

/** 断言统一响应体 code===200 并返回 data（后端业务错误也是 HTTP 200 + body.code）。 */
export function expectOk(res, label) {
  if (!res || !res.body) throw new Error(label + '：响应不是 JSON（HTTP ' + (res && res.status) + '）')
  if (res.body.code !== 200) {
    throw new Error(label + '：code=' + res.body.code + ' message=' + (res.body.message || res.body.msg || ''))
  }
  return res.body.data
}

async function ensureSyntheticStudent(api, adminSession, username, nickname) {
  const found = await api.get('/admin/users/list', {
    session: adminSession,
    params: { page: 1, size: 20, keyword: username },
  })
  const page = expectOk(found, '查询合成用户')
  const row = (page.records || []).find((item) => item.username === username)
  if (row) return row.id
  expectOk(
    await api.post('/admin/users/create', {
      session: adminSession,
      data: { username, password: PASSWORD, nickname, role: 1 },
    }),
    '创建合成学生账号'
  )
  const again = await api.get('/admin/users/list', {
    session: adminSession,
    params: { page: 1, size: 20, keyword: username },
  })
  const page2 = expectOk(again, '回查合成用户')
  const created = (page2.records || []).find((item) => item.username === username)
  if (!created) throw new Error('合成学生账号创建后未能查到：' + username)
  return created.id
}

async function findClassId(api, teacherSession, className) {
  const list = await api.get('/class/list', { session: teacherSession, params: { teacherId: 2 } })
  const rows = expectOk(list, '查询合成班级') || []
  const row = rows.find((item) => item.name === className)
  if (!row) throw new Error('未找到合成班级：' + className)
  return row.id
}

async function findContestByTitle(api, session, title) {
  const list = await api.get('/contest/list', {
    session,
    params: { currentPage: 1, pageSize: 100, keyword: title },
  })
  const page = expectOk(list, '查询合成比赛')
  const row = (page.records || []).find((item) => item.title === title)
  if (!row) throw new Error('未找到合成比赛：' + title)
  return row.id
}

/**
 * 重放链起点解析：优先选择「已固化且无快照不一致」的比赛。
 * - 后端结算调度器只会自动固化 seed 里已结束的比赛；seed 比赛 1 有 4 行 contest_rank 快照，
 *   但 seed submission.contest_id 全为 NULL，重放预览会报「正式参与者与固化快照不一致」，永远不可执行。
 * - 因此测试栈内由本脚本用管理员 API 显式固化 seed 比赛 2「2024 信奥校选模拟赛」（无 contest_rank 快照、
 *   无 contest_id 关联提交 → 空链自洽），使重放执行/恢复全链路在 enabled=true 测试窗口内可验证。
 *   这是测试栈合成数据操作（POST /contest/{id}/rank/finalize），不修改产品默认值，也不影响生产数据。
 */
export async function ensureReplayTarget(api, adminSession) {
  const list = await api.get('/contest/list', {
    session: adminSession,
    params: { currentPage: 1, pageSize: 100 },
  })
  const page = expectOk(list, '查询重放目标比赛')
  const rows = page.records || []
  const candidates = rows.filter((item) => item.title && item.title.includes('2024 信奥校选模拟赛'))
  const target = candidates[0] || rows.find((item) => item.rankFinalizedAt) || rows[0]
  if (!target) throw new Error('未找到可用于重放链的 seed 比赛')

  if (!target.rankFinalizedAt) {
    const finalized = await api.post('/contest/' + target.id + '/rank/finalize', { session: adminSession })
    expectOk(finalized, '固化重放目标比赛（测试栈合成数据操作）')
  }

  const preview = await api.get('/admin/settlement-replay/' + target.id + '/preview', { session: adminSession })
  const data = expectOk(preview, '查询重放预览（校验可执行性）')
  const blocking = (data.warnings || []).filter((w) => !String(w).includes('维护开关未开启'))
  if (blocking.length > 0) {
    throw new Error('重放目标比赛 #' + target.id + ' 存在非开关类阻断：' + blocking.join('；'))
  }
  return {
    contestId: target.id,
    title: target.title,
    finalized: true,
    affectedUsers: data.affectedUsers,
    totalOldRows: data.totalOldRows,
    warnings: data.warnings || [],
  }
}

async function waitJob(api, session, jobId) {
  for (let i = 0; i < 20; i += 1) {
    const job = expectOk(await api.get('/profile/report/jobs/' + jobId, { session }), '查询学习周报任务')
    if (job.status === 'GENERATED' || job.status === 'FAILED') return job
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error('学习周报任务未在 10 秒内完成')
}

export async function createFixture(api, sessions) {
  const { adminSession, teacherSession, studentSession } = sessions
  const suffix = Date.now().toString(36)

  // 1) 合成学生（管理员代建）+ 合成班级（teacher01 名下）+ 邀请码入班。
  const username = 'qa02_student_' + suffix
  const nickname = PREFIX + '学生'
  const studentId = await ensureSyntheticStudent(api, adminSession, username, nickname)
  const syntheticStudent = await login(username)

  const className = PREFIX + '报告班-' + suffix
  expectOk(
    await api.post('/class/create', {
      session: teacherSession,
      data: { name: className, teacherId: 2, description: 'QA-02 合成班级，仅用于隔离环境回归' },
    }),
    '创建合成班级'
  )
  const classId = await findClassId(api, teacherSession, className)
  const inviteCode = expectOk(
    await api.get('/class/inviteCode', { session: teacherSession, params: { classId } }),
    '获取合成班级邀请码'
  )
  const join = await api.post('/class/joinByCode', { session: syntheticStudent, data: { code: inviteCode } })
  if (!join.body || join.body.code !== 200) {
    const msg = (join.body && (join.body.message || join.body.msg)) || ''
    if (!msg.includes('已在班级中')) throw new Error('合成学生加入班级失败：' + msg)
  }

  // 2) 姓名快照基线：报告任务落库时写入 studentName 快照；生成后改昵称，历史里应保留旧名。
  const snapshotName = nickname
  const renamedNickname = PREFIX + '学生改名'

  const job = expectOk(
    await api.post('/profile/report/jobs', {
      session: teacherSession,
      params: { studentId, classId, ...REPORT_PERIOD },
    }),
    '生成合成学习周报'
  )
  const finishedJob = await waitJob(api, teacherSession, job.id)
  if (finishedJob.status !== 'GENERATED') {
    throw new Error('合成学习周报生成失败：status=' + finishedJob.status + ' error=' + (finishedJob.errorMessage || ''))
  }
  expectOk(
    await api.put('/user/profile', { session: syntheticStudent, data: { nickname: renamedNickname } }),
    '更新合成学生昵称（验证报告姓名快照）'
  )

  // 3) 合成比赛：报名审核赛（无 Rating 限制）+ 限额等级赛（用于超限打星号展示）。
  const reviewTitle = PREFIX + '报名审核赛-' + suffix
  expectOk(
    await api.post('/contest/add', {
      session: teacherSession,
      data: {
        title: reviewTitle,
        description: 'QA-02 合成比赛：报名审核流程',
        startTime: '2026-12-01 10:00:00',
        endTime: '2026-12-01 12:00:00',
        mode: 'ACM',
        category: 'FORMAL',
        registrationReview: 1,
      },
    }),
    '创建报名审核合成比赛'
  )
  const reviewContestId = await findContestByTitle(api, teacherSession, reviewTitle)

  const levelCode = 'QA02_LEVEL_' + suffix.toUpperCase()
  expectOk(
    await api.post('/admin/contest-levels', {
      session: adminSession,
      data: {
        code: levelCode,
        name: PREFIX + '限额等级',
        minRating: 1500,
        maxRating: 2000,
        enabled: true,
        sortOrder: 90,
        description: 'QA-02 合成等级，用于验证 Rating 限额清空',
      },
    }),
    '创建合成比赛等级'
  )
  const levels = expectOk(await api.get('/admin/contest-levels', { session: adminSession }), '查询比赛等级')
  const levelRow = (levels || []).find((item) => item.code === levelCode)
  if (!levelRow) throw new Error('合成比赛等级创建后未能查到：' + levelCode)

  const starTitle = PREFIX + '限额等级赛-' + suffix
  expectOk(
    await api.post('/contest/add', {
      session: teacherSession,
      data: {
        title: starTitle,
        description: 'QA-02 合成比赛：评级限额与打星号展示',
        startTime: '2026-12-02 10:00:00',
        endTime: '2026-12-02 12:00:00',
        mode: 'ACM',
        category: 'FORMAL',
        levelCode,
        registrationReview: 0,
      },
    }),
    '创建限额等级合成比赛'
  )
  const starContestId = await findContestByTitle(api, teacherSession, starTitle)

  // 4) 报名：审核赛由 student07 报名（PENDING，留给 UI 审核）；限额赛由 syntheticStudent 报名（eligible=0 超限）。
  const reviewRegister = await api.post('/contest/register/' + reviewContestId, { session: studentSession })
  expectOk(reviewRegister, 'student07 报名合成审核赛')
  const starRegister = await api.post('/contest/register/' + starContestId, { session: syntheticStudent })
  expectOk(starRegister, '合成学生报名限额等级赛')

  // 5) 重判样本：优先复用 api-smoke 产生的真实提交，其次退化为 seed 提交 1（contest_id 为空，非固化比赛）。
  const evidence = readEvidence()
  const submissionId =
    (evidence && evidence.rejudge && evidence.rejudge.submissionId) ||
    (evidence && evidence.submissions && evidence.submissions[0] && evidence.submissions[0].id) ||
    1

  // 6) 重放链起点：动态解析「已固化且无快照不一致」的比赛（见 ensureReplayTarget 注释）。
  const replayTarget = await ensureReplayTarget(api, adminSession)

  return {
    serverKey: API_BASE,
    createdAt: new Date().toISOString(),
    suffix,
    student: { id: studentId, username, nickname, session: syntheticStudent },
    class: { id: classId, name: className, snapshotName, renamedNickname },
    report: {
      studentId,
      jobId: finishedJob.id,
      periodStart: REPORT_PERIOD.periodStart,
      periodEnd: REPORT_PERIOD.periodEnd,
      snapshotName,
      renamedNickname,
    },
    contests: { review: { id: reviewContestId, title: reviewTitle }, star: { id: starContestId, title: starTitle } },
    level: { id: levelRow.id, code: levelCode },
    rejudge: { submissionId, evidence: evidence ? { operationId: evidence.rejudge && evidence.rejudge.operationId } : null },
    replay: replayTarget,
    student07: { id: 10, username: 'student07', nickname: '小杰' },
  }
}

let memo = null

/** 合成数据 fixture：同一次运行内所有 spec 文件共享同一份数据（按后端地址做隔离键）。 */
export const test = authTest.extend({
  fixture: async ({ api, adminSession, teacherSession, studentSession }, use) => {
    if (!memo) {
      try {
        const cached = JSON.parse(readFileSync(CACHE_FILE, 'utf8'))
        if (cached && cached.serverKey === API_BASE) memo = cached
      } catch {
        memo = null
      }
    }
    if (!memo) {
      memo = await createFixture(api, { adminSession, teacherSession, studentSession })
      try {
        writeFileSync(CACHE_FILE, JSON.stringify(memo, null, 2), 'utf8')
      } catch {
        // 证据目录不可写时仅内存共享；不影响测试结论。
      }
    }
    // 重放目标可能来自上一轮缓存的 fixture：每轮运行都重新校验/固化一次，保证可重放。
    memo.replay = await ensureReplayTarget(api, adminSession)
    await use(memo)
  },
})

export { expect, login }