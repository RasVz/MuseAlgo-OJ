import test from 'node:test'
import assert from 'node:assert/strict'

test('metrics retain unsent events and only remove the queued batch', async () => {
  const storage = new Map()
  const payloads = []
  let accepted = false
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { sendBeacon: (_url, blob) => { payloads.push(blob); return accepted } }
  })
  globalThis.window = { addEventListener() {} }
  globalThis.document = { addEventListener() {} }
  const originalSetInterval = globalThis.setInterval
  globalThis.setInterval = () => 0
  try {
    const { flush, trackApiFail } = await import('../../src/utils/metrics.js')
    for (let i = 0; i < 101; i++) trackApiFail(`/endpoint/${i}?secret=ignored`)
    flush()
    assert.equal(Object.keys(JSON.parse(storage.get('oj-metrics-v1')).api_fail).length, 101)
    accepted = true
    flush()
    const remaining = JSON.parse(storage.get('oj-metrics-v1')).api_fail
    assert.deepEqual(Object.keys(remaining), ['/endpoint/100'])
    flush()
    assert.deepEqual(JSON.parse(storage.get('oj-metrics-v1')), {})
    assert.equal(JSON.parse(await payloads[1].text()).events.length, 100)
    assert.deepEqual(JSON.parse(await payloads[2].text()).events.map(event => event.key), ['/endpoint/100'])
  } finally {
    globalThis.setInterval = originalSetInterval
    delete globalThis.localStorage
    delete globalThis.navigator
    delete globalThis.window
    delete globalThis.document
  }
})
