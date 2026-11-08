import request from '@/utils/request'

/**
 * 学生端 AI 助手接口。
 * 注意：流式问答（/ai/ask/stream）走原生 fetch（SSE），不经过 axios，见 AiAssistant.vue。
 */

/** 查询当前用户对某题已解锁的 Hint Ladder 级别（1~4，未解锁返回 1） */
export function getHintLevel(problemId) {
  return request({ url: '/ai/hint/level', method: 'get', params: { problemId } })
}
