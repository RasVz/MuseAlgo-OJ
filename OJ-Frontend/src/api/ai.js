import request from '@/utils/request'
import { readAiResponse } from '@/utils/aiStream'

export async function getConversation(problemId, signal) {
  const result = await request({ url: '/ai/conversation', params: { problemId }, signal })
  return result.data || []
}
export function clearConversation(problemId) {
  return request({ url: '/ai/conversation', method: 'delete', params: { problemId } })
}
export async function streamTeaching(path, payload, token, signal, onChunk, onMetadata) {
  const response = await fetch(`/api/ai/${path}`, {
    method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(payload)
  })
  return readAiResponse(response, onChunk, onMetadata)
}

export function getMyAiQuota() { return request({ url: "/ai/quota" }) }
