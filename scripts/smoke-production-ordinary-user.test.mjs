import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'

test('replays the ordinary-user upload journey and cleans up the synthetic document', async () => {
  const calls = []
  let planStarted = false
  let deleted = false
  const server = createServer(async (request, response) => {
    const body = await readBody(request)
    calls.push({ method: request.method, url: request.url, body })
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Set-Cookie', 'RULEPILOT_TEST=authenticated; Path=/')

    if (request.method === 'GET' && request.url === '/api/auth/csrf') {
      return json(response, 200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' })
    }
    if (request.method === 'POST' && request.url === '/api/auth/login') {
      assert.match(body.toString(), /username=player/)
      assert.match(body.toString(), /password=smoke-password/)
      return json(response, 200, {})
    }
    if (request.method === 'GET' && request.url === '/api/auth/session') {
      return json(response, 200, { username: 'player', roles: ['USER'] })
    }
    if (request.method === 'POST' && request.url === '/api/v1/documents') {
      const multipart = body.toString('latin1')
      assert.match(multipart, /Lantern Relay rulebook EN v4 12pages/)
      assert.match(multipart, /BASE_RULEBOOK/)
      return json(response, 201, {
        document: { id: '11111111-1111-1111-1111-111111111111' },
        version: { id: '22222222-2222-2222-2222-222222222222', status: 'UPLOADED' },
        duplicate: false,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/documents') {
      return json(response, 200, [{
        document: {
          id: '11111111-1111-1111-1111-111111111111',
          title: planStarted ? 'Lantern Relay' : 'Lantern Relay rulebook EN v4 12pages',
        },
        latestVersion: { id: '22222222-2222-2222-2222-222222222222', status: 'READY' },
      }])
    }
    if (request.method === 'POST'
      && /^\/api\/v1\/document-versions\/[^/]+\/teaching-plans$/.test(request.url ?? '')) {
      planStarted = true
      assert.deepEqual(JSON.parse(body.toString()), {
        playerCount: 2, beginnerCount: 1, durationMinutes: 20,
      })
      return json(response, 202, {
        assistantRunId: '33333333-3333-3333-3333-333333333333', state: 'RECEIVED', reused: false,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/assistant-runs/33333333-3333-3333-3333-333333333333') {
      return json(response, 200, { run: { state: 'COMPLETED' } })
    }
    if (request.method === 'GET' && request.url?.endsWith('/teaching-plans/latest')) {
      return json(response, 200, {
        id: '44444444-4444-4444-4444-444444444444',
        gameTitle: 'Lantern Relay',
        sections: [{ position: 1 }],
      })
    }
    if (request.method === 'POST' && request.url?.endsWith('/illustrated-lessons')) {
      return json(response, 202, {
        assistantRunId: '55555555-5555-5555-5555-555555555555', state: 'RECEIVED', reused: false,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/assistant-runs/55555555-5555-5555-5555-555555555555') {
      return json(response, 200, { run: { state: 'COMPLETED' } })
    }
    if (request.method === 'GET' && request.url?.endsWith('/illustrated-lessons/latest')) {
      return json(response, 200, { status: 'COMPLETE', sections: [{ position: 1 }] })
    }
    if (request.method === 'POST' && request.url?.endsWith('/cancellation')) {
      return json(response, 202, {})
    }
    if (request.method === 'DELETE' && request.url === '/api/v1/documents/11111111-1111-1111-1111-111111111111') {
      deleted = true
      response.statusCode = 204
      return response.end()
    }
    return json(response, 404, { error: 'unexpected request' })
  })

  await new Promise((resolvePromise) => server.listen(0, '127.0.0.1', resolvePromise))
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-production-smoke-'))
  const pdf = join(directory, 'Lantern_Relay_rulebook_EN_v4_12pages.pdf')
  await writeFile(pdf, '%PDF-1.4\n%%EOF\n')

  try {
    const address = server.address()
    assert.equal(typeof address, 'object')
    const result = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(result.code, 0, result.stderr)
    assert.deepEqual(JSON.parse(result.stdout), {
      title: 'Lantern Relay',
      preparationState: 'COMPLETED',
      lessonState: 'COMPLETED',
      lessonStatus: 'COMPLETE',
      sectionCount: 1,
      cleanup: 'scheduled',
    })
    assert.equal(deleted, true)
    assert.ok(calls.some((call) => call.method === 'POST' && call.url === '/api/v1/documents'))
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
    await rm(directory, { recursive: true, force: true })
  }
})

test('production workflows never execute an operator-supplied Git ref with production credentials', async () => {
  const deployment = await readFile(resolve('.github/workflows/deploy-production.yml'), 'utf8')
  const smoke = await readFile(resolve('.github/workflows/production-ordinary-user-smoke.yml'), 'utf8')

  assert.doesNotMatch(deployment, /inputs\.ref/)
  assert.match(deployment, /workflow_run\.head_sha \|\| 'main'/)
  assert.doesNotMatch(smoke, /inputs\.ref/)
  assert.match(smoke, /ref: main/)
  assert.match(smoke, /Production is not running the checked-out main commit/)
})

function json(response, status, value) {
  response.statusCode = status
  response.end(JSON.stringify(value))
}

async function readBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks)
}

function spawnResult(command, args, env) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { env })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += chunk })
    child.stderr.on('data', (chunk) => { stderr += chunk })
    child.on('error', reject)
    child.on('close', (code) => resolvePromise({ code, stdout, stderr }))
  })
}
