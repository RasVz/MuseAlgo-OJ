// =============================================================================
// 前端轻量体验埋点（匿名聚合采集 + 批量上报）
//
// 采集三类数据（匿名聚合，无用户关联）：
//   1. route       — 路由切换（每次切换 +1）
//   2. route_slow  — 慢切换 >500ms（独立类型便于定位慢页面）
//   3. api_fail    — 接口失败（key=请求路径）
//   4. js_error    — 页面 JS 错误 / 未处理 Promise 拒绝（key=消息前 200 字）
//
// 上报：内存聚合 + localStorage 备份，30s 定时 / pagehide / visibilitychange
// 触发 flush，navigator.sendBeacon 批量 POST /api/metrics/batch。
// 查看：DevTools Console 执行 window.__ojMetrics()
// =============================================================================

const STORAGE_KEY = 'oj-metrics-v1'
const FLUSH_INTERVAL = 30 * 1000 // 30s 定时上报
const BATCH_MAX = 100 // 单批最大条数（与后端一致）
const EVENT_MAX = 200 // 内存事件上限，超出先强刷
const SLOW_THRESHOLD = 500 // 路由慢切换阈值 ms

/** 内存桶：{ type: { key: count } } */
const bucket = {}

function persist() {
  try {
    const snapshot = JSON.stringify(bucket)
    if (snapshot.length > 100 * 1024) return // 容量防御：满 100KB 不落盘
    localStorage.setItem(STORAGE_KEY, snapshot)
  } catch {
    /* localStorage 不可用（隐私模式等）时静默降级为仅内存 */
  }
}

function restore() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    for (const [type, keys] of Object.entries(saved)) {
      if (!bucket[type]) bucket[type] = {}
      for (const [key, count] of Object.entries(keys)) {
        bucket[type][key] = (bucket[type][key] || 0) + count
      }
    }
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    /* 数据损坏忽略 */
  }
}

/** 聚合入桶（同 type+key 合并计数） */
function push(type, key, count = 1) {
  if (!type || !key) return
  if (!bucket[type]) bucket[type] = {}
  bucket[type][key] = (bucket[type][key] || 0) + count
  persist()
  // 超出上限先强刷，避免内存无限增长
  if (totalEvents() >= EVENT_MAX) flush()
}

function totalEvents() {
  let n = 0
  for (const keys of Object.values(bucket)) n += Object.keys(keys).length
  return n
}

/** 上报并清空（sendBeacon 页面卸载时仍可靠送达） */
export function flush() {
  const events = []
  for (const [type, keys] of Object.entries(bucket)) {
    for (const [key, count] of Object.entries(keys)) {
      events.push({ type, key, count })
    }
  }
  if (events.length === 0) return

  const payload = JSON.stringify({ events: events.slice(0, BATCH_MAX) })
  try {
    const ok = navigator.sendBeacon('/api/metrics/batch', new Blob([payload], { type: 'application/json' }))
    if (ok) {
      // 上报成功才清桶；失败保留待下轮重试
      for (const type of Object.keys(bucket)) delete bucket[type]
      try { localStorage.removeItem(STORAGE_KEY) } catch { /* 忽略 */ }
    }
  } catch {
    /* sendBeacon 不可用时丢弃（埋点低价值，不阻塞业务） */
  }
}

/** 路由切换计时（router.beforeEach 调用，返回 afterEach 用的结束函数） */
export function beginRouteTrack() {
  return performance.now()
}

/** 路由切换完成（router.afterEach 调用） */
export function trackRoute(to, t0) {
  const key = (to && (to.name || to.path)) || 'unknown'
  const duration = performance.now() - t0
  push('route', String(key), 1)
  if (duration > SLOW_THRESHOLD) push('route_slow', String(key), 1)
}

/** 接口失败（request.js 错误分支调用；url 去掉查询串） */
export function trackApiFail(url) {
  if (!url) return
  const key = String(url).split('?')[0]
  push('api_fail', key.length > 120 ? key.slice(0, 120) : key, 1)
}

/** 页面错误（main.js 注册 error/unhandledrejection 调用） */
export function trackError(err) {
  const msg = String(err?.message || err || 'unknown').slice(0, 200)
  push('js_error', msg || 'unknown')
}

/** DevTools 查看：window.__ojMetrics() */
export function dumpMetrics() {
  const rows = []
  for (const [type, keys] of Object.entries(bucket)) {
    for (const [key, count] of Object.entries(keys)) {
      rows.push({ type, key, count })
    }
  }
  console.table(rows.sort((a, b) => b.count - a.count))
  console.log('未上报条数：', rows.length, '｜上报接口：POST /api/metrics/batch（30s 定时）')
}

// 启动：恢复未上报数据 + 定时上报 + 卸载兜底
restore()
setInterval(flush, FLUSH_INTERVAL)
window.addEventListener('pagehide', flush)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') flush()
})
window.__ojMetrics = dumpMetrics
