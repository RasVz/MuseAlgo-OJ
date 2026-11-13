import test from 'node:test'
import assert from 'node:assert/strict'
import {
  classesRouteQuery,
  classCreatedLabel,
  classTeacherLabel,
  normalizeClassesQuery,
} from '../../src/utils/adminClassesQuery.js'

test('class query supplies compatible defaults and trims submitted keywords', () => {
  assert.deepEqual(normalizeClassesQuery(), {
    keyword: '',
    page: 1,
    size: 20,
    memberStatus: 'all',
    sort: 'newest',
  })
  assert.equal(normalizeClassesQuery({ keyword: '  算法  ' }).keyword, '算法')
})

test('shared class query rejects invalid pages, arrays and unknown enumerations', () => {
  for (const page of ['0', '-1', '1.2', '1e3', 'Infinity', '2147483648', ['2', '3']]) {
    assert.equal(normalizeClassesQuery({ page }).page, 1)
  }
  assert.deepEqual(
    normalizeClassesQuery({
      keyword: ['x'],
      size: '1000',
      memberStatus: 'unknown',
      sort: ['oldest'],
    }),
    normalizeClassesQuery(),
  )
  assert.equal(normalizeClassesQuery({ keyword: 'a'.repeat(101) }).keyword.length, 100)
})

test('class query round trips filters, ordering, page and size without losing state', () => {
  const state = { keyword: '算法', page: 3, size: 50, memberStatus: 'empty', sort: 'oldest' }
  assert.deepEqual(normalizeClassesQuery(classesRouteQuery(state)), state)
  assert.deepEqual(classesRouteQuery(normalizeClassesQuery()), {})
})

test('class preview labels distinguish missing teacher and time from zero members', () => {
  assert.equal(classTeacherLabel({ teacherName: ' 小林 ', teacherId: 3 }), '小林')
  assert.equal(classTeacherLabel({ teacherName: ' ', teacherId: 3 }), '教师账号 #3')
  assert.equal(classTeacherLabel({}), '教师信息缺失')
  assert.equal(classCreatedLabel('2026-11-13T12:30:11'), '2026-11-13 12:30')
  assert.equal(classCreatedLabel(null), '时间未记录')
})
