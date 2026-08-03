import assert from 'node:assert/strict'
import test from 'node:test'

import {
  classifyProbe,
  configuredOpenAiCompatibleProviders,
  parseEnv,
} from './probe-model-tool-capabilities.mjs'

function response({ calls = [], outcome = 'OK', status = 200 } = {}) {
  return {
    outcome,
    httpStatus: status,
    latencyMs: 10,
    body: outcome === 'OK'
      ? { choices: [{ finish_reason: calls.length ? 'tool_calls' : 'stop', message: { tool_calls: calls } }] }
      : null,
  }
}

const validCall = {
  type: 'function',
  function: { name: 'lookup_rule_code', arguments: '{"code":"alpha"}' },
}

test('loads only explicitly enabled and complete provider identities without exposing credentials', () => {
  const environment = parseEnv(`
DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=secret
DEEPSEEK_BASE_URL=https://api.example.test
DEEPSEEK_MODEL=model-a
QWEN_ENABLED=false
QWEN_API_KEY=other-secret
QWEN_MODEL=model-b
`)
  const providers = configuredOpenAiCompatibleProviders(environment)
  assert.equal(providers.length, 1)
  assert.equal(providers[0].provider, 'deepseek')
  assert.equal(providers[0].model, 'model-a')
})

test('classifies valid required-tool and no-tool behavior as supported', () => {
  const result = classifyProbe(response({ calls: [validCall] }), response())
  assert.equal(result.status, 'SUPPORTED')
  assert.equal(result.reason, 'REQUIRED_AND_NO_TOOL_BEHAVIOR_VALID')
  assert.equal(result.requiredTool.validArguments, true)
  assert.equal(result.noTool.toolCallCount, 0)
})

test('classifies direct answering when a tool is required as degraded', () => {
  const result = classifyProbe(response(), response())
  assert.equal(result.status, 'DEGRADED')
  assert.equal(result.reason, 'REQUIRED_TOOL_NOT_SELECTED')
})

test('separates protocol rejection from transient or credential failures', () => {
  assert.equal(classifyProbe(
    response({ outcome: 'TOOL_PROTOCOL_REJECTED', status: 400 }),
    response({ outcome: 'TOOL_PROTOCOL_REJECTED', status: 400 }),
  ).status, 'UNSUPPORTED')
  assert.equal(classifyProbe(
    response({ outcome: 'RATE_LIMIT', status: 429 }),
    response(),
  ).status, 'UNPROBED')
})

test('does not retain provider response prose in the capability result', () => {
  const required = response({ calls: [validCall] })
  required.body.choices[0].message.content = 'raw provider output'
  const serialized = JSON.stringify(classifyProbe(required, response()))
  assert.doesNotMatch(serialized, /raw provider output/)
})
