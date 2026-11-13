import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  renderProfileMarkdown,
  safeProfileImageUrl,
  validateProfileImage,
  insertProfileImage,
} from '../../src/utils/profileMarkdown.js'
import {
  visibleDifficultyBuckets,
  recentPracticeRows,
} from '../../src/utils/practicePresentation.js'

test('profile Markdown renders common formatting and treats raw HTML as text', () => {
  const html = renderProfileMarkdown(
    '# 介绍\n\n**算法**\n\n<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n\n[坏链接](javascript:alert(1))',
  )
  assert.match(html, /<h1>介绍<\/h1>/)
  assert.match(html, /<strong>算法<\/strong>/)
  assert.doesNotMatch(html, /<script|<img|href="javascript:/)
  assert.match(html, /&lt;script&gt;/)
})
test('images and links allow useful uploads without executable or credential-bearing URLs', () => {
  assert.equal(
    safeProfileImageUrl('/uploads/post-images/test.png'),
    '/uploads/post-images/test.png',
  )
  for (const url of [
    'javascript:alert(1)',
    'data:image/svg+xml,abc',
    '//evil.test/a',
    '/uploads/../x',
    'https://user:pass@example.com/a',
    'https://example.com/\na',
  ])
    assert.equal(safeProfileImageUrl(url), '')
  const html = renderProfileMarkdown('![示意图](/uploads/test.png)\n[博客](https://example.com)')
  assert.match(html, /src="\/uploads\/test.png"/)
  assert.match(html, /loading="lazy"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.doesNotMatch(renderProfileMarkdown('![x](file:///etc/passwd)'), /<img/)
})
test('image insertion replaces the selection and preserves surrounding Markdown', () => {
  const result = insertProfileImage('before SELECT after', '/uploads/test.png', 7, 13)
  assert.equal(result.text, 'before \n![图片](</uploads/test.png>)\n after')
  assert.equal(result.text.slice(result.cursor), ' after')
  assert.throws(() => insertProfileImage('', 'javascript:alert(1)', 0))
})
test('image upload rejects unsupported types and oversize files', () => {
  assert.equal(validateProfileImage({ type: 'image/webp', size: 5 * 1024 * 1024 }), '')
  assert.match(validateProfileImage({ type: 'image/svg+xml', size: 5 }), /请选择/)
  assert.match(validateProfileImage({ type: 'image/png', size: 5 * 1024 * 1024 + 1 }), /5MB/)
})
test('focused difficulty retains surrounding empty bins and full mode retains complete scale', () => {
  const rows = Array.from({ length: 23 }, (_, i) => ({
    rating: 800 + i * 100,
    solved: i === 1 ? 3 : 0,
  }))
  assert.deepEqual(
    visibleDifficultyBuckets(rows).map((row) => row.rating),
    [800, 900, 1000, 1100, 1200, 1300],
  )
  assert.equal(visibleDifficultyBuckets(rows, true).length, 23)
  assert.equal(visibleDifficultyBuckets(rows).find((row) => row.rating === 1000).count, 0)
})
test('practice records sort by their own timestamp without mutating response arrays', () => {
  const rows = [
    { problemId: 1, solvedAt: '2026-01-01T00:00:00' },
    { problemId: 2, solvedAt: '2026-03-01T00:00:00' },
  ]
  assert.deepEqual(
    recentPracticeRows(rows, 'passed').map((row) => row.problemId),
    [2, 1],
  )
  assert.equal(rows[0].problemId, 1)
  assert.equal(
    recentPracticeRows(
      [
        { problemId: 3, lastAttemptAt: 'bad' },
        { problemId: 4, lastAttemptAt: '2026-02-01' },
      ],
      'tried',
    )[0].problemId,
    4,
  )
})
