import request from '@/utils/request'

// 三项探针各最多 30 秒；默认请求的 10 秒上限会让页面提前失败而后端仍在测试。
const PROBE_TIMEOUT = 100000

export function getAiServices() {
  return request({ url: '/admin/ai/services', method: 'get' })
}

export function createAiService(data) {
  return request({ url: '/admin/ai/services', method: 'post', data })
}

export function updateAiService(id, data) {
  return request({ url: `/admin/ai/services/${id}`, method: 'put', data, timeout: PROBE_TIMEOUT })
}

export function testAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/test`, method: 'post', data: { version }, timeout: PROBE_TIMEOUT })
}

export function activateAiService(id, version, runtimeVersion) {
  return request({
    url: `/admin/ai/services/${id}/activate`,
    method: 'post',
    data: { version, runtimeVersion }
  })
}

export function enableAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/enable`, method: 'post', data: { version } })
}

export function disableAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}/disable`, method: 'post', data: { version } })
}

export function deleteAiService(id, version) {
  return request({ url: `/admin/ai/services/${id}`, method: 'delete', data: { version } })
}

export function getAiRuntime() {
  return request({ url: '/admin/ai/runtime', method: 'get' })
}

export function updateAiRuntime(mode, version) {
  return request({ url: '/admin/ai/runtime', method: 'put', data: { mode, version } })
}
