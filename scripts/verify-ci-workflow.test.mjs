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
const makefile = await readFile(new URL('../Makefile', import.meta.url), 'utf8')
const applicationConfig = await readFile(new URL('../backend/src/main/resources/application.yml', import.meta.url), 'utf8')
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
const productionOrdinaryUserSmokeScript = await readFile(
  new URL('./smoke-production-ordinary-user.sh', import.meta.url),
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

test('E2E CI uses a Node 24 artifact action and produces the uploaded HTML report', () => {
  assert.match(ciWorkflow, /uses:\s*actions\/upload-artifact@v(?:6|7)\b/)
  assert.match(ciWorkflow, /path:\s*frontend\/playwright-report\//)
  assert.match(ciWorkflow, /if-no-files-found:\s*error/)
  assert.match(playwrightConfig, /\['html',\s*\{\s*outputFolder:\s*'playwright-report',\s*open:\s*'never'\s*\}\]/)
})

test('E2E CI cannot regress to the deprecated Node 20 artifact action', () => {
  assert.doesNotMatch(ciWorkflow, /uses:\s*actions\/upload-artifact@v[1-5]\b/)
})

test('backend CI builds the exact native runtime image before production deployment', () => {
  assert.match(ciWorkflow, /make backend-test[\s\S]*?make backend-runtime-image-smoke/)
  assert.match(makefile,
    /backend-runtime-image-smoke:[\s\S]*?docker build --file backend\/Dockerfile\.runtime/)
  assert.match(deploymentWorkflow, /docker build[\s\S]*?--file backend\/Dockerfile\.runtime/)
})

test('production recommendation journey tests one exact deployed main-ancestry release without exposing its player credential', () => {
  assert.match(productionRecommendationWorkflow,
    /tested_sha:[\s\S]*?required: true[\s\S]*?type: string/)
  assert.match(productionRecommendationWorkflow,
    /uses:\s*actions\/checkout@v6[\s\S]*?ref:\s*\$\{\{ inputs\.tested_sha \}\}[\s\S]*?fetch-depth:\s*0/)
  assert.match(productionRecommendationWorkflow, /environment:\s*\n\s+name:\s*production/)
  assert.match(productionRecommendationWorkflow, /tested_sha=\$\(git rev-parse HEAD\)/)
  assert.match(productionRecommendationWorkflow,
    /git fetch --no-tags origin '\+refs\/heads\/main:refs\/remotes\/origin\/main'/)
  assert.match(productionRecommendationWorkflow, /git merge-base --is-ancestor "\$tested_sha" origin\/main/)
  assert.match(productionRecommendationWorkflow,
    /"\$\{active_release%\/\*\}" != "\$releases_root"/)
  assert.match(productionRecommendationWorkflow,
    /"\$active_release_id" =~ \^\$\{tested_sha\}-\[0-9\]\+\$/)
  assert.match(productionRecommendationWorkflow, /"\$active_release_sha" != "\$tested_sha"/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_username/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_password/)
  assert.match(productionRecommendationWorkflow, /RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY=true/)
  assert.match(productionRecommendationWorkflow, /opening_prompt:[\s\S]*?还没想清楚换什么方向/)
  assert.match(productionRecommendationWorkflow, /selection_prompt:[\s\S]*?你直接挑三款/)
  assert.match(productionRecommendationWorkflow, /rule_follow_up:[\s\S]*?同一本规则书/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_OPENING_PROMPT: \$\{\{ inputs\.opening_prompt \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_SELECTION_PROMPT: \$\{\{ inputs\.selection_prompt \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_RULE_FOLLOW_UP: \$\{\{ inputs\.rule_follow_up \}\}/)
  assert.doesNotMatch(productionRecommendationWorkflow, /target_bgg_id|target_names|230802|花砖物语|Azul/)
  assert.match(productionRecommendationWorkflow,
    /journey_mode:[\s\S]*?type: choice[\s\S]*?default: ready_public[\s\S]*?- ready_public[\s\S]*?- verified_import/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_JOURNEY_MODE: \$\{\{ inputs\.journey_mode \}\}/)
  assert.match(productionRecommendationWorkflow, /require_fresh_import:[\s\S]*?type: boolean[\s\S]*?default: false/)
  assert.match(productionRecommendationWorkflow, /recommendation_only:[\s\S]*?type: boolean[\s\S]*?default: false/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_ONLY: \$\{\{ inputs\.recommendation_only \}\}/)
  assert.match(productionRecommendationWorkflow,
    /expected_title_term:[\s\S]*?type: string[\s\S]*?default: ''/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM: \$\{\{ inputs\.expected_title_term \}\}/)
  assert.match(productionRecommendationWorkflow, /playwright\.recommendation-production\.config\.ts/)
  assert.match(productionRecommendationConfig, /testMatch:\s*'production-recommendation-journey\.spec\.ts'/)
  assert.match(productionRecommendationConfig, /trace:\s*'off'/)
  assert.match(productionRecommendationConfig, /screenshot:\s*'off'/)
  assert.match(productionRecommendationSpec, /chooseRecommendationRecovery\([\s\S]*?candidateResult\.candidates/)
  assert.match(productionRecommendationSpec, /candidate\.officialDomainVerified === true/)
  assert.match(productionRecommendationSpec, /candidate\.languageVerified === true/)
  assert.match(productionRecommendationSpec, /DOCUMENT_RESPONSE_CONFIRMED/)
  assert.match(productionRecommendationSpec, /ORDERED_PAGE_SEQUENCE_CONFIRMED/)
  assert.match(productionRecommendationSpec, /bggIdFromBindingPath\(new URL\(bindingResponse\.url\(\)\)\.pathname\)/)
  assert.match(productionRecommendationSpec, /report\.selectedGameName = selectedGameName/)
  assert.match(productionRecommendationSpec, /getAttribute\('data-bgg-id'\)/)
  assert.match(productionRecommendationSpec, /report\.selectedBggId = selectedCardBggId/)
  assert.match(productionRecommendationSpec, /toBe\(attemptedBggId\)/)
  assert.match(productionRecommendationSpec, /report\.attemptedBggIds\.push\(attemptedBggId\)/)
  assert.match(productionRecommendationSpec, /report\.selectedRecommendationRank = selectedRecommendationRank/)
  assert.match(productionRecommendationSpec, /report\.recommendationRecoveryOutcomes\.push\(/)
  assert.match(productionRecommendationSpec, /candidateResult\.configured \? candidateResult\.candidates : \[\]/)
  assert.match(productionRecommendationSpec, /REUSED_EXISTING_JOURNEY/)
  assert.match(productionRecommendationSpec, /SELECTED_VERIFIED_OFFICIAL_SOURCE/)
  assert.match(productionRecommendationSpec, /SKIPPED_NO_VERIFIED_OFFICIAL_SOURCE/)
  assert.match(productionRecommendationSpec, /Math\.min\(report\.recommendationCardCount, 3\)/)
  assert.match(productionRecommendationSpec, /for \(let cardIndex = 0; cardIndex < recommendationAttemptLimit/)
  assert.match(productionRecommendationSpec, /getByRole\('button', \{ name: '关闭小窗', exact: true \}\)\.click\(\)/)
  assert.match(productionRecommendationSpec, /None of the three Agent-ranked recommendations/)
  assert.match(productionRecommendationSpec,
    /\[data-testid="player-journey-continuation"\]\[data-bgg-id="\$\{boundGame\.bggId\}"\]/)
  assert.match(productionRecommendationSpec,
    /expect\(selectedJourneyContinuation\)\.toHaveCount\(1\)/)
  assert.match(productionRecommendationSpec,
    /expect\(selectedJourneyContinuation\)\.toBeVisible\(\)/)
  assert.match(productionRecommendationSpec,
    /selectedJourneyContinuation\.getByTestId\('player-journey-progress-button'\)/)
  assert.match(productionRecommendationSpec,
    /selectedJourneyContinuation\.getByTestId\('player-journey-dock'\)/)
  assert.doesNotMatch(productionRecommendationSpec,
    /page\.getByTestId\('player-journey-(?:progress-button|dock)'\)/)
  assert.doesNotMatch(productionRecommendationSpec, /TARGET_BGG_ID|TARGET_NAME|gstoneCandidate|gstonegames\.com/)
  assert.match(productionRecommendationSpec, /report\.importReused = launchedJob\.reused/)
  assert.match(productionRecommendationSpec, /report\.pdfDownloadToTeachingStartMs = Math\.max/)
  assert.match(productionRecommendationSpec, /report\.pdfDownloadToFirstCitedLessonMs = Math\.max/)
  assert.match(productionRecommendationSpec, /MAX_OPEN_GUIDANCE_MS = 15_000/)
  assert.match(productionRecommendationSpec, /MAX_SELECTION_RECOMMENDATION_MS = 20_000/)
  assert.match(productionRecommendationSpec, /MAX_SELECTION_TERMINAL_OBSERVATION_MS = 35_000/)
  assert.match(productionRecommendationSpec, /toContain\(report\.openGuidanceOutcome\)/)
  assert.match(productionRecommendationSpec, /RULEPILOT_RECOMMENDATION_ONLY === 'true'/)
  assert.match(productionRecommendationSpec,
    /type RecommendationJourneyMode = 'ready_public' \| 'verified_import'/)
  assert.match(productionRecommendationSpec,
    /const REQUIRE_READY_TEACHING = JOURNEY_MODE === 'ready_public'/)
  assert.match(productionRecommendationSpec, /READY_PUBLIC_TEACHING_NO_IMPORT/)
  assert.match(productionRecommendationSpec, /VERIFIED_RULEBOOK_JOURNEY_IMPORT_OR_REUSE/)
  assert.match(productionRecommendationSpec, /READY_TEACHING_NO_CANDIDATE/)
  assert.match(productionRecommendationSpec, /READY_TEACHING_AVAILABILITY_UNAVAILABLE/)
  assert.match(productionRecommendationWorkflow,
    /require_fresh_import is incompatible with journey_mode=ready_public/)
  assert.match(productionRecommendationSpec, /waitForPersistedRecommendationTerminal\(/)
  assert.match(productionRecommendationSpec, /selectionBaselineRevision = guidanceSession\.revision/)
  assert.match(productionRecommendationSpec, /latestResponse\.clientTurnId !== expectedClientTurnId/)
  assert.match(productionRecommendationSpec, /everyPublishedGameMatchesTitleTerm\(/)
  assert.match(productionRecommendationSpec, /recommendationPublishedGames/)
  assert.match(productionRecommendationSpec, /recommendationModelCalls/)
  assert.match(productionRecommendationSpec, /recommendationFailureBoundary/)
  assert.match(productionRecommendationSpec, /rawModelOutputCaptured: false/)
  assert.match(productionRecommendationSpec, /expectReadableCitationImage\(/)
  assert.match(productionRecommendationSpec, /response\.headers\(\)\['content-type'\]/)
  assert.match(productionRecommendationSpec, /response\.body\(\)/)
  assert.match(productionRecommendationSpec,
    /for \(const \[index, candidate\] of readyGames\.entries\(\)\)/)
  assert.match(productionRecommendationSpec, /renderedRecommendationAnswerTurnCount\(answerWorkspace\)/)
  assert.match(productionRecommendationSpec, /persistedFollowUp!\.answer/)
  assert.match(productionRecommendationSpec,
    /toBe\('RECOMMENDATIONS_WITHIN_INTERACTION_BUDGET'\)/)
  assert.match(productionRecommendationSpec, /finalResult\?\.outcome[\s\S]*?toBe\('recommendations'\)/)
  assert.match(productionRecommendationSpec, /recommendationCardCount\)\.toBeGreaterThanOrEqual\(2\)/)
  assert.match(productionRecommendationSpec, /positiveDistinctBggIds\(terminalGames\)/)
  assert.match(productionRecommendationSpec, /recommendationOutcome[\s\S]*?not\.toBe\('unavailable'\)/)
  assert.match(productionRecommendationSpec, /RECOMMENDATION_ONLY_NO_RULEBOOK_IMPORT/)
  assert.match(productionRecommendationSpec, /PERSISTED_FINAL_SESSION/)
  assert.match(productionRecommendationSpec,
    /Recommendation-only verification must not start a rulebook import[\s\S]*?toBe\(0\)/)
  assert.match(
    productionRecommendationSpec,
    /expect\(recommendationCards\)\.toHaveCount\(3, \{ timeout: MAX_SELECTION_RECOMMENDATION_MS \}\)/,
  )
  assert.match(
    productionRecommendationSpec,
    /report\.recommendationMs[\s\S]*?toBeLessThanOrEqual\(MAX_SELECTION_RECOMMENDATION_MS\)/,
  )
  assert.match(productionRecommendationSpec, /section\.evidenceStatus === 'SUPPORTED' \|\| section\.evidenceStatus === 'CITED_DRAFT'/)
  assert.match(
    productionRecommendationSpec,
    /if \(REQUIRE_FRESH_IMPORT\) \{\s*expect\(launchedJob\.reused[^\n]*\)\.toBe\(false\)/,
  )
  assert.doesNotMatch(productionRecommendationSpec, /const REQUIRE_FRESH_IMPORT\s*=\s*true/)
  assert.match(
    productionRecommendationSpec,
    /expect\(importRequestCount\)\.toBe\(restoredExistingJourney \? 0 : 1\)/,
  )
  assert.match(productionRecommendationSpec, /expect\(completedJob\.documentVersionId\)\.not\.toBeNull\(\)/)
  assert.match(productionRecommendationSpec, /receivedPlan\.documentVersionId[\s\S]*?toBe\(versionId\)/)
  assert.match(productionRecommendationSpec, /lesson\.teachingPlanId[\s\S]*?toBe\(plan\.id\)/)
  assert.match(productionRecommendationSpec, /plans\.find\(plan => plan\.id === firstCitedLesson\.planId\)/)
  assert.match(productionRecommendationSpec, /expect\(progressPayload\)\.toMatchObject\(\{ stage: 'READY', complete: true \}\)/)
  assert.match(productionRecommendationSpec, /const openRulebook = page\.getByRole\('button', \{ name: '先阅读原规则书' \}\)/)
  assert.match(productionRecommendationSpec, /await openRulebook\.click\(\)/)
  assert.match(productionRecommendationSpec, /RULEPILOT_RECOMMENDATION_RULE_QUESTION/)
  assert.match(productionRecommendationSpec, /标出规则书页码/)
  assert.match(productionRecommendationSpec, /toContain\(report\.answerStatus\)/)
  assert.match(productionRecommendationSpec, /expect\(report\.answerCitationCount\)\.toBeGreaterThan\(0\)/)
  assert.match(productionRecommendationSpec, /RULEPILOT_RECOMMENDATION_RULE_FOLLOW_UP/)
  assert.match(productionRecommendationSpec, /gameSessionId: answerSessionId/)
  assert.match(productionRecommendationSpec, /editionId: boundGame\.edition\.id/)
  assert.match(productionRecommendationSpec, /previousQuestion: RULE_QUESTION/)
  assert.match(productionRecommendationSpec, /documentVersionId: completedJob\.documentVersionId/)
  assert.match(productionRecommendationSpec, /report\.firstAnswerTurnId = persistedAnswer!\.id/)
  assert.match(productionRecommendationSpec, /report\.followUpAnswerTurnId = persistedFollowUp!\.id/)
  assert.match(productionRecommendationSpec, /expect\(report\.answerSessionPreserved[\s\S]*?\)\.toBe\(true\)/)
  assert.match(productionRecommendationSpec, /expect\(report\.followUpCitationCount\)\.toBeGreaterThan\(0\)/)
  assert.match(productionRecommendationSpec, /name: '继续推荐'/)
  assert.match(productionRecommendationSpec, /name: '规则答疑'/)
  assert.doesNotMatch(productionRecommendationWorkflow, /echo "\$player_password"/)
  assert.doesNotMatch(productionRecommendationWorkflow, /'bash -s' -- "\$DEPLOY_PATH" "\$player_password"/)
})

test('deployments and production journeys share one non-cancelling runtime lock', () => {
  for (const workflow of [
    deploymentWorkflow,
    productionRecommendationWorkflow,
    productionOrdinaryUserWorkflow,
    productionRealRulebookWorkflow,
    publicLessonCandidateWorkflow,
  ]) {
    assert.match(workflow, /concurrency:\s*\n\s+group: production-runtime\s*\n\s+cancel-in-progress: false/)
  }
})

test('production JVM ergonomics use the capacity of the two-core host', () => {
  const processorFlag = /-XX:ActiveProcessorCount=\$\{PRODUCTION_ACTIVE_PROCESSOR_COUNT:-2\}/g
  assert.equal([...productionCompose.matchAll(processorFlag)].length, 2)
  assert.match(productionCompose, /api:[\s\S]*?JAVA_TOOL_OPTIONS:[^\n]*-XX:\+UseG1GC/)
  assert.match(productionCompose, /worker:[\s\S]*?JAVA_TOOL_OPTIONS:[^\n]*-XX:\+UseSerialGC/)
})

test('production activation allows the measured cold boot to finish before rollback', () => {
  assert.match(productionScript, /PRODUCTION_API_READY_TIMEOUT_SECONDS:-300/)
  assert.match(productionScript, /Production API is ready after %s second\(s\)\./)
  assert.match(productionScript, /Production API did not become ready within %s second\(s\)\./)
  assert.doesNotMatch(productionScript, /while \[ "\$attempt" -le 36 \]/)
})

test('production tracing opt-in requires an explicit reachable collector endpoint', () => {
  assert.match(productionScript, /PRODUCTION_TRACING_EXPORT_OTLP_ENABLED:-false/)
  assert.match(productionScript,
    /read_managed_production_setting\(\)[\s\S]*?PRODUCTION_TRACING_EXPORT_OTLP_ENABLED\|PRODUCTION_TRACING_OTLP_ENDPOINT\|PRODUCTION_TRACING_SAMPLING_PROBABILITY\|TEMPO_PORT/)
  assert.match(productionScript,
    /managed_value=\$\(read_managed_production_setting PRODUCTION_TRACING_EXPORT_OTLP_ENABLED\)/)
  assert.doesNotMatch(productionScript, /^\s*(?:source|\.)\s+[^\n]*\.env/m)
  assert.match(productionScript,
    /PRODUCTION_TRACING_OTLP_ENDPOINT is required when production OTLP tracing is enabled\./)
  assert.match(productionScript,
    /compose stop prometheus grafana[\s\S]*?compose up -d --no-deps tempo[\s\S]*?wait_for_tempo/)
  assert.doesNotMatch(productionScript, /compose up[^\n]*--profile observability/)
  assert.match(productionScript, /down\)[\s\S]*?compose --profile observability down/)
  assert.match(productionScript, /Production Tempo must use the fixed loopback port 3200\./)
  assert.match(makefile, /verify:[\s\S]*?sh scripts\/run-production\.sh config/)
  assert.match(makefile,
    /verify:[\s\S]*?node --test scripts\/verify-production-availability\.test\.mjs/)
  const endpointOverride = /OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: \$\{PRODUCTION_TRACING_OTLP_ENDPOINT:-http:\/\/tempo:4318\/v1\/traces\}/g
  assert.equal([...productionCompose.matchAll(endpointOverride)].length, 2)
  assert.match(deploymentWorkflow, /'PRODUCTION_TRACING_EXPORT_OTLP_ENABLED=true'/)
  assert.match(deploymentWorkflow, /'PRODUCTION_TRACING_OTLP_ENDPOINT=http:\/\/tempo:4318\/v1\/traces'/)
  assert.match(deploymentWorkflow, /'PRODUCTION_TRACING_SAMPLING_PROBABILITY=0\.05'/)
  assert.doesNotMatch(deploymentWorkflow, /'PRODUCTION_TRACING_SAMPLING_PROBABILITY=1\.0'/)
  assert.match(productionCompose, /tempo:[\s\S]*?GOMEMLIMIT: \$\{PRODUCTION_TEMPO_GOMEMLIMIT:-192MiB\}/)
  assert.match(productionCompose, /tempo:[\s\S]*?mem_limit: 320m/)
  assert.match(productionCompose,
    /tempo:[\s\S]*?ports: !override\s*\n\s+- "127\.0\.0\.1:3200:3200"/)
  assert.equal([...productionCompose.matchAll(
    /MANAGEMENT_TRACING_SAMPLING_PROBABILITY: \$\{PRODUCTION_TRACING_SAMPLING_PROBABILITY:-0\.05\}/g,
  )].length, 2)
  assert.equal([...productionCompose.matchAll(/MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_MAX_QUEUE_SIZE: "512"/g)].length, 2)
  assert.equal([...productionCompose.matchAll(/MANAGEMENT_OPENTELEMETRY_TRACING_LIMITS_MAX_EVENTS: "0"/g)].length, 2)
  assert.match(applicationConfig, /service\.version: \$\{RULEPILOT_RELEASE_ID:local\}/)
  assert.match(applicationConfig,
    /deployment\.environment\.name: \$\{RULEPILOT_DEPLOYMENT_ENVIRONMENT:development\}/)
})

test('deployment and production journey query sanitized Tempo evidence for the exact release', () => {
  assert.match(deploymentWorkflow,
    /DEPLOY_TRACE_START_EPOCH_SECONDS=%s\\n' "\$\(date \+%s\)"/)
  assert.match(productionAvailabilityScript, /\/api\/auth\/csrf/)
  assert.match(productionAvailabilityScript, /\/api\/v1\/bgg\/recommendations/)
  assert.match(productionAvailabilityScript, /\/api\/v1\/bgg\/games\/\$\{firstGame\.bggId\}/)
  assert.ok(deploymentWorkflow.indexOf('name: Verify public release availability')
    < deploymentWorkflow.indexOf('name: Collect exact-release trace diagnostics'))
  assert.match(deploymentWorkflow,
    /name: Collect exact-release trace diagnostics\n\s+if: always\(\)\n\s+continue-on-error: true/)
  assert.match(deploymentWorkflow,
    /summarize-production-release-traces\.mjs[\s\S]*?--release-id "\$DEPLOY_RELEASE_ID"[\s\S]*?--trace-id "\$DEPLOY_CANARY_TRACE_ID"/)
  assert.doesNotMatch(deploymentWorkflow, /--require-release-trace|--require-workflow-terminal/)
  assert.match(deploymentWorkflow, /name: Upload sanitized deployment trace diagnostics/)
  assert.match(deploymentWorkflow,
    /name: Upload sanitized deployment trace diagnostics[\s\S]*?if-no-files-found: warn/)
  assert.match(deploymentWorkflow, /RULEPILOT_CANARY_TRACEPARENT=00-%s-%s-01/)
  assert.match(productionAvailabilityScript, /headers: \{ traceparent \}/)
  assert.match(productionAvailabilityScript,
    /RULEPILOT_CANARY_TRACEPARENT is required for exact-release verification/)

  assert.match(productionRecommendationWorkflow, /id: production_journey/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID=%s/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_TRACE_START_EPOCH_SECONDS=%s/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_TRACE_ID=%s/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_TRACEPARENT="\$canary_traceparent"/)
  assert.match(productionRecommendationConfig,
    /extraHTTPHeaders:[\s\S]*?traceparent: canaryTraceparent/)
  assert.match(productionRecommendationConfig,
    /\^00-\(\[0-9a-f\]\{32\}\)-\(\[0-9a-f\]\{16\}\)-01\$/)
  assert.match(productionRecommendationWorkflow,
    /name: Collect exact-release Tempo workflow terminals\n\s+if: always\(\)\n\s+continue-on-error: true/)
  assert.match(productionRecommendationWorkflow,
    /--release-id "\$RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID"[\s\S]*?--trace-id "\$RULEPILOT_RECOMMENDATION_TRACE_ID"/)
  assert.match(productionRecommendationWorkflow, /--wait-for-workflow-terminal/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /--require-release-trace|--require-workflow-terminal/)
  assert.match(productionRecommendationWorkflow,
    /CANARY_ASSERTION_OUTCOME: \$\{\{ steps\.production_journey\.outcome \}\}/)
  assert.match(productionRecommendationWorkflow, /canaryAssertion:/)
  assert.match(productionRecommendationWorkflow,
    /productWorkflowTerminal: \$traceEvidence\[0\]\.businessWorkflowTerminal/)
  assert.match(productionRecommendationWorkflow, /traceEvidence: \(\$traceEvidence\[0\] \| \{/)
  for (const workflow of [deploymentWorkflow, productionRecommendationWorkflow]) {
    assert.match(workflow, /tempo_ready=false[\s\S]*?tempo_ready=true[\s\S]*?NOT_AVAILABLE evidence/)
  }

  assert.match(productionTraceSummaryScript,
    /resource\.service\.version = \"\$\{releaseId\}\"/)
  assert.match(productionTraceSummaryScript, /trace:id = \"\$\{traceId\}\"/)
  assert.match(productionTraceSummaryScript, /Promise\.allSettled/)
  assert.match(productionTraceSummaryScript, /span:name = \"recommendation-react\"/)
  assert.match(productionTraceSummaryScript, /businessWorkflowTerminal:/)
  assert.match(productionTraceSummaryScript, /queryOutcome: 'NOT_AVAILABLE'/)
  assert.match(productionTraceSummaryScript, /queryFailureCause/)
  assert.match(productionRecommendationWorkflow, /queryFailureCause/)
  assert.match(productionRecommendationWorkflow, /traceId,/)
  assert.match(productionRecommendationWorkflow, /TRACE_SUMMARY_STEP_FAILED/)
  assert.doesNotMatch(productionTraceSummaryScript,
    /console\.log\([^\n]*(?:traceID|spanID|response|body)/)
})

test('post-activation failure, cancellation, or skipped public gate restores one validated release before diagnostics', () => {
  const traceContext = deploymentWorkflow.indexOf('name: Prepare exact-release canary trace context')
  const checkpoint = deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint')
  const activation = deploymentWorkflow.indexOf('name: Activate release and verify production health')
  const availability = deploymentWorkflow.indexOf('name: Verify public release availability')
  const traceDiagnostics = deploymentWorkflow.indexOf('name: Collect exact-release trace diagnostics')
  const rollback = deploymentWorkflow.indexOf(
    'name: Roll back any activated release without successful public availability',
  )
  assert.ok(traceContext >= 0)
  assert.ok(traceContext < checkpoint)
  assert.ok(checkpoint >= 0)
  assert.ok(checkpoint < activation)
  assert.ok(activation < availability)
  assert.ok(availability < rollback)
  assert.ok(rollback < traceDiagnostics)

  assert.match(deploymentWorkflow, /tar -xOf "\$archive" \.\/scripts\/production-release-guard\.sh/)
  assert.match(deploymentWorkflow, /"\$guard_script" checkpoint[\s\S]*?"\$guard_script" start/)
  assert.match(productionReleaseGuard,
    /docker tag "\$api_image" "rulepilot-backend:\$\{previous_release_id\}"/)
  assert.match(productionReleaseGuard,
    /docker tag "\$frontend_image" "rulepilot-frontend:\$\{previous_release_id\}"/)
  assert.match(productionReleaseGuard,
    /"\$api_image" == "\$worker_image"/)
  assert.match(deploymentWorkflow, /DEPLOY_PREVIOUS_RELEASE_ID=%s/)
  assert.match(deploymentWorkflow, /id: public_availability/)
  assert.match(deploymentWorkflow, /id: rollback_checkpoint/)
  assert.match(deploymentWorkflow, /id: activate_release/)
  assert.match(deploymentWorkflow,
    /always\(\) &&[\s\S]*?steps\.rollback_checkpoint\.outcome == 'success' &&[\s\S]*?steps\.activate_release\.outcome != 'skipped' &&[\s\S]*?steps\.public_availability\.outcome != 'success'/)
  assert.match(deploymentWorkflow, /exec 9>"\$\{application_root\}\/deployment\.lock"[\s\S]*?flock -x 9/)
  assert.match(deploymentWorkflow, /"\$guard_script" arm[\s\S]*?make production-up/)
  assert.match(deploymentWorkflow,
    /node scripts\/verify-production-availability\.mjs[\s\S]*?invoke_guard commit/)
  assert.match(deploymentWorkflow, /keep_guard_alive[\s\S]*?invoke_guard heartbeat/)
  assert.match(productionReleaseGuard,
    /exec 9>"\$application_root\/deployment\.lock"[\s\S]*?flock -x 9/)
  assert.match(productionReleaseGuard,
    /docker tag "\$backend_image" rulepilot-backend:local[\s\S]*?docker tag "\$frontend_image" rulepilot-frontend:local[\s\S]*?up -d --no-build --no-deps api worker frontend gateway/)
  assert.doesNotMatch(productionReleaseGuard, /make production-up/)
  assert.match(productionReleaseGuard,
    /require_running_image "\$previous_release" api[\s\S]*?require_running_image "\$previous_release" worker[\s\S]*?require_running_image "\$previous_release" frontend/)
  assert.match(productionReleaseGuard,
    /wait_for_worker "\$previous_release" "\$backend_image_id" 60[\s\S]*?atomic_write "\$state_dir\/rolled-back"/)
  assert.match(productionReleaseGuard,
    /active_release" != "\$failed_release" && "\$active_release" != "\$previous_release"/)
  assert.match(productionReleaseGuard,
    /atomic_write "\$state_dir\/committed" "\$release_id"/)
  assert.match(productionReleaseGuard,
    /now - lease_epoch >= LEASE_STALE_SECONDS[\s\S]*?rollback_if_stale "\$application_root"/)
  assert.match(productionReleaseGuard,
    /rollback_if_stale\(\)[\s\S]*?flock -x 9[\s\S]*?now - lease_epoch < LEASE_STALE_SECONDS/)
  assert.match(deploymentWorkflow,
    /recovery_trace_id[\s\S]*?RULEPILOT_CANARY_TRACEPARENT="00-\$\{recovery_trace_id\}-\$\{recovery_parent_span_id\}-01"/)
  assert.match(deploymentWorkflow,
    /queryOutcome: "NOT_RUN"[\s\S]*?queryFailureCause: "CANARY_TRACE_NOT_STARTED"/)
  assert.match(deploymentWorkflow,
    /trace_candidate[\s\S]*?retaining sanitized NOT_AVAILABLE evidence/)
  assert.match(deploymentWorkflow,
    /PUBLIC_AVAILABILITY_OUTCOME: \$\{\{ steps\.public_availability\.outcome \}\}[\s\S]*?failure[\s\S]*?trace_attempts=3/)

  const traceUpload = deploymentWorkflow.indexOf(
    'name: Upload sanitized deployment trace diagnostics',
  )
  const traceStep = deploymentWorkflow.slice(traceDiagnostics, traceUpload)
  const tempoRecovery = traceStep.indexOf(
    '--profile observability up -d --no-deps tempo',
  )
  const tempoTunnel = traceStep.indexOf('-N -L 13200:127.0.0.1:3200')
  assert.ok(tempoRecovery >= 0)
  assert.ok(tempoRecovery < tempoTunnel)
  assert.match(traceStep,
    /PUBLIC_AVAILABILITY_OUTCOME" != success[\s\S]*?failed_release=.*?\$releases_root\/\$release_id[\s\S]*?--profile observability up -d --no-deps tempo/)
  assert.doesNotMatch(traceStep, /RULEPILOT_PREBUILT_BACKEND_IMAGE=true[\s\S]*?make production-up/)
})

test('watchdog rechecks a stale lease after acquiring the activation lock', async (context) => {
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

test('production recommendation journey records exact-release single-user resource safety evidence', () => {
  const activeReleaseCheck = productionRecommendationWorkflow.indexOf(
    'Production active release SHA does not equal tested_sha',
  )
  const baselineCollection = productionRecommendationWorkflow.indexOf(
    'if collect_resource_state "$resource_baseline"',
  )
  const journeyStart = productionRecommendationWorkflow.indexOf(
    'RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY=true',
  )

  assert.ok(activeReleaseCheck >= 0)
  assert.ok(activeReleaseCheck < baselineCollection)
  assert.ok(baselineCollection < journeyStart)
  assert.match(productionRecommendationWorkflow,
    /docker ps -a --filter label=com\.docker\.compose\.project=rulepilot/)
  assert.match(productionRecommendationWorkflow,
    /\.RestartCount[\s\S]*?\.State\.OOMKilled[\s\S]*?\.State\.Running/)
  assert.match(productionRecommendationWorkflow, /MemAvailable:/)
  assert.match(productionRecommendationWorkflow, /docker stats --no-stream/)
  assert.match(productionRecommendationWorkflow,
    /sample_elapsed_seconds=.*?[\s\S]*?sleep \$\(\(5 - sample_elapsed_seconds\)\)/)
  assert.match(productionRecommendationWorkflow, /resource_sampler_pid=\$!/)
  assert.match(productionRecommendationWorkflow,
    /collect_resource_state\(\)[\s\S]*?timeout --signal=TERM --kill-after=5s 60s/)
  assert.match(productionRecommendationWorkflow,
    /stop_resource_sampler\(\)[\s\S]*?kill "\$resource_sampler_pid"[\s\S]*?kill -KILL "\$resource_sampler_pid"[\s\S]*?wait "\$resource_sampler_pid"/)
  assert.match(productionRecommendationWorkflow,
    /name: Summarize exact-release production resources\n\s+if: always\(\)/)
  assert.match(productionRecommendationWorkflow,
    /summarize-production-resource-samples\.mjs[\s\S]*?--release-id "\$RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID"[\s\S]*?--fail-on-runtime-reset/)
  assert.match(productionRecommendationWorkflow, /resourceEvidence: \$resourceEvidence\[0\]/)

  assert.match(productionResourceSummaryScript,
    /const REQUIRED_SERVICES = \['api', 'tempo', 'worker'\]/)
  assert.match(productionResourceSummaryScript,
    /summary\.safety\.evaluated && !summary\.safety\.passed/)
  assert.match(productionResourceSummaryScript,
    /oomKilledAtBaseline[\s\S]*?oomKilledAtEnd/)
  assert.match(productionResourceSummaryScript,
    /restartDelta[\s\S]*?instanceChanged/)
  assert.doesNotMatch(productionResourceSummaryScript,
    /(?:peakCpuPercent|peakMemoryMiB|minimumAvailableMemory(?:MiB|Percent))\s*[<>]=?\s*[0-9]/)
  assert.match(makefile,
    /verify:[\s\S]*?node --test scripts\/summarize-production-resource-samples\.test\.mjs/)
})

test('public production recommendation artifacts contain only sanitized journey evidence', () => {
  const publicSummaryStep = productionRecommendationWorkflow.match(
    /- name: Build public-safe journey summary([\s\S]*?)- name: Upload sanitized journey measurements/,
  )?.[1] ?? ''

  assert.notEqual(publicSummaryStep, '')
  assert.match(productionRecommendationWorkflow, /name: Upload sanitized journey measurements/)
  assert.match(productionRecommendationWorkflow,
    /path: \.artifacts\/production-recommendation-journey\/public-summary\.json/)
  assert.match(publicSummaryStep, /jq[\s\S]*?'\{/)
  assert.match(publicSummaryStep, /journeyMode/)
  assert.match(publicSummaryStep, /testedSha/)
  assert.match(publicSummaryStep, /activeReleaseSha/)
  assert.match(publicSummaryStep, /resourceEvidence/)
  assert.match(publicSummaryStep, /requireFreshImport/)
  assert.match(publicSummaryStep, /importReused/)
  assert.match(publicSummaryStep, /recommendationPublishedGames/)
  assert.doesNotMatch(publicSummaryStep,
    /recommendationConversationId|modelAssignments|sourceUrl|lessonDockText|teachingPlanId|answerSessionId|TurnId|ErrorCode/)
  assert.doesNotMatch(productionRecommendationWorkflow, /api-diagnostics\.log|docker compose[^\n]*logs|Upload private/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /path:.*(?:resource-(?:baseline|samples|final)\.tsv|resource-sampler-diagnostics\.log)/)
  assert.doesNotMatch(productionRecommendationSpec, /recommendationPublishedReply/)
  assert.match(productionRecommendationSpec,
    /recommendationPublishedGames = terminalGames\.map\(entry => \(\{[\s\S]*?bggId: entry\.game\.bggId,[\s\S]*?name: entry\.game\.name,[\s\S]*?originalName: entry\.game\.originalName,[\s\S]*?\}\)\)/)
  assert.doesNotMatch(productionRecommendationSpec, /terminalGames\.map\(entry => \(\{ \.\.\.entry\.game \}\)\)/)
  assert.match(productionRecommendationWorkflow,
    /if \[\[ "\$RULEPILOT_RECOMMENDATION_ONLY" == true[\s\S]*?recommendation_only requires a non-empty expected_title_term/)
})

test('public ordinary-user smoke artifacts exclude production service logs', () => {
  assert.match(productionOrdinaryUserWorkflow, /name: Upload sanitized journey output/)
  assert.match(productionOrdinaryUserWorkflow,
    /path: \.artifacts\/production-ordinary-user-smoke\/summary\.json/)
  assert.match(productionOrdinaryUserWorkflow, /success-summary\.tmp/)
  assert.match(productionOrdinaryUserWorkflow, /RULEPILOT_SMOKE_PUBLIC_STATUS_FILE="\$public_status"/)
  assert.match(productionOrdinaryUserWorkflow, /set \+e[\s\S]*?smoke_exit=\$\?[\s\S]*?set -e/)
  assert.match(productionOrdinaryUserWorkflow,
    /--validate-public-status "\$public_status" "\$smoke_exit"/)
  assert.match(productionOrdinaryUserWorkflow, /cleanupOutcome: "FAILED"/)
  assert.match(productionOrdinaryUserWorkflow, /pageAttempts: \(if \$source\.pageAttempts == null/)
  assert.match(productionOrdinaryUserWorkflow, /execution: \$execution\[0\]/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /\.\[0\] \+ \{execution:/)
  assert.match(productionOrdinaryUserWorkflow, /exit "\$smoke_exit"/)
  assert.match(productionOrdinaryUserWorkflow, /if-no-files-found: error/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /service-diagnostics\.log|docker compose[^\n]*logs|Upload private/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /path:.*(?:diagnostics\.log|result\.json|success-summary\.tmp)/)
  assert.match(productionOrdinaryUserSmokeScript, /last_completed_stage=not-started/)
  assert.match(productionOrdinaryUserSmokeScript, /pending_failure_code=INPUT_INVALID/)
  assert.match(productionOrdinaryUserSmokeScript, /cleanup_outcome=NOT_REQUIRED/)
  assert.match(productionOrdinaryUserSmokeScript,
    /cleanupOutcome", "exitCode", "failureCode", "lastCompletedStage", "outcome"/)
  assert.match(productionOrdinaryUserSmokeScript,
    /SUCCEEDED\|FAILED\|NOT_REQUIRED/)
  assert.match(productionOrdinaryUserSmokeScript, /pending_failure_code=TEACHING_PREPARATION_FAILED/)
  assert.match(productionOrdinaryUserSmokeScript, /pending_failure_code=ANSWER_EVIDENCE_INVALID/)
  assert.match(productionOrdinaryUserSmokeScript,
    /if \[ "\$navigation_failures" -gt 0 \]; then[\s\S]*?return 1[\s\S]*?log_stage "navigation-verified/)
})

test('ordinary-user production smoke can exercise one fresh official image gallery without uploading it', () => {
  assert.match(productionOrdinaryUserWorkflow,
    /source_mode:[\s\S]*?options:\s*\n\s+- upload\s*\n\s+- official_image_gallery/)
  assert.match(productionOrdinaryUserWorkflow,
    /if \[\[ "\$RULEBOOK_SOURCE_MODE" == official_image_gallery \]\]; then[\s\S]*?exit 0/)
  assert.match(productionOrdinaryUserWorkflow,
    /--source-mode official_image_gallery[\s\S]*?--bgg-id "\$RULEBOOK_BGG_ID"/)
  assert.match(productionOrdinaryUserWorkflow,
    /--expected-page-count "\$RULEBOOK_EXPECTED_PAGE_COUNT"[\s\S]*?--language "\$RULEBOOK_LANGUAGE"/)
  assert.match(productionOrdinaryUserWorkflow,
    /--canary-id "\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}"/)
  assert.match(productionOrdinaryUserWorkflow,
    /rights_confirmed:[\s\S]*?default: false[\s\S]*?type: boolean/)
  assert.match(productionOrdinaryUserWorkflow,
    /RULEBOOK_RIGHTS_CONFIRMED: \$\{\{ inputs\.rights_confirmed \}\}/)
  assert.match(productionOrdinaryUserWorkflow,
    /\[\[ "\$RULEBOOK_RIGHTS_CONFIRMED" == true \]\][\s\S]*?--rights-confirmed/)
  assert.match(productionOrdinaryUserWorkflow,
    /else\s*\n\s+smoke_args\+=\(--pdf "\$RUNNER_TEMP\/rulebook\.pdf"\)/)
  assert.match(productionOrdinaryUserSmokeScript,
    /canary_title="\$uploaded_title · RulePilot canary \$canary_id"/)
  assert.match(productionOrdinaryUserSmokeScript,
    /\.answer\.language == \$language/)
  assert.match(productionOrdinaryUserSmokeScript,
    /\.pageTo <= \$expected/)
  assert.match(productionOrdinaryUserSmokeScript,
    /inspectTeachingVisualRepair\|/)
  assert.match(productionOrdinaryUserSmokeScript,
    /transcribeTeachingVisualPage\|/)
})

test('production deployment synchronizes the protected BGG credential without packaging or logging it', () => {
  assert.match(deploymentWorkflow, /name: Synchronize protected BGG credential/)
  assert.match(deploymentWorkflow, /BGG_API_TOKEN: \$\{\{ secrets\.BGG_API_TOKEN \}\}/)
  assert.match(deploymentWorkflow, /--exclude=\.env/)
  assert.match(deploymentWorkflow, /--exclude='\.env\.\*'/)
  assert.match(deploymentWorkflow, /printf '%s' "\$BGG_API_TOKEN" > "\$local_token_file"/)
  assert.match(deploymentWorkflow, /mv "\$temporary_env" "\$env_file"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$BGG_API_TOKEN"/)
  assert.doesNotMatch(deploymentWorkflow, /'bash -s' -- "\$DEPLOY_PATH" "\$BGG_API_TOKEN"/)
})

test('production deployment configures every Agent role and probes only the roles required by the selected journey', () => {
  assert.match(deploymentWorkflow, /'TEACHING_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'TEACHING_MODEL_PROVIDER=deepseek'/)
  assert.match(deploymentWorkflow, /'VISUAL_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'VISUAL_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'ANSWER_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'ANSWER_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MODEL_PROVIDER=deepseek'/)
  assert.match(deploymentWorkflow, /'QWEN_VISION_CAPABLE=true'/)
  assert.match(deploymentWorkflow, /qwen_enabled.*qwen_key_present/s)
  assert.match(productionRecommendationSpec,
    /requiredProductionModelRoles\('ready_public', false\)[\s\S]*?\['recommendation', 'answer'\]/)
  assert.match(productionRecommendationSpec,
    /requiredProductionModelRoles\('verified_import', false\)[\s\S]*?\['recommendation', 'teaching', 'visual', 'answer'\]/)
  assert.match(productionRecommendationSpec,
    /for \(const role of requiredProductionModelRoles\(JOURNEY_MODE, RECOMMENDATION_ONLY\)\)/)
  assert.match(productionRecommendationSpec, /configuredProductionRole\(modelConfiguration, role\)/)
  assert.match(productionRecommendationSpec,
    /recommendationProvider\.id[\s\S]*?toBe\('deepseek'\)/)
  assert.match(productionRecommendationSpec,
    /recommendationProvider\.model[\s\S]*?toBe\('deepseek-v4-flash'\)/)
  assert.match(productionRecommendationSpec, /if \(role === 'visual'\)[\s\S]*?provider\.visionCapable/)
})

test('production deployment replaces stale recommendation sampling overrides with the verified deterministic value', () => {
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']*\bBGG_RECOMMENDATION_TEMPERATURE\b[^']*'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_TEMPERATURE=0\.0'/)
})

test('production deployment keeps the recommendation transport and run budget on the measured 45-second boundary', () => {
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']*\bBGG_RECOMMENDATION_AGENT_TIMEOUT\b[^']*'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_AGENT_TIMEOUT=PT45S'/)
  assert.match(deploymentCompose,
    /BGG_RECOMMENDATION_AGENT_TIMEOUT: \$\{BGG_RECOMMENDATION_AGENT_TIMEOUT:-PT45S\}/)
})

test('deployment forwards the local visual geometry tool kill switch and bounded process settings', () => {
  assert.match(deploymentCompose,
    /VISUAL_REGION_PROPOSAL_ENABLED: \$\{VISUAL_REGION_PROPOSAL_ENABLED:-true\}/)
  assert.match(deploymentCompose,
    /VISUAL_REGION_PROPOSAL_PYTHON_COMMAND: \$\{VISUAL_REGION_PROPOSAL_PYTHON_COMMAND:-python3\}/)
  assert.match(deploymentCompose,
    /VISUAL_REGION_PROPOSAL_TIMEOUT: \$\{VISUAL_REGION_PROPOSAL_TIMEOUT:-PT2S\}/)
  assert.match(deploymentCompose,
    /VISUAL_REGION_PROPOSAL_MAX_REGIONS_PER_PAGE: \$\{VISUAL_REGION_PROPOSAL_MAX_REGIONS_PER_PAGE:-32\}/)
})

test('production deployment enables the staged persistent Chinese catalog cache only with DeepSeek configured', () => {
  assert.match(deploymentWorkflow, /deepseek_enabled.*deepseek_key_present/s)
  assert.match(deploymentWorkflow, /'BGG_TRANSLATION_ENABLED=true'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_PREWARM_ENABLED=true'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_PREWARM_GAME_COUNT=10000'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_MAXIMUM_ENTRIES=20000'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_MAXIMUM_BYTES=268435456'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_PREWARM_COHORT_SIZE=500'/)
  assert.match(deploymentWorkflow, /'BGG_CACHE_PREWARM_TRANSLATION_COHORT_SIZE=60'/)
  assert.match(
    deploymentWorkflow,
    /Production DeepSeek configuration is required for persistent Chinese catalog translations/,
  )
})

test('production deployment uses the standalone deterministic availability verification', () => {
  assert.match(deploymentWorkflow, /name: Verify public release availability/)
  assert.match(deploymentWorkflow, /node scripts\/verify-production-availability\.mjs/)
  assert.match(productionAvailabilityScript, /\/api\/v1\/bgg\/recommendations/)
  assert.match(productionAvailabilityScript, /\/api\/v1\/bgg\/games\/\$\{firstGame\.bggId\}\?locale=zh-CN/)
  assert.match(productionAvailabilityScript, /typeof game\.descriptionTranslated !== 'boolean'/)
  assert.match(productionAvailabilityScript, /Array\.isArray\(game\.categories\)/)
  assert.match(productionAvailabilityScript, /Array\.isArray\(game\.mechanics\)/)
})

test('production deployment does not couple release availability to a stochastic paid Agent review', () => {
  assert.doesNotMatch(deploymentWorkflow, /recommendation-agent\/stream/)
  assert.doesNotMatch(deploymentWorkflow, /Load protected production verification account/)
  assert.doesNotMatch(deploymentWorkflow, /RULEPILOT_VERIFY_USERNAME|RULEPILOT_VERIFY_PASSWORD/)
  assert.doesNotMatch(deploymentWorkflow, /hasNaturalCandidateNarrative/)
  assert.doesNotMatch(deploymentWorkflow, /RECOMMENDATION_AVAILABILITY_SHORTFALL/)
  assert.doesNotMatch(deploymentWorkflow, /我想换成能谈判|今晚五个人聚会/)
  assert.doesNotMatch(productionAvailabilityScript, /recommendation-agent|authorization|paid model/i)
})

test('production deployment failure output includes service status but not application logs', () => {
  const deploymentCommands = deploymentWorkflow.replace(/\\\r?\n\s*/g, ' ')
  assert.match(deploymentWorkflow, /name: Collect production service status after a failed verification/)
  assert.match(deploymentWorkflow, /id: configure_ssh/)
  assert.match(deploymentWorkflow, /if: failure\(\) && steps\.configure_ssh\.outcome == 'success'/)
  assert.match(deploymentWorkflow, /ps api worker frontend gateway/)
  assert.match(deploymentWorkflow, /Refusing to inspect an active release outside/)
  assert.match(deploymentWorkflow, /status=\{\{\.State\.Status\}\}/)
  assert.match(deploymentWorkflow, /exit=\{\{\.State\.ExitCode\}\}/)
  assert.match(deploymentWorkflow, /health=\{\{if \.State\.Health\}\}\{\{\.State\.Health\.Status\}\}/)
  assert.doesNotMatch(deploymentCommands, /\bdocker\b[^\n]*\blogs\b/)
  assert.doesNotMatch(deploymentWorkflow, /Recent (?:API|PostgreSQL) logs/)
  assert.doesNotMatch(deploymentWorkflow, /\{\{json \.State\}\}/)
  assert.doesNotMatch(deploymentWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
})

test('production deployment reclaims only inactive releases and restores current services on failure', () => {
  assert.match(deploymentWorkflow, /current_release=\$\(readlink -f "\$\{application_root\}\/current"/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$current_release" \]\] && continue/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$release_dir" \]\] && continue/)
  assert.match(deploymentWorkflow, /"\$candidate_path" != "\$\{releases_root\}\/"\*/)
  assert.match(deploymentWorkflow, /rm -rf -- "\$candidate_path"/)
  assert.match(deploymentWorkflow, /Restoring the complete current release after failed activation/)
  assert.match(deploymentWorkflow,
    /"\$guard_script" rollback-held[\s\S]*?Restored the immutable frontend and backend checkpoint/)
  assert.match(deploymentWorkflow, /Ensuring the current release remains available while the replacement image builds/)
  assert.match(deploymentWorkflow, /ln -sfn "\$\{application_root\}\/\.env" "\$\{release_dir\}\/\.env"/)
  assert.doesNotMatch(deploymentWorkflow, /\.yml stop worker api/)
  assert.doesNotMatch(deploymentWorkflow, /docker builder prune --all/)
  assert.doesNotMatch(deploymentWorkflow, /docker image prune --all/)
  assert.match(deploymentWorkflow, /docker builder prune --force --filter "until=168h"/)
  assert.doesNotMatch(deploymentWorkflow, /docker volume (?:prune|rm)/)
})

test('production deployment keeps two prior hashed frontend asset generations for open tabs', () => {
  assert.match(deploymentWorkflow, /fetch-depth: 3/)
  assert.match(deploymentWorkflow, /for release_distance in 1 2/)
  assert.match(deploymentWorkflow, /git -C \.\. archive "\$previous_ref" frontend/)
  assert.match(deploymentWorkflow, /npm ci --prefer-offline --no-audit --no-fund/)
  assert.match(deploymentWorkflow, /bash \.\.\/scripts\/retain-frontend-release-assets\.sh/)
  assert.match(deploymentWorkflow, /"\$previous_root\/frontend\/dist\/assets"/)
  assert.doesNotMatch(deploymentWorkflow, /cp -[A-Za-z]*f[^\n]*previous.*frontend/i)
})

test('production deployment keeps long SSH activation sessions alive', () => {
  const activationStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('- name: Activate release and verify production health'),
    deploymentWorkflow.indexOf('- name: Verify public release availability'),
  )
  assert.match(activationStep, /-o ConnectTimeout=20/)
  assert.match(activationStep, /-o ServerAliveInterval=30/)
  assert.match(activationStep, /-o ServerAliveCountMax=20/)
})

test('production deploys an immutable backend image built off-host', () => {
  assert.match(deploymentWorkflow, /name: Prepare immutable release identity/)
  assert.match(deploymentWorkflow,
    /DEPLOY_RELEASE_ID=\$\(git rev-parse HEAD\)-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}/)
  assert.match(deploymentWorkflow, /name: Build immutable backend runtime image/)
  assert.match(deploymentWorkflow, /docker build \\/)
  assert.match(deploymentWorkflow, /--tag "\$backend_image"/)
  assert.match(deploymentWorkflow, /docker save "\$backend_image" \| gzip -1/)
  assert.match(deploymentWorkflow, /sha256sum "\$backend_image_archive" > "\$\{backend_image_archive\}\.sha256"/)
  assert.match(deploymentWorkflow, /name: Upload immutable backend runtime image/)
  assert.match(deploymentWorkflow, /gzip -dc "\$backend_image_archive" \| docker load/)
  assert.match(deploymentWorkflow, /docker image inspect "\$backend_image" >\/dev\/null/)
  assert.match(deploymentWorkflow, /RULEPILOT_BACKEND_IMAGE="\$backend_image" \\/)
  assert.match(deploymentWorkflow, /RULEPILOT_PREBUILT_BACKEND_IMAGE=true \\/)
  assert.match(deploymentWorkflow, /docker tag "\$backend_image" rulepilot-backend:local/)
  assert.match(productionScript,
    /wait_for_worker[\s\S]*?worker_health[\s\S]*?healthy[\s\S]*?expected_worker_image/)
  assert.match(deploymentCompose, /worker:[\s\S]*?healthcheck:[\s\S]*?rulepilot-worker-ready/)
  assert.match(productionReleaseGuard,
    /require_running_image "\$active_release" worker "\$backend_image_id"[\s\S]*?worker_health[\s\S]*?healthy/)
  assert.equal(
    productionCompose.match(/image: \$\{RULEPILOT_BACKEND_IMAGE:-rulepilot-backend:local\}/g)?.length,
    2,
  )
  assert.match(productionScript, /RULEPILOT_PREBUILT_BACKEND_IMAGE:-false/)
  assert.match(productionScript, /compose up -d --no-build --no-deps api/)
})
