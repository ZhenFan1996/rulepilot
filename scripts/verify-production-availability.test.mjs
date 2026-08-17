import assert from 'node:assert/strict'
import test from 'node:test'

import { verifyProductionAvailability } from './verify-production-availability.mjs'

const jsonResponse = (body, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'content-type': 'application/json' },
})

const successfulFetch = async (url) => {
  const path = new URL(url).pathname + new URL(url).search
  if (path === '/') return new Response('<html><title>RulePilot</title></html>')
  if (path === '/api/auth/csrf') return jsonResponse({ token: 'token', headerName: 'X-CSRF-TOKEN' })
  if (path === '/api/v1/bgg/recommendations') return jsonResponse([{ bggId: 42, name: 'Harbor' }])
  if (path === '/api/v1/bgg/games/42?locale=zh-CN') {
    return jsonResponse({
      bggId: 42,
      description: 'A game',
      descriptionTranslated: true,
      categories: [],
      mechanics: ['Drafting'],
    })
  }
  return new Response('missing', { status: 404 })
}

test('verifies the deterministic public release surface without invoking an Agent', async () => {
  const calls = []
  const result = await verifyProductionAvailability({
    publicUrl: 'https://rulepilot.example/',
    fetchImpl: async (url) => {
      calls.push(url)
      return successfulFetch(url)
    },
    attempts: 1,
  })

  assert.deepEqual(result, { attempt: 1, bggId: 42, gameName: 'Harbor' })
  assert.deepEqual(calls, [
    'https://rulepilot.example/',
    'https://rulepilot.example/api/auth/csrf',
    'https://rulepilot.example/api/v1/bgg/recommendations',
    'https://rulepilot.example/api/v1/bgg/games/42?locale=zh-CN',
  ])
  assert.ok(calls.every((url) => !url.includes('recommendation-agent')))
})

test('rejects a detail response for a different BGG identity', async () => {
  await assert.rejects(
    verifyProductionAvailability({
      publicUrl: 'https://rulepilot.example',
      fetchImpl: async (url) => {
        const response = await successfulFetch(url)
        if (url.includes('/api/v1/bgg/games/42')) {
          return jsonResponse({
            bggId: 99,
            description: 'wrong game',
            descriptionTranslated: false,
            categories: [],
            mechanics: [],
          })
        }
        return response
      },
      attempts: 1,
    }),
    /BGG game detail returned an invalid HTTP 200 response/,
  )
})

test('retries a transient cutover failure and reports the successful attempt', async () => {
  let homepageCalls = 0
  const delays = []
  const result = await verifyProductionAvailability({
    publicUrl: 'https://rulepilot.example',
    fetchImpl: async (url) => {
      if (new URL(url).pathname === '/' && homepageCalls++ === 0) {
        return new Response('temporarily unavailable', { status: 503 })
      }
      return successfulFetch(url)
    },
    attempts: 3,
    retryDelayMillis: 17,
    sleep: async (milliseconds) => delays.push(milliseconds),
  })

  assert.equal(result.attempt, 2)
  assert.deepEqual(delays, [17])
})

test('treats the deployed page as availability, not an exact title-copy contract', async () => {
  const result = await verifyProductionAvailability({
    publicUrl: 'https://rulepilot.example',
    fetchImpl: async (url) => {
      if (new URL(url).pathname === '/') return new Response('<html><title>新的产品标题</title></html>')
      return successfulFetch(url)
    },
    attempts: 1,
  })
  assert.equal(result.bggId, 42)

  await assert.rejects(
    verifyProductionAvailability({
      publicUrl: 'https://rulepilot.example',
      fetchImpl: async (url) => new URL(url).pathname === '/'
        ? new Response('   ')
        : successfulFetch(url),
      attempts: 1,
    }),
    /empty or unsuccessful/,
  )
})
