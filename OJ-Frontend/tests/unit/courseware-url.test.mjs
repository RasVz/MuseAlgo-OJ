import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { normalizeCoursewareUrl } from '../../src/utils/coursewareUrl.js'

test('courseware URL policy accepts HTTP(S) and same-origin relative paths', () => {
  assert.equal(normalizeCoursewareUrl('https://docs.qq.com/slide/example'), 'https://docs.qq.com/slide/example')
  assert.equal(normalizeCoursewareUrl('HTTP://example.test/courseware'), 'http://example.test/courseware')
  assert.equal(normalizeCoursewareUrl('/coursewares/0123456789abcdef0123456789abcdef/index.html'), '/coursewares/0123456789abcdef0123456789abcdef/index.html')
  assert.equal(normalizeCoursewareUrl('coursewares/lesson/index.html'), '/coursewares/lesson/index.html')
})

test('courseware URL policy rejects active and protocol-relative schemes', () => {
  for (const value of [
    'javascript:syntheticMarker',
    'data:text/html,syntheticMarker',
    'file:///etc/passwd',
    '//attacker.example/courseware',
    '///attacker.example/courseware',
    'https:\\\\attacker.example/courseware',
    '/coursewares/lesson\nindex.html',
    '?only-a-query',
    '#only-a-fragment'
  ]) {
    assert.equal(normalizeCoursewareUrl(value), null, value)
  }
})

function resolveStudyUrl(data) {
  const source = readFileSync(new URL('../../src/views/study/CourseStudyView.vue', import.meta.url), 'utf8')
  const start = source.indexOf('const resolveCoursewareUrl =')
  const end = source.indexOf('\n\n// 外部 http(s) 链接', start)
  const context = {
    normalizeCoursewareUrl,
    Date: { now: () => 123 }
  }
  vm.runInNewContext(`${source.slice(start, end)}\nglobalThis.resolve = resolveCoursewareUrl`, context)
  return context.resolve(data)
}

test('study page revalidates persisted URLs before assigning the iframe URL', () => {
  assert.equal(resolveStudyUrl({ url: 'javascript:syntheticMarker' }), '')
  assert.equal(resolveStudyUrl({ url: 'data:text/html,syntheticMarker' }), '')
  assert.equal(resolveStudyUrl({ url: '//attacker.example/courseware' }), '')
  assert.equal(resolveStudyUrl({ url: 'coursewares/lesson/index.html' }), '/coursewares/lesson/index.html?_t=123')
  assert.equal(resolveStudyUrl({ url: 'https://docs.qq.com/slide/example' }), 'https://docs.qq.com/slide/example')
})
