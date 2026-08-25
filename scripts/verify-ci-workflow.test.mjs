import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

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
const productionScript = await readFile(new URL('./run-production.sh', import.meta.url), 'utf8')
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

test('production recommendation journey tests the deployed main release without exposing its player credential', () => {
  assert.match(productionRecommendationWorkflow, /uses:\s*actions\/checkout@v6[\s\S]*?ref:\s*main/)
  assert.match(productionRecommendationWorkflow, /environment:\s*\n\s+name:\s*production/)
  assert.match(productionRecommendationWorkflow, /expected_sha=\$\(git rev-parse HEAD\)/)
  assert.match(productionRecommendationWorkflow, /"\$expected_sha"-\*\)/)
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
  assert.match(productionRecommendationWorkflow, /require_fresh_import:[\s\S]*?type: boolean[\s\S]*?default: false/)
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
  assert.match(productionRecommendationSpec, /toContain\(report\.openGuidanceOutcome\)/)
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
    /PRODUCTION_TRACING_OTLP_ENDPOINT is required when production OTLP tracing is enabled\./)
  const endpointOverride = /OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: \$\{PRODUCTION_TRACING_OTLP_ENDPOINT:-http:\/\/tempo:4318\/v1\/traces\}/g
  assert.equal([...productionCompose.matchAll(endpointOverride)].length, 2)
})

test('failed production recommendation journeys retain bounded API diagnostics without reading environment values', () => {
  assert.match(productionRecommendationWorkflow, /name: Collect bounded API diagnostics after a failed journey/)
  assert.match(productionRecommendationWorkflow, /if: failure\(\)/)
  assert.match(productionRecommendationWorkflow, /api-diagnostics\.log/)
  assert.match(productionRecommendationWorkflow, /logs --since 35m --tail 500 --no-color api/)
  assert.match(productionRecommendationWorkflow, /logs --since 35m --tail 500 --no-color worker/)
  assert.match(productionRecommendationWorkflow, /Refusing to inspect an active release outside/)
  assert.doesNotMatch(productionRecommendationWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
})

test('failed ordinary-user production journeys retain bounded API and worker diagnostics', () => {
  assert.match(productionOrdinaryUserWorkflow, /name: Collect bounded service diagnostics after a failed journey/)
  assert.match(productionOrdinaryUserWorkflow, /if: failure\(\)/)
  assert.match(productionOrdinaryUserWorkflow, /service-diagnostics\.log/)
  assert.match(productionOrdinaryUserWorkflow, /logs --since 35m --tail 500 --no-color api/)
  assert.match(productionOrdinaryUserWorkflow, /logs --since 35m --tail 500 --no-color worker/)
  assert.match(productionOrdinaryUserWorkflow, /Refusing to inspect an active release outside/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
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

test('production deployment assigns every player-facing Agent role to a configured vision-capable runtime', () => {
  assert.match(deploymentWorkflow, /'TEACHING_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'TEACHING_MODEL_PROVIDER=deepseek'/)
  assert.match(deploymentWorkflow, /'VISUAL_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'VISUAL_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'ANSWER_PROVIDER=spring-ai'/)
  assert.match(deploymentWorkflow, /'ANSWER_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'QWEN_VISION_CAPABLE=true'/)
  assert.match(deploymentWorkflow, /qwen_enabled.*qwen_key_present/s)
  assert.match(productionRecommendationSpec, /configuredProductionRole\(modelConfiguration, 'recommendation'\)/)
  assert.match(productionRecommendationSpec, /configuredProductionRole\(modelConfiguration, 'teaching'\)/)
  assert.match(productionRecommendationSpec, /configuredProductionRole\(modelConfiguration, 'visual'\)/)
  assert.match(productionRecommendationSpec, /configuredProductionRole\(modelConfiguration, 'answer'\)/)
  assert.match(productionRecommendationSpec, /visualProvider\.visionCapable/)
})

test('production deployment replaces stale recommendation sampling overrides with the verified deterministic value', () => {
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']*\bBGG_RECOMMENDATION_TEMPERATURE\b[^']*'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_TEMPERATURE=0\.0'/)
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

test('production deployment captures bounded API diagnostics without reading protected environment values', () => {
  assert.match(deploymentWorkflow, /name: Collect bounded production diagnostics after a failed verification/)
  assert.match(deploymentWorkflow, /if: failure\(\)/)
  assert.match(deploymentWorkflow, /logs --since 10m --tail 250 --no-color api/)
  assert.match(deploymentWorkflow, /Refusing to inspect an active release outside/)
  assert.doesNotMatch(deploymentWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
})

test('production deployment reclaims only inactive releases and restores current services on failure', () => {
  assert.match(deploymentWorkflow, /current_release=\$\(readlink -f "\$\{application_root\}\/current"/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$current_release" \]\] && continue/)
  assert.match(deploymentWorkflow, /\[\[ "\$candidate_path" == "\$release_dir" \]\] && continue/)
  assert.match(deploymentWorkflow, /"\$candidate_path" != "\$\{releases_root\}\/"\*/)
  assert.match(deploymentWorkflow, /rm -rf -- "\$candidate_path"/)
  assert.match(deploymentWorkflow, /Restoring API and worker from the current release after failed activation/)
  assert.match(deploymentWorkflow, /\.yml up -d --no-build api worker/)
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
  assert.equal(
    productionCompose.match(/image: \$\{RULEPILOT_BACKEND_IMAGE:-rulepilot-backend:local\}/g)?.length,
    2,
  )
  assert.match(productionScript, /RULEPILOT_PREBUILT_BACKEND_IMAGE:-false/)
  assert.match(productionScript, /compose up -d --no-build --no-deps api/)
})
