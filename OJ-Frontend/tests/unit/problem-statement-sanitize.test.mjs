import { test } from 'node:test'
import assert from 'node:assert/strict'
import { sanitizeStatementMarkup } from '../../src/utils/statement-markup.js'

// 与后端 StatementSanitizerTest 使用同一组输入：任何一侧放宽策略都会被这组用例拦住。

test('strips script, event handlers and javascript urls', () => {
  const out = sanitizeStatementMarkup(
    '<p onclick="alert(1)">safe</p><script>alert(2)</script>' +
      '<img src=x onerror="alert(3)"><a href="javascript:alert(4)">link</a>'
  )
  assert.match(out, /<p>safe<\/p>/)
  assert.match(out, /<img src="x">/)
  assert.match(out, /<a>link<\/a>/)
  assert.doesNotMatch(out, /script|onclick|onerror|javascript|alert/i)
})

test('drops iframe, style and svg with their content', () => {
  const out = sanitizeStatementMarkup(
    '<iframe src="javascript:alert(1)"></iframe><style>body{background:url(//evil)}</style>' +
      '<svg onload="alert(2)"><circle/></svg>正文'
  )
  assert.equal(out, '正文')
})

test('keeps fenced code byte for byte', () => {
  const markdown = '```cpp\n#include <iostream>\nstd::vector<int> v;\n```'
  assert.equal(sanitizeStatementMarkup(markdown), markdown)
})

test('keeps dangerous tags inside fenced code', () => {
  const markdown = '```html\n<script>alert(1)</script>\n<img src=x onerror=alert(2)>\n```'
  assert.equal(sanitizeStatementMarkup(markdown), markdown)
})

test('keeps inline code byte for byte', () => {
  const markdown = '行内 `<script>alert(1)</script>` 与 `vector<int>` 保留'
  assert.equal(sanitizeStatementMarkup(markdown), markdown)
})

test('still removes dangerous html after an inline code span', () => {
  assert.equal(sanitizeStatementMarkup('`a` <script>alert(1)</script> `b`'), '`a`  `b`')
})

test('keeps markdown tables and latex', () => {
  const markdown = '| 列1 | 列2 |\n| --- | --- |\n| 1   | 2   |\n\n给定 $a < b$，复杂度 $O(n\\log n)$。'
  assert.equal(sanitizeStatementMarkup(markdown), markdown)
})

test('keeps table structure with colspan and drops handlers', () => {
  const out = sanitizeStatementMarkup(
    '<table><thead><tr><th colspan="2" onclick="x">标题</th></tr></thead>' +
      '<tbody><tr><td rowspan="2">值</td></tr></tbody></table>'
  )
  assert.match(out, /<table>/)
  assert.match(out, /<th colspan="2">/)
  assert.match(out, /<td rowspan="2">/)
  assert.doesNotMatch(out, /onclick/)
})

test('keeps relative links and images', () => {
  const out = sanitizeStatementMarkup('<a href="/problem/1">站内</a><img src="/uploads/a.png" alt="图">')
  assert.match(out, /href="\/problem\/1"/)
  assert.match(out, /src="\/uploads\/a.png"/)
  assert.match(out, /alt="图"/)
})

test('keeps unknown inert tag text', () => {
  const markdown = '模板开头：#include <iostream> 与 vector<int>'
  assert.equal(sanitizeStatementMarkup(markdown), markdown)
})

test('escapes unparseable tag-like text', () => {
  assert.equal(sanitizeStatementMarkup('返回 pair<int, int>'), '返回 pair&lt;int, int>')
})

test('escapes malformed tags that browsers would still parse', () => {
  for (const payload of ['<img/src=x onerror=alert(1)>', '<img src=x onerror=alert(1)', '<script/x>alert(1)</script>']) {
    const out = sanitizeStatementMarkup(payload)
    assert.doesNotMatch(out, /<img|<script/i, payload)
    assert.match(out, /&lt;/i, payload)
  }
})

test('drops meta and form tags', () => {
  const out = sanitizeStatementMarkup(
    '<meta http-equiv="refresh" content="0;url=https://evil.test">正文<form><input name=x></form>'
  )
  assert.equal(out, '正文')
})

test('drops unknown tag carrying an event handler', () => {
  assert.equal(sanitizeStatementMarkup('<custom-el onclick="alert(1)">内容</custom-el>'), '内容')
})

test('removes html comments', () => {
  assert.equal(sanitizeStatementMarkup('<!-- <script>alert(1)</script> -->ok'), 'ok')
})

test('is idempotent', () => {
  const source = '`<b>` <p onclick="x">段落</p><script>alert(1)</script><table><tr><td>c</td></tr></table>'
  const once = sanitizeStatementMarkup(source)
  assert.equal(sanitizeStatementMarkup(once), once)
})

test('blank input stays empty', () => {
  assert.equal(sanitizeStatementMarkup(''), '')
  assert.equal(sanitizeStatementMarkup(null), '')
})
