import test from 'node:test'
import assert from 'node:assert/strict'
import {
  banSummary,
  formatUserTime,
  normalizeUserQuery,
  roleLabel,
  sortFromTable,
  userListParams,
  userStatusMeta,
  usersRouteQuery,
} from '../../src/utils/adminUserQuery.js'

test('user query supplies stable defaults and trims keywords', () => {
  assert.deepEqual(normalizeUserQuery(), {
    keyword: '',
    page: 1,
    size: 20,
    role: null,
    status: 'all',
    sortBy: 'id',
    order: 'desc',
  })
  assert.equal(normalizeUserQuery({ keyword: '  admin  ' }).keyword, 'admin')
})

test('user query rejects invalid pages, sizes and enumerations', () => {
  for (const page of ['0', '-1', '1.2', '1e3', 'Infinity', '2147483648', ['2']]) {
    assert.equal(normalizeUserQuery({ page }).page, 1)
  }
  assert.equal(normalizeUserQuery({ size: '1000' }).size, 20)
  assert.equal(normalizeUserQuery({ role: '9' }).role, null)
  assert.equal(normalizeUserQuery({ status: 'deleted' }).status, 'all')
  assert.equal(normalizeUserQuery({ sortBy: 'email' }).sortBy, 'id')
  assert.equal(normalizeUserQuery({ order: 'sideways' }).order, 'desc')
})

test('user query round trips filters, sorting and pagination', () => {
  const state = {
    keyword: 'admin',
    page: 3,
    size: 50,
    role: 2,
    status: 'banned',
    sortBy: 'submitCount',
    order: 'asc',
  }
  assert.deepEqual(normalizeUserQuery(usersRouteQuery(state)), state)
  assert.deepEqual(usersRouteQuery(normalizeUserQuery()), {})
})

test('user list params translate status filter into banned boolean', () => {
  const base = normalizeUserQuery()
  assert.equal(userListParams({ ...base, status: 'all' }).banned, undefined)
  assert.equal(userListParams({ ...base, status: 'active' }).banned, false)
  assert.equal(userListParams({ ...base, status: 'banned' }).banned, true)
})

test('status labels distinguish active and expired bans', () => {
  assert.deepEqual(userStatusMeta({ banActive: true, isBanned: true }), { type: 'danger', label: '封禁中' })
  assert.deepEqual(userStatusMeta({ banActive: false, isBanned: true }), { type: 'warning', label: '封禁已过期' })
  assert.deepEqual(userStatusMeta({ banActive: false, isBanned: false }), { type: 'success', label: '正常' })
  assert.equal(roleLabel(3), '管理员')
  assert.equal(roleLabel(9), '未知')
})

test('ban summary and time formatting handle permanent and expired bans', () => {
  assert.equal(formatUserTime('2026-11-15T12:30:11'), '2026-11-15 12:30')
  assert.equal(formatUserTime(null), '未记录')
  assert.equal(banSummary({ banActive: true, banReason: '', banExpireTime: null }), '未填写原因 · 永久')
  assert.match(
    banSummary({ banActive: false, isBanned: true, banExpireTime: '2026-11-10T10:00:00' }),
    /封禁已于 2026-11-10 10:00 到期/,
  )
})

test('table sorting maps only whitelisted fields', () => {
  assert.deepEqual(sortFromTable({ prop: 'submitCount', order: 'ascending' }), {
    sortBy: 'submitCount',
    order: 'asc',
  })
  assert.deepEqual(sortFromTable({ prop: 'email', order: 'descending' }), { sortBy: 'id', order: 'desc' })
  assert.deepEqual(sortFromTable({ prop: 'id', order: null }), { sortBy: 'id', order: 'desc' })
})
