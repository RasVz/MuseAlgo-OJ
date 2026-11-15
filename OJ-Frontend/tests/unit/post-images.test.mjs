import test from 'node:test'
import assert from 'node:assert/strict'

import { parsePostImages } from '../../src/utils/post-images.js'

test('images 字符串解析为数组并丢弃空项', () => {
  assert.deepEqual(parsePostImages('a.png, b.png,,c.png '), ['a.png', 'b.png', 'c.png'])
  assert.deepEqual(parsePostImages(''), [])
  assert.deepEqual(parsePostImages(null), [])
})

test('images 数组保持顺序并去除空白项', () => {
  assert.deepEqual(parsePostImages(['a.png', '', ' b.png ']), ['a.png', 'b.png'])
})
