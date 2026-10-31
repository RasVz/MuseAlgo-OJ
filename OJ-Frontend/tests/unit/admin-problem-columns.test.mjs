import test from 'node:test'
import assert from 'node:assert/strict'

import {
  ADMIN_PROBLEM_COLUMNS_KEY,
  DEFAULT_ADMIN_PROBLEM_COLUMNS,
  loadColumnVisibility,
  saveColumnVisibility
} from '../../src/utils/adminProblemColumns.js'

function memoryStorage(initial = {}) {
  const data = new Map(Object.entries(initial))
  return {
    getItem: (key) => (data.has(key) ? data.get(key) : null),
    setItem: (key, value) => data.set(key, String(value)),
    dump: () => data
  }
}

test('默认三列全部隐藏', () => {
  assert.deepEqual({ ...DEFAULT_ADMIN_PROBLEM_COLUMNS }, { passRatio: false, source: false, tags: false })
  assert.deepEqual(loadColumnVisibility(memoryStorage()), { passRatio: false, source: false, tags: false })
})

test('勾选的列持久化后能在下次打开时恢复', () => {
  const storage = memoryStorage()
  saveColumnVisibility(storage, { passRatio: true, source: false, tags: true })

  const raw = JSON.parse(storage.dump().get(ADMIN_PROBLEM_COLUMNS_KEY))
  assert.deepEqual(raw, { passRatio: true, source: false, tags: true })
  assert.deepEqual(loadColumnVisibility(storage), { passRatio: true, source: false, tags: true })
})

test('存储内容损坏或类型异常时回落到默认值', () => {
  assert.deepEqual(loadColumnVisibility(memoryStorage({ [ADMIN_PROBLEM_COLUMNS_KEY]: '{bad json' })),
    { passRatio: false, source: false, tags: false })
  assert.deepEqual(loadColumnVisibility(memoryStorage({ [ADMIN_PROBLEM_COLUMNS_KEY]: '["passRatio"]' })),
    { passRatio: false, source: false, tags: false })
  assert.deepEqual(loadColumnVisibility(memoryStorage({ [ADMIN_PROBLEM_COLUMNS_KEY]: '{"passRatio":"yes"}' })),
    { passRatio: false, source: false, tags: false })
})

test('storage 不可用时读写都不抛错', () => {
  assert.deepEqual(loadColumnVisibility(null), { passRatio: false, source: false, tags: false })
  assert.equal(saveColumnVisibility(null, { passRatio: true }), false)

  const throwing = {
    getItem() { throw new Error('denied') },
    setItem() { throw new Error('quota exceeded') }
  }
  assert.deepEqual(loadColumnVisibility(throwing), { passRatio: false, source: false, tags: false })
  assert.equal(saveColumnVisibility(throwing, { passRatio: true }), false)
})
