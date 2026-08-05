import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { resolve } from 'node:path'
import test from 'node:test'

const planId = '11111111-1111-1111-1111-111111111111'
const runId = '22222222-2222-2222-2222-222222222222'

test('resolves an exact public title, stages, and compares before a separate apply invocation', async () => {
  const calls = []
  let candidateState = 'COMPLETED'
  const comparison = {
    active: version('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'COMPLETE', 67),
    candidate: version('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'COMPLETE', 67),
    recommendation: 'KEEP_ACTIVE',
    reasons: ['安全、覆盖和引用指标持平。'],
  }
  const server = createServer(async (request, response) => {
    calls.push(`${request.method} ${request.url}`)
    await readBody(request)
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Set-Cookie', 'SESSION=candidate-test; Path=/')
    if (request.method === 'GET' && request.url === '/api/auth/csrf') {
      return json(response, 200, { headerName: 'X-CSRF-TOKEN', token: 'csrf' })
    }
    if (request.method === 'POST' && request.url === '/api/auth/login') return json(response, 204, null)
    if (request.method === 'GET' && request.url === '/api/auth/session') {
      return json(response, 200, { username: 'admin', roles: ['ADMIN'] })
    }
    if (request.method === 'GET' && request.url === '/api/public/lessons?limit=60') {
      return json(response, 200, [{ teachingPlanId: planId, rulebookTitle: 'Cascadia' }])
    }
    if (request.method === 'POST' && request.url === `/api/admin/public-lessons/${planId}/candidates`) {
      return json(response, 202, { assistantRunId: runId, state: 'RECEIVED', reused: false })
    }
    if (request.method === 'GET' && request.url === `/api/v1/assistant-runs/${runId}`) {
      return json(response, 200, {
        run: { id: runId, state: candidateState },
        activities: candidateState === 'COMPLETED' ? [] : [{
          operation: 'validateTeachingSection|7|2',
          outcome: 'REJECTED',
          summary: 'Required claims remained unsupported.',
        }],
      })
    }
    if (request.method === 'GET'
      && request.url === `/api/admin/public-lessons/${planId}/candidates/latest`) {
      return json(response, 200, comparison)
    }
    if (request.method === 'POST'
      && request.url === `/api/admin/public-lessons/${planId}/candidates/latest/apply-recommendation`) {
      return json(response, 200, {
        decision: 'KEEP_ACTIVE',
        winnerLessonId: comparison.active.lesson.id,
        candidateLessonId: comparison.candidate.lesson.id,
      })
    }
    return json(response, 404, { error: 'unexpected request' })
  })
  await new Promise((resolvePromise) => server.listen(0, '127.0.0.1', resolvePromise))

  try {
    const address = server.address()
    assert.equal(typeof address, 'object')
    const base = `http://127.0.0.1:${address.port}`
    const stage = await runScript(base, 'stage', 'title')
    assert.equal(stage.code, 0, stage.stderr)
    assert.deepEqual(
      pick(JSON.parse(stage.stdout), ['candidateRunState', 'recommendation']),
      { candidateRunState: 'COMPLETED', recommendation: 'KEEP_ACTIVE' },
    )
    assert.equal(calls.filter((call) => call.endsWith('/apply-recommendation')).length, 0)

    candidateState = 'INSUFFICIENT_EVIDENCE'
    comparison.candidate.lesson.status = 'INCOMPLETE'
    comparison.candidate.quality.score = 17
    const incomplete = await runScript(base, 'stage', 'plan')
    assert.equal(incomplete.code, 0, incomplete.stderr)
    assert.deepEqual(
      pick(JSON.parse(incomplete.stdout), ['candidateRunState', 'recommendation']),
      { candidateRunState: 'INSUFFICIENT_EVIDENCE', recommendation: 'KEEP_ACTIVE' },
    )
    assert.match(incomplete.stderr, /Required claims remained unsupported/)
    assert.equal(calls.filter((call) => call.endsWith('/apply-recommendation')).length, 0)

    const apply = await runScript(base, 'apply', 'plan')
    assert.equal(apply.code, 0, apply.stderr)
    assert.equal(JSON.parse(apply.stdout).decision, 'KEEP_ACTIVE')
    assert.equal(calls.filter((call) => call.endsWith('/apply-recommendation')).length, 1)
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
  }
})

function version(id, status, score) {
  return {
    lesson: {
      id,
      status,
      generatorVersion: 'adaptive-test',
      sections: [{ steps: [{ position: 1 }] }],
    },
    quality: { score, status: 'NEEDS_REVIEW', checks: [] },
  }
}

function pick(value, keys) {
  return Object.fromEntries(keys.map((key) => [key, value[key]]))
}

async function runScript(baseUrl, operation, selector) {
  return spawnResult('bash', [
    resolve('scripts/manage-public-lesson-candidate.sh'),
    '--base-url', baseUrl,
    ...(selector === 'title' ? ['--lesson-title', 'Cascadia'] : ['--plan-id', planId]),
    '--operation', operation,
    '--timeout-seconds', '5',
  ], { ...process.env, RULEPILOT_ADMIN_USERNAME: 'admin', RULEPILOT_ADMIN_PASSWORD: 'password' })
}

function json(response, status, value) {
  response.statusCode = status
  response.end(value === null ? '' : JSON.stringify(value))
}

async function readBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks)
}

function spawnResult(command, args, env) {
  return new Promise((resolvePromise) => {
    const child = spawn(command, args, { cwd: resolve('.'), env })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += chunk })
    child.stderr.on('data', (chunk) => { stderr += chunk })
    child.on('close', (code) => resolvePromise({ code, stdout, stderr }))
  })
}
