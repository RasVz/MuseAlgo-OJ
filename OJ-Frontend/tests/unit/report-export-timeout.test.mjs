import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

test('timed-out report export removes listeners and sends no further snapshot requests', async () => {
  const frameListeners = new Map()
  const windowListeners = new Map()
  const errors = []
  let timeoutCallback
  let intervalCallback
  let requests = 0
  let removed = false
  let timeoutCleared = false
  let intervalCleared = false
  const frame = {
    style: {},
    contentWindow: { postMessage: () => { requests += 1 } },
    setAttribute() {},
    addEventListener: (name, fn) => frameListeners.set(name, fn),
    removeEventListener: (name, fn) => { if (frameListeners.get(name) === fn) frameListeners.delete(name) },
    remove: () => { removed = true }
  }
  const context = {
    showApiError(error, fallback) { if (!error.notified && !error.authNotified) errors.push(error.message || fallback) },
    ref: value => ({ value }),
    ElMessage: { error: message => errors.push(message) },
    html2canvas() { throw new Error('timed-out report must not be rendered') },
    reportDocument: html => html,
    safeReportSnapshot() { throw new Error('timed-out report must not be used') },
    document: { createElement: () => frame, body: { appendChild() {} } },
    window: {
      addEventListener: (name, fn) => windowListeners.set(name, fn),
      removeEventListener: (name, fn) => { if (windowListeners.get(name) === fn) windowListeners.delete(name) }
    },
    crypto: { getRandomValues: array => array },
    setTimeout: fn => { timeoutCallback = fn; return 1 },
    clearTimeout: id => { if (id === 1) timeoutCleared = true },
    setInterval: fn => { intervalCallback = fn; return 2 },
    clearInterval: id => { if (id === 2) intervalCleared = true }
  }
  const source = readFileSync(new URL('../../src/views/admin/class/composables/useReportExport.js', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '')
    .replace('export function useReportExport()', 'function useReportExport()')
  vm.runInNewContext(source + '\nglobalThis.reportExport = useReportExport()', context)
  const pending = context.reportExport.exportImage('<html></html>', '测试')
  intervalCallback()
  assert.equal(requests, 1)
  timeoutCallback()
  await pending
  frameListeners.get('load')?.()
  intervalCallback()
  assert.equal(requests, 1)
  assert.equal(frameListeners.size, 0)
  assert.equal(windowListeners.size, 0)
  assert.equal(timeoutCleared, true)
  assert.equal(intervalCleared, true)
  assert.equal(context.reportExport.exporting.value, false)
  assert.equal(removed, true)
  assert.match(errors[0], /报告图表加载超时/)
})
