import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { normalizeCoursewareUrl } from '../../src/utils/coursewareUrl.js'

function preview(getDetail, popup) {
  const source = readFileSync(new URL('../../src/views/admin/AdminCoursewareView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const handlePreview =')
  const end = source.indexOf('\nconst handleGoProblemList', start)
  const messages = []
  const context = {
    window: { open: () => popup },
    getCoursewareDetail: getDetail,
    normalizeCoursewareUrl,
    showApiError: (error, fallback) => { if (!error.notified && !error.authNotified) messages.push(error.message || fallback) },
    ElMessage: { warning: value => messages.push(value), error: value => messages.push(value) }
  }
  vm.runInNewContext(source.slice(start, end) + '\nglobalThis.preview = handlePreview', context)
  return { run: context.preview, messages }
}

test('private preview waits for authorized detail before using its returned URL', async () => {
  const navigations = []
  let authorize
  const pending = new Promise(resolve => { authorize = resolve })
  const p = preview(() => pending, { location: { replace: url => navigations.push(url) } })
  const open = p.run({ id: 8, url: '/stale-url' })
  assert.deepEqual(navigations, [])
  authorize({ data: { url: '/coursewares/synthetic/index.html' } })
  await open
  assert.deepEqual(navigations, ['/coursewares/synthetic/index.html'])
})

test('denied private detail closes the popup without navigating', async () => {
  let closed = false
  const p = preview(async () => { throw new Error('denied') }, {
    location: { replace: () => assert.fail('denied preview must not navigate') },
    close: () => { closed = true }
  })
  await p.run({ id: 8, url: '/private-url' })
  assert.equal(closed, true)
  assert.equal(p.messages.length, 1)
})

test('blocked popup leaves authorization and navigation untouched', async () => {
  const p = preview(() => assert.fail('blocked popup needs no credential'), null)
  await p.run({ id: 8, url: '/private-url' })
  assert.equal(p.messages.length, 1)
})

test('preview rejects an unsafe URL returned from an existing database row', async () => {
  const navigations = []
  let closed = false
  const p = preview(async () => ({ data: { url: 'javascript:syntheticMarker' } }), {
    location: { replace: url => navigations.push(url) },
    close: () => { closed = true }
  })

  await p.run({ id: 9, url: '/legacy-url' })

  assert.deepEqual(navigations, [])
  assert.equal(closed, true)
  assert.equal(p.messages.length, 1)
})

test('external preview loses its opener before authorization and navigation', async () => {
  const popup = { opener: {}, location: { replace: () => {} } }
  const p = preview(async () => {
    assert.equal(popup.opener, null)
    return { data: { url: 'https://docs.qq.com/slide/example' } }
  }, popup)
  await p.run({ id: 9, url: '/stored-url' })
  assert.equal(popup.opener, null)
  assert.equal(p.messages.length, 0)
})
