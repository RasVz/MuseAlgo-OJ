import { test } from 'node:test'
import assert from 'node:assert/strict'
import { connectSSE } from '../../src/utils/sse.js'

function streamResponse(chunks, { status = 200, contentType = 'text/event-stream' } = {}) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: name => name.toLowerCase() === 'content-type' ? contentType : null },
    body: new ReadableStream({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(chunk)
        controller.close()
      }
    })
  }
}

function nextEvent(events, predicate = () => true, timeoutMs = 1000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('SSE event timeout')), timeoutMs)
    const poll = () => {
      const index = events.findIndex(predicate)
      if (index >= 0) {
        clearTimeout(timeout)
        resolve(events.splice(index, 1)[0])
      } else {
        setTimeout(poll, 1)
      }
    }
    poll()
  })
}

test('transient failures back off and stop after the configured retry budget', async () => {
  const originalFetch = globalThis.fetch
  const statuses = []
  let calls = 0
  let done
  const exhausted = new Promise(resolve => { done = resolve })
  globalThis.fetch = async () => { calls += 1; return streamResponse([], { status: 503 }) }
  try {
    const connection = connectSSE('/ranking/stream', () => {}, {
      getAccessToken: () => 'token', retryDelayMs: 1, maxRetryDelayMs: 10, maxRetries: 3,
      onStatus: status => { statuses.push(status); if (status.state === 'exhausted') done() }
    })
    await exhausted
    connection.close()
    assert.equal(calls, 4)
    assert.deepEqual(statuses.filter(s => s.state === 'reconnecting').map(s => s.delayMs), [1, 2, 4])
  } finally { globalThis.fetch = originalFetch }
})

test('short connected streams cannot reset the retry budget indefinitely', async () => {
  const originalFetch = globalThis.fetch
  let calls = 0
  let done
  const exhausted = new Promise(resolve => { done = resolve })
  globalThis.fetch = async () => {
    calls += 1
    return streamResponse([new TextEncoder().encode('event: connected\ndata: ok\n\n')])
  }
  try {
    const connection = connectSSE('/ranking/stream', () => {}, {
      getAccessToken: () => 'token', retryDelayMs: 1, maxRetries: 2,
      onStatus: status => { if (status.state === 'exhausted') done() }
    })
    await exhausted
    connection.close()
    assert.equal(calls, 3)
  } finally { globalThis.fetch = originalFetch }
})

