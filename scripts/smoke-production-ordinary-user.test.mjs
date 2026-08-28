import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'

test('writes a controlled public failure status even when input validation stops before the journey', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-production-smoke-status-'))
  const publicStatus = join(directory, 'public-status.json')
  try {
    const result = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'), '--unsupported-input'],
      { ...process.env, RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: publicStatus },
    )

    assert.equal(result.code, 2)
    assert.deepEqual(JSON.parse(await readFile(publicStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 2,
      lastCompletedStage: 'not-started',
      failureCode: 'INPUT_INVALID',
      failureCauseCode: null,
      cleanupOutcome: 'NOT_REQUIRED',
    })
    assert.doesNotMatch(await readFile(publicStatus, 'utf8'), /unsupported-input/)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('public status validator rejects contradictory or expanded workflow artifacts', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-production-status-contract-'))
  const publicStatus = join(directory, 'public-status.json')
  const validator = resolve('scripts/smoke-production-ordinary-user.sh')
  const validDoubleFailure = {
    outcome: 'FAILED',
    exitCode: 1,
    lastCompletedStage: 'answer-verified',
    failureCode: 'NAVIGATION_FAILED',
    failureCauseCode: null,
    cleanupOutcome: 'FAILED',
  }
  try {
    await writeFile(publicStatus, JSON.stringify(validDoubleFailure))
    assert.equal((await spawnResult('bash', [validator, '--validate-public-status', publicStatus, '1'])).code, 0)
    await writeFile(publicStatus, JSON.stringify({
      ...validDoubleFailure,
      failureCode: 'LESSON_GENERATION_FAILED',
      failureCauseCode: 'AGENT_TIMEOUT',
    }))
    assert.equal((await spawnResult('bash', [validator, '--validate-public-status', publicStatus, '1'])).code, 0)

    const counterexamples = [
      { ...validDoubleFailure, outcome: 'SUCCEEDED' },
      { ...validDoubleFailure, exitCode: 0, failureCode: null, cleanupOutcome: 'SUCCEEDED' },
      { ...validDoubleFailure, failureCode: null },
      { ...validDoubleFailure, failureCode: 'UNBOUNDED_FAILURE_CODE' },
      { ...validDoubleFailure, failureCauseCode: 'model timed out' },
      {
        outcome: 'SUCCEEDED', exitCode: 0, lastCompletedStage: 'journey-completed',
        failureCode: 'NAVIGATION_FAILED', failureCauseCode: null, cleanupOutcome: 'SUCCEEDED',
      },
      {
        outcome: 'FAILED', exitCode: 1, lastCompletedStage: 'journey-completed',
        failureCode: 'CLEANUP_FAILED', failureCauseCode: null, cleanupOutcome: 'SUCCEEDED',
      },
      { ...validDoubleFailure, rawModelOutput: 'must never become public' },
    ]
    for (const status of counterexamples) {
      await writeFile(publicStatus, JSON.stringify(status))
      const expectedExit = String(status.exitCode)
      const result = await spawnResult('bash', [validator, '--validate-public-status', publicStatus, expectedExit])
      assert.notEqual(result.code, 0, JSON.stringify(status))
    }

    await writeFile(publicStatus, JSON.stringify(validDoubleFailure))
    assert.notEqual(
      (await spawnResult('bash', [validator, '--validate-public-status', publicStatus, '0'])).code,
      0,
    )
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('replays the ordinary-user upload journey and cleans up the synthetic document', async () => {
  const calls = []
  let planStarted = false
  let deleted = false
  let slowFirstLessonSection = false
  let regressLessonStatus = false
  let insufficientLesson = false
  let lessonRunReads = 0
  let lessonReads = 0
  let synchronousVisualEnabled = false
  let preparationFailureCode = null
  let lessonFailureCode = null
  let answerHasCitations = true
  let answerReferencesAlign = true
  let navigationFails = false
  let deletionFails = false
  let completeLessonImmediately = false
  let expectedQuestion = 'How many victory points is each lit dock worth during final scoring?'
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
    if (request.method === 'GET' && ['/', '/teach', '/lessons', '/catalog', '/library', '/account'].includes(request.url)) {
      return json(response, 200, {})
    }
    if (request.method === 'GET' && request.url === '/api/v1/teaching-plans' && navigationFails) {
      return json(response, 503, { error: 'navigation unavailable' })
    }
    if (request.method === 'GET' && ['/api/v1/teaching-plans', '/api/public/lessons'].includes(request.url)) {
      return json(response, 200, [])
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
      assert.deepEqual(JSON.parse(body.toString()), {})
      return json(response, 202, {
        assistantRunId: '33333333-3333-3333-3333-333333333333', state: 'RECEIVED', reused: false,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/assistant-runs/33333333-3333-3333-3333-333333333333') {
      const preparationState = preparationFailureCode ? 'FAILED' : 'COMPLETED'
      return json(response, 200, {
        run: {
          id: '33333333-3333-3333-3333-333333333333', state: preparationState,
          createdAt: '2026-08-02T00:00:00Z', completedAt: '2026-08-02T00:00:12Z',
          lastErrorCode: preparationFailureCode,
        },
        steps: [{ sequence: 1, fromState: 'RECEIVED', toState: 'LESSON_PLANNING', occurredAt: '2026-08-02T00:00:01Z' }],
        activities: [{
            sequence: 1, type: 'MODEL', operation: 'organizeTeachingOutline', outcome: 'SUCCEEDED',
            latencyMs: 11_000, estimatedInputTokens: 1200, estimatedOutputTokens: 300,
            occurredAt: '2026-08-02T00:00:11Z',
          },
          {
            sequence: 2, type: 'VALIDATION', operation: 'deferSelectedVisualPageCatalog', outcome: 'SUCCEEDED',
            latencyMs: 0, estimatedInputTokens: 0, estimatedOutputTokens: 0,
            occurredAt: '2026-08-02T00:00:12Z',
          }],
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
      const state = lessonFailureCode
        ? lessonRunReads === 1 ? 'RECEIVED' : 'FAILED'
        : completeLessonImmediately ? 'COMPLETED' : lessonRunReads === 1
        ? 'RECEIVED'
        : lessonRunReads === 2
          ? 'RETRIEVING'
          : insufficientLesson ? 'INSUFFICIENT_EVIDENCE' : 'COMPLETED'
      return json(response, 200, {
        run: {
          id: '55555555-5555-5555-5555-555555555555',
          state,
          lastErrorCode: state === 'FAILED' ? lessonFailureCode : null,
          createdAt: '2026-08-02T00:00:13Z',
          completedAt: ['COMPLETED', 'FAILED', 'INSUFFICIENT_EVIDENCE'].includes(state)
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
        sections: [{
          position: 1,
          steps: synchronousVisualEnabled ? [{
            position: 1, kind: 'VISUAL', visualFocus: {
              pageNumber: 1, label: 'board', x: 100, y: 100, width: 400, height: 300,
            },
          }] : [],
        }],
      })
    }
    if (request.method === 'POST'
      && request.url === '/api/v1/document-versions/22222222-2222-2222-2222-222222222222/answers') {
      assert.deepEqual(JSON.parse(body.toString()), {
        question: expectedQuestion,
        language: 'en',
      })
      return json(response, 200, {
        assistantRunId: '77777777-7777-4777-8777-777777777777',
        answer: {
          language: 'en',
          status: 'ANSWERED',
          shortVerdict: 'Each lit dock is worth three victory points.',
          explanation: 'Final scoring awards three points for every dock you lit.',
          citations: answerHasCitations ? [{
            heading: 'GAME END AND SCORING',
            pageFrom: 4,
            pageTo: 4,
            excerpt: 'Score three victory points for each dock you lit.',
          }] : [],
        },
        rulingReference: {
          citationIds: answerHasCitations && answerReferencesAlign
            ? ['88888888-8888-4888-8888-888888888888']
            : [],
          confirmedRulingId: null,
          confirmedRulingVersion: null,
        },
      })
    }
    if (request.method === 'POST' && request.url?.endsWith('/cancellation')) {
      return json(response, 202, {})
    }
    if (request.method === 'DELETE' && request.url === '/api/v1/documents/11111111-1111-1111-1111-111111111111') {
      if (deletionFails) return json(response, 503, { error: 'cleanup unavailable' })
      deleted = true
      response.statusCode = 204
      return response.end()
    }
    return json(response, 404, { error: 'unexpected request' })
  })

  await new Promise((resolvePromise) => server.listen(0, '127.0.0.1', resolvePromise))
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-production-smoke-'))
  const pdf = join(directory, 'Lantern_Relay_rulebook_EN_v4_12pages.pdf')
  const navigation = join(directory, 'navigation.tsv')
  const retainedResult = join(directory, 'retained-result.json')
  const retainedPlanCheckpoint = join(directory, 'retained-plan-checkpoint.json')
  const publicStatus = join(directory, 'public-status.json')
  const failedPublicStatus = join(directory, 'failed-public-status.json')
  await writeFile(pdf, '%PDF-1.4\n%%EOF\n')

  try {
    const address = server.address()
    assert.equal(typeof address, 'object')
    const result = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--official-source-url', 'https://example.com/lantern-relay-rules.pdf',
        '--navigation-mode', 'api',
        '--navigation-file', navigation,
        '--result-file', retainedResult,
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: publicStatus,
      },
    )
    assert.equal(result.code, 0, result.stderr)
    const summary = JSON.parse(result.stdout)
    assert.deepEqual({ ...summary, navigation: undefined }, {
      title: 'Lantern Relay',
      preparationState: 'COMPLETED',
      lessonState: 'COMPLETED',
      lessonStatus: 'COMPLETE',
      answerStatus: 'ANSWERED',
      sectionCount: 1,
      answerCitationCount: 1,
      visualStepCount: 0,
      focusedVisualStepCount: 0,
      visualAssemblyMode: 'IN_TEACHING',
      navigation: undefined,
      cleanup: 'scheduled',
    })
    assert.ok(summary.navigation.requestCount >= 3)
    assert.equal(summary.navigation.failureCount, 0)
    assert.ok(summary.navigation.averageMs >= 0)
    assert.ok(summary.navigation.maxMs >= 0)
    assert.deepEqual(JSON.parse(await readFile(publicStatus, 'utf8')), {
      outcome: 'SUCCEEDED',
      exitCode: 0,
      lastCompletedStage: 'journey-completed',
      failureCode: null,
      failureCauseCode: null,
      cleanupOutcome: 'SUCCEEDED',
    })
    const retained = JSON.parse(await readFile(retainedResult, 'utf8'))
    assert.equal(retained.stage, 'lesson')
    assert.equal(retained.sourceUrl, 'https://example.com/lantern-relay-rules.pdf')
    assert.equal(retained.plan.gameTitle, 'Lantern Relay')
    assert.equal(retained.lesson.status, 'COMPLETE')
    assert.equal(retained.visualRun, undefined)
    assert.equal((await readFile(navigation, 'utf8')).trim().split('\n').length,
      summary.navigation.requestCount)
    assert.ok((await readFile(navigation, 'utf8')).trim().split('\n')
      .every((line) => line.split('\t')[1].startsWith('/api/')))
    assert.equal(deleted, true)
    assert.ok(calls.some((call) => call.method === 'POST' && call.url === '/api/v1/documents'))
    assert.ok(calls.some((call) => call.method === 'POST'
      && call.url === '/api/v1/documents'
      && call.body.toString('latin1').includes('https://example.com/lantern-relay-rules.pdf')))
    assert.match(result.stderr, /SMOKE_STAGE login-completed/)
    assert.match(result.stderr, /SMOKE_STAGE title-verified/)
    assert.match(result.stderr, /SMOKE_STAGE lesson-verified/)
    assert.match(result.stderr, /SMOKE_STAGE answer-verified run=77777777-7777-4777-8777-777777777777 status=ANSWERED citations=1/)
    assert.match(result.stderr, /SMOKE_STAGE lesson-launch-visible run=55555555-5555-5555-5555-555555555555 state=RECEIVED/)
    assert.match(result.stderr, /SMOKE_STAGE cleanup-completed/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=organizeTeachingOutline .*latencyMs=11000/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=deferSelectedVisualPageCatalog .*outcome=SUCCEEDED/)
    assert.doesNotMatch(result.stderr, /SMOKE_TIMING phase=preparation kind=activity .*operation=inspectTeachingVisualBatch/)
    assert.match(result.stderr, /SMOKE_TIMING phase=preparation kind=budget usedModelCalls=1 usedToolCalls=0 usedTokens=1500/)
    assert.match(result.stderr, /SMOKE_TIMING phase=lesson kind=activity .*operation=composeLessonSection .*latencyMs=6500/)
    assert.match(result.stderr, /SMOKE_PERFORMANCE phase=lesson firstSectionSeconds=7 totalSeconds=7 usedModelCalls=1 modelCallLimit=5 correctionCalls=0/)
    assert.match(result.stderr, /SMOKE_PERFORMANCE phase=preparation-start-to-first-cited-section seconds=20/)

    preparationFailureCode = 'TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED'
    deleted = false
    planStarted = false
    const preparationFailureStatus = join(directory, 'preparation-failure-status.json')
    const preparationFailure = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: preparationFailureStatus,
      },
    )
    assert.equal(preparationFailure.code, 1, preparationFailure.stderr)
    assert.deepEqual(JSON.parse(await readFile(preparationFailureStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'document-ready',
      failureCode: 'TEACHING_PREPARATION_FAILED',
      failureCauseCode: 'TEACHING_PREPARATION_PLAN_RESOLUTION_FAILED',
      cleanupOutcome: 'SUCCEEDED',
    })
    assert.equal(deleted, true)
    preparationFailureCode = null

    lessonFailureCode = 'TEACHING_WORKFLOW_FAILED'
    deleted = false
    planStarted = false
    const lessonFailureStatus = join(directory, 'lesson-failure-status.json')
    const lessonFailure = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: lessonFailureStatus,
      },
    )
    assert.equal(lessonFailure.code, 1, lessonFailure.stderr)
    assert.deepEqual(JSON.parse(await readFile(lessonFailureStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'lesson-launch-visible',
      failureCode: 'LESSON_GENERATION_FAILED',
      failureCauseCode: 'TEACHING_WORKFLOW_FAILED',
      cleanupOutcome: 'SUCCEEDED',
    })
    assert.equal(deleted, true)
    lessonFailureCode = null

    answerHasCitations = false
    deleted = false
    planStarted = false
    const ungroundedAnswer = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: failedPublicStatus,
      },
    )
    assert.notEqual(ungroundedAnswer.code, 0)
    assert.match(ungroundedAnswer.stderr, /Rule answer did not publish a conclusion with page evidence and aligned source references/)
    assert.deepEqual(JSON.parse(await readFile(failedPublicStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'lesson-verified',
      failureCode: 'ANSWER_EVIDENCE_INVALID',
      failureCauseCode: null,
      cleanupOutcome: 'SUCCEEDED',
    })
    assert.equal(deleted, true)
    answerHasCitations = true

    answerReferencesAlign = false
    deleted = false
    planStarted = false
    const unboundAnswer = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.notEqual(unboundAnswer.code, 0)
    assert.match(unboundAnswer.stderr, /Rule answer did not publish a conclusion with page evidence and aligned source references/)
    assert.equal(deleted, true)
    answerReferencesAlign = true

    deleted = false
    planStarted = false
    const rejectedAfterPlan = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--expected-title', 'Different Game',
        '--result-file', retainedPlanCheckpoint,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.notEqual(rejectedAfterPlan.code, 0)
    assert.match(rejectedAfterPlan.stderr, /Teaching plan was unusable/)
    const checkpoint = JSON.parse(await readFile(retainedPlanCheckpoint, 'utf8'))
    assert.equal(checkpoint.stage, 'plan')
    assert.equal(checkpoint.plan.gameTitle, 'Lantern Relay')
    assert.equal(checkpoint.lesson, undefined)
    assert.equal(deleted, true)

    synchronousVisualEnabled = true
    deleted = false
    planStarted = false
    const requiredVisual = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--visual-expectation', 'required',
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(requiredVisual.code, 0, requiredVisual.stderr)
    assert.equal(JSON.parse(requiredVisual.stdout).visualStepCount, 1)
    assert.equal(JSON.parse(requiredVisual.stdout).focusedVisualStepCount, 1)
    assert.equal(JSON.parse(requiredVisual.stdout).visualAssemblyMode, 'IN_TEACHING')
    assert.equal(calls.some(call => call.url?.includes('mode=VISUAL_ENRICHMENT')), false)
    assert.match(requiredVisual.stderr, /SMOKE_STAGE visual-expectation-verified expectation=required visualSteps=1 focusedVisualSteps=1/)
    assert.equal(deleted, true)
    synchronousVisualEnabled = false

    deleted = false
    planStarted = false
    const textOnly = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--visual-expectation', 'forbidden',
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(textOnly.code, 0, textOnly.stderr)
    assert.equal(JSON.parse(textOnly.stdout).visualStepCount, 0)
    assert.equal(JSON.parse(textOnly.stdout).visualAssemblyMode, 'IN_TEACHING')
    assert.match(textOnly.stderr, /SMOKE_STAGE visual-expectation-verified expectation=forbidden visualSteps=0 focusedVisualSteps=0/)
    assert.equal(deleted, true)

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

    insufficientLesson = false
    completeLessonImmediately = true
    navigationFails = true
    deletionFails = false
    deleted = false
    planStarted = false
    const navigationFailureStatus = join(directory, 'navigation-failure-status.json')
    const navigationFailure = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--navigation-mode', 'api',
        '--navigation-file', join(directory, 'navigation-failure.tsv'),
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: navigationFailureStatus,
      },
    )
    assert.equal(navigationFailure.code, 1, navigationFailure.stderr)
    assert.match(navigationFailure.stderr, /Concurrent navigation observed 1 non-successful responses/)
    assert.doesNotMatch(navigationFailure.stderr, /SMOKE_STAGE navigation-verified/)
    assert.deepEqual(JSON.parse(await readFile(navigationFailureStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'answer-verified',
      failureCode: 'NAVIGATION_FAILED',
      failureCauseCode: null,
      cleanupOutcome: 'SUCCEEDED',
    })
    assert.equal(deleted, true)

    deletionFails = true
    deleted = false
    planStarted = false
    const doubleFailureStatus = join(directory, 'double-failure-status.json')
    const doubleFailure = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--navigation-mode', 'api',
        '--navigation-file', join(directory, 'double-failure-navigation.tsv'),
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: doubleFailureStatus,
      },
    )
    assert.equal(doubleFailure.code, 1, doubleFailure.stderr)
    assert.match(doubleFailure.stderr, /SMOKE_STAGE cleanup-failed/)
    assert.deepEqual(JSON.parse(await readFile(doubleFailureStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'answer-verified',
      failureCode: 'NAVIGATION_FAILED',
      failureCauseCode: null,
      cleanupOutcome: 'FAILED',
    })
    assert.equal(deleted, false)

    navigationFails = false
    deleted = false
    planStarted = false
    const cleanupFailureStatus = join(directory, 'cleanup-failure-status.json')
    const cleanupFailure = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--pdf', pdf,
        '--timeout-seconds', '10'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: cleanupFailureStatus,
      },
    )
    assert.equal(cleanupFailure.code, 1, cleanupFailure.stderr)
    assert.match(cleanupFailure.stderr, /SMOKE_STAGE journey-completed/)
    assert.match(cleanupFailure.stderr, /SMOKE_STAGE cleanup-failed/)
    assert.deepEqual(JSON.parse(await readFile(cleanupFailureStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'journey-completed',
      failureCode: 'CLEANUP_FAILED',
      failureCauseCode: null,
      cleanupOutcome: 'FAILED',
    })
    assert.equal(deleted, false)
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
    await rm(directory, { recursive: true, force: true })
  }
})

