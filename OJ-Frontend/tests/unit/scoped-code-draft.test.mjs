import assert from 'node:assert/strict'
import test from 'node:test'
import { createScopedDraftAdapter } from '../../src/composables/useScopedCodeDraft.js'

test('contest code drafts are isolated between users and ignore unowned legacy keys', async () => {
  const values = new Map()
  const storage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), key: index => [...values.keys()][index], get length() { return values.size } }
  const previous = globalThis.window
  globalThis.window = { localStorage: storage }
  try {
    storage.setItem('musealgo:scoped-draft:contest:77:201:cpp', JSON.stringify({ code: 'unowned' }))
    const first = createScopedDraftAdapter('contest:77', 41)
    const second = createScopedDraftAdapter('contest:77', 42)
    const anonymous = createScopedDraftAdapter('contest:77', null)
    await first.save({ problemId: 201, language: 'cpp', code: 'private A' })
    assert.equal(await second.load({ problemId: 201, language: 'cpp' }), null)
    assert.equal(await second.latest({ problemId: 201 }), null)
    assert.equal(await anonymous.load({ problemId: 201, language: 'cpp' }), null)
    await anonymous.save({ problemId: 201, language: 'cpp', code: 'anonymous' })
    assert.equal((await first.load({ problemId: 201, language: 'cpp' })).code, 'private A')
    await second.save({ problemId: 201, language: 'cpp', code: 'private B' })
    assert.equal((await second.latest({ problemId: 201 })).code, 'private B')
    await first.clear({ problemId: 201, language: 'cpp' })
    assert.equal((await second.load({ problemId: 201, language: 'cpp' })).code, 'private B')
  } finally { globalThis.window = previous }
})
