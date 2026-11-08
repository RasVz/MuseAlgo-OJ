/** Error data shared by Axios, downloads and fetch/SSE handshakes. */
export class ApiError extends Error {
  constructor(message, { status = 0, code = 0, errorCode = 'REQUEST_FAILED', details, traceId, response, config } = {}) {
    super(message)
    this.name = 'ApiError'
    Object.assign(this, { status, code, errorCode, details, traceId, response, config })
  }
}

export function getErrorCode(error) {
  const body = error?.response?.data
  return error?.errorCode || body?.errorCode || body?.data?.errorCode ||
    (/^[A-Z][A-Z0-9_]+$/.test(body?.message || '') ? body.message : undefined)
}

export function responseError(response, config = response?.config) {
  const body = response?.data
  const structured = body && typeof body === 'object' && !Array.isArray(body)
  return new ApiError(structured && typeof body.message === 'string' ? body.message : '请求失败，请稍后重试', {
    status: response?.status || 0,
    code: structured ? body.code : response?.status,
    errorCode: structured ? body.errorCode || body.data?.errorCode || getErrorCode({ response }) : undefined,
    details: structured ? body.details : undefined,
    traceId: (structured && body.traceId) || response?.headers?.['x-trace-id'],
    response, config
  })
}

export async function decodeResponse(response) {
  if (response?.config?.responseType !== 'blob') return response.data
  const type = response.headers?.['content-type'] || response.data?.type || ''
  if (!type.includes('json')) return response.data
  try {
    return JSON.parse(await response.data.text())
  } catch {
    throw new ApiError('下载响应格式错误', { errorCode: 'INVALID_RESPONSE', response, config: response.config })
  }
}