test('refuses to assert image-gallery rights unless the operator passed the explicit flag', async () => {
  const result = await spawnResult(
    'bash',
    [resolve('scripts/smoke-production-ordinary-user.sh'),
      '--base-url', 'http://127.0.0.1:1',
      '--source-mode', 'official_image_gallery',
      '--official-source-url', 'https://www.gstonegames.com/game/doc-4417.html',
      '--expected-title', 'dune: imperium',
      '--uploaded-title', 'Dune: Imperium',
      '--bgg-id', '316554',
      '--expected-page-count', '20',
      '--language', 'zh-CN',
      '--canary-id', 'rights-negative',
      '--question', '游戏什么时候结束？'],
    { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
  )

  assert.equal(result.code, 2)
  assert.match(result.stderr, /--rights-confirmed is required/)
})

test('imports one fresh ordered image gallery, reuses its automatic Teaching handoff, and cleans up only that document', async () => {
  const calls = []
  const editionId = '11111111-2222-4333-8444-555555555555'
  const documentId = '22222222-3333-4444-8555-666666666666'
  const retainedDocumentId = '22222222-3333-4444-8555-777777777777'
  const versionId = '33333333-4444-4555-8666-777777777777'
  const importJobId = '44444444-5555-4666-8777-888888888888'
  const preparationRunId = '55555555-6666-4777-8888-999999999999'
  const planId = '66666666-7777-4888-8999-000000000000'
  const lessonRunId = '77777777-8888-4999-8000-111111111111'
  const canonicalSource = 'https://www.gstonegames.com/game/doc-4417.html'
  const effectiveSource = `${canonicalSource}?rulepilot_canary=run-1`
  const canaryTitle = 'Dune: Imperium · RulePilot canary run-1'
  let deleted = false

  const run = (id, state, createdAt, completedAt, activities) => ({
    run: { id, state, createdAt, completedAt, lastErrorCode: null },
    steps: [],
    activities,
    budget: { usedModelCalls: activities.filter(activity => activity.type === 'MODEL').length,
      usedToolCalls: 0, usedTokens: 1000 },
  })
  const preparation = run(preparationRunId, 'COMPLETED', '2026-08-25T00:00:00Z', '2026-08-25T00:00:10Z', [
    { sequence: 1, type: 'MODEL', operation: 'inspectTeachingVisualPage|1|3', outcome: 'FAILED',
      latencyMs: 1000, occurredAt: '2026-08-25T00:00:04Z' },
    { sequence: 2, type: 'MODEL', operation: 'inspectTeachingVisualPage|2|3', outcome: 'FAILED',
      latencyMs: 900, occurredAt: '2026-08-25T00:00:04Z' },
    { sequence: 3, type: 'MODEL', operation: 'inspectTeachingVisualPage|3|3', outcome: 'SUCCEEDED',
      latencyMs: 900, occurredAt: '2026-08-25T00:00:04Z' },
    { sequence: 4, type: 'MODEL', operation: 'inspectTeachingVisualRepair|1|3|DUPLICATE_RULE_GROUP', outcome: 'SUCCEEDED',
      latencyMs: 1100, occurredAt: '2026-08-25T00:00:05Z' },
    { sequence: 5, type: 'MODEL', operation: 'inspectTeachingVisualRetry|2|3', outcome: 'FAILED',
      latencyMs: 1050, occurredAt: '2026-08-25T00:00:05Z' },
  ])
  const lessonRun = run(lessonRunId, 'COMPLETED', '2026-08-25T00:00:10Z', '2026-08-25T00:00:18Z', [
    { sequence: 1, type: 'MODEL', operation: 'composeLessonSection', outcome: 'SUCCEEDED',
      latencyMs: 5000, occurredAt: '2026-08-25T00:00:15Z' },
    { sequence: 2, type: 'VALIDATION', operation: 'publishTeachingSection|1', outcome: 'SUCCEEDED',
      latencyMs: 0, occurredAt: '2026-08-25T00:00:16Z' },
  ])
  const importJob = {
    id: importJobId,
    title: 'Dune: Imperium',
    rulebookTitle: canaryTitle,
    editionId,
    officialSourceUrl: effectiveSource,
    stage: 'COMPLETED',
    downloadedBytes: 8192,
    totalBytes: null,
    documentVersionId: versionId,
    duplicate: false,
    errorCode: null,
    teachingHandoffState: 'LAUNCHED',
    teachingPreparationRunId: preparationRunId,
    teachingErrorCode: null,
    teachingAutomaticRecoveryCount: 0,
    downloadCompletedAt: '2026-08-25T00:00:00Z',
    importCompletedAt: '2026-08-25T00:00:01Z',
    teachingHandoffUpdatedAt: '2026-08-25T00:00:01Z',
    reused: false,
  }
  const lesson = {
    teachingPlanId: planId,
    status: 'COMPLETE',
    sections: [{ position: 1, evidenceStatus: 'SUPPORTED', steps: [{
      kind: 'RULE', citationIds: ['99999999-0000-4111-8222-333333333333'],
    }] }],
  }

  const server = createServer(async (request, response) => {
    const body = await readBody(request)
    calls.push({ method: request.method, url: request.url, body })
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Set-Cookie', 'RULEPILOT_TEST=authenticated; Path=/')
    if (request.method === 'GET' && request.url === '/api/auth/csrf') {
      return json(response, 200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' })
    }
    if (request.method === 'POST' && request.url === '/api/auth/login') return json(response, 200, {})
    if (request.method === 'GET' && request.url === '/api/auth/session') {
      return json(response, 200, { username: 'player', roles: ['USER'] })
    }
    if (request.method === 'POST' && request.url === '/api/v1/bgg/games/316554/import') {
      return json(response, 200, {
        game: { id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Dune: Imperium' },
        edition: { id: editionId, gameId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'BGG version', language: 'und' },
        bggId: 316554,
      })
    }
    if (request.method === 'GET' && request.url
      === `/api/v1/documents/rulebook-candidates?editionId=${editionId}&language=zh-CN`) {
      return json(response, 200, {
        configured: true,
        identity: { editionId, gameName: 'Dune: Imperium', editionName: 'BGG version', language: 'und' },
        candidates: [{
          title: '官方中文规则', url: canonicalSource, publisher: '集石', language: 'zh-CN',
          edition: 'BGG version', sourceDomain: 'www.gstonegames.com', officialDomainVerified: false,
          languageVerified: true, sourceType: 'COMMUNITY_PLATFORM', acquisitionMode: 'IMAGE_GALLERY',
          capability: 'CONTIGUOUS_RULE_PAGES', capabilityEvidence: ['ORDERED_PAGE_SEQUENCE_CONFIRMED'],
          nextAction: 'IMPORT_PAGE_SEQUENCE',
        }],
      })
    }
    if (request.method === 'POST' && request.url === '/api/v1/documents/official-imports') {
      const payload = JSON.parse(body.toString())
      assert.deepEqual(payload, {
        editionId,
        title: canaryTitle,
        sourceType: 'BASE_RULEBOOK',
        officialSourceUrl: effectiveSource,
        rightsConfirmed: true,
        startTeaching: true,
        learningGoal: null,
        discoveredForEditionId: editionId,
        sourceEdition: 'BGG version',
        sourceLanguage: 'zh-CN',
        sourceLanguageVerified: true,
        identityConfirmed: true,
      })
      return json(response, 202, { ...importJob, stage: 'QUEUED', downloadedBytes: 0,
        documentVersionId: null, teachingHandoffState: 'WAITING_FOR_DOCUMENT',
        teachingPreparationRunId: null })
    }
    if (request.method === 'GET' && request.url === `/api/v1/documents/official-imports/${importJobId}`) {
      return json(response, 200, importJob)
    }
    if (request.method === 'GET' && request.url === '/api/v1/documents') {
      return json(response, 200, [
        {
          document: { id: retainedDocumentId, gameEditionId: editionId, title: 'Existing user rulebook' },
          latestVersion: { id: '33333333-4444-4555-8666-888888888888', status: 'READY' },
        },
        ...deleted ? [] : [{
          document: { id: documentId, gameEditionId: editionId, title: canaryTitle },
          latestVersion: { id: versionId, status: 'READY' },
        }],
      ])
    }
    if (request.method === 'GET' && request.url === '/api/v1/teaching-plans') return json(response, 200, [])
    if (request.method === 'GET' && request.url === '/api/public/lessons') return json(response, 200, [])
    if (request.method === 'GET' && request.url === `/api/v1/document-versions/${versionId}/pages/summaries`) {
      return json(response, 200, [
        { pageNumber: 1, characterCount: 0 },
        { pageNumber: 2, characterCount: 0 },
        { pageNumber: 3, characterCount: 0 },
      ])
    }
    if (request.method === 'GET' && request.url === `/api/v1/assistant-runs/${preparationRunId}`) {
      return json(response, 200, preparation)
    }
    if (request.method === 'GET' && request.url === `/api/v1/document-versions/${versionId}/teaching-plans/latest`) {
      return json(response, 200, { id: planId, documentVersionId: versionId,
        gameTitle: 'Dune: Imperium', sections: [{ position: 1 }] })
    }
    if (request.method === 'GET'
      && request.url === `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${planId}`) {
      return json(response, 200, lessonRun)
    }
    if (request.method === 'GET' && request.url === `/api/v1/assistant-runs/${lessonRunId}`) {
      return json(response, 200, lessonRun)
    }
    if (request.method === 'GET' && request.url === `/api/v1/teaching-plans/${planId}/illustrated-lessons/latest`) {
      return json(response, 200, lesson)
    }
    if (request.method === 'POST' && request.url === `/api/v1/document-versions/${versionId}/answers`) {
      assert.deepEqual(JSON.parse(body.toString()), {
        question: '游戏什么时候结束？胜利点相同时如何决胜？请标出规则书页码。',
        language: 'zh-CN',
      })
      return json(response, 200, {
        answer: {
          language: 'zh-CN', status: 'ANSWERED', shortVerdict: '达到结束条件后比较胜利点。',
          explanation: '平手时按规则书列出的资源顺序决胜。',
          citations: [{ pageFrom: 3, pageTo: 3, excerpt: '游戏结束与平手规则。' }],
        },
        rulingReference: { citationIds: ['99999999-0000-4111-8222-333333333333'],
          confirmedRulingId: null, confirmedRulingVersion: null },
      })
    }
    if (request.method === 'POST' && request.url?.endsWith('/cancellation')) return json(response, 202, {})
    if (request.method === 'DELETE' && request.url === `/api/v1/documents/${documentId}`) {
      deleted = true
      response.statusCode = 204
      return response.end()
    }
    return json(response, 404, { error: 'unexpected request' })
  })

  await new Promise((resolvePromise) => server.listen(0, '127.0.0.1', resolvePromise))
  const directory = await mkdtemp(join(tmpdir(), 'rulepilot-production-gallery-smoke-'))
  const retainedResult = join(directory, 'retained-result.json')
  try {
    const address = server.address()
    assert.equal(typeof address, 'object')
    const result = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--source-mode', 'official_image_gallery',
        '--official-source-url', canonicalSource,
        '--expected-title', 'dune: imperium',
        '--uploaded-title', 'Dune: Imperium',
        '--bgg-id', '316554',
        '--expected-page-count', '3',
        '--language', 'zh-CN',
        '--canary-id', 'run-1',
        '--rights-confirmed',
        '--navigation-mode', 'api',
        '--question', '游戏什么时候结束？胜利点相同时如何决胜？请标出规则书页码。',
        '--result-file', retainedResult,
        '--timeout-seconds', '10'],
      { ...process.env, RULEPILOT_SMOKE_PASSWORD: 'smoke-password' },
    )
    assert.equal(result.code, 0, result.stderr)
    const summary = JSON.parse(result.stdout)
    assert.equal(summary.sourceMode, 'official_image_gallery')
    assert.equal(summary.title, 'Dune: Imperium')
    assert.equal(summary.sourceUrl, canonicalSource)
    assert.equal(summary.effectiveSourceUrl, effectiveSource)
    assert.equal(summary.pageCount, 3)
    assert.deepEqual(summary.pageAttempts, {
      pages: [
        { page: 1, initialOutcome: 'FAILED',
          recoveryKind: 'CONTRACT_REPAIR', repairCode: 'DUPLICATE_RULE_GROUP',
          recoveryOutcome: 'SUCCEEDED', semanticAttempts: 2,
          finalOutcome: 'SUCCEEDED' },
        { page: 2, initialOutcome: 'FAILED', recoveryKind: 'TRANSIENT_RETRY',
          repairCode: null, recoveryOutcome: 'FAILED', semanticAttempts: 2, finalOutcome: 'FAILED' },
        { page: 3, initialOutcome: 'SUCCEEDED', recoveryKind: null,
          repairCode: null, recoveryOutcome: null, semanticAttempts: 1, finalOutcome: 'SUCCEEDED' },
      ],
      initialSucceeded: 1,
      initialFailed: 2,
      initialRejected: 0,
      transientRetryAttempted: 1,
      transientRetrySucceeded: 0,
      transientRetryFailed: 1,
      repairAttempted: 1,
      repairSucceeded: 1,
      repairFailed: 0,
      finalUnavailablePages: [2],
      maximumSemanticAttemptsForAnyPage: 2,
      valid: true,
    })
    assert.equal(summary.preparationState, 'COMPLETED')
    assert.equal(summary.lessonState, 'COMPLETED')
    assert.equal(summary.lessonStatus, 'COMPLETE')
    assert.equal(summary.answerStatus, 'ANSWERED')
    assert.equal(summary.visualAssemblyMode, 'IN_TEACHING')
    assert.equal(deleted, true)
    assert.match(result.stderr, /SMOKE_STAGE image-gallery-candidate-verified/)
    assert.match(result.stderr, /SMOKE_STAGE official-import-completed/)
    assert.match(result.stderr, /SMOKE_PAGE_ATTEMPTS .*"maximumSemanticAttemptsForAnyPage":2/)
    assert.match(result.stderr, /SMOKE_STAGE cleanup-completed/)
    assert.equal(calls.filter(call => call.method === 'POST'
      && call.url === '/api/v1/documents/official-imports').length, 1)
    assert.equal(calls.filter(call => call.method === 'POST'
      && /\/document-versions\/[^/]+\/teaching-plans$/.test(call.url ?? '')).length, 0)
    assert.equal(calls.filter(call => call.method === 'POST'
      && /\/illustrated-lessons$/.test(call.url ?? '')).length, 0)
    assert.equal(calls.filter(call => call.method === 'POST' && call.url === '/api/v1/documents').length, 0)
    assert.deepEqual(calls.filter(call => call.method === 'DELETE').map(call => call.url),
      [`/api/v1/documents/${documentId}`])
    const retained = JSON.parse(await readFile(retainedResult, 'utf8'))
    assert.equal(retained.importJobId, importJobId)
    assert.equal(retained.documentId, documentId)
    assert.equal(retained.documentVersionId, versionId)
    assert.equal(retained.summary.pageAttempts.repairSucceeded, 1)
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
    await rm(directory, { recursive: true, force: true })
  }
})

test('keeps a timed-out accepted import failed while deleting only its later exact fresh document', async () => {
  const editionId = '81111111-2222-4333-8444-555555555555'
  const importJobId = '82222222-3333-4444-8555-666666666666'
  const documentId = '83333333-4444-4555-8666-777777777777'
  const retainedDocumentId = '84444444-5555-4666-8777-888888888888'
  const versionId = '85555555-6666-4777-8888-999999999999'
  const source = 'https://www.gstonegames.com/game/doc-4417.html'
  const effectiveSource = `${source}?rulepilot_canary=cleanup-later`
  const canaryTitle = 'Dune: Imperium · RulePilot canary cleanup-later'
  const deleted = []
  let importReads = 0
  const statusDirectory = await mkdtemp(join(tmpdir(), 'rulepilot-production-gallery-status-'))
  const publicStatus = join(statusDirectory, 'public-status.json')

  const server = createServer(async (request, response) => {
    await readBody(request)
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Set-Cookie', 'RULEPILOT_TEST=authenticated; Path=/')
    if (request.method === 'GET' && request.url === '/api/auth/csrf') {
      return json(response, 200, { headerName: 'X-CSRF-TOKEN', token: 'csrf-token' })
    }
    if (request.method === 'POST' && request.url === '/api/auth/login') return json(response, 200, {})
    if (request.method === 'GET' && request.url === '/api/auth/session') {
      return json(response, 200, { username: 'player', roles: ['USER'] })
    }
    if (request.method === 'POST' && request.url === '/api/v1/bgg/games/316554/import') {
      return json(response, 200, {
        game: { id: '8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Dune: Imperium' },
        edition: { id: editionId, gameId: '8aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'BGG version', language: 'und' },
        bggId: 316554,
      })
    }
    if (request.method === 'GET'
      && request.url === `/api/v1/documents/rulebook-candidates?editionId=${editionId}&language=zh-CN`) {
      return json(response, 200, {
        configured: true,
        identity: { editionId },
        candidates: [{
          title: '官方中文规则', url: source, language: 'zh-CN', languageVerified: true,
          edition: 'BGG version', acquisitionMode: 'IMAGE_GALLERY', capability: 'CONTIGUOUS_RULE_PAGES',
          capabilityEvidence: ['ORDERED_PAGE_SEQUENCE_CONFIRMED'], nextAction: 'IMPORT_PAGE_SEQUENCE',
        }],
      })
    }
    if (request.method === 'POST' && request.url === '/api/v1/documents/official-imports') {
      return json(response, 202, {
        id: importJobId, reused: false, editionId, officialSourceUrl: effectiveSource,
        stage: 'QUEUED', duplicate: false, documentVersionId: null,
      })
    }
    if (request.method === 'GET' && request.url === `/api/v1/documents/official-imports/${importJobId}`) {
      importReads += 1
      if (importReads === 1) {
        return json(response, 200, {
          id: importJobId, stage: 'QUEUED', duplicate: false, documentVersionId: null,
          teachingHandoffState: 'WAITING_FOR_DOCUMENT',
        })
      }
      return json(response, 200, {
        id: importJobId, stage: 'COMPLETED', duplicate: false, documentVersionId: versionId,
        teachingHandoffState: 'LAUNCHED', teachingPreparationRunId: null,
      })
    }
    if (request.method === 'GET' && request.url === '/api/v1/documents') {
      return json(response, 200, [
        {
          document: { id: retainedDocumentId, title: canaryTitle, gameEditionId: editionId },
          latestVersion: { id: '86666666-7777-4888-8999-000000000000', status: 'READY' },
        },
        {
          document: { id: documentId, title: canaryTitle, gameEditionId: editionId },
          latestVersion: { id: versionId, status: 'READY' },
        },
      ])
    }
    if (request.method === 'DELETE') {
      deleted.push(request.url)
      response.statusCode = 204
      return response.end()
    }
    return json(response, 404, { error: 'unexpected request' })
  })

  await new Promise((resolvePromise) => server.listen(0, '127.0.0.1', resolvePromise))
  try {
    const address = server.address()
    assert.equal(typeof address, 'object')
    const result = await spawnResult(
      'bash',
      [resolve('scripts/smoke-production-ordinary-user.sh'),
        '--base-url', `http://127.0.0.1:${address.port}`,
        '--source-mode', 'official_image_gallery',
        '--official-source-url', source,
        '--expected-title', 'dune: imperium',
        '--uploaded-title', 'Dune: Imperium',
        '--bgg-id', '316554',
        '--expected-page-count', '20',
        '--language', 'zh-CN',
        '--canary-id', 'cleanup-later',
        '--rights-confirmed',
        '--navigation-mode', 'api',
        '--question', '游戏什么时候结束？',
        '--timeout-seconds', '1'],
      {
        ...process.env,
        RULEPILOT_SMOKE_PASSWORD: 'smoke-password',
        RULEPILOT_SMOKE_CLEANUP_TIMEOUT_SECONDS: '5',
        RULEPILOT_SMOKE_PUBLIC_STATUS_FILE: publicStatus,
      },
    )

    assert.equal(result.code, 1, result.stderr)
    assert.match(result.stderr, /Official image-gallery import timed out/)
    assert.match(result.stderr, /SMOKE_STAGE cleanup-import-resolved/)
    assert.match(result.stderr, /SMOKE_STAGE cleanup-completed/)
    assert.deepEqual(JSON.parse(await readFile(publicStatus, 'utf8')), {
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'official-import-accepted',
      failureCode: 'OFFICIAL_IMPORT_FAILED',
      failureCauseCode: null,
      cleanupOutcome: 'SUCCEEDED',
    })
    assert.deepEqual(deleted, [`/api/v1/documents/${documentId}`])
  } finally {
    server.closeAllConnections()
    await new Promise((resolvePromise) => server.close(resolvePromise))
    await rm(statusDirectory, { recursive: true, force: true })
  }
})

test('production workflows never execute an operator-supplied Git ref with production credentials', async () => {
  const deployment = await readFile(resolve('.github/workflows/deploy-production.yml'), 'utf8')
  const smoke = await readFile(resolve('.github/workflows/production-ordinary-user-smoke.yml'), 'utf8')
  const candidates = await readFile(resolve('.github/workflows/public-lesson-candidate.yml'), 'utf8')

  assert.doesNotMatch(deployment, /inputs\.ref/)
  assert.match(deployment, /ref: \$\{\{ github\.event\.workflow_run\.head_sha \}\}/)
  assert.doesNotMatch(deployment, /workflow_run\.head_sha\s*\|\|/)
  assert.doesNotMatch(smoke, /inputs\.ref/)
  assert.match(smoke, /ref: main/)
  assert.doesNotMatch(candidates, /inputs\.ref/)
  assert.match(candidates, /ref: main/)
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
