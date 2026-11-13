import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveAiEndpoint } from '../../src/utils/aiEndpoint.js'

test('base URL and complete URL resolve without duplicate paths', () => {
  for (const input of ['https://api.deepseek.com', 'https://api.deepseek.com/', 'https://api.deepseek.com:443/']) {
    assert.equal(resolveAiEndpoint(input), 'https://api.deepseek.com/chat/completions')
  }
  assert.equal(resolveAiEndpoint('https://api.example.com/v1/'), 'https://api.example.com/v1/chat/completions')
  assert.equal(resolveAiEndpoint('https://api.example.com/openai/v1'), 'https://api.example.com/openai/v1/chat/completions')
  assert.equal(resolveAiEndpoint('https://api.example.com/v1/chat/completions'), 'https://api.example.com/v1/chat/completions')
  assert.equal(resolveAiEndpoint('https://api.example.com/v1/chat/completions/'), 'https://api.example.com/v1/chat/completions/')
})

test('explicit modes support custom bases and preserve custom full endpoints', () => {
  assert.equal(resolveAiEndpoint('https://api.example.com/custom'), 'https://api.example.com/custom')
  assert.equal(resolveAiEndpoint('https://api.example.com/custom', 'BASE_URL'), 'https://api.example.com/custom/chat/completions')
  assert.equal(resolveAiEndpoint('https://api.example.com', 'FULL_URL'), 'https://api.example.com/')
  assert.equal(resolveAiEndpoint('https://api.example.com/v1/chat/completions', 'BASE_URL'), 'https://api.example.com/v1/chat/completions')
})

test('invalid preview never displays embedded credentials', () => {
  for (const input of ['invalid', 'https://user:secret@api.example.com', 'https://api.example.com?key=secret',
    'https://api.example.com/#secret', 'file:///tmp/model']) assert.equal(resolveAiEndpoint(input), '')
  assert.equal(resolveAiEndpoint('https://api.example.com', 'OTHER'), '')
})
