import { test } from 'node:test'
import assert from 'node:assert/strict'
import { aiServiceErrorMessage } from '../../src/utils/aiServiceErrors.js'

test('AI service errors distinguish deployment, DNS, policy and input even with HTTP 200', () => {
  assert.match(aiServiceErrorMessage({ status: 200, errorCode: 'AI_MASTER_KEY_NOT_READY' }), /部署初始化/)
  assert.match(aiServiceErrorMessage({ status: 200, errorCode: 'AI_DNS_UNAVAILABLE' }), /环境未就绪/)
  assert.match(aiServiceErrorMessage({ errorCode: 'AI_ENDPOINT_FORBIDDEN' }), /地址安全检查/)
  assert.equal(aiServiceErrorMessage({ errorCode: 'AI_SERVICE_INPUT_INVALID', message: '模型名称不能为空' }), '模型名称不能为空')
  assert.equal(aiServiceErrorMessage(null), '操作失败，请稍后重试')
})
