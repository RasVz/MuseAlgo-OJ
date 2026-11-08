import { ApiError, responseError } from './apiError.js'
import { readEventStream } from './sse.js'

/** A completed AI answer requires an explicit done event; EOF alone is not success. */
export async function readAiResponse(response, onChunk) {
  const type = response.headers.get('content-type') || ''
  if (type.includes('json')) {
    let data
    try { data = await response.json() } catch {
      throw new ApiError('AI 响应格式错误，请稍后重试', { errorCode: 'INVALID_STREAM_RESPONSE' })
    }
    throw responseError({ status: response.status, data, headers: { 'x-trace-id': response.headers.get('x-trace-id') } })
  }
  if (!response.ok || !type.includes('text/event-stream') || !response.body) {
    throw new ApiError('AI 服务暂时不可用，请稍后重试', { status: response.status, errorCode: 'INVALID_STREAM_RESPONSE' })
  }
  let completed = false
  let text = ''
  try {
    await readEventStream(response.body, {
      isStopped: () => completed,
      onEvent(name, data) {
        if (completed) return
        if (name === 'app-error') {
          let payload
          try { payload = JSON.parse(data) } catch {
            throw new ApiError('AI 回复中断，请重试', { errorCode: 'INVALID_STREAM_RESPONSE' })
          }
          throw responseError({ status: response.status, data: payload })
        }
        if (name === 'done') { completed = true; return }
        if (name !== 'message') return
        text += data
        onChunk(text)
      }
    })
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('AI 回复中断，请重试', { errorCode: 'STREAM_INTERRUPTED' })
  }
  if (!completed) throw new ApiError('AI 回复中断，请重试', { errorCode: 'STREAM_INTERRUPTED' })
  return text
}
