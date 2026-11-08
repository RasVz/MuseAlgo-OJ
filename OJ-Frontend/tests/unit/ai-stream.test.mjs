import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readAiResponse } from '../../src/utils/aiStream.js'

function stream(text) {
  return new Response(new ReadableStream({ start(controller) {
    const bytes = new TextEncoder().encode(text)
    // Deliberately split UTF-8 and SSE frames at every byte boundary.
    for (const byte of bytes) controller.enqueue(new Uint8Array([byte]))
    controller.close()
  } }), { headers: { 'content-type': 'text/event-stream' } })
}

test('AI stream preserves whitespace and requires done across split UTF-8 frames', async () => {
  const chunks = []
  assert.equal(await readAiResponse(stream('data:   中文\r\ndata: 下一行\r\n\r\nevent: done\r\ndata: \r\n\r\n'), value => chunks.push(value)), '  中文\n下一行')
  assert.deepEqual(chunks, ['  中文\n下一行'])
})

test('HTTP 200 JSON handshake errors retain their business reason', async () => {
  const response = new Response(JSON.stringify({ code: 429, errorCode: 'RATE_LIMITED', message: '稍后再试', details: { retryAfterSeconds: 12 } }), { headers: { 'content-type': 'application/json' } })
  await assert.rejects(readAiResponse(response, () => assert.fail('JSON error is not an answer')), error => error.errorCode === 'RATE_LIMITED' && error.details.retryAfterSeconds === 12)
})

test('partial response followed by app-error or premature EOF never completes successfully', async () => {
  for (const tail of ['', 'event: app-error\ndata: {"code":503,"errorCode":"AI_UPSTREAM_FAILED","message":"生成中断"}\n\n']) {
    const chunks = []
    await assert.rejects(readAiResponse(stream('data: 半段回复\n\n' + tail), value => chunks.push(value)), error => ['STREAM_INTERRUPTED', 'AI_UPSTREAM_FAILED'].includes(error.errorCode))
    assert.deepEqual(chunks, ['半段回复'])
  }
})
