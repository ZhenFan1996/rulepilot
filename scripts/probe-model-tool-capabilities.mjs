#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const TOOL = {
  type: 'function',
  function: {
    name: 'lookup_rule_code',
    description: 'Return a synthetic protocol marker for a supplied code.',
    parameters: {
      type: 'object',
      properties: { code: { type: 'string', enum: ['alpha'] } },
      required: ['code'],
      additionalProperties: false,
    },
  },
}

export function parseEnv(text) {
  const values = {}
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#') || !line.includes('=')) continue
    const separator = line.indexOf('=')
    const key = line.slice(0, separator).trim()
    let value = line.slice(separator + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    values[key] = value
  }
  return values
}

export function configuredOpenAiCompatibleProviders(environment) {
  const definitions = [
    ['openai', 'OPENAI', 'https://api.openai.com/v1'],
    ['deepseek', 'DEEPSEEK', 'https://api.deepseek.com'],
    ['qwen', 'QWEN', 'https://dashscope.aliyuncs.com/compatible-mode/v1'],
    ['compatible', 'COMPATIBLE_MODEL', 'http://localhost:11434/v1'],
  ]
  return definitions.flatMap(([provider, prefix, defaultBaseUrl]) => {
    if (environment[`${prefix}_ENABLED`]?.toLowerCase() !== 'true') return []
    const apiKey = environment[`${prefix}_API_KEY`]?.trim()
    const model = (environment[`${prefix}_MODEL`] ?? environment[`${prefix}_NAME`])?.trim()
    const baseUrl = (environment[`${prefix}_BASE_URL`] ?? defaultBaseUrl)?.trim()
    if (!apiKey || !model || !baseUrl) return [{ provider, model: model ?? null, unprobedReason: 'INCOMPLETE_CONFIGURATION' }]
    return [{ provider, model, baseUrl, apiKey }]
  })
}

function toolCalls(response) {
  const calls = response?.body?.choices?.[0]?.message?.tool_calls
  return Array.isArray(calls) ? calls : []
}

function validRequiredToolCall(response) {
  const calls = toolCalls(response)
  if (calls.length !== 1 || calls[0]?.function?.name !== 'lookup_rule_code') return false
  try {
    const arguments_ = JSON.parse(calls[0].function.arguments)
    return arguments_?.code === 'alpha' && Object.keys(arguments_).length === 1
  } catch {
    return false
  }
}

function requestSummary(response) {
  return {
    outcome: response.outcome,
    httpStatus: response.httpStatus ?? null,
    latencyMs: response.latencyMs,
    finishReason: response?.body?.choices?.[0]?.finish_reason ?? null,
    toolCallCount: toolCalls(response).length,
    promptTokens: response?.body?.usage?.prompt_tokens ?? null,
    completionTokens: response?.body?.usage?.completion_tokens ?? null,
  }
}

export function classifyProbe(requiredTool, noTool) {
  const requiredSummary = requestSummary(requiredTool)
  const noToolSummary = requestSummary(noTool)
  const failures = [requiredTool, noTool].filter((response) => response.outcome !== 'OK')
  if (failures.length) {
    const categories = [...new Set(failures.map((failure) => failure.outcome))]
    const unsupported = categories.every((category) => category === 'TOOL_PROTOCOL_REJECTED')
    return {
      status: unsupported ? 'UNSUPPORTED' : 'UNPROBED',
      reason: categories.join('+'),
      requiredTool: requiredSummary,
      noTool: noToolSummary,
    }
  }

  const validRequired = validRequiredToolCall(requiredTool)
  const respectedNoTool = toolCalls(noTool).length === 0
  const supported = validRequired && respectedNoTool
  return {
    status: supported ? 'SUPPORTED' : 'DEGRADED',
    reason: supported
      ? 'REQUIRED_AND_NO_TOOL_BEHAVIOR_VALID'
      : validRequired
      ? 'UNNECESSARY_TOOL_CALL'
      : toolCalls(requiredTool).length === 0
        ? 'REQUIRED_TOOL_NOT_SELECTED'
        : 'INVALID_TOOL_CALL',
    requiredTool: { ...requiredSummary, validArguments: validRequired },
    noTool: noToolSummary,
  }
}

