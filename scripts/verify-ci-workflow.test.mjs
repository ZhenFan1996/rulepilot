import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { access, mkdir, mkdtemp, readFile, rm, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import test from 'node:test'

const execFileAsync = promisify(execFile)

const ciWorkflow = await readFile(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8')
const deploymentWorkflow = await readFile(
  new URL('../.github/workflows/deploy-production.yml', import.meta.url),
  'utf8',
)
const productionAvailabilityScript = await readFile(
  new URL('./verify-production-availability.mjs', import.meta.url),
  'utf8',
)
const productionCompose = await readFile(new URL('../infra/compose.production.yml', import.meta.url), 'utf8')
const deploymentCompose = await readFile(new URL('../infra/compose.deployment.yml', import.meta.url), 'utf8')
const productionScript = await readFile(new URL('./run-production.sh', import.meta.url), 'utf8')
const productionReleaseGuard = await readFile(
  new URL('./production-release-guard.sh', import.meta.url),
  'utf8',
)
const productionTraceSummaryScript = await readFile(
  new URL('./summarize-production-release-traces.mjs', import.meta.url),
  'utf8',
)
const productionResourceSummaryScript = await readFile(
  new URL('./summarize-production-resource-samples.mjs', import.meta.url),
  'utf8',
)
const playwrightConfig = await readFile(new URL('../frontend/playwright.config.ts', import.meta.url), 'utf8')
const productionRecommendationWorkflow = await readFile(
  new URL('../.github/workflows/production-recommendation-journey.yml', import.meta.url),
  'utf8',
)
const productionOrdinaryUserWorkflow = await readFile(
  new URL('../.github/workflows/production-ordinary-user-smoke.yml', import.meta.url),
  'utf8',
)
const productionRealRulebookWorkflow = await readFile(
  new URL('../.github/workflows/production-real-rulebook-experience.yml', import.meta.url),
  'utf8',
)
const publicLessonCandidateWorkflow = await readFile(
  new URL('../.github/workflows/public-lesson-candidate.yml', import.meta.url),
  'utf8',
)
const productionRecommendationConfig = await readFile(
  new URL('../frontend/playwright.recommendation-production.config.ts', import.meta.url),
  'utf8',
)
const productionRecommendationSpec = await readFile(
  new URL('../frontend/e2e/production-recommendation-journey.spec.ts', import.meta.url),
  'utf8',
)

test('CI publishes the browser report and builds the native backend image used by deployment', () => {
  assert.match(ciWorkflow, /uses:\s*actions\/upload-artifact@v(?:6|7)\b/)
  assert.doesNotMatch(ciWorkflow, /uses:\s*actions\/upload-artifact@v[1-5]\b/)
  assert.match(ciWorkflow, /path:\s*frontend\/playwright-report\//)
  assert.match(ciWorkflow, /if-no-files-found:\s*error/)
  assert.match(playwrightConfig, /outputFolder:\s*'playwright-report'/)
  assert.match(ciWorkflow, /make backend-test[\s\S]*?make backend-runtime-image-smoke/)
  assert.match(deploymentWorkflow, /docker build[\s\S]*?--file backend\/Dockerfile\.runtime/)
})

test('production recommendation verifies one deployed main release and the complete player journey safely', () => {
  assert.match(productionRecommendationWorkflow,
    /tested_sha:[\s\S]*?required: true[\s\S]*?type: string/)
  assert.match(productionRecommendationWorkflow,
    /uses:\s*actions\/checkout@v6[\s\S]*?ref:\s*\$\{\{ inputs\.tested_sha \}\}[\s\S]*?fetch-depth:\s*0/)
  assert.match(productionRecommendationWorkflow, /environment:\s*\n\s+name:\s*production/)
  assert.match(productionRecommendationWorkflow,
    /git merge-base --is-ancestor "\$tested_sha" origin\/main/)
  assert.match(productionRecommendationWorkflow, /"\$active_release_sha" != "\$tested_sha"/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_username/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_password/)
  assert.match(productionRecommendationWorkflow,
    /journey_mode:[\s\S]*?- ready_public[\s\S]*?- verified_import/)
  assert.match(productionRecommendationWorkflow, /recommendation_only:[\s\S]*?type: boolean/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_SELECTION_PROMPT: \$\{\{ inputs\.selection_prompt \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_RULE_FOLLOW_UP: \$\{\{ inputs\.rule_follow_up \}\}/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /target_bgg_id|target_names|230802|花砖物语|Azul/)

  assert.match(productionRecommendationWorkflow, /playwright\.recommendation-production\.config\.ts/)
  assert.match(productionRecommendationConfig,
    /testMatch:\s*'production-recommendation-journey\.spec\.ts'/)
  assert.match(productionRecommendationSpec, /candidate\.officialDomainVerified === true/)
  assert.match(productionRecommendationSpec, /candidate\.languageVerified === true/)
  assert.match(productionRecommendationSpec, /positiveDistinctBggIds\(terminalGames\)/)
  assert.match(productionRecommendationSpec, /RECOMMENDATION_ONLY_NO_RULEBOOK_IMPORT/)
  assert.match(productionRecommendationSpec,
    /Recommendation-only verification must not start a rulebook import/)
  assert.match(productionRecommendationSpec, /expect\(report\.answerCitationCount\)\.toBeGreaterThan\(0\)/)
  assert.match(productionRecommendationSpec, /expect\(report\.followUpCitationCount\)\.toBeGreaterThan\(0\)/)
  assert.match(productionRecommendationSpec, /expect\(report\.answerSessionPreserved[\s\S]*?\)\.toBe\(true\)/)
  assert.match(productionRecommendationSpec, /rawModelOutputCaptured: false/)
  assert.doesNotMatch(productionRecommendationSpec,
    /TARGET_BGG_ID|TARGET_NAME|gstoneCandidate|gstonegames\.com/)
  assert.doesNotMatch(productionRecommendationWorkflow, /echo "\$player_password"/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$player_password"/)
})

test('production mutations share one non-cancelling runtime lock', () => {
  for (const workflow of [
    deploymentWorkflow,
    productionRecommendationWorkflow,
    productionOrdinaryUserWorkflow,
    productionRealRulebookWorkflow,
    publicLessonCandidateWorkflow,
  ]) {
    assert.match(workflow,
      /concurrency:\s*\n\s+group: production-runtime\s*\n\s+cancel-in-progress: false/)
  }
})

test('production tracing is explicit, release-scoped, sanitized, and never a diagnostic release gate', () => {
  assert.match(productionScript, /PRODUCTION_TRACING_EXPORT_OTLP_ENABLED:-false/)
  assert.match(productionScript,
    /PRODUCTION_TRACING_OTLP_ENDPOINT is required when production OTLP tracing is enabled\./)
  assert.doesNotMatch(productionScript, /^\s*(?:source|\.)\s+[^\n]*\.env/m)
  assert.match(productionCompose,
    /tempo:[\s\S]*?ports: !override\s*\n\s+- "127\.0\.0\.1:3200:3200"/)

  assert.match(deploymentWorkflow,
    /name: Collect exact-release trace diagnostics\n\s+if: always\(\)\n\s+continue-on-error: true/)
  assert.match(deploymentWorkflow,
    /summarize-production-release-traces\.mjs[\s\S]*?--release-id "\$DEPLOY_RELEASE_ID"[\s\S]*?--trace-id "\$DEPLOY_CANARY_TRACE_ID"/)
  assert.match(productionRecommendationWorkflow,
    /name: Collect exact-release Tempo workflow terminals\n\s+if: always\(\)\n\s+continue-on-error: true/)
  assert.match(productionRecommendationWorkflow,
    /--release-id "\$RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID"[\s\S]*?--trace-id "\$RULEPILOT_RECOMMENDATION_TRACE_ID"/)
  assert.doesNotMatch(deploymentWorkflow, /--require-release-trace|--require-workflow-terminal/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /--require-release-trace|--require-workflow-terminal/)

  assert.match(productionTraceSummaryScript,
    /resource\.service\.version = "\$\{releaseId\}"/)
  assert.match(productionTraceSummaryScript, /trace:id = "\$\{traceId\}"/)
  assert.match(productionTraceSummaryScript, /businessWorkflowTerminal:/)
  assert.match(productionTraceSummaryScript, /queryOutcome: 'NOT_AVAILABLE'/)
  assert.doesNotMatch(productionTraceSummaryScript,
    /console\.log\([^\n]*(?:traceID|spanID|response|body)/)
})

test('post-activation failure, cancellation, or skipped public gate restores only a validated checkpoint', () => {
  const checkpoint = deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint')
  const activation = deploymentWorkflow.indexOf('name: Activate release and verify production health')
  const availability = deploymentWorkflow.indexOf('name: Verify public release availability')
  const rollback = deploymentWorkflow.indexOf(
    'name: Roll back any activated release without successful public availability',
  )
  const diagnostics = deploymentWorkflow.indexOf('name: Collect exact-release trace diagnostics')

  assert.ok(checkpoint >= 0)
  assert.ok(checkpoint < activation)
  assert.ok(activation < availability)
  assert.ok(availability < rollback)
  assert.ok(rollback < diagnostics)
  assert.match(deploymentWorkflow,
    /always\(\) &&[\s\S]*?steps\.rollback_checkpoint\.outcome == 'success' &&[\s\S]*?steps\.activate_release\.outcome != 'skipped' &&[\s\S]*?steps\.public_availability\.outcome != 'success'/)
})

test('release guard stays live from checkpoint through the public commit', () => {
  const checkpointStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint'),
    deploymentWorkflow.indexOf('name: Activate release and verify production health'),
  )
  const activationStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Activate release and verify production health'),
    deploymentWorkflow.indexOf('name: Verify public release availability'),
  )
  const publicGateStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Verify public release availability'),
    deploymentWorkflow.indexOf(
      'name: Roll back any activated release without successful public availability',
    ),
  )

  assert.match(checkpointStep, /"\$guard_script" checkpoint[\s\S]*?"\$guard_script" start/)
  assert.match(activationStep,
    /"\$guard_script" arm[\s\S]*?make production-up[\s\S]*?"\$guard_script" heartbeat/)
  assert.match(publicGateStep,
    /invoke_guard heartbeat[\s\S]*?keep_guard_alive[\s\S]*?invoke_guard heartbeat[\s\S]*?verify-production-availability\.mjs[\s\S]*?invoke_guard commit/)
  for (const command of ['checkpoint', 'start', 'arm', 'heartbeat', 'commit']) {
    assert.match(productionReleaseGuard, new RegExp(`^\\t${command}\\)`, 'm'))
  }
  assert.match(productionReleaseGuard, /atomic_write "\$state_dir\/committed" "\$release_id"/)
})

test('watchdog rechecks a stale lease after acquiring the activation lock', async (context) => {
  const staleLeaseGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('rollback_if_stale()'),
    productionReleaseGuard.indexOf('commit_release()'),
  )
  const lockAcquired = staleLeaseGuard.indexOf('flock -x 9')
  const leaseReread = staleLeaseGuard.indexOf('stat -c %Y', lockAcquired)
  const rollback = staleLeaseGuard.indexOf('rollback_held', leaseReread)
  assert.ok(lockAcquired >= 0 && lockAcquired < leaseReread && leaseReread < rollback)

  if (process.platform !== 'linux') {
    context.skip('production lock semantics are exercised on the Linux CI runner')
    return
  }
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-release-guard.'))
  const release = `${'a'.repeat(40)}-101-2`
  const previous = `${'b'.repeat(40)}-100`
  const state = join(root, 'deployment-guards', release)
  const lease = join(state, 'lease')
  try {
    await mkdir(state, { recursive: true })
    await writeFile(join(state, 'previous-release'), `${previous}\n`, { mode: 0o600 })
    await writeFile(join(state, 'armed'), `${release}\n`, { mode: 0o600 })
    await writeFile(lease, '')
    const stale = new Date(Date.now() - 10 * 60 * 1000)
    await utimes(lease, stale, stale)
    await execFileAsync('bash', [
      '-c',
      'set -Eeuo pipefail; exec 8>"$2/deployment.lock"; flock -x 8; "$1" rollback-if-stale "$2" "$3" "$4" & guard_pid=$!; sleep 0.2; touch "$2/deployment-guards/$3/lease"; flock -u 8; wait "$guard_pid"',
      'release-guard-test',
      new URL('./production-release-guard.sh', import.meta.url).pathname,
      root,
      release,
      previous,
    ])
    await assert.rejects(access(join(state, 'rolled-back')))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('rollback is bounded to immutable releases and publishes only a revalidated topology', () => {
  const rollbackGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('rollback_held()'),
    productionReleaseGuard.indexOf('\nrollback()'),
  )
  assert.match(rollbackGuard,
    /\$\{previous_release%\/\*\}" == "\$releases_root"[\s\S]*?\$\{failed_release%\/\*\}" != "\$releases_root"[\s\S]*?\$active_release" != "\$failed_release" && "\$active_release" != "\$previous_release"/)

  const topologyStart = rollbackGuard.indexOf(
    'up -d --no-build --no-deps api worker frontend gateway',
  )
  const workerHealthy = rollbackGuard.indexOf('wait_for_worker', topologyStart)
  const apiIdentity = rollbackGuard.indexOf('require_running_image "$previous_release" api', workerHealthy)
  const workerIdentity = rollbackGuard.indexOf(
    'require_running_image "$previous_release" worker',
    apiIdentity,
  )
  const frontendIdentity = rollbackGuard.indexOf(
    'require_running_image "$previous_release" frontend',
    workerIdentity,
  )
  const publishCurrent = rollbackGuard.indexOf('ln -sfn "$previous_release"', frontendIdentity)
  const terminal = rollbackGuard.indexOf('atomic_write "$state_dir/rolled-back"', publishCurrent)
  assert.ok(topologyStart >= 0 && topologyStart < workerHealthy)
  assert.ok(workerHealthy < apiIdentity && apiIdentity < workerIdentity)
  assert.ok(workerIdentity < frontendIdentity && frontendIdentity < publishCurrent)
  assert.ok(publishCurrent < terminal)
  assert.doesNotMatch(rollbackGuard, /make production-up/)
})

test('production recommendation records release-scoped runtime reset evidence', () => {
  const releaseCheck = productionRecommendationWorkflow.indexOf(
    'Production active release SHA does not equal tested_sha',
  )
  const baseline = productionRecommendationWorkflow.indexOf(
    'if collect_resource_state "$resource_baseline"',
  )
  const journey = productionRecommendationWorkflow.indexOf(
    'RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY=true',
  )
  assert.ok(releaseCheck >= 0 && releaseCheck < baseline && baseline < journey)
  assert.match(productionRecommendationWorkflow,
    /\.RestartCount[\s\S]*?\.State\.OOMKilled[\s\S]*?\.State\.Running/)
  assert.match(productionRecommendationWorkflow, /docker stats --no-stream/)
  assert.match(productionRecommendationWorkflow,
    /summarize-production-resource-samples\.mjs[\s\S]*?--release-id "\$RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID"[\s\S]*?--fail-on-runtime-reset/)
  assert.match(productionResourceSummaryScript,
    /oomKilledAtBaseline[\s\S]*?oomKilledAtEnd/)
  assert.match(productionResourceSummaryScript,
    /restartDelta[\s\S]*?instanceChanged/)
  assert.doesNotMatch(productionResourceSummaryScript,
    /(?:peakCpuPercent|peakMemoryMiB|minimumAvailableMemory(?:MiB|Percent))\s*[<>]=?\s*[0-9]/)
})

test('public production artifacts retain decisions and measurements but exclude sensitive diagnostics', () => {
  const recommendationSummary = productionRecommendationWorkflow.match(
    /- name: Build public-safe journey summary([\s\S]*?)- name: Upload sanitized journey measurements/,
  )?.[1] ?? ''
  assert.notEqual(recommendationSummary, '')
  assert.match(productionRecommendationWorkflow,
    /path: \.artifacts\/production-recommendation-journey\/public-summary\.json/)
  for (const requiredField of [
    'testedSha',
    'activeReleaseSha',
    'resourceEvidence',
    'recommendationPublishedGames',
    'teaching:',
    'lessonReadable',
    'answerCitationCount',
    'followUpCitationCount',
  ]) {
    assert.match(recommendationSummary, new RegExp(requiredField))
  }
  assert.doesNotMatch(recommendationSummary,
    /recommendationConversationId|modelAssignments|sourceUrl|lessonDockText|teachingPlanId|answerSessionId|TurnId|ErrorCode/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /api-diagnostics\.log|docker compose[^\n]*logs|Upload private/)
  assert.doesNotMatch(productionRecommendationSpec, /recommendationPublishedReply/)
  assert.doesNotMatch(productionRecommendationSpec,
    /terminalGames\.map\(entry => \(\{ \.\.\.entry\.game \}\)\)/)

  assert.match(productionOrdinaryUserWorkflow, /name: Upload sanitized journey output/)
  assert.match(productionOrdinaryUserWorkflow,
    /path: \.artifacts\/production-ordinary-user-smoke\/summary\.json/)
  assert.match(productionOrdinaryUserWorkflow,
    /--validate-public-status "\$public_status" "\$smoke_exit"/)
  assert.match(productionOrdinaryUserWorkflow, /exit "\$smoke_exit"/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /service-diagnostics\.log|docker compose[^\n]*logs|Upload private/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /path:.*(?:diagnostics\.log|result\.json|success-summary\.tmp)/)
})

test('official image-gallery production smoke requires explicit rights and bounded identity', () => {
  assert.match(productionOrdinaryUserWorkflow,
    /source_mode:[\s\S]*?options:\s*\n\s+- upload\s*\n\s+- official_image_gallery/)
  assert.match(productionOrdinaryUserWorkflow,
    /rights_confirmed:[\s\S]*?default: false[\s\S]*?type: boolean/)
  assert.match(productionOrdinaryUserWorkflow,
    /\[\[ "\$RULEBOOK_RIGHTS_CONFIRMED" == true \]\][\s\S]*?--rights-confirmed/)
  assert.match(productionOrdinaryUserWorkflow,
    /--source-mode official_image_gallery[\s\S]*?--bgg-id "\$RULEBOOK_BGG_ID"[\s\S]*?--expected-page-count "\$RULEBOOK_EXPECTED_PAGE_COUNT"/)
})

test('deployment keeps protected BGG credentials out of packages and command arguments', () => {
  assert.match(deploymentWorkflow, /BGG_API_TOKEN: \$\{\{ secrets\.BGG_API_TOKEN \}\}/)
  assert.match(deploymentWorkflow, /--exclude=\.env/)
  assert.match(deploymentWorkflow, /--exclude='\.env\.\*'/)
  assert.match(deploymentWorkflow, /printf '%s' "\$BGG_API_TOKEN" > "\$local_token_file"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$BGG_API_TOKEN"/)
  assert.doesNotMatch(deploymentWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$BGG_API_TOKEN"/)
})

test('deployment availability is deterministic and does not invoke a paid Agent', () => {
  assert.match(deploymentWorkflow, /name: Verify public release availability/)
  assert.match(deploymentWorkflow, /node scripts\/verify-production-availability\.mjs/)
  assert.match(productionAvailabilityScript, /\/api\/v1\/bgg\/recommendations/)
  assert.match(productionAvailabilityScript,
    /\/api\/v1\/bgg\/games\/\$\{firstGame\.bggId\}\?locale=zh-CN/)
  assert.doesNotMatch(deploymentWorkflow, /recommendation-agent\/stream/)
  assert.doesNotMatch(deploymentWorkflow,
    /RULEPILOT_VERIFY_USERNAME|RULEPILOT_VERIFY_PASSWORD/)
  assert.doesNotMatch(productionAvailabilityScript,
    /recommendation-agent|authorization|paid model/i)
})

test('deployment failure diagnostics expose service state without logs, secrets, or environment files', () => {
  const deploymentCommands = deploymentWorkflow.replace(/\\\r?\n\s*/g, ' ')
  assert.match(deploymentWorkflow,
    /name: Collect production service status after a failed verification/)
  assert.match(deploymentWorkflow, /ps api worker frontend gateway/)
  assert.match(deploymentWorkflow, /status=\{\{\.State\.Status\}\}/)
  assert.match(deploymentWorkflow, /exit=\{\{\.State\.ExitCode\}\}/)
  assert.doesNotMatch(deploymentCommands, /\bdocker\b[^\n]*\blogs\b/)
  assert.doesNotMatch(deploymentWorkflow, /\{\{json \.State\}\}/)
  assert.doesNotMatch(deploymentWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
})

test('deployment cleanup cannot reclaim the active release or durable volumes', () => {
  assert.match(deploymentWorkflow,
    /current_release=\$\(readlink -f "\$\{application_root\}\/current"/)
  assert.match(deploymentWorkflow,
    /\[\[ "\$candidate_path" == "\$current_release" \]\] && continue/)
  assert.match(deploymentWorkflow,
    /\[\[ "\$candidate_path" == "\$release_dir" \]\] && continue/)
  assert.match(deploymentWorkflow, /"\$candidate_path" != "\$\{releases_root\}\/"\*/)
  assert.doesNotMatch(deploymentWorkflow, /docker builder prune --all/)
  assert.doesNotMatch(deploymentWorkflow, /docker image prune --all/)
  assert.doesNotMatch(deploymentWorkflow, /docker volume (?:prune|rm)/)
})

test('deployment retains prior hashed frontend assets for open tabs', () => {
  assert.match(deploymentWorkflow, /for release_distance in 1 2/)
  assert.match(deploymentWorkflow, /git -C \.\. archive "\$previous_ref" frontend/)
  assert.match(deploymentWorkflow, /bash \.\.\/scripts\/retain-frontend-release-assets\.sh/)
  assert.match(deploymentWorkflow, /"\$previous_root\/frontend\/dist\/assets"/)
  assert.doesNotMatch(deploymentWorkflow,
    /cp -[A-Za-z]*f[^\n]*previous.*frontend/i)
})

test('deployment activates one immutable backend image for both API and worker', () => {
  assert.match(deploymentWorkflow, /name: Prepare immutable release identity/)
  assert.match(deploymentWorkflow,
    /DEPLOY_RELEASE_ID=\$\(git rev-parse HEAD\)-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}/)
  assert.match(deploymentWorkflow, /name: Build immutable backend runtime image/)
  assert.match(deploymentWorkflow, /docker save "\$backend_image" \| gzip -1/)
  assert.match(deploymentWorkflow,
    /sha256sum "\$backend_image_archive" > "\$\{backend_image_archive\}\.sha256"/)
  assert.match(deploymentWorkflow, /gzip -dc "\$backend_image_archive" \| docker load/)
  assert.match(deploymentWorkflow, /RULEPILOT_PREBUILT_BACKEND_IMAGE=true \\/)
  assert.match(deploymentCompose,
    /worker:[\s\S]*?healthcheck:[\s\S]*?rulepilot-worker-ready/)
  assert.equal(
    productionCompose.match(/image: \$\{RULEPILOT_BACKEND_IMAGE:-rulepilot-backend:local\}/g)?.length,
    2,
  )
})
