/**
 * Authenticated fetch-based SSE client.
 *
 * Native EventSource cannot attach the application's Bearer token. Fetch keeps
 * the stream same-origin, carries Authorization in a header, and gives callers
 * an AbortController-backed close handle for route/tab changes.
 */
export function connectSSE(url, onEvent, options = {}) {
  const {
    getAccessToken = () => '',
    onUnauthorized,
    onError,
    onStatus,
    retryDelayMs = 1500,
    maxRetryDelayMs = 30000,
    maxRetries = 5
  } = options

  let stopped = false
  let retryTimer = null
  let controller = null
  let retryCount = 0
  let retryHintMs = retryDelayMs
  let lastEventId = ''
  let recoveringUnauthorized = false

  function reportStatus(state, extra = {}) {
    try { onStatus?.({ state, ...extra }) } catch { /* UI status is advisory. */ }
  }

  const reportError = error => {
    try {
      onError?.(error)
    } catch {
      // Reporting must not break connection cleanup or retry behavior.
    }
  }

  function scheduleReconnect(delayMs = retryHintMs) {
    if (stopped || retryTimer !== null) return
    if (retryCount > maxRetries) {
      stopped = true
      reportStatus('exhausted', { attempts: maxRetries })
      return
    }
    const delay = Math.min(Math.max(0, delayMs), maxRetryDelayMs)
    reportStatus('reconnecting', { attempt: retryCount, delayMs: delay })
    retryTimer = setTimeout(() => {
      retryTimer = null
      void connect()
    }, delay)
  }

  async function connect() {
    if (stopped) return

    const token = getAccessToken()
    if (!token) {
      const error = new Error('排行榜实时连接需要登录')
      error.status = 401
      stopped = true
      reportError(error)
      return
    }

    const activeController = new AbortController()
    reportStatus('connecting')
    controller = activeController
    const headers = {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
      'Cache-Control': 'no-cache'
    }
    if (lastEventId) headers['Last-Event-ID'] = lastEventId

    try {
      const response = await fetch(url, {
        method: 'GET',
        headers,
        cache: 'no-store',
        signal: activeController.signal
      })
      if (stopped) return

      if (response.status === 401 && onUnauthorized && !recoveringUnauthorized) {
        recoveringUnauthorized = true
        try {
          await onUnauthorized()
        } catch (error) {
          stopped = true
          reportError(error)
          return
        }
        if (stopped) return
        scheduleReconnect(0)
        return
      }

      if (!response.ok) {
        const error = new Error(`排行榜实时连接失败（HTTP ${response.status}）`)
        error.status = response.status
        if ([408, 429, 500, 502, 503, 504].includes(response.status)) {
          scheduleReconnect(nextRetryDelay())
        } else {
          stopped = true
          reportError(error)
        }
        return
      }

      const contentType = response.headers?.get?.('content-type') || ''
      if (!contentType.toLowerCase().includes('text/event-stream')) {
        const error = new Error('排行榜实时连接响应格式错误')
        error.status = response.status
        stopped = true
        reportError(error)
        return
      }

      recoveringUnauthorized = false
      const connectedAt = Date.now()
      await readEventStream(response.body, {
        isStopped: () => stopped,
        onEvent: (eventName, rawData) => {
          if (eventName === 'connected') reportStatus('connected')
          let data = rawData
          try {
            data = JSON.parse(rawData)
          } catch {
            // Plain-text data is valid SSE and is used for board invalidations.
          }
          try {
            onEvent(eventName, data)
          } catch {
            // One view callback must not poison the stream reader.
          }
        },
        onLastEventId: id => { lastEventId = id },
        onRetry: delay => { retryHintMs = delay }
      })
      if (!stopped) {
        // A short-lived 200/connected response is still a failed connection, not recovery.
        if (Date.now() - connectedAt >= 30000) retryCount = 0
        scheduleReconnect(nextRetryDelay())
      }
    } catch (error) {
      if (stopped || activeController.signal.aborted || error?.name === 'AbortError') return
      scheduleReconnect(nextRetryDelay())
    } finally {
      if (controller === activeController) controller = null
    }
  }

  function nextRetryDelay() {
    const delay = Math.min(retryHintMs * (2 ** retryCount), maxRetryDelayMs)
    retryCount += 1
    return delay
  }

  void connect()

  return {
    close() {
      stopped = true
      if (retryTimer !== null) clearTimeout(retryTimer)
      retryTimer = null
      controller?.abort()
      controller = null
    }
  }
}

export async function readEventStream(body, handlers) {
  if (!body?.getReader) throw new Error('排行榜实时连接不支持流式响应')

  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'
  let dataLines = []

  const dispatch = () => {
    if (dataLines.length > 0) handlers.onEvent(eventName || 'message', dataLines.join('\n'))
    eventName = 'message'
    dataLines = []
  }

  const readLine = line => {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) return

    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') eventName = value
    else if (field === 'data') dataLines.push(value)
    else if (field === 'id' && !value.includes('\u0000')) handlers.onLastEventId?.(value)
    else if (field === 'retry' && /^\d+$/.test(value)) handlers.onRetry?.(Number(value))
  }

  const drainLines = final => {
    while (buffer.length > 0) {
      const cr = buffer.indexOf('\r')
      const lf = buffer.indexOf('\n')
      let end
      if (cr < 0) end = lf
      else if (lf < 0) end = cr
      else end = Math.min(cr, lf)
      if (end < 0 || (!final && buffer[end] === '\r' && end === buffer.length - 1)) break

      const separatorLength = buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1
      readLine(buffer.slice(0, end))
      buffer = buffer.slice(end + separatorLength)
    }
    if (final && buffer.length > 0) {
      readLine(buffer)
      buffer = ''
    }
  }

  try {
    while (!handlers.isStopped()) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      drainLines(false)
    }
    buffer += decoder.decode()
    drainLines(true)
    if (dataLines.length > 0) dispatch()
  } finally {
    try { await reader.cancel() } catch { /* A closed stream must not replace its original error. */ }
    finally { reader.releaseLock() }
  }
}
