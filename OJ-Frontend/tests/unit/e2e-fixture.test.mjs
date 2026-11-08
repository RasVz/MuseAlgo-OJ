import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

test('shared browser fixture publishes both contests before student registration', async () => {
  const source = readFileSync(new URL('../e2e/fixtures/e2e_prepare.js', import.meta.url), 'utf8')
    .split('let memo = null')[0].replace(/^import .*\n/gm, '').replace(/export /g, '')
  const context = {
    process: { env: {} }, join: (...parts) => parts.join('/'), tmpdir: () => '/tmp',
    readFileSync: () => { throw new Error('no earlier evidence') },
    login: async username => ({ username }), API_BASE: 'http://synthetic.invalid'
  }
  vm.runInNewContext(source + '\nglobalThis.createFixture = createFixture', context)
  const contests = []
  const registrations = []
  let className, level
  const ok = data => ({ status: 200, body: { code: 200, data } })
  const api = {
    async get(path, { params = {} } = {}) {
      if (path === '/admin/users/list') return ok({ records: [{ id: 30, username: params.keyword }] })
      if (path === '/class/list') return ok([{ id: 40, name: className }])
      if (path === '/class/inviteCode') return ok('synthetic-code')
      if (path.startsWith('/profile/report/jobs/')) return ok({ id: 50, status: 'GENERATED' })
      if (path === '/admin/contest-levels') return ok([level])
      if (path === '/contest/list') return ok({ records: params.keyword
        ? contests.filter(row => row.title === params.keyword)
        : [{ id: 2, title: '2024 信奥校选模拟赛', rankFinalizedAt: 'synthetic' }] })
      if (path.startsWith('/admin/settlement-replay/')) return ok({ warnings: [] })
      throw new Error('Unexpected GET ' + path)
    },
    async post(path, { data } = {}) {
      if (path === '/class/create') className = data.name
      if (path === '/profile/report/jobs') return ok({ id: 50 })
      if (path === '/admin/contest-levels') level = { ...data, id: 60 }
      if (path === '/contest/add') contests.push({ ...data, id: contests.length + 70 })
      if (path.startsWith('/contest/register/')) {
        const id = Number(path.split('/').pop())
        if (contests.find(row => row.id === id)?.visible !== 1) {
          return { status: 200, body: { code: 403, message: '需要教师或管理员权限' } }
        }
        registrations.push(id)
      }
      return ok(null)
    },
    async put() { return ok(null) }
  }
  const result = await context.createFixture(api, {
    adminSession: {}, teacherSession: {}, studentSession: {}
  })
  assert.equal(contests.length, 2)
  assert.deepEqual(registrations, [result.contests.review.id, result.contests.star.id])
})
