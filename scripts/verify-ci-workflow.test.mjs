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
  assert.match(productionRecommendationWorkflow, /target_bgg_id:[\s\S]*?default: '230802'/)
  assert.match(productionRecommendationWorkflow, /require_fresh_import:[\s\S]*?type: boolean[\s\S]*?default: false/)
  assert.match(productionRecommendationWorkflow, /playwright\.recommendation-production\.config\.ts/)
  assert.match(productionRecommendationConfig, /testMatch:\s*'production-recommendation-journey\.spec\.ts'/)
  assert.match(productionRecommendationConfig, /trace:\s*'off'/)
  assert.match(productionRecommendationConfig, /screenshot:\s*'off'/)
  assert.match(productionRecommendationSpec, /gstoneCandidate/)
  assert.match(productionRecommendationSpec, /report\.importReused = launchedJob\.reused/)
  assert.match(productionRecommendationSpec, /report\.pdfDownloadToTeachingStartMs = Math\.max/)
  assert.match(productionRecommendationSpec, /report\.pdfDownloadToFirstCitedLessonMs = Math\.max/)
  assert.match(productionRecommendationSpec, /section\.evidenceStatus === 'SUPPORTED' \|\| section\.evidenceStatus === 'CITED_DRAFT'/)
  assert.match(
    productionRecommendationSpec,
    /if \(REQUIRE_FRESH_IMPORT\) \{\s*expect\(launchedJob\.reused[^\n]*\)\.toBe\(false\)/,
  )
  assert.doesNotMatch(productionRecommendationSpec, /const REQUIRE_FRESH_IMPORT\s*=\s*true/)
  assert.match(productionRecommendationSpec, /expect\(importRequestCount\)\.toBe\(1\)/)
  assert.match(productionRecommendationSpec, /expect\(completedJob\.documentVersionId\)\.not\.toBeNull\(\)/)
  assert.match(productionRecommendationSpec, /expect\(progressPayload\)\.toMatchObject\(\{ stage: 'READY', complete: true \}\)/)
  assert.match(productionRecommendationSpec, /const openRulebook = page\.getByRole\('button', \{ name: '先阅读原规则书' \}\)/)
  assert.match(productionRecommendationSpec, /await openRulebook\.click\(\)/)
  assert.match(productionRecommendationSpec, /RULEPILOT_RECOMMENDATION_RULE_QUESTION/)
  assert.match(productionRecommendationSpec, /引用规则书页码/)
  assert.match(productionRecommendationSpec, /toContain\(report\.answerStatus\)/)
  assert.match(productionRecommendationSpec, /expect\(report\.answerCitationCount\)\.toBeGreaterThan\(0\)/)
  assert.match(productionRecommendationSpec, /name: '继续推荐'/)
  assert.match(productionRecommendationSpec, /name: '规则答疑'/)
  assert.doesNotMatch(productionRecommendationWorkflow, /echo "\$player_password"/)
  assert.doesNotMatch(productionRecommendationWorkflow, /'bash -s' -- "\$DEPLOY_PATH" "\$player_password"/)
})

test('failed production recommendation journeys retain bounded API diagnostics without reading environment values', () => {
  assert.match(productionRecommendationWorkflow, /name: Collect bounded API diagnostics after a failed journey/)
  assert.match(productionRecommendationWorkflow, /if: failure\(\)/)
  assert.match(productionRecommendationWorkflow, /api-diagnostics\.log/)
  assert.match(productionRecommendationWorkflow, /logs --since 10m --tail 125 --no-color api/)
  assert.match(productionRecommendationWorkflow, /logs --since 10m --tail 125 --no-color worker/)
  assert.match(productionRecommendationWorkflow, /Refusing to inspect an active release outside/)
  assert.doesNotMatch(productionRecommendationWorkflow, /(?:cat|sed|grep|rg) [^\n]*\.env/)
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
  assert.doesNotMatch(deploymentWorkflow, /\.yml stop worker api/)
  assert.doesNotMatch(deploymentWorkflow, /docker builder prune --all/)
  assert.doesNotMatch(deploymentWorkflow, /docker image prune --all/)
  assert.match(deploymentWorkflow, /docker builder prune --force --filter "until=168h"/)
  assert.doesNotMatch(deploymentWorkflow, /docker volume (?:prune|rm)/)
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
