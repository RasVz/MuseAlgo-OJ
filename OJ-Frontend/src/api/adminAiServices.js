import request from '@/utils/request'

// 三项探针各最多 30 秒调用 + 8 秒地址检查；包含调度余量。
const PROBE_TIMEOUT = 130000

export function getAiServices() {
  return request({ url: '/admin/ai/services', method: 'get', silent: true })
}

export function createAiService(data) {
  return request({ url: '/admin/ai/services', method: 'post', silent: true, data, timeout: 15000 })
}

export function updateAiService(id, data) {
  return request({ url: `/admin/ai/services/${id}`, method: 'put', silent: true, data, timeout: PROBE_TIMEOUT })
}

export function testAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/test`, method: 'post', silent: true, data: { version }, timeout: PROBE_TIMEOUT })
}

export function activateAiService(id, version, runtimeVersion) {
  return request({
    url: `/admin/ai/services/${id}/activate`,
    method: 'post', silent: true,
    data: { version, runtimeVersion }
  })
}

export function enableAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/enable`, method: 'post', silent: true, data: { version } })
}

export function disableAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/disable`, method: 'post', silent: true, data: { version } })
}

export function deleteAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}`, method: 'delete', silent: true, data: { version } })
}

export function getAiRuntime() {
  return request({ url: '/admin/ai/runtime', method: 'get', silent: true })
}

export function updateAiRuntime(mode, version) {
  return request({ url: '/admin/ai/runtime', method: 'put', silent: true, data: { mode, version } })
}

export function diagnoseAiAddress(apiUrl, addressType = 'AUTO') {
  return request({ url: '/admin/ai/services/diagnose', method: 'post', silent: true, data: { apiUrl, addressType }, timeout: 15000 })
}