test('fetch stream sends Bearer auth and parses fragmented UTF-8 SSE fields', async () => {
  const originalFetch = globalThis.fetch
  const events = []
  const frame = new TextEncoder().encode(': heartbeat\r\nevent: ranking-invalidated\r\ndata: global-榜\r\ndata: version-7\r\n\r\n')
  const marker = new TextEncoder().encode('榜')[0]
  const splitAt = frame.findIndex(byte => byte === marker) + 1
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return streamResponse([frame.slice(0, splitAt), frame.slice(splitAt, splitAt + 1), frame.slice(splitAt + 1)])
  }

  try {
    const connection = connectSSE('/api/contest/42/ranking/global/stream', (name, data) => events.push({ name, data }), {
      getAccessToken: () => 'access-42'
    })
    const event = await nextEvent(events)
    connection.close()

    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/api/contest/42/ranking/global/stream')
    assert.equal(calls[0].options.headers.Authorization, 'Bearer access-42')
    assert.equal(calls[0].options.headers.Accept, 'text/event-stream')
    assert.equal(calls[0].options.signal instanceof AbortSignal, true)
    assert.equal(event.name, 'ranking-invalidated')
    assert.equal(event.data, 'global-榜\nversion-7')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('a dropped stream reconnects with the latest access token', async () => {
  const originalFetch = globalThis.fetch
  let token = 'before-refresh'
  const calls = []
  const events = []
  globalThis.fetch = async (_url, options) => {
    calls.push(options.headers.Authorization)
    if (calls.length === 1) {
      token = 'after-refresh'
      return streamResponse([new TextEncoder().encode('event: connected\ndata: ok\n\n')])
    }
    return streamResponse([new TextEncoder().encode('event: ranking-invalidated\ndata: official\n\n')])
  }

  try {
    const connection = connectSSE('/ranking/stream', (name, data) => events.push({ name, data }), {
      getAccessToken: () => token,
      retryDelayMs: 1,
      maxRetryDelayMs: 1
    })
    const event = await nextEvent(events, value => value.name === 'ranking-invalidated')
    connection.close()

    assert.deepEqual(calls, ['Bearer before-refresh', 'Bearer after-refresh'])
    assert.deepEqual(event, { name: 'ranking-invalidated', data: 'official' })
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('reconnect resumes from the last SSE event id', async () => {
  const originalFetch = globalThis.fetch
  const calls = []
  const events = []
  globalThis.fetch = async (_url, options) => {
    calls.push(options)
    if (calls.length === 1) {
      return streamResponse([new TextEncoder().encode('id: rank-17\nevent: connected\ndata: ok\n\n')])
    }
    return streamResponse([new TextEncoder().encode('event: ranking-invalidated\ndata: official\n\n')])
  }

  try {
    const connection = connectSSE('/ranking/stream', (name, data) => events.push({ name, data }), {
      getAccessToken: () => 'token',
      retryDelayMs: 1,
      maxRetryDelayMs: 1
    })
    const event = await nextEvent(events, value => value.name === 'ranking-invalidated')
    connection.close()

    assert.equal(calls[1].headers['Last-Event-ID'], 'rank-17')
    assert.equal(event.data, 'official')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('401 refreshes through the supplied authenticated API path before reconnecting', async () => {
  const originalFetch = globalThis.fetch
  let token = 'expired'
  let refreshes = 0
  const calls = []
  const events = []
  globalThis.fetch = async (_url, options) => {
    calls.push(options.headers.Authorization)
    if (calls.length === 1) return streamResponse([], { status: 401, contentType: 'application/json' })
    return streamResponse([new TextEncoder().encode('event: ranking-invalidated\ndata: global\n\n')])
  }

  try {
    const connection = connectSSE('/ranking/global/stream', (name, data) => events.push({ name, data }), {
      getAccessToken: () => token,
      onUnauthorized: async () => {
        refreshes += 1
        token = 'renewed'
      }
    })
    const event = await nextEvent(events)
    connection.close()

    assert.equal(refreshes, 1)
    assert.deepEqual(calls, ['Bearer expired', 'Bearer renewed'])
    assert.equal(event.data, 'global')
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('403 is terminal and closing an open stream aborts its fetch', async () => {
  const originalFetch = globalThis.fetch
  const failures = []
  const forbiddenCalls = []
  globalThis.fetch = async (_url, options) => {
    forbiddenCalls.push(options)
    return streamResponse([], { status: 403, contentType: 'text/plain' })
  }

  try {
    const forbidden = connectSSE('/ranking/stream', () => {}, {
      getAccessToken: () => 'student-token',
      retryDelayMs: 1,
      onError: error => failures.push(error)
    })
    const failure = await nextEvent(failures)
    forbidden.close()
    await new Promise(resolve => setTimeout(resolve, 10))
    assert.equal(forbiddenCalls.length, 1)
    assert.equal(failure.status, 403)

    let signal
    let started
    const opened = new Promise(resolve => { started = resolve })
    globalThis.fetch = async (_url, options) => {
      signal = options.signal
      started()
      return new Promise((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    }
    const connection = connectSSE('/ranking/stream', () => {}, { getAccessToken: () => 'token' })
    await opened
    connection.close()
    assert.equal(signal.aborted, true)
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('missing access token never opens an anonymous stream', async () => {
  const originalFetch = globalThis.fetch
  let calls = 0
  const failures = []
  globalThis.fetch = async () => { calls += 1 }

  try {
    const connection = connectSSE('/ranking/stream', () => {}, {
      getAccessToken: () => '',
      onError: error => failures.push(error)
    })
    const failure = await nextEvent(failures)
    connection.close()
    assert.equal(calls, 0)
    assert.equal(failure.status, 401)
  } finally {
    globalThis.fetch = originalFetch
  }
})
