// 批量受理学习周报的纯函数边界：选择归一化、50 人分批与结果映射。
// 仅覆盖纯数据转换；提交与轮询行为由组件/接口联调覆盖。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  BATCH_LIMIT, chunkStudents, filterStudents, mergeJobIntoItem, normalizeBatchResult,
  normalizeStudents, summarizeBatchItems
} from '../../src/views/admin/report/composables/reportBatch.js'

test('normalizeStudents 去重、剔除非法 ID 并使用备注名兜底', () => {
  const roster = normalizeStudents([
    { studentId: 7, name: '张三' },
    { userId: '7', name: '重复学生' },
    { userId: 9 },
    { userId: 0 },
    { userId: -3 },
    { name: '缺少 ID' },
    null
  ])
  assert.deepEqual(roster, [
    { studentId: 7, name: '张三' },
    { studentId: 9, name: '学生 #9' }
  ])
  assert.deepEqual(normalizeStudents(null), [])
})

test('filterStudents 支持备注、用户名、邮箱与 ID 模糊搜索', () => {
  const members = [
    { userId: 4, username: 'student01', remark: '陈小明', email: 'chen@example.com' },
    { userId: 5, username: 'student02', remark: '林小红', email: 'lin@example.com' },
    { userId: 6, username: 'student03' }
  ]
  assert.equal(filterStudents(members, '').length, 3)
  assert.equal(filterStudents(members, '   ').length, 3)
  assert.deepEqual(filterStudents(members, '陈').map((m) => m.userId), [4])
  assert.deepEqual(filterStudents(members, 'STUDENT02').map((m) => m.userId), [5])
  assert.deepEqual(filterStudents(members, 'lin@').map((m) => m.userId), [5])
  assert.deepEqual(filterStudents(members, '6').map((m) => m.userId), [6])
  assert.deepEqual(filterStudents(members, '不存在'), [])
  assert.deepEqual(filterStudents(null, '陈'), [])
})

test('chunkStudents 按后端上限分批且保持顺序', () => {
  assert.equal(BATCH_LIMIT, 50)
  const students = Array.from({ length: 120 }, (_, index) => ({
    studentId: index + 1,
    name: `学生${index + 1}`
  }))
  const chunks = chunkStudents(students)
  assert.deepEqual(chunks.map((chunk) => chunk.length), [50, 50, 20])
  assert.equal(chunks[0][0].studentId, 1)
  assert.equal(chunks[2][19].studentId, 120)
  assert.deepEqual(chunkStudents(students.slice(0, 3), 2).map((chunk) => chunk.length), [2, 1])
  // 非法上限回退到 BATCH_LIMIT，避免一次提交被后端整体拒绝。
  assert.equal(chunkStudents(students, 0).length, 3)
})

test('normalizeBatchResult 区分已受理、失败与未受理', () => {
  const student = { studentId: 11, name: '李四' }

  const pending = normalizeBatchResult(
    { studentId: 11, success: false, accepted: true, status: 'PENDING', jobId: 301, error: null },
    student
  )
  assert.equal(pending.jobId, 301)
  assert.equal(pending.status, 'PENDING')
  assert.equal(pending.rejected, false)
  assert.equal(pending.canRetry, false)
  assert.equal(pending.errorMessage, '')

  const generated = normalizeBatchResult(
    { studentId: 11, success: true, accepted: true, status: 'GENERATED', jobId: 302 },
    student
  )
  assert.equal(generated.status, 'GENERATED')
  assert.equal(generated.rejected, false)

  const failed = normalizeBatchResult(
    { studentId: 11, success: false, accepted: false, status: 'FAILED', jobId: 303, errorCode: 'RENDER_FAILED' },
    student
  )
  assert.equal(failed.status, 'FAILED')
  assert.equal(failed.rejected, false)
  assert.equal(failed.failure.code, 'RENDER_FAILED')
  assert.equal(failed.errorMessage, 'RENDER_FAILED')

  // 未受理：后端返回 accepted=false、status=null，error 为稳定错误码。
  const rejected = normalizeBatchResult(
    { studentId: 11, success: false, accepted: false, status: null, errorCode: 'NO_PERMISSION' },
    student
  )
  assert.equal(rejected.status, 'REJECTED')
  assert.equal(rejected.rejected, true)
  assert.equal(rejected.jobId, null)
  assert.equal(rejected.errorMessage, 'NO_PERMISSION')
})

test('mergeJobIntoItem 同步服务端状态与可重试判定', () => {
  const created = {
    studentId: 5,
    name: '王五',
    jobId: 88,
    status: 'PENDING',
    accepted: true,
    rejected: false,
    attempts: 0,
    canRetry: false,
    failure: null,
    errorMessage: '',
    retrying: false
  }

  mergeJobIntoItem(created, {
    id: 88,
    status: 'FAILED',
    attempts: 3,
    canRetry: true,
    errorMessage: '渲染超时',
    generationFailure: { code: 'RENDER_TIMEOUT', safeMessage: '渲染超时', canRetry: true }
  })
  assert.equal(created.status, 'FAILED')
  assert.equal(created.attempts, 3)
  assert.equal(created.canRetry, true)
  assert.equal(created.failure.code, 'RENDER_TIMEOUT')
  assert.equal(created.errorMessage, '渲染超时')

  mergeJobIntoItem(created, { id: 88, status: 'GENERATED', attempts: 3, canRetry: false })
  assert.equal(created.status, 'GENERATED')
  assert.equal(created.canRetry, false)
  assert.equal(created.failure, null)
})

test('summarizeBatchItems 汇总受理、排队、生成、失败与未受理', () => {
  const summary = summarizeBatchItems([
    { status: 'PENDING', rejected: false },
    { status: 'GENERATING', rejected: false },
    { status: 'GENERATED', rejected: false },
    { status: 'FAILED', rejected: false },
    { status: 'REJECTED', rejected: true }
  ])
  assert.deepEqual(summary, {
    total: 5, accepted: 4, queued: 2, generated: 1, failed: 1, rejected: 1
  })
  assert.deepEqual(summarizeBatchItems([]), {
    total: 0, accepted: 0, queued: 0, generated: 0, failed: 0, rejected: 0
  })
})
