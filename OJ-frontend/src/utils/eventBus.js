/**
 * eventBus — 轻量事件总线（无外部依赖）
 * 用于解耦数据层和 UI 层：request.js 在鉴权/网络错误时 emit 事件，
 * UI 层（App.vue / composables）监听并处理。
 */

const listeners = new Map()

function on(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set())
  listeners.get(event).add(fn)
  return () => listeners.get(event)?.delete(fn)
}

function emit(event, payload) {
  listeners.get(event)?.forEach(fn => fn(payload))
}

function clear() {
  listeners.clear()
}

export const authBus = {
  on,
  emit,
  clear
}
