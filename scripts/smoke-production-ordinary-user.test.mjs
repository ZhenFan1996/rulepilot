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
  let includeBlockingVisualCatalog = false
  let slowFirstLessonSection = false
  let regressLessonStatus = false
  let insufficientLesson = false
  let lessonRunReads = 0
  let lessonReads = 0
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
      return json(response, 200, {
        run: {
          id: '33333333-3333-3333-3333-333333333333', state: 'COMPLETED',
          createdAt: '2026-08-02T00:00:00Z', completedAt: '2026-08-02T00:00:12Z',
        },
        steps: [{ sequence: 1, fromState: 'RECEIVED', toState: 'LESSON_PLANNING', occurredAt: '2026-08-02T00:00:01Z' }],
        activities: [
          {
            sequence: 1, type: 'MODEL', operation: 'organizeTeachingOutline', outcome: 'SUCCEEDED',
            latencyMs: 11_000, estimatedInputTokens: 1200, estimatedOutputTokens: 300,
            occurredAt: '2026-08-02T00:00:11Z',
          },
          {
            sequence: 2, type: 'VALIDATION', operation: 'deferSelectedVisualPageCatalog', outcome: 'SUCCEEDED',
            latencyMs: 0, estimatedInputTokens: 0, estimatedOutputTokens: 0,
            occurredAt: '2026-08-02T00:00:12Z',
          },
          ...(includeBlockingVisualCatalog ? [{
            sequence: 3, type: 'MODEL', operation: 'inspectRulebookVisualBatch|1', outcome: 'SUCCEEDED',
            latencyMs: 19_000, estimatedInputTokens: 800, estimatedOutputTokens: 250,
            occurredAt: '2026-08-02T00:00:12Z',
          }] : []),
        ],
        budget: { usedModelCalls: 1, usedToolCalls: 0, usedTokens: 1500 },
      })
    }
    if (request.method === 'GET' && request.url?.endsWith('/teaching-plans/latest')) {
      return json(response, 200, {
        id: '44444444-4444-4444-4444-444444444444',
        gameTitle: 'Lantern Relay',
        sections: [{ position: 1 }],
      })
    }
    if (request.method === 'POST' && request.url?.endsWith('/illustrated-lessons')) {
      lessonRunReads = 0
      lessonReads = 0
      return json(response, 202, {
        assistantRunId: '55555555-5555-5555-5555-555555555555', state: 'RECEIVED', reused: false,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/assistant-runs/55555555-5555-5555-5555-555555555555') {
      lessonRunReads += 1
      const state = lessonRunReads === 1
        ? 'RECEIVED'
        : lessonRunReads === 2
          ? 'RETRIEVING'
          : insufficientLesson ? 'INSUFFICIENT_EVIDENCE' : 'COMPLETED'
      return json(response, 200, {
        run: {
          id: '55555555-5555-5555-5555-555555555555',
          state,
          createdAt: '2026-08-02T00:00:13Z',
          completedAt: ['COMPLETED', 'INSUFFICIENT_EVIDENCE'].includes(state)
            ? slowFirstLessonSection ? '2026-08-02T00:00:36Z' : '2026-08-02T00:00:20Z'
            : null,
        },
        steps: [],
        activities: [
          {
            sequence: 1, type: 'MODEL', operation: 'composeLessonSection', outcome: 'SUCCEEDED',
            latencyMs: 6500, estimatedInputTokens: 900, estimatedOutputTokens: 250,
            occurredAt: '2026-08-02T00:00:19Z',
          },
          {
            sequence: 2, type: 'VALIDATION', operation: 'publishTeachingSection|1', outcome: 'SUCCEEDED',
            latencyMs: 0, estimatedInputTokens: 0, estimatedOutputTokens: 0,
            occurredAt: slowFirstLessonSection ? '2026-08-02T00:00:35Z' : '2026-08-02T00:00:20Z',
          },
        ],
        budget: { usedModelCalls: 1, usedToolCalls: 0, usedTokens: 1150 },
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/assistant-runs/active?mode=TEACHING') {
      return json(response, 200, [{ id: '55555555-5555-5555-5555-555555555555', subjectId: '44444444-4444-4444-4444-444444444444' }])
    }
    if (request.method === 'GET' && request.url?.endsWith('/illustrated-lessons/latest')) {
      lessonReads += 1
      return json(response, 200, {
        status: regressLessonStatus
          ? lessonReads === 1 ? 'COMPLETE' : 'DRAFT_READY'
          : lessonReads === 1 ? 'DRAFT_READY' : 'COMPLETE',
        sections: [{ position: 1 }],
      })
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
    assert.match(result.stderr, /SMOKE_STAGE login-completed/)
    assert.match(result.stderr, /SMOKE_STAGE title-verified/)
    assert.match(result.stderr, /SMOKE_STAGE lesson-verified/)
    assert.match(result.stderr, /SMOKE_STAGE lesson-launch-visible run=55555555-5555-5555-5555-555555555555 state=RECEIVED/)
    assert.match(result.stderr, /SMOKE_STAGE cleanup-completed/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=organizeTeachingOutline .*latencyMs=11000/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=deferSelectedVisualPageCatalog .*outcome=SUCCEEDED/)
    assert.doesNotMatch(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=inspectRulebookVisualBatch/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=budget usedModelCalls=1 usedToolCalls=0 usedTokens=1500/)
    assert.match(result.stderr, /SMOKE_TIMING phase=lesson kind=activity .*operation=composeLessonSection .*latencyMs=6500/)
    assert.match(result.stderr, /SMOKE_PERFORMANCE phase=lesson firstSectionSeconds=7 totalSeconds=7 usedModelCalls=1 modelCallLimit=5 correctionCalls=0/)

    includeBlockingVisualCatalog = true
    deleted = false
    planStarted = false
    const visualWarning = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(visualWarning.code, 0, visualWarning.stderr)
    assert.match(visualWarning.stderr, /SMOKE_WARNING Text-rulebook preparation performed visual catalog work before publishing the plan/)
    assert.equal(deleted, true)

    includeBlockingVisualCatalog = false
    slowFirstLessonSection = true
    deleted = false
    planStarted = false
    const slowLesson = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(slowLesson.code, 0, slowLesson.stderr)
    assert.match(slowLesson.stderr, /SMOKE_WARNING First cited lesson section exceeded the 15-second target/)
    assert.equal(deleted, true)

    regressLessonStatus = true
    slowFirstLessonSection = false
    deleted = false
    planStarted = false
    const regressedLesson = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.notEqual(regressedLesson.code, 0)
    assert.match(regressedLesson.stderr, /Lesson status regressed from rank 3 to DRAFT_READY/)
    assert.equal(deleted, true)

    regressLessonStatus = false
    insufficientLesson = true
    deleted = false
    planStarted = false
    const insufficient = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.notEqual(insufficient.code, 0)
    assert.match(insufficient.stderr, /SMOKE_TIMING phase=Illustrated-lesson-failure kind=activity/)
    assert.match(insufficient.stderr, /Illustrated lesson ended in INSUFFICIENT_EVIDENCE/)
    assert.equal(deleted, true)
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
    await rm(directory, { recursive: true, force: true })
  }
})

test('production workflows never execute an operator-supplied Git ref with production credentials', async () => {
  const deployment = await readFile(resolve('.github/workflows/deploy-production.yml'), 'utf8')
  const smoke = await readFile(resolve('.github/workflows/production-ordinary-user-smoke.yml'), 'utf8')
  const candidates = await readFile(resolve('.github/workflows/public-lesson-candidate.yml'), 'utf8')

  assert.doesNotMatch(deployment, /inputs\.ref/)
  assert.match(deployment, /workflow_run\.head_sha \|\| 'main'/)
  assert.doesNotMatch(smoke, /inputs\.ref/)
  assert.match(smoke, /ref: main/)
  assert.match(smoke, /Production is not running the checked-out main commit/)
  assert.doesNotMatch(candidates, /inputs\.ref/)
  assert.match(candidates, /ref: main/)
  assert.match(candidates, /Production is not running the checked-out main commit/)
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