function endpoint(baseUrl) {
  return `${baseUrl.replace(/\/+$/, '')}/chat/completions`
}

function categorizeHttpFailure(status, body) {
  if (status === 401 || status === 403) return 'AUTHENTICATION'
  if (status === 429) return 'RATE_LIMIT'
  const message = JSON.stringify(body ?? '').toLowerCase()
  if (status === 400 && /(tool|function)/.test(message) && /(unsupported|not support|unknown|invalid)/.test(message)) {
    return 'TOOL_PROTOCOL_REJECTED'
  }
  return status >= 500 ? 'PROVIDER_ERROR' : 'REQUEST_REJECTED'
}

async function request(config, messages, timeoutMs) {
  const started = performance.now()
  try {
    const response = await fetch(endpoint(config.baseUrl), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: config.model,
        messages,
        tools: [TOOL],
        tool_choice: 'auto',
        temperature: 0,
        max_tokens: 128,
      }),
      signal: AbortSignal.timeout(timeoutMs),
    })
    let body
    try {
      body = await response.json()
    } catch {
      body = null
    }
    return {
      outcome: response.ok ? 'OK' : categorizeHttpFailure(response.status, body),
      httpStatus: response.status,
      latencyMs: Math.round(performance.now() - started),
      body,
    }
  } catch (error) {
    return {
      outcome: error?.name === 'TimeoutError' ? 'TIMEOUT' : 'NETWORK_ERROR',
      latencyMs: Math.round(performance.now() - started),
      body: null,
    }
  }
}

export async function probeProvider(config, timeoutMs = 45_000) {
  if (config.unprobedReason) {
    return { provider: config.provider, model: config.model, status: 'UNPROBED', reason: config.unprobedReason }
  }
  const requiredTool = await request(config, [
    { role: 'system', content: 'You are a protocol capability probe. Follow the request exactly and do not explain.' },
    { role: 'user', content: 'Call lookup_rule_code exactly once with code alpha. Do not answer with text.' },
  ], timeoutMs)
  const noTool = await request(config, [
    { role: 'system', content: 'You are a protocol capability probe. Follow the request exactly.' },
    { role: 'user', content: 'Reply with READY. Do not call any tool.' },
  ], timeoutMs)
  return { provider: config.provider, model: config.model, ...classifyProbe(requiredTool, noTool) }
}

function parseArguments(arguments_) {
  const options = {
    env: '.env',
    output: '.local/agent-evaluation/provider-capabilities.json',
    providers: null,
    timeoutMs: 45_000,
  }
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index]
    if (argument === '--env') options.env = arguments_[++index]
    else if (argument === '--output') options.output = arguments_[++index]
    else if (argument === '--providers') options.providers = new Set(arguments_[++index].split(',').map((value) => value.trim()))
    else if (argument === '--timeout-ms') options.timeoutMs = Number(arguments_[++index])
    else throw new Error(`unknown argument: ${argument}`)
  }
  if (!Number.isInteger(options.timeoutMs) || options.timeoutMs < 1_000 || options.timeoutMs > 120_000) {
    throw new Error('timeout must be between 1000 and 120000 milliseconds')
  }
  return options
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const options = parseArguments(process.argv.slice(2))
    const envPath = resolve(root, options.env)
    if (!existsSync(envPath)) throw new Error(`environment file is missing: ${options.env}`)
    let providers = configuredOpenAiCompatibleProviders(parseEnv(readFileSync(envPath, 'utf8')))
    if (options.providers) providers = providers.filter((provider) => options.providers.has(provider.provider))
    if (providers.length === 0) throw new Error('no enabled OpenAI-compatible provider is configured for probing')

    const results = []
    for (const provider of providers) results.push(await probeProvider(provider, options.timeoutMs))
    const output = resolve(root, options.output)
    mkdirSync(dirname(output), { recursive: true })
    writeFileSync(output, `${JSON.stringify({
      schemaVersion: 1,
      generatedAt: new Date().toISOString(),
      probeSchema: 'required-tool-and-no-tool-v1',
      results,
    }, null, 2)}\n`, { mode: 0o600 })
    for (const result of results) console.log(`${result.provider}/${result.model ?? 'unknown'}: ${result.status} (${result.reason})`)
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
