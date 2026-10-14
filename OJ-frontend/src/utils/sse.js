/**
 * SSE 简单封装 —— 支持自动重连。
 * @param url SSE 端点 URL
 * @param onEvent 接收到事件的回调 (eventName, data)
 * @returns {{ close: () => void }}
 */
export function connectSSE(url, onEvent) {
  let es = null
  let retryTimer = null

  function connect() {
    if (es) es.close()
    es = new EventSource(url)

    es.onmessage = (e) => {
      try {
        const data = JSON.parse(e.data)
        onEvent(e.type || 'message', data)
      } catch {
        onEvent(e.type || 'message', e.data)
      }
    }

    // 监听自定义事件
    es.addEventListener('ranking', (e) => {
      try { onEvent('ranking', JSON.parse(e.data)) } catch { /* 静默忽略，保持长连接 */ }
    })
    es.addEventListener('connected', () => {})

    es.onerror = () => {
      es.close()
      clearTimeout(retryTimer)
      retryTimer = setTimeout(connect, 3000) // 3秒后重连
    }
  }

  connect()

  return {
    close() {
      clearTimeout(retryTimer)
      if (es) es.close()
    }
  }
}
