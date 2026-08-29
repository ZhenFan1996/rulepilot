import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  access,
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  symlink,
  utimes,
  writeFile,
} from 'node:fs/promises'
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
const applicationConfiguration = await readFile(
  new URL('../backend/src/main/resources/application.yml', import.meta.url),
  'utf8',
)
const productionCompose = await readFile(new URL('../infra/compose.production.yml', import.meta.url), 'utf8')
const deploymentCompose = await readFile(new URL('../infra/compose.deployment.yml', import.meta.url), 'utf8')
const productionReleaseGuard = await readFile(
  new URL('./production-release-guard.sh', import.meta.url),
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
const productionReleaseGuardPath = new URL('./production-release-guard.sh', import.meta.url).pathname

function workflowRunBlock(workflow, stepName) {
  const lines = workflow.split(/\r?\n/)
  const stepIndex = lines.findIndex((line) => line.trim() === `- name: ${stepName}`)
  assert.notEqual(stepIndex, -1, `Workflow step was not found: ${stepName}`)
  const stepIndent = lines[stepIndex].search(/\S/)
  let runIndex = -1
  for (let index = stepIndex + 1; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.trim() === '') continue
    const indentation = line.search(/\S/)
    if (indentation <= stepIndent) break
    if (line.trim() === 'run: |') {
      runIndex = index
      break
    }
  }
  assert.notEqual(runIndex, -1, `Workflow run block was not found: ${stepName}`)
  const runIndent = lines[runIndex].search(/\S/)
  const content = []
  for (let index = runIndex + 1; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.trim() === '') {
      content.push('')
      continue
    }
    const indentation = line.search(/\S/)
    if (indentation <= runIndent) break
    assert.ok(indentation >= runIndent + 2, `Malformed run block indentation: ${stepName}`)
    content.push(line.slice(runIndent + 2))
  }
  return `${content.join('\n')}\n`
}

const productionRecommendationSanitizer = workflowRunBlock(
  productionRecommendationWorkflow,
  'Prove credentials are absent and rebuild the allowlisted journey report',
)
const productionRecommendationSuccessGate = workflowRunBlock(
  productionRecommendationWorkflow,
  'Require a completed production recommendation journey',
)
const productionOrdinaryUserSuccessGate = workflowRunBlock(
  productionOrdinaryUserWorkflow,
  'Require a successful ordinary-user journey',
)

function embeddedCandidateBoundaryParser() {
  const startMarker = '"$api_container" python3 -c \'\n'
  const endMarker = '\n\' < "$payload"'
  const start = productionReleaseGuard.indexOf(startMarker)
  assert.notEqual(start, -1, 'candidate publication parser start was not found')
  const bodyStart = start + startMarker.length
  const end = productionReleaseGuard.indexOf(endMarker, bodyStart)
  assert.notEqual(end, -1, 'candidate publication parser end was not found')
  return productionReleaseGuard.slice(bodyStart, end)
}

function embeddedPublicObserverPython() {
  const runBlock = workflowRunBlock(
    deploymentWorkflow,
    'Classify independent public observation without repository code or production SSH authority',
  )
  const startMarker = "python3 - <<'PY'\n"
  const endMarker = '\nPY\n'
  const start = runBlock.indexOf(startMarker)
  assert.notEqual(start, -1, 'public observer Python start was not found')
  const bodyStart = start + startMarker.length
  const end = runBlock.indexOf(endMarker, bodyStart)
  assert.notEqual(end, -1, 'public observer Python end was not found')
  return runBlock.slice(bodyStart, end)
}

function embeddedPublicObserverRetryBlock() {
  const runBlock = workflowRunBlock(
    deploymentWorkflow,
    'Classify independent public observation without repository code or production SSH authority',
  )
  const startMarker = 'verified=false\n'
  const endMarker = 'unset preflight_username preflight_password credential_lines'
  const start = runBlock.indexOf(startMarker)
  assert.notEqual(start, -1, 'public observer retry start was not found')
  const end = runBlock.indexOf(endMarker, start)
  assert.notEqual(end, -1, 'public observer retry end was not found')
  return runBlock.slice(start, end + endMarker.length)
}

async function runCandidateBoundaryParser(contract, payload, overrides = {}) {
  const source = [
    'import io',
    'import os',
    'import sys',
    'sys.stdin = io.StringIO(os.environ.pop("RULEPILOT_TEST_PAYLOAD"))',
    embeddedCandidateBoundaryParser(),
  ].join('\n')
  return execFileAsync('python3', ['-c', source], {
    env: {
      ...process.env,
      RULEPILOT_TEST_PAYLOAD: payload,
      RULEPILOT_BOUNDARY_CONTRACT: contract,
      RULEPILOT_EXPECTED_RELEASE_ID: `${'a'.repeat(40)}-101-2`,
      RULEPILOT_EXPECTED_COMMIT_SHA: 'a'.repeat(40),
      RULEPILOT_EXPECTED_PROVIDER: 'qwen',
      RULEPILOT_EXPECTED_MODEL: 'qwen3.8-flash',
      RULEPILOT_EXPECTED_BGG_ID: '1',
      ...overrides,
    },
  })
}

async function publicObserverPythonExit(scenario, root, overrides = {}) {
  const monkeyPatch = `
import http.client as _http_client
import os as _os
import ssl as _ssl
from urllib import error as _error
from urllib import request as _request

class _Headers:
    def items(self):
        return [("Cache-Control", "no-store"), ("Content-Type", "application/json")]

class _Response:
    headers = _Headers()
    def __enter__(self):
        return self
    def __exit__(self, *_args):
        return False
    def geturl(self):
        return _os.environ["RULEPILOT_HTTPS_URL"]
    def read(self, _limit):
        scenario = _os.environ["RULEPILOT_TEST_OBSERVER_SCENARIO"]
        if scenario == "incomplete-read":
            raise _http_client.IncompleteRead(b"partial", 8)
        if scenario == "ssl-eof":
            raise _ssl.SSLEOFError(8, "unexpected EOF")
        return b"{}"

class _Opener:
    def open(self, request_value, timeout=None):
        scenario = _os.environ["RULEPILOT_TEST_OBSERVER_SCENARIO"]
        if scenario.startswith("http-"):
            status = int(scenario.removeprefix("http-"))
            raise _error.HTTPError(request_value.full_url, status, "injected", {}, None)
        if scenario == "connection-reset":
            raise _error.URLError(ConnectionResetError("injected"))
        return _Response()

_request.build_opener = lambda *_handlers: _Opener()
`
  try {
    await execFileAsync('python3', ['-c', `${monkeyPatch}\n${embeddedPublicObserverPython()}`], {
      env: {
        ...process.env,
        RULEPILOT_TEST_OBSERVER_SCENARIO: scenario,
        RULEPILOT_HTTPS_URL: 'https://rulepilot.cn/api/public/release',
        RULEPILOT_HTTPS_BODY_PATH: join(root, 'observer.body'),
        RULEPILOT_HTTPS_HEADERS_PATH: join(root, 'observer.headers'),
        RULEPILOT_HTTPS_BASIC_USERNAME: '',
        RULEPILOT_HTTPS_BASIC_PASSWORD: '',
        RULEPILOT_HTTPS_ACCEPT: '',
        ...overrides,
      },
    })
    return 0
  } catch (error) {
    return Number(error.code)
  }
}

async function runPublicObserverRetry(statuses) {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-public-observer.'))
  const callLog = join(root, 'calls')
  const source = `
set -Eeuo pipefail
statuses=(${statuses.join(' ')})
verification_call=0
verify_public_once() {
  local status=\${statuses[verification_call]}
  verification_call=$((verification_call + 1))
  printf '%s\\n' "$status" >> "$RULEPILOT_TEST_CALL_LOG"
  return "$status"
}
sleep() { :; }
${embeddedPublicObserverRetryBlock()}
`
  let status = 0
  let stdout = ''
  let stderr = ''
  try {
    const result = await execFileAsync('bash', ['-c', source], {
      env: { ...process.env, RULEPILOT_TEST_CALL_LOG: callLog },
    })
    stdout = result.stdout
    stderr = result.stderr
  } catch (error) {
    status = Number(error.code)
    stdout = error.stdout ?? ''
    stderr = error.stderr ?? ''
  }
  const calls = (await readFile(callLog, 'utf8')).trim().split('\n').filter(Boolean).map(Number)
  await rm(root, { recursive: true, force: true })
  return { status, stdout, stderr, calls }
}

function productionRecommendationRawReport(overrides = {}) {
  const testedSha = 'a'.repeat(40)
  const activeReleaseId = `${testedSha}-101-1`
  const importJobId = '11111111-1111-4111-8111-111111111111'
  const gameId = '22222222-2222-4222-8222-222222222222'
  const editionId = '33333333-3333-4333-8333-333333333333'
  const documentVersionId = '44444444-4444-4444-8444-444444444444'
  const preparationRunId = '55555555-5555-4555-8555-555555555555'
  return {
    generatedAt: '2026-08-29T00:00:00.000Z',
    completed: true,
    stage: 'completed',
    testedSha,
    activeReleaseSha: testedSha,
    activeReleaseId,
    publicReleaseId: activeReleaseId,
    publicReleaseSha: testedSha,
    publicReleaseNoStore: true,
    routeStayedOnDiscover: true,
    recommendationRequestedCardCount: 3,
    recommendationExpectedPlayerCount: 5,
    recommendationMaximumDurationMinutes: 90,
    recommendationMaximumComplexity: 2.5,
    recommendationExpectedGameType: 'party',
    recommendationRequestMessageMatched: true,
    recommendationProfileHardConstraintsMatched: true,
    recommendationCardsHardConstraintsMatched: true,
    recommendationFitClaimsHardConstraintsMatched: true,
    recommendationComplexityHardConstraintsMatched: true,
    recommendationGameTypeHardConstraintsMatched: true,
    recommendationEvidenceBoundReplyParts: true,
    recommendationPersistedCardCount: 3,
    recommendationShortfallCount: 0,
    recommendationOutcome: 'recommendations',
    recommendationTerminalCategory: 'RECOMMENDATIONS',
    recommendationTerminalObserved: true,
    recommendationClickCaptured: true,
    recommendationFirstProgressMs: 120,
    recommendationSseTerminalCategory: 'RESULT',
    recommendationSseTerminalMs: 900,
    recommendationSseResultMs: 900,
    recommendationSseErrorCode: null,
    recommendationSseFailureBoundary: null,
    recommendationPersistedTerminalMs: 950,
    recommendationRenderedSlateMs: 1_000,
    recommendationElapsedMs: 1_000,
    recommendationSloMet: true,
    recommendationProgressEvents: [{ stage: 'untrusted', phase: 'player-secret-marker' }],
    recommendationStreamProbeFailed: false,
    recommendationPublishedBggIds: [101, 102, 103],
    recommendationAssistantReplyCharacterCount: 500,
    recommendationRenderedReplyCharacterCount: 500,
    recommendationCardReplyPartCount: 6,
    recommendationUsableCardCount: 3,
    recommendationUsableReplyPartCount: 6,
    recommendationAssistantReplyUsable: true,
    recommendationAllCardsUsable: true,
    recommendationAllReplyPartsUsable: true,
    recommendationSseContentDigest: {
      assistantMessageSha256: '6'.repeat(64),
      assistantMessageCharacterCount: 500,
      cardReplyPartsSha256: '7'.repeat(64),
      cardReplyPartsCharacterCount: 500,
      cardReplyPartCount: 6,
    },
    recommendationPersistedContentDigest: {
      assistantMessageSha256: '6'.repeat(64),
      assistantMessageCharacterCount: 500,
      cardReplyPartsSha256: '7'.repeat(64),
      cardReplyPartsCharacterCount: 500,
      cardReplyPartCount: 6,
    },
    recommendationRenderedContentDigest: {
      assistantMessageSha256: '6'.repeat(64),
      assistantMessageCharacterCount: 500,
      cardReplyPartsSha256: '7'.repeat(64),
      cardReplyPartsCharacterCount: 500,
      cardReplyPartCount: 6,
    },
    recommendationSsePersistedContentConsistent: true,
    recommendationPersistedDomContentConsistent: true,
    recommendationCompletedWork: ['player-secret-marker'],
    recommendationExpectedModel: { provider: 'qwen', model: 'qwen3.8-flash' },
    recommendationModelBeforeRequest: { provider: 'qwen', model: 'qwen3.8-flash' },
    recommendationModelAfterRequest: { provider: 'qwen', model: 'qwen3.8-flash' },
    recommendationModelProvider: 'qwen',
    recommendationModelName: 'qwen3.8-flash',
    recommendationModelCalls: 2,
    recommendationModelCallElapsedMs: [300, 400],
    recommendationAgentElapsedMs: 800,
    recommendationModelElapsedShare: 0.875,
    recommendationCatalogCalls: 1,
    recommendationWebResearchCalls: 0,
    recommendationFailureBoundary: null,
    expectedRecommendationTitleTermSha256:
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    handoffSelectedBggId: 101,
    handoffActionClicked: true,
    handoffSurfaceVisible: true,
    handoffImportRequestedBggId: 101,
    handoffImportResponseStatus: 200,
    handoffImportResponseOk: true,
    handoffImportResponseBggId: 101,
    handoffImportedGameId: gameId,
    handoffImportedEditionId: editionId,
    handoffEditionBelongsToImportedGame: true,
    handoffImportElapsedMs: 200,
    handoffDiscoveryRequestedEditionId: null,
    handoffDiscoveryResponseStatus: null,
    handoffDiscoveryResponseOk: null,
    handoffDiscoveryIdentityEditionId: null,
    handoffDiscoveryIdentityMatched: null,
    handoffDiscoveryConfigured: null,
    handoffDiscoveryCandidateCount: null,
    handoffImportableCandidateCount: null,
    handoffImportableCandidateFound: null,
    handoffDiscoveryCandidateIdentitySha256: null,
    handoffRenderedCandidateIdentitySha256: null,
    handoffCandidateIdentityOrderConsistent: null,
    handoffDiscoveryElapsedMs: null,
    handoffTerminalCategory: 'RESTORED_EXISTING',
    handoffTerminalVisible: true,
    handoffElapsedMs: 500,
    handoffRulebookImportStarted: false,
    handoffRestoredExistingJourney: true,
    handoffRestoredImportJobId: importJobId,
    handoffRestoredDocumentVersionId: documentVersionId,
    handoffRestoredPreparationRunId: preparationRunId,
    handoffFreshnessRequestPreparationRunMatched: true,
    handoffFreshnessResponseStatus: 202,
    handoffFreshnessResponseIdentityMatched: true,
    handoffFreshnessResponseEligible: true,
    handoffOfficialMutationAttemptedPaths: [
      `POST /api/v1/documents/official-imports/${importJobId}/teaching-ensure-current`,
    ],
    handoffOfficialMutationBlocked: false,
    handoffStoppedAtDiscoveryBoundary: false,
    rawModelOutputCaptured: false,
    credentialLeak: 'player-secret-marker',
    ...overrides,
  }
}

function productionRecommendationSanitizerEnvironment(root) {
  const testedSha = 'a'.repeat(40)
  return {
    ...process.env,
    HOME: join(root, 'home'),
    RUNNER_TEMP: root,
    RULEPILOT_RECOMMENDATION_TESTED_SHA: testedSha,
    RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID: `${testedSha}-101-1`,
    RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT: '3',
    RULEPILOT_RECOMMENDATION_EXPECTED_PLAYER_COUNT: '5',
    RULEPILOT_RECOMMENDATION_MAXIMUM_DURATION_MINUTES: '90',
    RULEPILOT_RECOMMENDATION_MAXIMUM_COMPLEXITY: '2.5',
    RULEPILOT_RECOMMENDATION_EXPECTED_GAME_TYPE: 'party',
    RULEPILOT_RECOMMENDATION_EXPECTED_PROVIDER: 'qwen',
    RULEPILOT_RECOMMENDATION_EXPECTED_MODEL: 'qwen3.8-flash',
    RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM: '',
  }
}

async function createReleaseGuardFixture() {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-release-transaction.'))
  const release = `${'a'.repeat(40)}-101-2`
  const previous = `${'b'.repeat(40)}-100`
  const releases = join(root, 'releases')
  const previousRelease = join(releases, previous)
  const candidateRelease = join(releases, release)
  const current = join(root, 'current')
  const environmentFile = join(root, '.env')
  const fakeBin = join(root, 'fake-bin')
  await mkdir(previousRelease, { recursive: true })
  await mkdir(candidateRelease, { recursive: true })
  await mkdir(fakeBin)
  await writeFile(
    environmentFile,
    'DEPLOY_MARKER=checkpoint\nBACKEND_PORT=18080\nRULEPILOT_HTTP_PORT=127.0.0.1:18081\n',
    { mode: 0o600 },
  )
  await symlink(environmentFile, join(previousRelease, '.env'))
  await symlink(environmentFile, join(candidateRelease, '.env'))
  await symlink(previousRelease, current)

  const dockerStub = [
    '#!/usr/bin/env bash',
    'set -Eeuo pipefail',
    'if [[ "$1" == compose ]]; then',
    '  if [[ " $* " == *" port api 8080 "* ]]; then',
    '    printf "%s\\n" "${RULEPILOT_TEST_API_ENDPOINT:-127.0.0.1:18080}"',
    '    exit 0',
    '  fi',
    '  if [[ " $* " == *" port frontend 80 "* ]]; then',
    '    printf "%s\\n" "${RULEPILOT_TEST_FRONTEND_ENDPOINT:-127.0.0.1:18081}"',
    '    exit 0',
    '  fi',
    '  if [[ " $* " == *" ps -q "* ]]; then',
    '    service="${!#}"',
    '    printf "%s-container\\n" "$service"',
    '    exit 0',
    '  fi',
    '  if [[ " $* " == *" up -d "* ]]; then',
    '    grep -q "^DEPLOY_MARKER=checkpoint$" "$RULEPILOT_TEST_ROOT/.env"',
    '    exit 0',
    '  fi',
    'fi',
    'if [[ "$1" == exec ]]; then',
    '  if [[ -n "${RULEPILOT_TEST_CANDIDATE_BOUNDARY_EXIT:-}" ]]; then',
    '    exit "$RULEPILOT_TEST_CANDIDATE_BOUNDARY_EXIT"',
    '  fi',
    '  shift',
    '  if [[ "${1:-}" == -i ]]; then shift; fi',
    '  while [[ "${1:-}" == -e ]]; do',
    '    export "$2"',
    '    shift 2',
    '  done',
    '  [[ $# -ge 2 ]] || { echo "Malformed docker exec invocation." >&2; exit 64; }',
    '  shift',
    '  exec "$@"',
    'fi',
    'if [[ "$1" == inspect ]]; then',
    '  format=$3',
    '  container=$4',
    '  if [[ "$format" == *".State.Running"* ]]; then',
    '    printf "true\\n"',
    '  elif [[ "$format" == *".State.Health"* ]]; then',
    '    printf "healthy\\n"',
    '  elif [[ "$container" == frontend-container ]]; then',
    '    printf "sha256:frontend-id\\n"',
    '  else',
    '    printf "sha256:backend-id\\n"',
    '  fi',
    '  exit 0',
    'fi',
    'if [[ "$1" == image && "$2" == inspect ]]; then',
    '  target="${!#}"',
    '  if [[ -n "${RULEPILOT_TEST_IMAGE_FAILURE_COUNTER:-}" && "$target" == rulepilot-backend:* ]]; then',
    '    remaining=$(<"$RULEPILOT_TEST_IMAGE_FAILURE_COUNTER")',
    '    if (( remaining > 0 )); then',
    '      printf "%s\\n" "$((remaining - 1))" > "$RULEPILOT_TEST_IMAGE_FAILURE_COUNTER"',
    '      echo "Injected bounded rollback image inspection failure." >&2',
    '      exit 75',
    '    fi',
    '  fi',
    '  if [[ " $* " == *" --format "* ]]; then',
    '    if [[ "$target" == rulepilot-frontend:* ]]; then',
    '      printf "sha256:frontend-id\\n"',
    '    else',
    '      printf "sha256:backend-id\\n"',
    '    fi',
    '  fi',
    '  exit 0',
    'fi',
    'if [[ "$1" == tag ]]; then exit 0; fi',
    'echo "Unexpected docker invocation: $*" >&2',
    'exit 64',
  ].join('\n')
  const dockerPath = join(fakeBin, 'docker')
  const curlPath = join(fakeBin, 'curl')
  const installPath = join(fakeBin, 'install')
  const gitPath = join(fakeBin, 'git')
  await writeFile(dockerPath, `${dockerStub}\n`, { mode: 0o755 })
  await writeFile(curlPath, [
    '#!/usr/bin/env bash',
    'set -Eeuo pipefail',
    'if [[ -n "${RULEPILOT_TEST_CURL_LOG:-}" ]]; then printf "%s\\n" "$*" >> "$RULEPILOT_TEST_CURL_LOG"; fi',
    'if [[ -n "${RULEPILOT_TEST_CURL_FAILURE_COUNTER:-}" ]]; then',
    '  remaining=$(<"$RULEPILOT_TEST_CURL_FAILURE_COUNTER")',
    '  if (( remaining > 0 )); then',
    '    printf "%s\\n" "$((remaining - 1))" > "$RULEPILOT_TEST_CURL_FAILURE_COUNTER"',
    '    exit 22',
    '  fi',
    'fi',
    'body_path=',
    'headers_path=',
    'request_url=',
    'write_status=false',
    'while (( $# > 0 )); do',
    '  case "$1" in',
    '    --output)',
    '      body_path=$2',
    '      shift 2',
    '      ;;',
    '    --dump-header)',
    '      headers_path=$2',
    '      shift 2',
    '      ;;',
    '    --write-out)',
    '      write_status=true',
    '      shift 2',
    '      ;;',
    '    *)',
    '      if [[ "$1" == https://* || "$1" == http://* ]]; then request_url=$1; fi',
    '      shift',
    '      ;;',
    '  esac',
    'done',
    'if [[ -n "${RULEPILOT_TEST_CANDIDATE_CURL_EXIT:-}" ]]; then',
    '  exit "$RULEPILOT_TEST_CANDIDATE_CURL_EXIT"',
    'fi',
    'http_status=${RULEPILOT_TEST_CANDIDATE_HTTP_STATUS:-200}',
    'if [[ -n "$body_path" && "$body_path" != /dev/null ]]; then',
    '  case "$request_url" in',
    '    */api/public/release)',
    '      if [[ -n "${RULEPILOT_TEST_RELEASE_BODY+x}" ]]; then',
    '        printf "%s\\n" "$RULEPILOT_TEST_RELEASE_BODY" > "$body_path"',
    '      else',
    '        printf "{\\"releaseId\\":\\"%s\\",\\"commitSha\\":\\"%s\\"}\\n" "$RULEPILOT_TEST_RELEASE_ID" "$RULEPILOT_TEST_CURRENT_MAIN_SHA" > "$body_path"',
    '      fi',
    '      ;;',
    '    */api/v1/model-configuration)',
    '      if [[ -n "${RULEPILOT_TEST_MODEL_BODY+x}" ]]; then',
    '        printf "%s\\n" "$RULEPILOT_TEST_MODEL_BODY" > "$body_path"',
    '      else',
    '        printf "{\\"recommendationModel\\":{\\"provider\\":\\"qwen\\",\\"model\\":\\"qwen3.8-flash\\"}}\\n" > "$body_path"',
    '      fi',
    '      ;;',
    '    */api/auth/csrf)',
    '      if [[ -n "${RULEPILOT_TEST_CSRF_BODY+x}" ]]; then',
    '        printf "%s\\n" "$RULEPILOT_TEST_CSRF_BODY" > "$body_path"',
    '      else',
    '        printf "{\\"token\\":\\"csrf-token\\",\\"headerName\\":\\"X-CSRF-TOKEN\\"}\\n" > "$body_path"',
    '      fi',
    '      ;;',
    '    */api/v1/bgg/recommendations)',
    '      if [[ -n "${RULEPILOT_TEST_RECOMMENDATIONS_BODY+x}" ]]; then',
    '        printf "%s\\n" "$RULEPILOT_TEST_RECOMMENDATIONS_BODY" > "$body_path"',
    '      else',
    '        printf "[{\\"bggId\\":1,\\"name\\":\\"Fixture Game\\"}]\\n" > "$body_path"',
    '      fi',
    '      ;;',
    '    */api/v1/bgg/games/1*)',
    '      if [[ -n "${RULEPILOT_TEST_DETAIL_BODY+x}" ]]; then',
    '        printf "%s\\n" "$RULEPILOT_TEST_DETAIL_BODY" > "$body_path"',
    '      else',
    '        printf "{\\"bggId\\":1,\\"description\\":\\"Fixture\\",\\"descriptionTranslated\\":false,\\"categories\\":[],\\"mechanics\\":[]}\\n" > "$body_path"',
    '      fi',
    '      ;;',
    '    */)',
    '      printf "<html><body>RulePilot</body></html>\\n" > "$body_path"',
    '      ;;',
    '    *)',
    '      printf "{}\\n" > "$body_path"',
    '      ;;',
    '  esac',
    'fi',
    'if [[ -n "$headers_path" ]]; then',
    '  printf "HTTP/2 %s\\nCache-Control: %s\\nContent-Type: text/html; charset=utf-8\\n\\n" "$http_status" "${RULEPILOT_TEST_CACHE_CONTROL:-no-cache, no-store}" > "$headers_path"',
    'fi',
    'if [[ "$write_status" == true ]]; then printf "%s" "$http_status"; fi',
    'exit 0',
    '',
  ].join('\n'), { mode: 0o755 })
  await writeFile(installPath, [
    '#!/usr/bin/env bash',
    'set -Eeuo pipefail',
    'if [[ "${RULEPILOT_TEST_FAIL_ENV_SNAPSHOT:-}" == true',
    '  && " $* " == *"/environment.snapshot.tmp."* ]]; then',
    '  echo "Injected environment snapshot failure." >&2',
    '  exit 75',
    'fi',
    'exec /usr/bin/install "$@"',
    '',
  ].join('\n'), { mode: 0o755 })
  await writeFile(gitPath, [
    '#!/usr/bin/env bash',
    'set -Eeuo pipefail',
    '[[ "$1" == ls-remote || "$3" == ls-remote ]] || { echo "Unexpected git invocation: $*" >&2; exit 64; }',
    'printf "%s\\trefs/heads/main\\n" "${RULEPILOT_TEST_CURRENT_MAIN_SHA:?}"',
    '',
  ].join('\n'), { mode: 0o755 })

  return {
    root,
    release,
    previous,
    previousRelease,
    candidateRelease,
    current,
    environmentFile,
    guardState: join(root, 'deployment-guards', release),
    processEnvironment: {
      ...process.env,
      PATH: `${fakeBin}:${process.env.PATH}`,
      RULEPILOT_TEST_ROOT: root,
      RULEPILOT_TEST_CURRENT_MAIN_SHA: release.slice(0, 40),
      RULEPILOT_TEST_RELEASE_ID: release,
    },
  }
}

async function invokeReleaseGuard(fixture, command) {
  return execFileAsync(
    'bash',
    [
      productionReleaseGuardPath,
      command,
      fixture.root,
      fixture.release,
      ...(command === 'checkpoint' ? [] : [fixture.previous]),
    ],
    { env: fixture.processEnvironment },
  )
}

async function stopReleaseGuardWatchdog(fixture) {
  try {
    const pid = Number.parseInt(await readFile(join(fixture.guardState, 'watchdog.pid'), 'utf8'), 10)
    if (Number.isSafeInteger(pid) && pid > 0) process.kill(pid, 'SIGTERM')
  } catch {
    // A terminal watchdog may already have exited or removed its fixture state.
  }
}

async function writeFastReleaseGuard(fixture, deadlineSeconds = 4) {
  const guardPath = join(fixture.root, 'production-release-guard.fast.sh')
  const fastGuard = productionReleaseGuard
    .replace('readonly LEASE_STALE_SECONDS=150', 'readonly LEASE_STALE_SECONDS=1')
    .replace(
      'readonly WATCHDOG_DEADLINE_SECONDS=2100',
      `readonly WATCHDOG_DEADLINE_SECONDS=${deadlineSeconds}`,
    )
    .replace('readonly WATCHDOG_POLL_SECONDS=5', 'readonly WATCHDOG_POLL_SECONDS=1')
    .replace('readonly ROLLBACK_READY_TIMEOUT_SECONDS=360', 'readonly ROLLBACK_READY_TIMEOUT_SECONDS=3')
    .replace('readonly ROLLBACK_READY_POLL_SECONDS=2', 'readonly ROLLBACK_READY_POLL_SECONDS=0.05')
  await writeFile(guardPath, fastGuard, { mode: 0o755 })
  return guardPath
}

async function seedArmedReleaseGuard(fixture, failureCount) {
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  const lease = join(fixture.guardState, 'lease')
  const failureCounter = join(fixture.root, 'rollback-image-failures')
  await mkdir(fixture.guardState, { recursive: true })
  await writeFile(
    join(fixture.root, 'deployment-guards', 'active-transaction'),
    `${fixture.release}\n`,
    { mode: 0o600 },
  )
  await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, { mode: 0o600 })
  await writeFile(
    snapshot,
    'DEPLOY_MARKER=checkpoint\nBACKEND_PORT=18080\nRULEPILOT_HTTP_PORT=127.0.0.1:18081\n',
    { mode: 0o600 },
  )
  await writeFile(join(fixture.guardState, 'armed'), `${fixture.release}\n`, { mode: 0o600 })
  await writeFile(lease, '')
  const stale = new Date(Date.now() - 10 * 60 * 1000)
  await utimes(lease, stale, stale)
  await writeFile(
    fixture.environmentFile,
    'DEPLOY_MARKER=candidate\nBACKEND_PORT=28080\nRULEPILOT_HTTP_PORT=127.0.0.1:28081\n',
    { mode: 0o600 },
  )
  await rm(fixture.current)
  await symlink(fixture.candidateRelease, fixture.current)
  await writeFile(failureCounter, `${failureCount}\n`)
  return { failureCounter, snapshot }
}

test('CI owns automatic and manual deployment qualification', () => {
  assert.match(ciWorkflow, /uses:\s*actions\/upload-artifact@v(?:6|7)\b/)
  assert.doesNotMatch(ciWorkflow, /uses:\s*actions\/upload-artifact@v[1-5]\b/)
  assert.match(ciWorkflow, /path:\s*frontend\/playwright-report\//)
  assert.match(ciWorkflow, /if-no-files-found:\s*error/)
  assert.match(playwrightConfig, /outputFolder:\s*'playwright-report'/)
  assert.match(ciWorkflow, /make backend-test[\s\S]*?make backend-runtime-image-smoke/)
  assert.match(ciWorkflow, /^  workflow_dispatch:\s*$/m)
  assert.match(deploymentWorkflow,
    /docker build[\s\S]*?--file "\$RULEPILOT_BUILD_SOURCE\/backend\/Dockerfile\.runtime"/)
  assert.match(deploymentWorkflow,
    /workflow_run\.event == 'push' \|\|[\s\S]*?workflow_run\.event == 'workflow_dispatch'/)
  assert.match(deploymentWorkflow, /workflow_run\.head_branch == 'main'/)
  assert.match(deploymentWorkflow, /ref: \$\{\{ github\.sha \}\}/)
  assert.doesNotMatch(deploymentWorkflow, /^  workflow_dispatch:\s*$/m)
  assert.doesNotMatch(deploymentWorkflow, /github\.event_name == 'workflow_dispatch'/)
})

test('production recommendation verifies one deployed main release through the exact rulebook discovery handoff', () => {
  assert.match(productionRecommendationWorkflow,
    /default: '今晚五个人，90 分钟内，BGG 复杂度不超过 2\.5，并且只看 BGG 的 PARTY（聚会游戏）分类。请直接挑三款，并把最推荐的一款放第一。'/)
  assert.match(productionRecommendationWorkflow,
    /tested_sha:[\s\S]*?required: true[\s\S]*?type: string/)
  assert.match(productionRecommendationWorkflow,
    /expected_title_term:[\s\S]*?required: false[\s\S]*?type: string/)
  assert.match(productionRecommendationWorkflow,
    /expected_provider:[\s\S]*?required: true[\s\S]*?type: string[\s\S]*?default: 'qwen'/)
  assert.match(productionRecommendationWorkflow,
    /expected_model:[\s\S]*?required: true[\s\S]*?type: string[\s\S]*?default: 'qwen3\.8-flash'/)
  assert.match(productionRecommendationWorkflow,
    /maximum_complexity:[\s\S]{0,220}?required: true[\s\S]{0,120}?type: number[\s\S]{0,120}?default: 2\.5/)
  assert.match(productionRecommendationWorkflow,
    /expected_game_type:[\s\S]{0,220}?required: true[\s\S]{0,120}?type: choice[\s\S]{0,120}?default: party[\s\S]{0,360}?- expansion/)
  assert.match(productionRecommendationSpec,
    /isSafeInteger\(maximumDuration\)[\s\S]{0,100}?maximumDuration > 0[\s\S]{0,100}?maximumDuration <= maximumDurationMinutes/)
  assert.match(productionRecommendationWorkflow,
    /uses:\s*actions\/checkout@v6[\s\S]*?ref:\s*main[\s\S]*?fetch-depth:\s*0/)
  assert.match(productionRecommendationWorkflow, /environment:\s*\n\s+name:\s*production/)
  assert.match(productionRecommendationWorkflow,
    /git merge-base --is-ancestor "\$tested_sha" origin\/main/)
  assert.match(productionRecommendationWorkflow, /git checkout --detach "\$tested_sha"/)
  const prepareProbeStart = productionRecommendationWorkflow.indexOf('  prepare_probe:')
  const journeyStart = productionRecommendationWorkflow.indexOf('  journey:')
  const prepareProbeJob = productionRecommendationWorkflow.slice(prepareProbeStart, journeyStart)
  const journeyJob = productionRecommendationWorkflow.slice(journeyStart)
  assert.ok(prepareProbeStart >= 0 && journeyStart > prepareProbeStart)
  assert.match(prepareProbeJob, /uses: actions\/checkout@v6/)
  assert.match(prepareProbeJob, /npm --prefix frontend ci/)
  assert.match(prepareProbeJob, /actions\/upload-artifact@v7/)
  assert.doesNotMatch(prepareProbeJob,
    /environment:\s*\n\s+name:\s*production|secrets\.|DEPLOY_SSH_PRIVATE_KEY|\bssh\s|\bscp\s/)
  assert.match(journeyJob, /needs: prepare_probe/)
  assert.match(journeyJob,
    /mcr\.microsoft\.com\/playwright:v1\.61\.1-noble@sha256:[0-9a-f]{64}/)
  assert.match(journeyJob, /actions\/download-artifact@v8/)
  assert.doesNotMatch(journeyJob,
    /actions\/checkout|actions\/setup-node|npm (?:ci|install|exec)|npx playwright/)
  const recommendationCredentialRead = productionRecommendationWorkflow.indexOf(
    'name: Read exact active release and bounded player credentials',
  )
  const recommendationProbeVerification = productionRecommendationWorkflow.indexOf(
    'name: Verify and extract the exact probe before production authority exists',
  )
  const recommendationKeyRemoval = productionRecommendationWorkflow.indexOf(
    'rm -f "$HOME/.ssh/id_ed25519"',
    recommendationCredentialRead,
  )
  const recommendationExercise = productionRecommendationWorkflow.indexOf(
    'name: Exercise one production recommendation with only player authority',
  )
  const recommendationCredentialCleanup = productionRecommendationWorkflow.indexOf(
    'name: Prove credentials are absent and rebuild the allowlisted journey report',
  )
  const recommendationReportUpload = productionRecommendationWorkflow.indexOf(
    'name: Upload sanitized journey report',
  )
  assert.ok(recommendationProbeVerification >= journeyStart
    && recommendationProbeVerification < recommendationCredentialRead)
  assert.ok(recommendationCredentialRead < recommendationKeyRemoval
    && recommendationKeyRemoval < recommendationExercise)
  assert.ok(recommendationExercise < recommendationCredentialCleanup
    && recommendationCredentialCleanup < recommendationReportUpload)
  assert.match(journeyJob,
    /printf '%s\\n%s\\n' "\$player_username_b64" "\$player_password_b64" > "\$credential_file"/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /PLAYER_(?:USERNAME|PASSWORD)_B64|player_(?:username|password)_b64[^\n]*GITHUB_ENV/)
  assert.doesNotMatch(productionRecommendationWorkflow, /needs: production_credentials/)
  assert.match(productionRecommendationWorkflow,
    /id: production_identity[\s\S]*?active_release_id=%s\\n[^\n]*GITHUB_OUTPUT/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /player_(?:username|password)[^\n]*GITHUB_(?:ENV|OUTPUT)/)
  assert.match(productionRecommendationWorkflow,
    /! "\$active_release_id" =~ \^\$\{tested_sha\}-\[0-9\]\+-\[0-9\]\+\$/)
  assert.match(journeyJob,
    /"\$node_binary" frontend\/node_modules\/@playwright\/test\/cli\.js test/)
  assert.match(journeyJob,
    /reset_runner_command_file "\$runner_env_file"[\s\S]{0,180}?reset_runner_command_file "\$runner_path_file"/)
  assert.match(journeyJob,
    /\/usr\/bin\/env -i[\s\S]{0,1800}?RULEPILOT_RECOMMENDATION_REPORT="\$raw_report"/)
  assert.match(journeyJob,
    /deployment-guards\/active-transaction[\s\S]{0,180}?Production has an active deployment transaction/)
  assert.match(journeyJob, /id: cleanup_credentials/)
  assert.match(journeyJob,
    /name: Upload sanitized journey report\s+if: always\(\) && steps\.cleanup_credentials\.outcome == 'success'/)
  assert.match(journeyJob,
    /rm -f "\$credential_file"[\s\S]{0,5000}?"\$node_binary" frontend\/node_modules\/@playwright\/test\/cli\.js/)
  assert.match(journeyJob,
    /raw_report="\$artifact_dir\/journey\.raw\.json"[\s\S]{0,2600}?RULEPILOT_RECOMMENDATION_REPORT="\$raw_report"/)
  assert.match(journeyJob,
    /name: Exercise one production recommendation with only player authority[\s\S]{0,300}?RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID: \$\{\{ steps\.production_identity\.outputs\.active_release_id \}\}/)
  const sanitizerStep = productionRecommendationWorkflow.slice(
    recommendationCredentialCleanup,
    recommendationReportUpload,
  )
  assert.match(sanitizerStep,
    /raw_report="\$artifact_dir\/journey\.raw\.json"[\s\S]*?sanitized_report="\$artifact_dir\/journey\.json"/)
  assert.match(sanitizerStep, /jq --exit-status --compact-output/)
  assert.match(sanitizerStep, /reportSchemaVersion: 1/)
  assert.match(sanitizerStep,
    /recommendationExpectedPlayerCount: \$expectedPlayerCount[\s\S]*?recommendationMaximumDurationMinutes: \$maximumDurationMinutes[\s\S]*?recommendationMaximumComplexity: \$maximumComplexity[\s\S]*?recommendationExpectedGameType: \$expectedGameType/)
  assert.ok(sanitizerStep.includes(
    '[[ "$maximum_complexity" =~ ^(0|[1-9][0-9]*)(\\.[0-9]+)?$ ]]',
  ))
  assert.match(sanitizerStep,
    /\(\$value \| tonumber\) <= 5[\s\S]*?abstract\|customizable\|children\|family\|party\|strategy\|thematic\|war\|expansion/)
  assert.match(sanitizerStep,
    /shell: \/bin\/bash --noprofile --norc -p \{0\}[\s\S]*?BASH_ENV: \/dev\/null[\s\S]*?PATH: \/usr\/bin:\/bin/)
  assert.match(sanitizerStep, /LD_LIBRARY_PATH: ''[\s\S]{0,80}?LD_PRELOAD: ''/)
  assert.match(sanitizerStep,
    /\/usr\/bin\/python3 -c[\s\S]{0,260}?unicodedata\.normalize\("NFKC"[\s\S]{0,180}?\.strip\(\)\.lower\(\)/)
  assert.match(sanitizerStep,
    /RULEPILOT_RECOMMENDATION_ACTIVE_RELEASE_ID: \$\{\{ steps\.production_identity\.outputs\.active_release_id \}\}/)
  const reportInterface = productionRecommendationSpec.match(
    /interface ProductionRecommendationReport \{([\s\S]*?)\n\}/,
  )
  const sanitizerRequiredKeys = sanitizerStep.match(/has_keys\(\[([\s\S]*?)\]\)\)/)
  assert.notEqual(reportInterface, null)
  assert.notEqual(sanitizerRequiredKeys, null)
  const reportKeys = [...reportInterface[1].matchAll(/^\s{2}([A-Za-z][A-Za-z0-9]+):/gm)]
    .map((match) => match[1])
    .sort()
  const requiredKeys = [...sanitizerRequiredKeys[1].matchAll(/"([A-Za-z][A-Za-z0-9]+)"/g)]
    .map((match) => match[1])
    .sort()
  assert.deepEqual(requiredKeys, reportKeys)
  assert.match(sanitizerStep,
    /recommendationModelCalls: \$raw\.recommendationModelCalls[\s\S]*?recommendationModelCallElapsedMs: \$raw\.recommendationModelCallElapsedMs/)
  assert.match(sanitizerStep,
    /handoffRestoredExistingJourney: \$raw\.handoffRestoredExistingJourney[\s\S]*?handoffRestoredImportJobId: \$raw\.handoffRestoredImportJobId[\s\S]*?handoffRestoredDocumentVersionId: \$raw\.handoffRestoredDocumentVersionId[\s\S]*?handoffRestoredPreparationRunId: \$raw\.handoffRestoredPreparationRunId/)
  assert.match(sanitizerStep,
    /recommendationComplexityHardConstraintsMatched: \$raw\.recommendationComplexityHardConstraintsMatched[\s\S]*?recommendationGameTypeHardConstraintsMatched: \$raw\.recommendationGameTypeHardConstraintsMatched/)
  assert.match(sanitizerStep,
    /def completed_recommendation_acceptance\(\$raw\):[\s\S]*?recommendationGameTypeHardConstraintsMatched == true[\s\S]*?recommendationSloMet == true[\s\S]*?handoffTerminalVisible == true/)
  assert.match(sanitizerStep,
    /recommendationModelCalls \| is_integer_between\(2; 6\)[\s\S]{0,160}?recommendationModelCallElapsedMs \| length\) == \$raw\.recommendationModelCalls/)
  assert.match(sanitizerStep,
    /\(\(\$raw\.completed \| not\) or completed_recommendation_acceptance\(\$raw\)\)/)
  assert.match(sanitizerStep, /def is_finite_number:[\s\S]{0,100}?\(\(\. - \.\) == 0\)/)
  assert.match(sanitizerStep, /"invalid_stream_error", "unknown_stream_error"/)
  assert.match(sanitizerStep,
    /recommendationProgressEventCount: \(\$raw\.recommendationProgressEvents \| length\)[\s\S]*?recommendationCompletedWorkCount: \(\$raw\.recommendationCompletedWork \| length\)[\s\S]*?handoffOfficialMutationAttemptCount: \(\$raw\.handoffOfficialMutationAttemptedPaths \| length\)/)
  assert.doesNotMatch(sanitizerStep,
    /recommendationProgressEvents: \$raw|recommendationCompletedWork: \$raw|handoffOfficialMutationAttemptedPaths: \$raw|\+ \$raw|with_entries/)
  assert.match(sanitizerStep,
    /mv "\$temporary_report" "\$sanitized_report"[\s\S]*?rm -f "\$raw_report"/)
  assert.match(journeyJob,
    /name: Upload sanitized journey report[\s\S]*?path: \$\{\{ runner\.temp \}\}\/production-recommendation-journey\/journey\.json/)
  assert.doesNotMatch(journeyJob,
    /name: Upload sanitized journey report[\s\S]{0,500}?journey\.raw\.json/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_username/)
  assert.match(productionRecommendationWorkflow, /::add-mask::\$player_password/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_SELECTION_PROMPT: \$\{\{ inputs\.selection_prompt \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_CARD_COUNT: \$\{\{ inputs\.requested_card_count \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_MAXIMUM_COMPLEXITY: \$\{\{ inputs\.maximum_complexity \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_GAME_TYPE: \$\{\{ inputs\.expected_game_type \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM: \$\{\{ inputs\.expected_title_term \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_PROVIDER: \$\{\{ inputs\.expected_provider \}\}/)
  assert.match(productionRecommendationWorkflow,
    /RULEPILOT_RECOMMENDATION_EXPECTED_MODEL: \$\{\{ inputs\.expected_model \}\}/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /target_bgg_id|target_names|230802|花砖物语|Azul/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /ready_public|verified_import|journey_mode|require_fresh_import|recommendation_only|rule_question|rule_follow_up/)

  assert.match(productionRecommendationWorkflow, /playwright\.recommendation-production\.config\.ts/)
  assert.match(productionRecommendationConfig,
    /testMatch:\s*'production-recommendation-journey\.spec\.ts'/)
  assert.match(productionRecommendationSpec,
    /production returns one recommendation slate and hands its exact identity to rulebook discovery/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationModelCalls,[\s\S]{0,240}?\.toBeGreaterThanOrEqual\(2\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationModelCalls,[\s\S]{0,240}?\.toBeLessThanOrEqual\(MAX_RECOMMENDATION_MODEL_CALLS\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationCatalogCalls,[\s\S]{0,240}?\.toBe\(1\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationWebResearchCalls,[\s\S]{0,240}?\.toBe\(0\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationSloMet,[\s\S]{0,240}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec,
    /for \(const \[gameIndex, entry\] of result!\.games\.entries\(\)\)/)
  assert.match(productionRecommendationSpec,
    /expectUsablePlayerSurface\([\s\S]{0,180}?renderedCard/)
  assert.match(productionRecommendationSpec,
    /expectUsablePlayerSurface\([\s\S]{0,180}?renderedReplyParts\.nth\(partIndex\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationAllCardsUsable,[\s\S]{0,180}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationAllReplyPartsUsable,[\s\S]{0,180}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec,
    /expectUsablePlayerSurface\([\s\S]{0,220}?renderedAssistantReply/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.recommendationAssistantReplyUsable,[\s\S]{0,180}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec, /const sseResultPromise = waitForBrowserSseResult/)
  assert.match(productionRecommendationSpec, /const renderedSlatePromise = waitForFirstRenderedSlate/)
  assert.match(productionRecommendationSpec,
    /const \[firstProgressVisible, terminal, sseResult, renderedSlate\] = await Promise\.all/)
  assert.doesNotMatch(productionRecommendationSpec,
    /const terminal = await waitForPersistedTerminal/)
  const sseObservation = productionRecommendationSpec.indexOf(
    'const sseResultPromise = waitForBrowserSseResult',
  )
  const renderedObservation = productionRecommendationSpec.indexOf(
    'const renderedSlatePromise = waitForFirstRenderedSlate',
  )
  const sendClick = productionRecommendationSpec.indexOf(
    "await page.getByRole('button', { name: '发送', exact: true }).click()",
  )
  const persistedObservation = productionRecommendationSpec.indexOf(
    'const persistedTerminalPromise = waitForPersistedTerminal',
  )
  const observationJoin = productionRecommendationSpec.indexOf(
    'const [firstProgressVisible, terminal, sseResult, renderedSlate] = await Promise.all',
  )
  assert.ok(sseObservation >= 0 && sseObservation < sendClick)
  assert.ok(renderedObservation >= 0 && renderedObservation < sendClick)
  assert.ok(sendClick < persistedObservation && persistedObservation < observationJoin)
  assert.match(productionRecommendationSpec,
    /recommendationProgressEvents: RecommendationProgressEvidence\[\]/)
  assert.match(productionRecommendationSpec,
    /serverElapsedMs: number[\s\S]{0,120}?browserReceivedMs: number/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationSseResultMs = sseResult === null/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationPersistedTerminalMs = terminal\.elapsedMs/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationRenderedSlateMs = slate\.elapsedMs/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationSloMet = slate\.rendered && slate\.elapsedMs <= INTERACTION_SLO_MS/)
  assert.match(productionRecommendationSpec,
    /snapshot\.recommendationModel\?\.provider[\s\S]{0,160}?snapshot\.recommendationModel\?\.model/)
  assert.match(productionRecommendationSpec,
    /expect\(modelAssignment\.provider,[\s\S]{0,180}?\.toBe\(EXPECTED_MODEL_PROVIDER\)/)
  assert.match(productionRecommendationSpec,
    /expect\(modelAssignment\.model,[\s\S]{0,180}?\.toBe\(EXPECTED_MODEL_NAME\)/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationModelCallElapsedMs = publicNonNegativeIntegers\(result\?\.modelCallElapsedMs\)/)
  assert.match(productionRecommendationSpec,
    /\.toHaveLength\(report\.recommendationModelCalls!\)/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationAgentElapsedMs = publicNonNegativeInteger\(result\?\.agentElapsedMs\)/)
  assert.match(productionRecommendationSpec,
    /report\.recommendationPublishedBggIds = result\?\.games\.map\(\(\{ game \}\) => game\.bggId\)/)
  assert.match(productionRecommendationSpec,
    /expectedRecommendationTitleTermSha256:\s*sha256\(EXPECTED_TITLE_TERM\)/)
  assert.match(productionRecommendationSpec,
    /request\.get\('\/api\/public\/release'\)[\s\S]{0,500}?cache-control/)
  assert.match(productionRecommendationSpec,
    /publicReleaseBefore\.commitSha[\s\S]{0,220}?\.toBe\(TESTED_SHA\)/)
  assert.match(productionRecommendationSpec,
    /publicReleaseBefore\.releaseId[\s\S]{0,220}?\.toBe\(ACTIVE_RELEASE_ID\)/)
  assert.match(productionRecommendationSpec,
    /publicReleaseAfter[\s\S]{0,220}?\.toEqual\(publicReleaseBefore\)/)
  assert.doesNotMatch(productionRecommendationSpec,
    /recommendationPublishedGames|expectedRecommendationTitleTerm:\s*EXPECTED_TITLE_TERM/)
  assert.match(productionRecommendationSpec,
    /const selectedBggId = persistedBggIds\[0\][\s\S]{0,400}?report\.handoffSelectedBggId = selectedBggId!/)
  assert.match(productionRecommendationSpec,
    /\/api\\\/v1\\\/bgg\\\/games\\\/\\d\+\\\/import\$[\s\S]{0,260}?response\.request\(\)\.method\(\) === 'POST'/)
  assert.match(productionRecommendationSpec,
    /report\.handoffImportRequestedBggId[\s\S]{0,260}?\.toBe\(selectedBggId\)/)
  assert.match(productionRecommendationSpec,
    /report\.handoffImportResponseBggId[\s\S]{0,260}?\.toBe\(selectedBggId\)/)
  assert.match(productionRecommendationSpec,
    /report\.handoffEditionBelongsToImportedGame[\s\S]{0,260}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec,
    /report\.handoffDiscoveryRequestedEditionId[\s\S]{0,260}?\.toBe\(importedIdentity!\.edition\.id\)/)
  assert.match(productionRecommendationSpec,
    /report\.handoffDiscoveryIdentityMatched[\s\S]{0,260}?\.toBe\(true\)/)
  assert.match(productionRecommendationWorkflow,
    /expected_player_count:[\s\S]{0,180}?default: 5[\s\S]{0,260}?maximum_duration_minutes:[\s\S]{0,180}?default: 90/)
  assert.match(productionRecommendationSpec,
    /requestBody\?\.message[\s\S]{0,180}?\.toBe\(SELECTION_PROMPT\)/)
  assert.match(productionRecommendationSpec,
    /recommendationProfileHardConstraintsMatched[\s\S]{0,500}?recommendationCardsHardConstraintsMatched[\s\S]{0,500}?recommendationFitClaimsHardConstraintsMatched/)
  assert.match(productionRecommendationSpec,
    /gamesMatchCatalogBggType\(result\.games, expectedGameType\)[\s\S]{0,260}?claim\.subject === 'bggType'/)
  assert.match(productionRecommendationSpec,
    /isSafeInteger\(maximumDuration\)[\s\S]{0,220}?isFiniteNumber\(game\.averageWeight\)/)
  assert.match(productionRecommendationSpec,
    /isExactPreparationRunRequest\(requestBody, existing\.teachingPreparationRunId\)/)
  assert.match(productionRecommendationSpec,
    /ensureCurrentResponse\.status\(\)[\s\S]{0,220}?\.toBe\(202\)/)
  assert.match(productionRecommendationSpec,
    /handoffFreshnessResponseIdentityMatched = ensuredExisting !== null[\s\S]{0,320}?ensuredExisting\.teachingPreparationRunId === existingRestore\.teachingPreparationRunId[\s\S]{0,180}?handoffFreshnessResponseEligible = ensuredExisting\?\.freshnessEligible/)
  assert.match(sanitizerStep,
    /handoffFreshnessRequestPreparationRunMatched == true[\s\S]{0,180}?handoffFreshnessResponseStatus == 202[\s\S]{0,180}?handoffFreshnessResponseIdentityMatched == true[\s\S]{0,180}?handoffFreshnessResponseEligible == true/)
  assert.match(productionRecommendationSpec,
    /data-acquisition-mode/)
  assert.match(productionRecommendationSpec,
    /terminalFailure !== null\) throw new Error\(terminalFailure\)/)
  assert.match(productionRecommendationSpec,
    /handoffOfficialMutationAttemptedPaths\.push/)
  assert.match(productionRecommendationSpec,
    /RESTORED_EXISTING/)
  assert.match(productionRecommendationSpec,
    /expect\(report\.handoffRulebookImportStarted,[\s\S]{0,180}?\.toBe\(false\)/)
  assert.match(productionRecommendationSpec,
    /async function expectUsablePlayerSurface\(element: Locator, message: string\)/)
  assert.match(productionRecommendationSpec,
    /const hit = document\.elementFromPoint/)
  assert.match(productionRecommendationSpec,
    /expectUsablePlayerSurface\([\s\S]{0,220}?candidateItem/)
  assert.match(productionRecommendationSpec,
    /expectUsablePlayerSurface\([\s\S]{0,220}?candidateAction/)
  assert.match(productionRecommendationSpec,
    /handoffCandidateIdentityOrderConsistent[\s\S]{0,260}?\.toBe\(true\)/)
  assert.match(productionRecommendationSpec,
    /terminalFailure = 'Production discovery returned candidates but no importable rulebook source'/)
  assert.doesNotMatch(productionRecommendationSpec,
    /readyTeaching|TeachingPlan|Lesson|Answer|teaching-plans|answers\/stream/)
  assert.doesNotMatch(productionRecommendationSpec,
    /TARGET_BGG_ID|TARGET_NAME|gstoneCandidate|gstonegames\.com/)
  assert.doesNotMatch(productionRecommendationWorkflow, /echo "\$player_password"/)
  assert.doesNotMatch(productionRecommendationWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$player_password"/)
  assert.match(productionRecommendationWorkflow,
    /name: Prove credentials are absent and rebuild the allowlisted journey report[\s\S]*?if: always\(\)[\s\S]*?id_ed25519/)
  assert.match(productionRecommendationWorkflow, /test ! -e "\$HOME\/\.ssh\/id_ed25519"/)
  assert.match(productionRecommendationWorkflow, /test ! -e "\$HOME\/\.ssh\/known_hosts"/)
})

test('production recommendation sanitizer rebuilds an allowlisted report after repo code exits', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-recommendation-sanitizer.'))
  const home = join(root, 'home')
  const artifactDirectory = join(root, 'production-recommendation-journey')
  const rawReportPath = join(artifactDirectory, 'journey.raw.json')
  const sanitizedReportPath = join(artifactDirectory, 'journey.json')
  const credentialPath = join(root, 'rulepilot-recommendation-player-credentials')
  try {
    await mkdir(join(home, '.ssh'), { recursive: true })
    await mkdir(artifactDirectory, { recursive: true })
    await writeFile(join(home, '.ssh', 'id_ed25519'), 'deployment-secret-marker')
    await writeFile(join(home, '.ssh', 'known_hosts'), 'production-host-marker')
    await writeFile(credentialPath, 'player-secret-marker')
    await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
      recommendationModelCalls: 3,
      recommendationModelCallElapsedMs: [250, 300, 400],
    })))

    await execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
      env: productionRecommendationSanitizerEnvironment(root),
    })

    const sanitizedText = await readFile(sanitizedReportPath, 'utf8')
    const sanitized = JSON.parse(sanitizedText)
    assert.equal(sanitized.reportSchemaVersion, 1)
    assert.equal(sanitized.recommendationExpectedPlayerCount, 5)
    assert.equal(sanitized.recommendationMaximumDurationMinutes, 90)
    assert.equal(sanitized.recommendationMaximumComplexity, 2.5)
    assert.equal(sanitized.recommendationExpectedGameType, 'party')
    assert.equal(sanitized.recommendationProfileHardConstraintsMatched, true)
    assert.equal(sanitized.recommendationCardsHardConstraintsMatched, true)
    assert.equal(sanitized.recommendationFitClaimsHardConstraintsMatched, true)
    assert.equal(sanitized.recommendationComplexityHardConstraintsMatched, true)
    assert.equal(sanitized.recommendationGameTypeHardConstraintsMatched, true)
    assert.equal(sanitized.recommendationModelCalls, 3)
    assert.deepEqual(sanitized.recommendationModelCallElapsedMs, [250, 300, 400])
    assert.equal(sanitized.handoffRestoredExistingJourney, true)
    assert.equal(sanitized.handoffRestoredImportJobId, '11111111-1111-4111-8111-111111111111')
    assert.equal(sanitized.handoffRestoredDocumentVersionId,
      '44444444-4444-4444-8444-444444444444')
    assert.equal(sanitized.handoffRestoredPreparationRunId,
      '55555555-5555-4555-8555-555555555555')
    assert.equal(sanitized.recommendationProgressEventCount, 1)
    assert.equal(sanitized.recommendationCompletedWorkCount, 1)
    assert.equal(sanitized.handoffOfficialMutationAttemptCount, 1)
    assert.equal(sanitized.rawModelOutputCaptured, false)
    assert.equal(Object.hasOwn(sanitized, 'credentialLeak'), false)
    assert.equal(Object.hasOwn(sanitized, 'recommendationProgressEvents'), false)
    assert.equal(Object.hasOwn(sanitized, 'recommendationCompletedWork'), false)
    assert.equal(Object.hasOwn(sanitized, 'handoffOfficialMutationAttemptedPaths'), false)
    assert.doesNotMatch(sanitizedText, /player-secret-marker|deployment-secret-marker/)
    await assert.rejects(access(rawReportPath))
    await assert.rejects(access(credentialPath))
    await assert.rejects(access(join(home, '.ssh', 'id_ed25519')))
    await assert.rejects(access(join(home, '.ssh', 'known_hosts')))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('production recommendation sanitizer independently rejects a false completed acceptance claim', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-recommendation-sanitizer-incomplete.'))
  const artifactDirectory = join(root, 'production-recommendation-journey')
  const rawReportPath = join(artifactDirectory, 'journey.raw.json')
  const sanitizedReportPath = join(artifactDirectory, 'journey.json')
  try {
    await mkdir(join(root, 'home', '.ssh'), { recursive: true })
    await mkdir(artifactDirectory, { recursive: true })
    await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
      recommendationGameTypeHardConstraintsMatched: false,
    })))

    await assert.rejects(
      execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: productionRecommendationSanitizerEnvironment(root),
      }),
    )
    await assert.rejects(access(rawReportPath))
    await assert.rejects(access(sanitizedReportPath))

    await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
      recommendationModelCalls: 7,
      recommendationModelCallElapsedMs: [100, 100, 100, 100, 100, 100, 100],
    })))
    await assert.rejects(
      execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: productionRecommendationSanitizerEnvironment(root),
      }),
    )
    await assert.rejects(access(rawReportPath))
    await assert.rejects(access(sanitizedReportPath))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('production journey success gates reject diagnostic-only artifacts', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-production-success-gates.'))
  const recommendationDirectory = join(root, 'production-recommendation-journey')
  const ordinaryDirectory = join(root, 'production-ordinary-user-smoke')
  try {
    await mkdir(recommendationDirectory, { recursive: true })
    await mkdir(ordinaryDirectory, { recursive: true })
    await writeFile(join(recommendationDirectory, 'journey.json'), JSON.stringify({
      completed: false,
      stage: 'preflight-failed',
      recommendationOutcome: null,
      recommendationSloMet: null,
      handoffTerminalCategory: 'NOT_OBSERVED',
    }))
    await writeFile(join(ordinaryDirectory, 'summary.json'), JSON.stringify({
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'summary-unavailable',
    }))

    await assert.rejects(execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    }))
    await assert.rejects(execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    }))

    await writeFile(join(recommendationDirectory, 'journey.json'), JSON.stringify({
      completed: true,
      stage: 'completed',
      recommendationOutcome: 'recommendations',
      recommendationSloMet: true,
      handoffTerminalCategory: 'REVIEW',
    }))
    await writeFile(join(ordinaryDirectory, 'summary.json'), JSON.stringify({
      execution: { outcome: 'SUCCEEDED', exitCode: 0 },
      preparationState: 'COMPLETED',
      lessonState: 'COMPLETED',
      lessonStatus: 'COMPLETE',
      answerStatus: 'ANSWERED',
    }))

    await execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    })
    await execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('production recommendation sanitizer rejects a completed discovery claim without a successful exact-edition response', async () => {
  const editionId = '33333333-3333-4333-8333-333333333333'
  for (const [label, corruptedEvidence] of [
    ['response-not-ok', { handoffDiscoveryResponseOk: false }],
    ['rendered-order-mismatch', {
      handoffRenderedCandidateIdentitySha256: '9'.repeat(64),
      handoffCandidateIdentityOrderConsistent: false,
    }],
  ]) {
    const root = await mkdtemp(join(tmpdir(), `rulepilot-recommendation-discovery-${label}.`))
    const artifactDirectory = join(root, 'production-recommendation-journey')
    const rawReportPath = join(artifactDirectory, 'journey.raw.json')
    const sanitizedReportPath = join(artifactDirectory, 'journey.json')
    try {
      await mkdir(join(root, 'home', '.ssh'), { recursive: true })
      await mkdir(artifactDirectory, { recursive: true })
      await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
        handoffDiscoveryRequestedEditionId: editionId,
        handoffDiscoveryResponseStatus: 200,
        handoffDiscoveryResponseOk: true,
        handoffDiscoveryIdentityEditionId: editionId,
        handoffDiscoveryIdentityMatched: true,
        handoffDiscoveryConfigured: true,
        handoffDiscoveryCandidateCount: 1,
        handoffImportableCandidateCount: 1,
        handoffImportableCandidateFound: true,
        handoffDiscoveryCandidateIdentitySha256: '8'.repeat(64),
        handoffRenderedCandidateIdentitySha256: '8'.repeat(64),
        handoffCandidateIdentityOrderConsistent: true,
        handoffDiscoveryElapsedMs: 400,
        handoffTerminalCategory: 'REVIEW',
        handoffRestoredExistingJourney: false,
        handoffRestoredImportJobId: null,
        handoffRestoredDocumentVersionId: null,
        handoffRestoredPreparationRunId: null,
        handoffFreshnessRequestPreparationRunMatched: null,
        handoffFreshnessResponseStatus: null,
        handoffFreshnessResponseIdentityMatched: null,
        handoffFreshnessResponseEligible: null,
        handoffOfficialMutationAttemptedPaths: [],
        handoffStoppedAtDiscoveryBoundary: true,
        ...corruptedEvidence,
      })))

      await assert.rejects(
        execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
          env: productionRecommendationSanitizerEnvironment(root),
        }),
      )
      await assert.rejects(access(rawReportPath))
      await assert.rejects(access(sanitizedReportPath))
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer safely publishes both bounded stream failure diagnostics', async () => {
  for (const code of ['invalid_stream_error', 'unknown_stream_error']) {
    const root = await mkdtemp(join(tmpdir(), `rulepilot-recommendation-sanitizer-${code}.`))
    const artifactDirectory = join(root, 'production-recommendation-journey')
    const rawReportPath = join(artifactDirectory, 'journey.raw.json')
    const sanitizedReportPath = join(artifactDirectory, 'journey.json')
    try {
      await mkdir(join(root, 'home', '.ssh'), { recursive: true })
      await mkdir(artifactDirectory, { recursive: true })
      await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
        completed: false,
        stage: 'recommendation-stream-error',
        recommendationSseTerminalCategory: 'ERROR',
        recommendationSseErrorCode: code,
        recommendationSseFailureBoundary: null,
      })))

      await execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: productionRecommendationSanitizerEnvironment(root),
      })
      const sanitized = JSON.parse(await readFile(sanitizedReportPath, 'utf8'))
      assert.equal(sanitized.completed, false)
      assert.equal(sanitized.stage, 'recommendation-stream-error')
      assert.equal(sanitized.recommendationSseErrorCode, code)
      await assert.rejects(access(rawReportPath))
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer hashes the trusted NFKC trimmed lowercase title term', async () => {
  const normalizedTerm = 'abc'
  const normalizedDigest = createHash('sha256').update(normalizedTerm).digest('hex')
  const rawTermDigest = createHash('sha256').update('  ＡBc  ').digest('hex')
  for (const [label, digest, shouldPass] of [
    ['normalized', normalizedDigest, true],
    ['raw', rawTermDigest, false],
  ]) {
    const root = await mkdtemp(join(tmpdir(), `rulepilot-recommendation-title-${label}.`))
    const artifactDirectory = join(root, 'production-recommendation-journey')
    const rawReportPath = join(artifactDirectory, 'journey.raw.json')
    const sanitizedReportPath = join(artifactDirectory, 'journey.json')
    try {
      await mkdir(join(root, 'home', '.ssh'), { recursive: true })
      await mkdir(artifactDirectory, { recursive: true })
      await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
        expectedRecommendationTitleTermSha256: digest,
      })))
      const execution = execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: {
          ...productionRecommendationSanitizerEnvironment(root),
          RULEPILOT_RECOMMENDATION_EXPECTED_TITLE_TERM: '  ＡBc  ',
        },
      })
      if (shouldPass) {
        await execution
        const sanitized = JSON.parse(await readFile(sanitizedReportPath, 'utf8'))
        assert.equal(sanitized.expectedRecommendationTitleTermSha256, normalizedDigest)
      } else {
        await assert.rejects(execution)
        await assert.rejects(access(sanitizedReportPath))
      }
      await assert.rejects(access(rawReportPath))
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer rejects fractional integers and non-finite numbers', async () => {
  for (const invalid of ['fractional-integer', 'non-finite-number']) {
    const root = await mkdtemp(join(tmpdir(), `rulepilot-recommendation-number-${invalid}.`))
    const artifactDirectory = join(root, 'production-recommendation-journey')
    const rawReportPath = join(artifactDirectory, 'journey.raw.json')
    const sanitizedReportPath = join(artifactDirectory, 'journey.json')
    try {
      await mkdir(join(root, 'home', '.ssh'), { recursive: true })
      await mkdir(artifactDirectory, { recursive: true })
      let report = JSON.stringify(productionRecommendationRawReport({
        recommendationAgentElapsedMs: invalid === 'fractional-integer' ? 800.5 : 800,
      }))
      if (invalid === 'non-finite-number') {
        report = report.replace('"recommendationModelElapsedShare":0.875',
          '"recommendationModelElapsedShare":1e999')
      }
      await writeFile(rawReportPath, report)
      await assert.rejects(
        execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
          env: productionRecommendationSanitizerEnvironment(root),
        }),
      )
      await assert.rejects(access(rawReportPath))
      await assert.rejects(access(sanitizedReportPath))
    } finally {
      await rm(root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer rejects mistyped evidence and deletes the raw report', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-recommendation-sanitizer-invalid.'))
  const artifactDirectory = join(root, 'production-recommendation-journey')
  const rawReportPath = join(artifactDirectory, 'journey.raw.json')
  const sanitizedReportPath = join(artifactDirectory, 'journey.json')
  try {
    await mkdir(join(root, 'home', '.ssh'), { recursive: true })
    await mkdir(artifactDirectory, { recursive: true })
    await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport({
      recommendationModelCalls: 'player-secret-marker',
    })))

    await assert.rejects(
      execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: productionRecommendationSanitizerEnvironment(root),
      }),
    )
    await assert.rejects(access(rawReportPath))
    await assert.rejects(access(sanitizedReportPath))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('production recommendation sanitizer rejects workflow controls outside the fixed game taxonomy', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-recommendation-sanitizer-enum.'))
  const artifactDirectory = join(root, 'production-recommendation-journey')
  const rawReportPath = join(artifactDirectory, 'journey.raw.json')
  try {
    await mkdir(join(root, 'home', '.ssh'), { recursive: true })
    await mkdir(artifactDirectory, { recursive: true })
    await writeFile(rawReportPath, JSON.stringify(productionRecommendationRawReport()))
    await assert.rejects(
      execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
        env: {
          ...productionRecommendationSanitizerEnvironment(root),
          RULEPILOT_RECOMMENDATION_EXPECTED_GAME_TYPE: 'player-secret-marker',
        },
      }),
    )
    await assert.rejects(access(rawReportPath))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('production mutations share one non-cancelling runtime lock', () => {
  for (const workflow of [
    deploymentWorkflow,
    productionRecommendationWorkflow,
    productionOrdinaryUserWorkflow,
    publicLessonCandidateWorkflow,
  ]) {
    assert.match(workflow,
      /concurrency:\s*\n\s+group: production-runtime\s*\n\s+queue: max\s*\n\s+cancel-in-progress: false/)
  }
})

test('public lesson candidate removes deployment authority before repository code runs', () => {
  const checkout = publicLessonCandidateWorkflow.indexOf('uses: actions/checkout@v6')
  const configure = publicLessonCandidateWorkflow.indexOf('name: Configure production SSH trust')
  const manage = publicLessonCandidateWorkflow.indexOf(
    'name: Open an authenticated production tunnel and manage the candidate',
  )
  const repositoryScript = publicLessonCandidateWorkflow.indexOf(
    '/bin/bash scripts/manage-public-lesson-candidate.sh',
  )
  assert.ok(checkout >= 0 && checkout < configure && configure < manage && manage < repositoryScript)
  const jobHeader = publicLessonCandidateWorkflow.slice(
    publicLessonCandidateWorkflow.indexOf('  candidate:'),
    publicLessonCandidateWorkflow.indexOf('    steps:'),
  )
  assert.doesNotMatch(jobHeader, /DEPLOY_|secrets\./)
  assert.match(publicLessonCandidateWorkflow,
    /rm -f "\$HOME\/\.ssh\/id_ed25519" "\$HOME\/\.ssh\/known_hosts"[\s\S]{0,220}?unset DEPLOY_HOST DEPLOY_PATH DEPLOY_SSH_PORT DEPLOY_USER credentials/)
  assert.match(publicLessonCandidateWorkflow,
    /\/usr\/bin\/env -i[\s\S]{0,180}?PATH=\/usr\/bin:\/bin[\s\S]{0,220}?\/bin\/bash scripts\/manage-public-lesson-candidate\.sh/)
  assert.ok(publicLessonCandidateWorkflow.lastIndexOf('rm -f "$HOME/.ssh/id_ed25519"', repositoryScript)
    < repositoryScript)
})

test('production SSH authority is unavailable to build and local verification code', () => {
  const sealJob = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('  seal_source:'),
    deploymentWorkflow.indexOf('  build:'),
  )
  const buildJob = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('  build:'),
    deploymentWorkflow.indexOf('  deploy:'),
  )
  const deployJob = deploymentWorkflow.slice(deploymentWorkflow.indexOf('  deploy:'))
  assert.doesNotMatch(sealJob,
    /secrets\.DEPLOY_|DEPLOY_SSH_PRIVATE_KEY|id_ed25519/)
  assert.doesNotMatch(buildJob,
    /secrets\.DEPLOY_|DEPLOY_SSH_PRIVATE_KEY|id_ed25519/)
  assert.match(buildJob, /actions\/upload-artifact@v7/)
  assert.match(deployJob, /actions\/download-artifact@v8/)
  assert.doesNotMatch(deployJob,
    /actions\/checkout|setup-node|setup-java|npm\s|\.\/mvnw|node scripts\//)
  const preAuthorityDeploy = deployJob.slice(
    deployJob.indexOf('    steps:'),
    deployJob.indexOf('name: Configure production SSH trust'),
  )
  assert.doesNotMatch(preAuthorityDeploy,
    /secrets\.DEPLOY_|DEPLOY_SSH_PRIVATE_KEY|id_ed25519/)
  assert.match(preAuthorityDeploy,
    /Verify the exact artifact set before production authority exists[\s\S]*?sha256sum --check/)

  const localPublicVerification = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf(
      'name: Classify independent public observation without repository code or production SSH authority',
    ),
    deploymentWorkflow.indexOf('name: Verify the candidate publication boundary and commit the release'),
  )
  assert.match(localPublicVerification, /test ! -e "\$HOME\/\.ssh\/id_ed25519"/)
  assert.doesNotMatch(localPublicVerification,
    /secrets\.DEPLOY_|DEPLOY_SSH_PRIVATE_KEY|\bssh\s|\bscp\s/)
  assert.match(localPublicVerification,
    /\/api\/public\/release[\s\S]*?no-store[\s\S]*?\/api\/v1\/model-configuration[\s\S]*?\/api\/v1\/bgg\/recommendations/)
  assert.doesNotMatch(localPublicVerification, /node\s|npm\s|scripts\//)
  assert.doesNotMatch(localPublicVerification, /\bcurl\b/)
  assert.match(deploymentWorkflow,
    /name: Remove runner-side production credentials[\s\S]*?if: always\(\)[\s\S]*?id_ed25519/)
})

test('deployment seals control-plane code before build and keeps it isolated through deploy', () => {
  const sealStart = deploymentWorkflow.indexOf('  seal_source:')
  const buildStart = deploymentWorkflow.indexOf('  build:')
  const deployStart = deploymentWorkflow.indexOf('  deploy:')
  assert.ok(sealStart >= 0 && sealStart < buildStart && buildStart < deployStart)
  const sealJob = deploymentWorkflow.slice(sealStart, buildStart)
  const buildJob = deploymentWorkflow.slice(buildStart, deployStart)
  const deployJob = deploymentWorkflow.slice(deployStart)

  assert.match(sealJob, /ref: \$\{\{ github\.sha \}\}/)
  assert.match(sealJob, /WORKFLOW_SHA: \$\{\{ github\.sha \}\}/)
  assert.match(sealJob, /QUALIFIED_SHA: \$\{\{ github\.event\.workflow_run\.head_sha \}\}/)
  assert.match(sealJob,
    /deploy_sha=\$\(git rev-parse HEAD\)[\s\S]*?"\$deploy_sha" == "\$WORKFLOW_SHA"[\s\S]*?"\$deploy_sha" == "\$QUALIFIED_SHA"[\s\S]*?"\$deploy_sha" == "\$current_main_sha"/)
  assert.match(sealJob,
    /git archive --format=tar --prefix=\.\/ "\$WORKFLOW_SHA"[\s\S]*?gzip -n > "\$control_plane_dir\/\$release_bundle"/)
  assert.match(sealJob,
    /deploy_tree=\$\(git rev-parse "\$\{deploy_sha\}\^\{tree\}"\)/)
  assert.match(sealJob,
    /name: rulepilot-control-plane-\$\{\{ steps\.release_identity\.outputs\.release_id \}\}[\s\S]*?path: \$\{\{ runner\.temp \}\}\/rulepilot-control-plane/)
  assert.doesNotMatch(sealJob,
    /actions\/setup-(?:java|node)|\.\/mvnw|\bnpm\b|\bdocker\b|\bmake\b|(?:bash|sh) [^\n]*scripts\//)

  assert.match(buildJob, /needs: seal_source/)
  assert.match(buildJob,
    /name: rulepilot-control-plane-\$\{\{ needs\.seal_source\.outputs\.release_id \}\}/)
  assert.match(buildJob,
    /actual_control_plane_sha256=.*?[\s\S]*?"\$actual_control_plane_sha256" == "\$CONTROL_PLANE_SHA256"/)
  assert.match(buildJob,
    /gzip -dc "\$control_plane_dir\/\$release_bundle" > "\$release_tar"[\s\S]*?git get-tar-commit-id < "\$release_tar"\)[\s\S]*?"\$archive_commit" == "\$DEPLOY_SHA"/)
  assert.match(buildJob,
    /tar -xf "\$release_tar" -C "\$source_root"/)
  assert.match(buildJob,
    /working-directory: \$\{\{ runner\.temp \}\}\/rulepilot-build-source\/backend/)
  assert.match(buildJob,
    /path: \$\{\{ runner\.temp \}\}\/rulepilot-release-artifacts/)
  assert.doesNotMatch(buildJob, /actions\/checkout|git archive|tar[\s\S]{0,160}?--exclude/)
  const buildVerification = buildJob.indexOf('name: Verify and extract an isolated sealed source copy')
  const firstRepositoryExecution = buildJob.indexOf('name: Build immutable deployment artifacts')
  assert.ok(buildVerification >= 0 && buildVerification < firstRepositoryExecution)
  const setupJava = buildJob.indexOf('- uses: actions/setup-java@v5')
  assert.ok(setupJava >= 0 && setupJava < firstRepositoryExecution)
  const isolatedToolchainSetup = buildJob.slice(
    setupJava,
    firstRepositoryExecution,
  )
  assert.doesNotMatch(isolatedToolchainSetup, /\n\s+cache:/)
  assert.doesNotMatch(isolatedToolchainSetup, /cache-dependency-path:/)
  assert.match(isolatedToolchainSetup,
    /actions\/setup-node@v6[\s\S]*?package-manager-cache: false/)

  assert.match(deployJob, /needs: \[seal_source, build\]/)
  assert.match(deployJob,
    /name: Download the sealed production control plane[\s\S]*?path: \$\{\{ runner\.temp \}\}\/rulepilot-sealed-control-plane/)
  assert.match(deployJob,
    /name: Download immutable release artifacts[\s\S]*?path: \$\{\{ runner\.temp \}\}\/rulepilot-runtime-artifacts/)
  assert.match(deployJob,
    /find "\$control_plane_dir"[^\n]*== 3[\s\S]*?find "\$runtime_artifact_dir"[^\n]*== 5/)
  assert.match(deployJob,
    /sha256sum --check "\$control_plane_manifest"[\s\S]*?sha256sum --check release-artifacts\.sha256/)
  assert.match(deployJob,
    /actual_control_plane_sha256=.*?[\s\S]*?"\$actual_control_plane_sha256" == "\$CONTROL_PLANE_SHA256"[\s\S]*?gzip -dc "\$control_plane_dir\/\$release_bundle" > "\$release_tar"[\s\S]*?git get-tar-commit-id < "\$release_tar"\)[\s\S]*?"\$archive_commit" == "\$DEPLOY_SHA"/)
  assert.doesNotMatch(deploymentWorkflow,
    /gzip -dc [^\n]*\| git get-tar-commit-id/)
  assert.match(deployJob,
    /rulepilot-sealed-control-plane\/rulepilot-release-\$\{DEPLOY_RELEASE_ID\}\.tar\.gz[\s\S]*?\/tmp\/rulepilot-\$\{DEPLOY_RELEASE_ID\}\.tar\.gz/)
  assert.doesNotMatch(deployJob,
    /actions\/checkout|actions\/setup-(?:java|node)|rulepilot-build-source|\.\/mvnw|\bnpm\b|\bdocker build(?:\s|$)/)
  const remoteExtraction = deployJob.indexOf('tar -xzf "${archive}" -C "${release_dir}"')
  const necessaryProductionStart = deployJob.indexOf('make production-up')
  assert.ok(remoteExtraction >= 0 && remoteExtraction < necessaryProductionStart)
  const activationStep = deployJob.slice(
    deployJob.indexOf('name: Activate release and verify production health'),
    deployJob.indexOf('name: Classify independent public observation'),
  )
  assert.match(activationStep, /"\$guard_script" assert-activation-held/)
  assert.match(activationStep,
    /RULEPILOT_PREBUILT_BACKEND_IMAGE=true[\s\S]*?RULEPILOT_PREBUILT_FRONTEND_IMAGE=true[\s\S]*?make production-up/)
  assert.doesNotMatch(activationStep,
    /sh scripts\/run-production\.sh config|scripts\/cleanup-production-staging\.sh/)
})

test('every mutation after checkpoint failure restores the validated environment and release', () => {
  const checkpoint = deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint')
  const synchronization = deploymentWorkflow.indexOf(
    'name: Synchronize protected BGG credential and managed runtime configuration',
  )
  const activation = deploymentWorkflow.indexOf('name: Activate release and verify production health')
  const availability = deploymentWorkflow.indexOf('name: Classify independent public observation')
  const rollback = deploymentWorkflow.indexOf(
    'name: Roll back any uncommitted production mutation',
  )

  assert.ok(checkpoint >= 0)
  assert.ok(checkpoint < synchronization)
  assert.ok(synchronization < activation)
  assert.ok(activation < availability)
  assert.ok(availability < rollback)
  assert.match(deploymentWorkflow,
    /always\(\) &&[\s\S]*?steps\.rollback_checkpoint\.outcome == 'success' &&[\s\S]*?steps\.public_availability\.outcome != 'success'/)
  const rollbackCondition = deploymentWorkflow.slice(rollback, deploymentWorkflow.indexOf('shell: bash', rollback))
  assert.doesNotMatch(rollbackCondition, /activate_release/)
})

test('release guard stays live from checkpoint through the public commit', () => {
  const checkpointStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint'),
    deploymentWorkflow.indexOf(
      'name: Synchronize protected BGG credential and managed runtime configuration',
    ),
  )
  const synchronizationStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf(
      'name: Synchronize protected BGG credential and managed runtime configuration',
    ),
    deploymentWorkflow.indexOf('name: Activate release and verify production health'),
  )
  const activationStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Activate release and verify production health'),
    deploymentWorkflow.indexOf('name: Read bounded production preflight credentials'),
  )
  const publicGateStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Read bounded production preflight credentials'),
    deploymentWorkflow.indexOf(
      'name: Roll back any uncommitted production mutation',
    ),
  )

  assert.match(checkpointStep, /previous_release_id=\$\("\$guard_script" checkpoint/)
  assert.doesNotMatch(checkpointStep, /"\$guard_script" start/)
  const checkpointGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('checkpoint()'),
    productionReleaseGuard.indexOf('require_checkpoint()'),
  )
  assert.match(checkpointGuard,
    /snapshot_environment "\$application_root" "\$state_dir"[\s\S]*?bash "\$0" start/)
  assert.match(checkpointGuard,
    /flock -x 9[\s\S]*?claim_active_transaction_held[\s\S]*?current_release=\$\(readlink -f/)
  assert.match(checkpointGuard, /bash "\$0" start[^\n]*9>&-/)
  assert.match(synchronizationStep,
    /flock -x 9[\s\S]*?"\$guard_script" arm[\s\S]*?done < "\$env_file" > "\$temporary_env"[\s\S]*?mv "\$temporary_env" "\$env_file"/)
  assert.match(activationStep,
    /make production-up[\s\S]*?ln -sfn "\$\{release_dir\}" "\$\{application_root\}\/current"[\s\S]*?"\$guard_script" heartbeat/)
  assert.doesNotMatch(activationStep, /"\$guard_script" arm/)
  assert.match(publicGateStep,
    /"\$guard_script" heartbeat[\s\S]*?cleanup_deploy_key[\s\S]*?test ! -e "\$HOME\/\.ssh\/id_ed25519"[\s\S]*?\/api\/public\/release[\s\S]*?\/api\/v1\/model-configuration[\s\S]*?DEPLOY_SSH_PRIVATE_KEY[\s\S]*?"\$guard_script" heartbeat[\s\S]*?"\$guard_script" commit/)
  assert.match(productionReleaseGuard,
    /heartbeat\(\)[\s\S]*?active_release=\$\(readlink -f "\$application_root\/current"[\s\S]*?"\$active_release" == "\$releases_root\/\$release_id"/)
  for (const command of [
    'checkpoint',
    'start',
    'arm',
    'assert-activation-held',
    'heartbeat',
    'commit',
  ]) {
    assert.match(productionReleaseGuard, new RegExp(`^\\t${command}\\)`, 'm'))
  }
  assert.match(productionReleaseGuard, /atomic_write "\$state_dir\/committed" "\$release_id"/)
  assert.match(productionReleaseGuard,
    /watchdog\(\)[\s\S]*?bash "\$0" rollback-if-stale[\s\S]*?retrying before the deadline/)
  assert.match(productionReleaseGuard,
    /bash "\$0" finalize-deadline[\s\S]*?record_watchdog_failure[\s\S]*?return "\$action_status"/)
  assert.match(productionReleaseGuard,
    /start_watchdog\(\)[\s\S]*?watchdog_ready_matches "\$state_dir" "\$generation"[\s\S]*?watchdog_process_matches "\$process_id" "\$generation"[\s\S]*?Rollback watchdog did not become ready/)
  for (const guardedAction of ['heartbeat()', 'arm()', 'assert_activation_held()']) {
    const start = productionReleaseGuard.indexOf(`\n${guardedAction}`)
    assert.ok(start >= 0)
    const body = productionReleaseGuard.slice(start, productionReleaseGuard.indexOf('\n}', start) + 2)
    assert.match(body, /require_live_watchdog "\$state_dir"/)
  }
  assert.match(productionReleaseGuard,
    /claim_active_transaction_held\(\)[\s\S]*?Another production release transaction is still active/)
  const activationCurrentRead = activationStep.indexOf(
    'current_release=$(readlink -f "${application_root}/current"',
  )
  const activationLock = activationStep.indexOf('flock -x 9')
  const activationAssertion = activationStep.indexOf(
    '"$guard_script" assert-activation-held',
    activationLock,
  )
  const firstActivationMutation = activationStep.indexOf('find /tmp', activationAssertion)
  assert.ok(activationLock >= 0
    && activationLock < activationAssertion
    && activationAssertion < activationCurrentRead
    && activationCurrentRead < firstActivationMutation)
})

test('late activation fails closed after the watchdog has rolled the transaction back', async (context) => {
  const assertionGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('assert_activation_held()'),
    productionReleaseGuard.indexOf('finalize_unchanged_held()'),
  )
  assert.match(assertionGuard,
    /transaction_terminal "\$state_dir"[\s\S]*?require_active_transaction[\s\S]*?"\$state_dir\/armed"[\s\S]*?"\$active_release" == "\$releases_root\/\$previous_release_id"[\s\S]*?touch "\$state_dir\/lease"/)

  if (process.platform !== 'linux') {
    context.skip('late activation ownership is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const mutationMarker = join(fixture.root, 'late-activation-mutated')
  try {
    await mkdir(fixture.guardState, { recursive: true })
    await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, {
      mode: 0o600,
    })
    await writeFile(join(fixture.guardState, 'armed'), `${fixture.release}\n`, { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'rolled-back'), `${fixture.previous}\n`, {
      mode: 0o600,
    })

    await assert.rejects(
      execFileAsync('bash', [
        '-c',
        'set -Eeuo pipefail; exec 9>"$2/deployment.lock"; flock -x 9; bash "$1" assert-activation-held "$2" "$3" "$4"; touch "$5"',
        'late-activation-test',
        productionReleaseGuardPath,
        fixture.root,
        fixture.release,
        fixture.previous,
        mutationMarker,
      ], { env: fixture.processEnvironment }),
      (error) => {
        assert.match(error.stderr, /Terminal release checkpoint cannot be activated/)
        return true
      },
    )
    await assert.rejects(access(mutationMarker))
    assert.equal(await lstat(fixture.current).then((entry) => entry.isSymbolicLink()), true)
    assert.equal(await realpath(fixture.current), fixture.previousRelease)
    assert.equal(await readFile(join(fixture.guardState, 'rolled-back'), 'utf8'), `${fixture.previous}\n`)
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('release ownership rejects overlap without orphaning the next transaction or leaking the lock', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production transaction ownership is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const overlapping = `${'c'.repeat(40)}-102-1`
  const following = `${'d'.repeat(40)}-103-1`
  const guardsRoot = join(fixture.root, 'deployment-guards')
  const invoke = (command, release) => execFileAsync('bash', [
    productionReleaseGuardPath,
    command,
    fixture.root,
    release,
    ...(command === 'checkpoint' ? [] : [fixture.previous]),
  ], {
    env: {
      ...fixture.processEnvironment,
      RULEPILOT_TEST_CURRENT_MAIN_SHA: release.slice(0, 40),
    },
  })
  const stopWatchdog = async (release) => {
    try {
      const pid = Number.parseInt(
        await readFile(join(guardsRoot, release, 'watchdog.pid'), 'utf8'),
        10,
      )
      if (Number.isSafeInteger(pid) && pid > 0) process.kill(pid, 'SIGTERM')
    } catch {
      // A terminal watchdog may already have exited.
    }
  }
  try {
    await invoke('checkpoint', fixture.release)
    await execFileAsync('bash', [
      '-c',
      'set -Eeuo pipefail; exec 8>"$1/deployment.lock"; flock -w 2 -x 8',
      'release-lock-probe',
      fixture.root,
    ])

    await assert.rejects(
      invoke('checkpoint', overlapping),
      (error) => {
        assert.match(error.stderr, /Another production release transaction is still active/)
        return true
      },
    )
    await assert.rejects(access(join(guardsRoot, overlapping, 'previous-release')))

    await invoke('finalize-unchanged', fixture.release)
    await assert.rejects(access(join(guardsRoot, 'active-transaction')))

    const accepted = await invoke('checkpoint', following)
    assert.equal(accepted.stdout.trim(), fixture.previous)
    assert.equal(
      await readFile(join(guardsRoot, 'active-transaction'), 'utf8'),
      `${following}\n`,
    )
    await invoke('finalize-unchanged', following)
  } finally {
    await stopWatchdog(fixture.release)
    await stopWatchdog(following)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('a new checkpoint uses the current guard to recover one armed stale predecessor', async (context) => {
  const checkpointGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('claim_active_transaction_held()'),
    productionReleaseGuard.indexOf('release_active_transaction_held()'),
  )
  assert.match(checkpointGuard,
    /nonterminal_count <= 1[\s\S]*?recover_stale_transaction_held "\$application_root" "\$nonterminal_id"/)
  assert.match(checkpointGuard,
    /"\$state_dir\/armed"[\s\S]*?"\$state_dir\/lease"[\s\S]*?now - lease_epoch < LEASE_STALE_SECONDS[\s\S]*?rollback_held "\$application_root" "\$release_id" "\$previous_release_id"/)
  assert.match(checkpointGuard,
    /transaction_terminal "\$state_dir"[\s\S]*?active_transaction_file "\$application_root"/)

  if (process.platform !== 'linux') {
    context.skip('stale transaction takeover is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const following = `${'d'.repeat(40)}-103-1`
  const guardsRoot = join(fixture.root, 'deployment-guards')
  const followingState = join(guardsRoot, following)
  const invoke = (command, release) => execFileAsync('bash', [
    productionReleaseGuardPath,
    command,
    fixture.root,
    release,
    ...(command === 'checkpoint' ? [] : [fixture.previous]),
  ], {
    env: {
      ...fixture.processEnvironment,
      RULEPILOT_TEST_CURRENT_MAIN_SHA: release.slice(0, 40),
    },
  })
  try {
    const { snapshot } = await seedArmedReleaseGuard(fixture, 0)
    await writeFile(join(fixture.guardState, 'watchdog-failed'), 'deadline-recovery:exit-75\n', {
      mode: 0o600,
    })

    const accepted = await invoke('checkpoint', following)

    assert.equal(accepted.stdout.trim(), fixture.previous)
    assert.match(accepted.stderr, /Recovering previous production transaction[\s\S]*stale lease after watchdog failure/)
    assert.equal(await realpath(fixture.current), fixture.previousRelease)
    assert.equal(
      await readFile(join(fixture.guardState, 'rolled-back'), 'utf8'),
      `${fixture.previous}\n`,
    )
    await assert.rejects(access(snapshot))
    await assert.rejects(access(join(fixture.guardState, 'watchdog-failed')))
    assert.equal(
      await readFile(join(guardsRoot, 'active-transaction'), 'utf8'),
      `${following}\n`,
    )
    assert.equal(
      await readFile(join(followingState, 'previous-release'), 'utf8'),
      `${fixture.previous}\n`,
    )
    await invoke('finalize-unchanged', following)
  } finally {
    try {
      const pid = Number.parseInt(await readFile(join(followingState, 'watchdog.pid'), 'utf8'), 10)
      if (Number.isSafeInteger(pid) && pid > 0) process.kill(pid, 'SIGTERM')
    } catch {
      // The following watchdog may already have observed its terminal marker.
    }
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('a fresh predecessor lease rejects checkpoint takeover even after watchdog failure', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('fresh transaction takeover rejection is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const following = `${'e'.repeat(40)}-104-1`
  const ownership = join(fixture.root, 'deployment-guards', 'active-transaction')
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  try {
    await seedArmedReleaseGuard(fixture, 0)
    const fresh = new Date()
    await utimes(join(fixture.guardState, 'lease'), fresh, fresh)
    await writeFile(join(fixture.guardState, 'watchdog-failed'), 'deadline-recovery:exit-75\n', {
      mode: 0o600,
    })

    await assert.rejects(
      execFileAsync('bash', [productionReleaseGuardPath, 'checkpoint', fixture.root, following], {
        env: {
          ...fixture.processEnvironment,
          RULEPILOT_TEST_CURRENT_MAIN_SHA: following.slice(0, 40),
        },
      }),
      (error) => {
        assert.match(error.stderr, /still has a fresh lease/)
        return true
      },
    )

    assert.equal(await readFile(ownership, 'utf8'), `${fixture.release}\n`)
    assert.equal(await realpath(fixture.current), fixture.candidateRelease)
    await access(snapshot)
    await access(join(fixture.guardState, 'watchdog-failed'))
    await assert.rejects(access(join(fixture.root, 'deployment-guards', following, 'previous-release')))
    await assert.rejects(access(join(fixture.guardState, 'rolled-back')))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('an unarmed stale predecessor is never taken over by a new checkpoint', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('unarmed stale transaction rejection is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const following = `${'f'.repeat(40)}-105-1`
  const ownership = join(fixture.root, 'deployment-guards', 'active-transaction')
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  try {
    await seedArmedReleaseGuard(fixture, 0)
    await rm(join(fixture.guardState, 'armed'))
    await writeFile(join(fixture.guardState, 'watchdog-failed'), 'deadline-recovery:exit-75\n', {
      mode: 0o600,
    })

    await assert.rejects(
      execFileAsync('bash', [productionReleaseGuardPath, 'checkpoint', fixture.root, following], {
        env: {
          ...fixture.processEnvironment,
          RULEPILOT_TEST_CURRENT_MAIN_SHA: following.slice(0, 40),
        },
      }),
      (error) => {
        assert.match(error.stderr, /unarmed checkpoint requires the watchdog/)
        return true
      },
    )

    assert.equal(await readFile(ownership, 'utf8'), `${fixture.release}\n`)
    assert.equal(await realpath(fixture.current), fixture.candidateRelease)
    await access(snapshot)
    await access(join(fixture.guardState, 'watchdog-failed'))
    await assert.rejects(access(join(fixture.guardState, 'rolled-back')))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('stale qualified commits cannot create a production checkpoint', async (context) => {
  const releaseIdentityStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Verify and seal the exact qualified Git tree'),
    deploymentWorkflow.indexOf('name: Publish the sealed production control plane'),
  )
  assert.match(releaseIdentityStep,
    /git fetch --no-tags --depth=3 origin \+refs\/heads\/main:refs\/remotes\/origin\/main/)
  assert.match(releaseIdentityStep,
    /deploy_sha=\$\(git rev-parse HEAD\)[\s\S]*?current_main_sha=\$\(git rev-parse refs\/remotes\/origin\/main\)[\s\S]*?"\$deploy_sha" == "\$WORKFLOW_SHA"[\s\S]*?"\$deploy_sha" == "\$QUALIFIED_SHA"[\s\S]*?"\$deploy_sha" == "\$current_main_sha"/)
  const checkpointGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('checkpoint()'),
    productionReleaseGuard.indexOf('require_checkpoint()'),
  )
  const lock = checkpointGuard.indexOf('flock -x 9')
  const currentMainGate = checkpointGuard.indexOf('require_current_qualified_main "$release_id"', lock)
  const firstMutation = checkpointGuard.indexOf('claim_active_transaction_held', currentMainGate)
  assert.match(productionReleaseGuard,
    /require_current_qualified_main\(\)[\s\S]*?timeout 20s git ls-remote[\s\S]*?refs\/heads\/main[\s\S]*?Candidate release is no longer/)
  assert.ok(lock >= 0 && lock < currentMainGate && currentMainGate < firstMutation)

  if (process.platform !== 'linux') {
    context.skip('stale main rejection is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  try {
    await assert.rejects(
      execFileAsync(
        'bash',
        [productionReleaseGuardPath, 'checkpoint', fixture.root, fixture.release],
        {
          env: {
            ...fixture.processEnvironment,
            RULEPILOT_TEST_CURRENT_MAIN_SHA: 'c'.repeat(40),
          },
        },
      ),
      (error) => {
        assert.match(error.stderr, /no longer the current qualified main revision/)
        return true
      },
    )
    await assert.rejects(access(join(fixture.root, 'deployment-guards', 'active-transaction')))
    await assert.rejects(access(join(fixture.guardState, 'previous-release')))
    assert.equal(await realpath(fixture.current), fixture.previousRelease)
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('an unpublished checkpoint failure removes ownership and cannot poison the next release', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production checkpoint failure cleanup is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const following = `${'e'.repeat(40)}-104-1`
  const invoke = (command, release) => execFileAsync('bash', [
    productionReleaseGuardPath,
    command,
    fixture.root,
    release,
    ...(command === 'checkpoint' ? [] : [fixture.previous]),
  ], {
    env: {
      ...fixture.processEnvironment,
      RULEPILOT_TEST_CURRENT_MAIN_SHA: release.slice(0, 40),
      RULEPILOT_TEST_FAIL_ENV_SNAPSHOT: command === 'checkpoint' && release === fixture.release
        ? 'true'
        : '',
    },
  })
  const stopWatchdog = async (release) => {
    try {
      const pid = Number.parseInt(
        await readFile(join(fixture.root, 'deployment-guards', release, 'watchdog.pid'), 'utf8'),
        10,
      )
      if (Number.isSafeInteger(pid) && pid > 0) process.kill(pid, 'SIGTERM')
    } catch {
      // A terminal watchdog may already have exited.
    }
  }
  try {
    await assert.rejects(invoke('checkpoint', fixture.release))
    await assert.rejects(access(join(fixture.root, 'deployment-guards', 'active-transaction')))
    await assert.rejects(access(join(fixture.guardState, 'previous-release')))

    await rm(fixture.guardState, { recursive: true, force: true })
    const accepted = await invoke('checkpoint', following)
    assert.equal(accepted.stdout.trim(), fixture.previous)
    await invoke('finalize-unchanged', following)
  } finally {
    await stopWatchdog(following)
    await rm(fixture.root, { recursive: true, force: true })
  }
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
    await writeFile(join(root, 'deployment-guards', 'active-transaction'), `${release}\n`, {
      mode: 0o600,
    })
    await writeFile(join(state, 'previous-release'), `${previous}\n`, { mode: 0o600 })
    await writeFile(join(state, 'environment.snapshot'), 'CHECKPOINT=true\n', { mode: 0o600 })
    await writeFile(join(state, 'armed'), `${release}\n`, { mode: 0o600 })
    await writeFile(lease, '')
    const stale = new Date(Date.now() - 10 * 60 * 1000)
    await utimes(lease, stale, stale)
    await execFileAsync('bash', [
      '-c',
      'set -Eeuo pipefail; exec 8>"$2/deployment.lock"; flock -x 8; bash "$1" rollback-if-stale "$2" "$3" "$4" & guard_pid=$!; sleep 0.2; touch "$2/deployment-guards/$3/lease"; flock -u 8; wait "$guard_pid"',
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

test('watchdog start replaces a live but unrelated reused PID with a release generation', async (context) => {
  const startGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('watchdog_process_matches()'),
    productionReleaseGuard.indexOf('\ncase "${1:-}" in'),
  )
  assert.match(startGuard,
    /\/proc\/\$process_id\/cmdline[\s\S]*?watchdog-generation[\s\S]*?date \+%s%N[\s\S]*?"\$generation"/)

  if (process.platform !== 'linux') {
    context.skip('watchdog generation identity is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  try {
    await mkdir(fixture.guardState, { recursive: true })
    await writeFile(
      join(fixture.root, 'deployment-guards', 'active-transaction'),
      `${fixture.release}\n`,
      { mode: 0o600 },
    )
    await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'environment.snapshot'), 'CHECKPOINT=true\n', { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'watchdog.pid'), `${process.pid}\n`, { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'watchdog-generation'), 'unrelated-live-process\n', {
      mode: 0o600,
    })

    await invokeReleaseGuard(fixture, 'start')

    const replacementPid = Number.parseInt(
      await readFile(join(fixture.guardState, 'watchdog.pid'), 'utf8'),
      10,
    )
    const generation = (await readFile(
      join(fixture.guardState, 'watchdog-generation'),
      'utf8',
    )).trim()
    const readyGeneration = (await readFile(
      join(fixture.guardState, 'watchdog-ready'),
      'utf8',
    )).trim()
    assert.notEqual(replacementPid, process.pid)
    assert.match(generation, /^\d+-\d+-\d+$/)
    assert.equal(readyGeneration, generation)
    process.kill(replacementPid, 0)
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('watchdog startup fails closed when the detached child exits before publishing readiness', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('watchdog readiness failure is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = join(fixture.root, 'production-release-guard.no-ready.sh')
  const noReadyGuard = productionReleaseGuard.replace(
    '\tatomic_write "$state_dir/watchdog-ready" "$generation"',
    '\treturn 73',
  )
  assert.notEqual(noReadyGuard, productionReleaseGuard)
  try {
    await writeFile(guardPath, noReadyGuard, { mode: 0o755 })
    await mkdir(fixture.guardState, { recursive: true })
    await writeFile(
      join(fixture.root, 'deployment-guards', 'active-transaction'),
      `${fixture.release}\n`,
      { mode: 0o600 },
    )
    await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'environment.snapshot'), 'CHECKPOINT=true\n', { mode: 0o600 })

    await assert.rejects(
      execFileAsync('bash', [guardPath, 'start', fixture.root, fixture.release, fixture.previous], {
        env: fixture.processEnvironment,
      }),
      /Rollback watchdog did not become ready/,
    )
    await assert.rejects(access(join(fixture.guardState, 'watchdog-ready')))
    await assert.rejects(access(join(fixture.guardState, 'watchdog.pid')))
    await assert.rejects(access(join(fixture.guardState, 'watchdog-generation')))
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('activation refreshes its lease after every slow cleanup while still holding the lock', () => {
  const activation = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Activate release and verify production health'),
    deploymentWorkflow.indexOf('name: Read bounded production preflight credentials'),
  )
  const lockAcquired = activation.indexOf('flock -x 9')
  const builderPrune = activation.indexOf('docker builder prune', lockAcquired)
  const imagePrune = activation.indexOf('docker image prune', builderPrune)
  const finalHeartbeat = activation.indexOf(
    '"$guard_script" heartbeat "$application_root" "$release_id" "$previous_release_id"',
    imagePrune,
  )
  const lockScopeEnd = activation.indexOf('          REMOTE', finalHeartbeat)
  assert.ok(lockAcquired >= 0
    && lockAcquired < builderPrune
    && builderPrune < imagePrune
    && imagePrune < finalHeartbeat
    && finalHeartbeat < lockScopeEnd)
})

test('activation diagnostics safely describe containers without a Docker healthcheck', () => {
  const activation = workflowRunBlock(
    deploymentWorkflow,
    'Activate release and verify production health',
  )
  const functionStart = activation.indexOf('safe_container_state()')
  const functionEnd = activation.indexOf('\n}', functionStart) + 2
  assert.ok(functionStart >= 0 && functionEnd > functionStart)
  const diagnostic = activation.slice(functionStart, functionEnd)
  assert.match(diagnostic, /status=\{\{\.State\.Status\}\}/)
  assert.match(diagnostic, /running=\{\{\.State\.Running\}\}/)
  assert.match(diagnostic, /restartCount=\{\{\.RestartCount\}\}/)
  assert.match(diagnostic, /oomKilled=\{\{\.State\.OOMKilled\}\}/)
  assert.match(diagnostic, /exit=\{\{\.State\.ExitCode\}\}/)
  assert.match(diagnostic, /error=\{\{json \.State\.Error\}\}/)
  assert.match(diagnostic, /startedAt=\{\{\.State\.StartedAt\}\}/)
  assert.match(diagnostic, /finishedAt=\{\{\.State\.FinishedAt\}\}/)
  assert.match(diagnostic,
    /health=\{\{with index \.State "Health"\}\}\{\{\.Status\}\}\{\{else\}\}not-configured\{\{end\}\}/)
  assert.doesNotMatch(diagnostic,
    /\.State\.Health|docker logs|\.Config\.Env|printenv|inspect .*\.Config|inspect .*Log/)
})

test('unarmed watchdog deadline records an unchanged terminal and deletes its secret snapshot', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production watchdog finalization is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = await writeFastReleaseGuard(fixture, 1)
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  try {
    await mkdir(fixture.guardState, { recursive: true })
    await writeFile(
      join(fixture.root, 'deployment-guards', 'active-transaction'),
      `${fixture.release}\n`,
      { mode: 0o600 },
    )
    await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, { mode: 0o600 })
    await writeFile(snapshot, 'DEPLOY_MARKER=checkpoint\n', { mode: 0o600 })

    await execFileAsync('bash', [
      guardPath,
      'watchdog',
      fixture.root,
      fixture.release,
      fixture.previous,
    ], { env: fixture.processEnvironment })

    assert.equal(
      await readFile(join(fixture.guardState, 'unchanged'), 'utf8'),
      `${fixture.previous}\n`,
    )
    await assert.rejects(access(snapshot))
    await assert.rejects(access(join(fixture.guardState, 'watchdog-failed')))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('watchdog retries a transient rollback failure and reaches the exact previous terminal', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production watchdog retries are exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = await writeFastReleaseGuard(fixture)
  const { failureCounter, snapshot } = await seedArmedReleaseGuard(fixture, 1)
  try {
    const result = await execFileAsync('bash', [
      guardPath,
      'watchdog',
      fixture.root,
      fixture.release,
      fixture.previous,
    ], {
      env: {
        ...fixture.processEnvironment,
        RULEPILOT_TEST_IMAGE_FAILURE_COUNTER: failureCounter,
      },
    })

    assert.match(result.stderr, /rollback attempt failed[\s\S]*?retrying before the deadline/)
    await access(join(fixture.guardState, 'rolled-back'))
    await assert.rejects(access(snapshot))
    assert.equal(await readFile(failureCounter, 'utf8'), '0\n')
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('watchdog deadline leaves a nonzero diagnostic when its final rollback still fails', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production watchdog terminal failure is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = await writeFastReleaseGuard(fixture, 2)
  const { failureCounter, snapshot } = await seedArmedReleaseGuard(fixture, 99)
  try {
    await assert.rejects(
      execFileAsync('bash', [
        guardPath,
        'watchdog',
        fixture.root,
        fixture.release,
        fixture.previous,
      ], {
        env: {
          ...fixture.processEnvironment,
          RULEPILOT_TEST_IMAGE_FAILURE_COUNTER: failureCounter,
        },
      }),
      (error) => {
        assert.equal(error.code, 75)
        assert.match(error.stderr, /deadline recovery failed[\s\S]*?exit 75/)
        return true
      },
    )

    assert.equal(
      await readFile(join(fixture.guardState, 'watchdog-failed'), 'utf8'),
      'deadline-recovery:exit-75\n',
    )
    await access(snapshot)
    await assert.rejects(access(join(fixture.guardState, 'rolled-back')))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
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

  const endpointGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('compose_loopback_endpoint()'),
    productionReleaseGuard.indexOf('wait_for_http()'),
  )
  assert.match(endpointGuard,
    /docker compose[\s\S]*?port "\$service" "\$container_port"/)
  assert.match(endpointGuard,
    /\^127\\\.0\\\.0\\\.1:[\s\S]*?\^\\\[::1\\\]:[\s\S]*?exactly one loopback endpoint/)
  assert.doesNotMatch(endpointGuard, /sed|BACKEND_PORT|RULEPILOT_HTTP_PORT|\.env.*=|0\.0\.0\.0/)
  assert.match(rollbackGuard,
    /compose_loopback_endpoint "\$previous_release" api 8080[\s\S]*?compose_loopback_endpoint "\$previous_release" frontend 80/)
  assert.match(rollbackGuard,
    /rollback_ready_deadline=.*?ROLLBACK_READY_TIMEOUT_SECONDS[\s\S]*?wait_for_http api[\s\S]*?wait_for_http frontend[\s\S]*?wait_for_worker[\s\S]*?wait_for_http gateway/)
  assert.match(rollbackGuard,
    /https:\/\/rulepilot\.cn\/api\/auth\/csrf[\s\S]*?--noproxy '\*'[\s\S]*?--resolve 'rulepilot\.cn:443:127\.0\.0\.1'/)
  assert.match(rollbackGuard,
    /ln -sfn "\$previous_release"[\s\S]*?readlink -f "\$application_root\/current"[\s\S]*?"\$active_release" == "\$previous_release"[\s\S]*?rolled-back/)
})

test('rollback recovers a delayed candidate through IPv6 loopback and the compatible gateway route', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('rollback IPv6 endpoint validation is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = await writeFastReleaseGuard(fixture)
  const curlLog = join(fixture.root, 'rollback-curl.log')
  const curlFailures = join(fixture.root, 'rollback-curl-failures')
  try {
    await invokeReleaseGuard(fixture, 'checkpoint')
    await invokeReleaseGuard(fixture, 'arm')
    await rm(fixture.current)
    await symlink(fixture.candidateRelease, fixture.current)
    await writeFile(
      fixture.environmentFile,
      'DEPLOY_MARKER=candidate\nBACKEND_PORT=28080\nRULEPILOT_HTTP_PORT=127.0.0.1:28081\n',
      { mode: 0o600 },
    )
    await writeFile(curlFailures, '2\n')

    await execFileAsync(
      'bash',
      [
        guardPath,
        'rollback',
        fixture.root,
        fixture.release,
        fixture.previous,
      ],
      {
        env: {
          ...fixture.processEnvironment,
          RULEPILOT_TEST_API_ENDPOINT: '[::1]:18080',
          RULEPILOT_TEST_FRONTEND_ENDPOINT: '[::1]:18081',
          RULEPILOT_TEST_CURL_LOG: curlLog,
          RULEPILOT_TEST_CURL_FAILURE_COUNTER: curlFailures,
        },
      },
    )

    assert.equal(await readFile(join(fixture.guardState, 'rolled-back'), 'utf8'), `${fixture.previous}\n`)
    await assert.rejects(access(join(fixture.root, 'deployment-guards', 'active-transaction')))
    assert.equal(await realpath(fixture.current), fixture.previousRelease)
    assert.equal(
      await readFile(fixture.environmentFile, 'utf8'),
      'DEPLOY_MARKER=checkpoint\nBACKEND_PORT=18080\nRULEPILOT_HTTP_PORT=127.0.0.1:18081\n',
    )
    const probes = await readFile(curlLog, 'utf8')
    assert.match(probes, /http:\/\/\[::1\]:18080\/actuator\/health/)
    assert.match(probes, /http:\/\/\[::1\]:18081\//)
    assert.match(probes,
      /--noproxy \* --resolve rulepilot\.cn:443:127\.0\.0\.1 https:\/\/rulepilot\.cn\/api\/auth\/csrf/)
    assert.doesNotMatch(probes, /api\/public\/release/)
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

for (const [endpointCase, endpoint] of [
  ['an external IPv4 endpoint', '192.0.2.10:18080'],
  ['an external IPv6 endpoint', '[2001:db8::10]:18080'],
  ['multiple endpoint lines', '127.0.0.1:18080\n127.0.0.1:28080'],
]) {
  test(`rollback refuses ${endpointCase} without publishing a terminal`, async (context) => {
    if (process.platform !== 'linux') {
      context.skip('rollback endpoint rejection is exercised on the Linux CI runner')
      return
    }
    const fixture = await createReleaseGuardFixture()
    const snapshot = join(fixture.guardState, 'environment.snapshot')
    try {
      await invokeReleaseGuard(fixture, 'checkpoint')
      await invokeReleaseGuard(fixture, 'arm')

      await assert.rejects(
        execFileAsync(
          'bash',
          [
            productionReleaseGuardPath,
            'rollback',
            fixture.root,
            fixture.release,
            fixture.previous,
          ],
          {
            env: {
              ...fixture.processEnvironment,
              RULEPILOT_TEST_API_ENDPOINT: endpoint,
            },
          },
        ),
        (error) => {
          assert.match(error.stderr, /must publish exactly one loopback endpoint/)
          return true
        },
      )

      await access(snapshot)
      await assert.rejects(access(join(fixture.guardState, 'rolled-back')))
      assert.equal(
        await readFile(join(fixture.root, 'deployment-guards', 'active-transaction'), 'utf8'),
        `${fixture.release}\n`,
      )
    } finally {
      await stopReleaseGuardWatchdog(fixture)
      await rm(fixture.root, { recursive: true, force: true })
    }
  })
}

test('release transaction restores the 0600 environment before restart and discards it only when terminal', () => {
  const checkpointGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('checkpoint()'),
    productionReleaseGuard.indexOf('require_checkpoint()'),
  )
  const rollbackGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('rollback_held()'),
    productionReleaseGuard.indexOf('\nrollback()'),
  )
  const commitGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('commit_release()'),
    productionReleaseGuard.indexOf('\nwatchdog()'),
  )

  assert.match(productionReleaseGuard,
    /snapshot_environment\(\)[\s\S]*?install -m 0600 "\$source" "\$temporary"[\s\S]*?mv -f "\$temporary" "\$snapshot"/)
  assert.match(productionReleaseGuard,
    /restore_environment\(\)[\s\S]*?install -m 0600 "\$snapshot" "\$temporary"[\s\S]*?mv -f "\$temporary" "\$target"/)
  assert.match(checkpointGuard,
    /atomic_write "\$state_dir\/previous-release"[\s\S]*?snapshot_environment "\$application_root" "\$state_dir"/)

  const restoreEnvironment = rollbackGuard.indexOf('restore_environment "$application_root"')
  const validateBackendImage = rollbackGuard.indexOf('docker image inspect "$backend_image"')
  const validateFrontendImage = rollbackGuard.indexOf('docker image inspect "$frontend_image"')
  const restartPreviousRelease = rollbackGuard.indexOf(
    'up -d --no-build --no-deps api worker frontend gateway',
  )
  const rollbackTerminal = rollbackGuard.indexOf('atomic_write "$state_dir/rolled-back"')
  const rollbackCleanup = rollbackGuard.indexOf('discard_transaction_secrets', rollbackTerminal)
  assert.ok(validateBackendImage >= 0 && validateBackendImage < validateFrontendImage)
  assert.ok(validateFrontendImage < restoreEnvironment && restoreEnvironment < restartPreviousRelease)
  assert.ok(restartPreviousRelease < rollbackTerminal && rollbackTerminal < rollbackCleanup)

  const commitTerminal = commitGuard.indexOf('atomic_write "$state_dir/committed"')
  const commitCleanup = commitGuard.indexOf('discard_transaction_secrets', commitTerminal)
  assert.ok(commitTerminal >= 0 && commitTerminal < commitCleanup)
})

test('release guard restores the exact environment checkpoint and removes the secret snapshot', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production environment rollback is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  try {
    const checkpoint = await invokeReleaseGuard(fixture, 'checkpoint')
    assert.equal(checkpoint.stdout.trim(), fixture.previous)
    assert.equal(
      await readFile(snapshot, 'utf8'),
      'DEPLOY_MARKER=checkpoint\nBACKEND_PORT=18080\nRULEPILOT_HTTP_PORT=127.0.0.1:18081\n',
    )
    assert.equal((await lstat(snapshot)).mode & 0o777, 0o600)

    await invokeReleaseGuard(fixture, 'arm')
    await writeFile(
      fixture.environmentFile,
      'DEPLOY_MARKER=candidate\nBACKEND_PORT=28080\nRULEPILOT_HTTP_PORT=127.0.0.1:28081\n',
    )
    await chmod(fixture.environmentFile, 0o644)
    await invokeReleaseGuard(fixture, 'rollback')

    assert.equal(
      await readFile(fixture.environmentFile, 'utf8'),
      'DEPLOY_MARKER=checkpoint\nBACKEND_PORT=18080\nRULEPILOT_HTTP_PORT=127.0.0.1:18081\n',
    )
    assert.equal((await lstat(fixture.environmentFile)).mode & 0o777, 0o600)
    await access(join(fixture.guardState, 'rolled-back'))
    await assert.rejects(access(snapshot))
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('release guard commit keeps the candidate environment and removes the secret snapshot', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('production environment commit is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  try {
    await invokeReleaseGuard(fixture, 'checkpoint')
    await invokeReleaseGuard(fixture, 'arm')
    await rm(fixture.current)
    await symlink(fixture.candidateRelease, fixture.current)
    await writeFile(
      fixture.environmentFile,
      [
        'DEPLOY_MARKER=candidate',
        'BACKEND_PORT=28080',
        'RULEPILOT_HTTP_PORT=127.0.0.1:28081',
        'RULEPILOT_USER_USERNAME=player',
        'RULEPILOT_USER_PASSWORD=player-secret-marker',
        'BGG_RECOMMENDATION_MODEL_PROVIDER=qwen',
        'BGG_RECOMMENDATION_MODEL=qwen3.8-flash',
        '',
      ].join('\n'),
    )
    await chmod(fixture.environmentFile, 0o600)
    await invokeReleaseGuard(fixture, 'heartbeat')
    await invokeReleaseGuard(fixture, 'commit')

    assert.equal(
      await readFile(fixture.environmentFile, 'utf8'),
      [
        'DEPLOY_MARKER=candidate',
        'BACKEND_PORT=28080',
        'RULEPILOT_HTTP_PORT=127.0.0.1:28081',
        'RULEPILOT_USER_USERNAME=player',
        'RULEPILOT_USER_PASSWORD=player-secret-marker',
        'BGG_RECOMMENDATION_MODEL_PROVIDER=qwen',
        'BGG_RECOMMENDATION_MODEL=qwen3.8-flash',
        '',
      ].join('\n'),
    )
    await access(join(fixture.guardState, 'committed'))
    await assert.rejects(access(snapshot))
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('candidate publication failures preserve exact rollback eligibility', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('candidate publication failure classification is exercised on the Linux CI runner')
    return
  }
  const scenarios = [
    {
      name: 'wrong release identity',
      expectedStatus: 1,
      environment: (fixture) => ({
        RULEPILOT_TEST_RELEASE_BODY: JSON.stringify({
          releaseId: fixture.release,
          commitSha: 'c'.repeat(40),
        }),
      }),
    },
    {
      name: 'invalid release JSON',
      expectedStatus: 1,
      environment: () => ({ RULEPILOT_TEST_RELEASE_BODY: '{' }),
    },
    {
      name: 'candidate environment self-certifies a wrong model',
      expectedStatus: 1,
      candidateModel: 'wrong-model',
      environment: () => ({
        RULEPILOT_TEST_MODEL_BODY: JSON.stringify({
          recommendationModel: { provider: 'qwen', model: 'wrong-model' },
        }),
      }),
    },
    {
      name: 'release identity is cacheable',
      expectedStatus: 1,
      environment: () => ({ RULEPILOT_TEST_CACHE_CONTROL: 'public, max-age=60' }),
    },
    {
      name: 'HTTP/2 framing fails before a complete response',
      expectedStatus: 75,
      environment: () => ({ RULEPILOT_TEST_CANDIDATE_CURL_EXIT: '16' }),
    },
    {
      name: 'response body is truncated',
      expectedStatus: 75,
      environment: () => ({ RULEPILOT_TEST_CANDIDATE_CURL_EXIT: '18' }),
    },
    {
      name: 'TLS shutdown fails before publication is proven',
      expectedStatus: 75,
      environment: () => ({ RULEPILOT_TEST_CANDIDATE_CURL_EXIT: '80' }),
    },
    {
      name: 'HTTP/2 stream resets before a complete response',
      expectedStatus: 75,
      environment: () => ({ RULEPILOT_TEST_CANDIDATE_CURL_EXIT: '92' }),
    },
    {
      name: 'gateway is temporarily unavailable',
      expectedStatus: 75,
      environment: () => ({ RULEPILOT_TEST_CANDIDATE_HTTP_STATUS: '502' }),
    },
  ]

  for (const scenario of scenarios) {
    const fixture = await createReleaseGuardFixture()
    const snapshot = join(fixture.guardState, 'environment.snapshot')
    const ownership = join(fixture.root, 'deployment-guards', 'active-transaction')
    try {
      await invokeReleaseGuard(fixture, 'checkpoint')
      await invokeReleaseGuard(fixture, 'arm')
      await rm(fixture.current)
      await symlink(fixture.candidateRelease, fixture.current)
      await writeFile(
        fixture.environmentFile,
        [
          'DEPLOY_MARKER=candidate',
          'BACKEND_PORT=28080',
          'RULEPILOT_HTTP_PORT=127.0.0.1:28081',
          'RULEPILOT_USER_USERNAME=player',
          'RULEPILOT_USER_PASSWORD=player-secret-marker',
          'BGG_RECOMMENDATION_MODEL_PROVIDER=qwen',
          `BGG_RECOMMENDATION_MODEL=${scenario.candidateModel ?? 'qwen3.8-flash'}`,
          '',
        ].join('\n'),
        { mode: 0o600 },
      )
      await invokeReleaseGuard(fixture, 'heartbeat')

      await assert.rejects(
        execFileAsync(
          'bash',
          [
            productionReleaseGuardPath,
            'commit',
            fixture.root,
            fixture.release,
            fixture.previous,
          ],
          {
            env: {
              ...fixture.processEnvironment,
              ...scenario.environment(fixture),
            },
          },
        ),
        (error) => {
          assert.equal(Number(error.code), scenario.expectedStatus, scenario.name)
          return true
        },
      )

      await assert.rejects(access(join(fixture.guardState, 'committed')), scenario.name)
      await access(snapshot)
      assert.equal(await readFile(ownership, 'utf8'), `${fixture.release}\n`, scenario.name)
      assert.equal(await realpath(fixture.current), fixture.candidateRelease, scenario.name)
    } finally {
      await stopReleaseGuardWatchdog(fixture)
      await rm(fixture.root, { recursive: true, force: true })
    }
  }
})

test('an acknowledged commit retry releases ownership left by a crash after the terminal marker', async (context) => {
  const commitGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('commit_release()'),
    productionReleaseGuard.indexOf('\nrecord_watchdog_failure()'),
  )
  const lock = commitGuard.indexOf('flock -x 9')
  const committedCheck = commitGuard.indexOf('[[ -f "$state_dir/committed" ]]', lock)
  const ownershipRelease = commitGuard.indexOf('release_active_transaction_held', committedCheck)
  assert.ok(lock >= 0 && lock < committedCheck && committedCheck < ownershipRelease)

  if (process.platform !== 'linux') {
    context.skip('commit acknowledgement recovery is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const snapshot = join(fixture.guardState, 'environment.snapshot')
  const ownership = join(fixture.root, 'deployment-guards', 'active-transaction')
  try {
    await invokeReleaseGuard(fixture, 'checkpoint')
    await writeFile(join(fixture.guardState, 'committed'), `${fixture.release}\n`, { mode: 0o600 })

    await invokeReleaseGuard(fixture, 'commit')

    await assert.rejects(access(ownership))
    await assert.rejects(access(snapshot))
  } finally {
    await stopReleaseGuardWatchdog(fixture)
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('ordinary-user production artifacts retain a bounded public status', () => {
  assert.match(productionOrdinaryUserWorkflow, /name: Upload sanitized journey output/)
  assert.match(productionOrdinaryUserWorkflow,
    /path: \$\{\{ runner\.temp \}\}\/production-ordinary-user-smoke\/summary\.json/)
  assert.match(productionOrdinaryUserWorkflow,
    /name: Prove credentials are absent and rebuild the allowlisted journey artifact/)
  assert.match(productionOrdinaryUserWorkflow,
    /shell: \/bin\/bash --noprofile --norc -p \{0\}/)
  assert.match(productionOrdinaryUserWorkflow,
    /BASH_ENV: \/dev\/null[\s\S]{0,180}?LD_PRELOAD: ''[\s\S]{0,80}?PATH: \/usr\/bin:\/bin/)
  assert.match(productionOrdinaryUserWorkflow,
    /\(keys \| sort\) == \["cleanupOutcome", "exitCode", "failureCauseCode",[\s\S]{0,100}?"failureCode", "lastCompletedStage", "outcome"\]/)
  assert.match(productionOrdinaryUserWorkflow,
    /\(keys \| sort\) == \["answerCitationCount", "answerStatus", "cleanup",[\s\S]{0,220}?"visualAssemblyMode", "visualStepCount"\]/)
  assert.match(productionOrdinaryUserWorkflow,
    /raw_summary_size > 0 && raw_summary_size <= 1048576/)
  assert.match(productionOrdinaryUserWorkflow,
    /artifact_size > 0 && artifact_size <= 1048576/)
  assert.match(productionOrdinaryUserWorkflow, /exit "\$smoke_exit"/)
  assert.match(productionOrdinaryUserWorkflow,
    /\. \+ \{releaseId:\$releaseId,commitSha:\$commitSha\}/)
  assert.match(productionOrdinaryUserWorkflow, /sourceUrlSha256:/)
  assert.match(productionOrdinaryUserWorkflow, /effectiveSourceUrlSha256:/)
  assert.match(productionOrdinaryUserWorkflow, /del\(\.sourceUrl, \.effectiveSourceUrl\)/)
  assert.match(productionOrdinaryUserWorkflow, /rm -rf "\$raw_dir"/)
  assert.match(productionOrdinaryUserWorkflow,
    /find "\$artifact_dir" -mindepth 1 -maxdepth 1[\s\S]{0,80}?== 1/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /--validate-public-status "\$public_status" "\$smoke_exit"/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /\n\s+sourceUrl: \$source\.sourceUrl|\n\s+effectiveSourceUrl: \$source\.effectiveSourceUrl/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /service-diagnostics\.log|docker compose[^\n]*logs|Upload private/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /path:.*(?:diagnostics|result|success-summary|smoke-raw)/)
})

test('ordinary-user production probe isolates deployment authority from repository code', () => {
  const prepareStart = productionOrdinaryUserWorkflow.indexOf('  prepare_smoke:')
  const smokeStart = productionOrdinaryUserWorkflow.indexOf('  smoke:')
  const prepareJob = productionOrdinaryUserWorkflow.slice(prepareStart, smokeStart)
  const smokeJob = productionOrdinaryUserWorkflow.slice(smokeStart)
  assert.ok(prepareStart >= 0 && smokeStart > prepareStart)
  assert.match(productionOrdinaryUserWorkflow,
    /tested_sha:[\s\S]{0,180}?required: true[\s\S]{0,100}?type: string/)
  assert.match(prepareJob, /actions\/checkout@v6/)
  assert.match(prepareJob, /actions\/setup-node@v6/)
  assert.match(prepareJob, /actions\/upload-artifact@v7/)
  assert.doesNotMatch(prepareJob,
    /environment:\s*\n\s+name:\s*production|secrets\.|DEPLOY_SSH_PRIVATE_KEY|\bssh\s|\bscp\s/)
  assert.match(smokeJob, /needs: prepare_smoke/)
  assert.match(smokeJob, /actions\/download-artifact@v8/)
  assert.doesNotMatch(smokeJob,
    /actions\/checkout|actions\/setup-node|npm (?:ci|install|exec)|\bnode\s+scripts\//)
  const credentialRead = smokeJob.indexOf(
    'name: Read exact active release and bounded player credentials',
  )
  const keyRemoval = smokeJob.indexOf('rm -f "$HOME/.ssh/id_ed25519"', credentialRead)
  const replay = smokeJob.indexOf(
    'name: Replay the public ordinary-user journey with only player authority',
  )
  const credentialCleanup = smokeJob.indexOf(
    'name: Prove credentials are absent and rebuild the allowlisted journey artifact',
  )
  const upload = smokeJob.indexOf('name: Upload sanitized journey output')
  assert.ok(credentialRead >= 0 && credentialRead < keyRemoval && keyRemoval < replay)
  assert.ok(replay < credentialCleanup && credentialCleanup < upload)
  assert.match(smokeJob,
    /printf '%s\\n%s\\n' "\$player_username_b64" "\$player_password_b64" > "\$credential_file"/)
  assert.match(smokeJob, /printf 'active_release_id=%s\\n' "\$active_release_id" >> "\$GITHUB_OUTPUT"/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /PLAYER_(?:USERNAME|PASSWORD)_B64|player_(?:username|password)_b64[^\n]*GITHUB_ENV/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow,
    /RULEPILOT_SMOKE_ACTIVE_RELEASE_ID=%s[^\n]*GITHUB_ENV/)
  assert.doesNotMatch(smokeJob, /-N -L|127\.0\.0\.1:18080|\/actuator\/health/)
  assert.match(smokeJob, /test ! -e "\$HOME\/\.ssh\/known_hosts"/)
  assert.match(smokeJob,
    /--base-url "\$RULEPILOT_PUBLIC_URL"[\s\S]{0,120}?--username "\$player_username"/)
  assert.match(smokeJob,
    /\.commitSha == \$tested_sha and \.releaseId == \$active_release_id/)
  assert.ok((smokeJob.match(/verify_public_release/g) ?? []).length >= 3)
  assert.match(smokeJob,
    /deployment-guards\/active-transaction[\s\S]{0,180}?Production has an active deployment transaction/)
  assert.match(smokeJob,
    /\/usr\/bin\/env -i[\s\S]{0,180}?PATH=\/usr\/bin:\/bin[\s\S]{0,260}?\/bin\/bash "\$probe_dir\/smoke-production-ordinary-user\.sh"/)
  assert.match(smokeJob,
    /reset_runner_command_file "\$runner_env_file"[\s\S]{0,80}?reset_runner_command_file "\$runner_path_file"/)
  assert.match(smokeJob,
    /raw_dir="\$RUNNER_TEMP\/production-ordinary-user-smoke-raw"/)
  assert.match(smokeJob,
    /if ! verify_public_release; then[\s\S]{0,220}?>> "\$raw_diagnostics"/)
  assert.doesNotMatch(smokeJob,
    /if ! verify_public_release; then[\s\S]{0,220}?>> "\$artifact_dir\/diagnostics\.log"/)
  assert.match(smokeJob,
    /rm -rf "\$raw_dir"[\s\S]{0,260}?test -f "\$artifact_dir\/summary\.json"/)
  assert.match(smokeJob, /id: cleanup_credentials/)
  assert.match(smokeJob,
    /name: Upload sanitized journey output\s+if: always\(\) && steps\.cleanup_credentials\.outcome == 'success'/)
})

test('official image-gallery production smoke requires explicit rights and bounded identity', () => {
  assert.match(productionOrdinaryUserWorkflow,
    /source_mode:[\s\S]*?options:\s*\n\s+- upload\s*\n\s+- official_image_gallery/)
  assert.match(productionOrdinaryUserWorkflow,
    /rights_confirmed:[\s\S]*?default: false[\s\S]*?type: boolean/)
  assert.match(productionOrdinaryUserWorkflow,
    /\[\[ "\$RULEBOOK_RIGHTS_CONFIRMED" == true \]\][\s\S]*?--rights-confirmed/)
  assert.match(productionOrdinaryUserWorkflow,
    /RULEBOOK_EXPECTED_PAGE_COUNT <= 20[\s\S]*?--source-mode official_image_gallery[\s\S]*?--bgg-id "\$RULEBOOK_BGG_ID"[\s\S]*?--expected-page-count "\$RULEBOOK_EXPECTED_PAGE_COUNT"[\s\S]*?--timeout-seconds 6600/)
  assert.match(productionOrdinaryUserWorkflow,
    /smoke:[\s\S]*?timeout-minutes: 135[\s\S]*?--timeout-seconds 6600/)
})

test('deployment keeps protected BGG credentials out of packages and command arguments', () => {
  assert.match(deploymentWorkflow, /BGG_API_TOKEN: \$\{\{ secrets\.BGG_API_TOKEN \}\}/)
  assert.match(deploymentWorkflow,
    /git archive --format=tar --prefix=\.\/ "\$WORKFLOW_SHA"/)
  assert.doesNotMatch(deploymentWorkflow,
    /tar[\s\S]{0,240}?--exclude=\.env[\s\S]{0,240}?rulepilot-release-/)
  assert.match(deploymentWorkflow, /printf '%s' "\$BGG_API_TOKEN" > "\$local_token_file"/)
  assert.match(deploymentWorkflow,
    /remote_token_file="\/tmp\/rulepilot-bgg-token-\$\{DEPLOY_RELEASE_ID\}"/)
  assert.match(deploymentWorkflow,
    /name: Remove staged BGG credential[\s\S]*?if: always\(\)[^\n]*[\s\S]*?rm -f -- "\/tmp\/rulepilot-bgg-token-\$\{release_id\}"/)
  assert.match(productionReleaseGuard,
    /discard_transaction_secrets\(\)[\s\S]*?staged_bgg_credential "\$release_id"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$BGG_API_TOKEN"/)
  assert.doesNotMatch(deploymentWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$BGG_API_TOKEN"/)
})

test('deployment isolates the recommendation startup model from shared Qwen roles', () => {
  assert.match(applicationConfiguration,
    /recommendation-agent:[\s\S]{0,180}?model-provider: \$\{BGG_RECOMMENDATION_MODEL_PROVIDER:qwen\}[\s\S]{0,100}?model: \$\{BGG_RECOMMENDATION_MODEL:\}/)
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']* BGG_RECOMMENDATION_MODEL [^']*'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MODEL=qwen3\.8-flash'/)
  assert.match(deploymentCompose,
    /BGG_RECOMMENDATION_MODEL: \$\{BGG_RECOMMENDATION_MODEL:-\}/)
  assert.match(deploymentWorkflow, /'VISUAL_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'ANSWER_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'QWEN_MODEL=qwen3\.7-plus'/)
  assert.doesNotMatch(deploymentWorkflow, /'QWEN_MODEL=qwen3\.8-flash'/)
})

test('deployment forwards and owns the bounded long-teaching workload controls', () => {
  for (const [key, value] of [
    ['TEACHING_AGENT_MAX_WORKLOAD_TIMEOUT', 'PT16H'],
    ['TEACHING_AGENT_MAX_WORKLOAD_TOKENS', '16000000'],
    ['TEACHING_OUTLINE_SHARD_PARALLELISM', '10'],
  ]) {
    assert.match(deploymentCompose, new RegExp(`${key}: \\$\\{${key}:-${value}\\}`))
    assert.match(deploymentWorkflow, new RegExp(`'${key}=${value}'`))
  }
})

test('deploy-only reruns retain the immutable identity of the successful build attempt', () => {
  assert.match(deploymentWorkflow,
    /build_attempt: \$\{\{ steps\.release_identity\.outputs\.build_attempt \}\}/)
  assert.match(deploymentWorkflow,
    /build_attempt=%s\\ncontrol_plane_sha256=%s\\n'[\s\S]*?"\$GITHUB_RUN_ATTEMPT"[\s\S]*?>> "\$GITHUB_OUTPUT"/)
  assert.match(deploymentWorkflow,
    /DEPLOY_BUILD_ATTEMPT: \$\{\{ needs\.seal_source\.outputs\.build_attempt \}\}/)
  assert.match(deploymentWorkflow,
    /DEPLOY_RELEASE_ID" == "\$\{DEPLOY_SHA\}-\$\{GITHUB_RUN_ID\}-\$\{DEPLOY_BUILD_ATTEMPT\}"/)
  const sha = 'a'.repeat(40)
  const runId = '9001'
  const buildAttempt = '1'
  const deployOnlyRerunAttempt = '2'
  const artifactRelease = `${sha}-${runId}-${buildAttempt}`
  assert.equal(artifactRelease, `${sha}-${runId}-${buildAttempt}`)
  assert.notEqual(artifactRelease, `${sha}-${runId}-${deployOnlyRerunAttempt}`)
})

test('candidate publication parser executes exact positive and negative contracts', async () => {
  const releaseId = `${'a'.repeat(40)}-101-2`
  const releaseSha = 'a'.repeat(40)
  await runCandidateBoundaryParser('release', JSON.stringify({ releaseId, commitSha: releaseSha }))
  await runCandidateBoundaryParser('model', JSON.stringify({
    recommendationModel: { provider: 'qwen', model: 'qwen3.8-flash' },
  }))
  await runCandidateBoundaryParser('csrf', JSON.stringify({
    token: 'csrf-token',
    headerName: 'X-CSRF-TOKEN',
  }))
  const recommendation = await runCandidateBoundaryParser('recommendations', JSON.stringify([
    { bggId: 1, name: 'Fixture Game' },
  ]))
  assert.equal(recommendation.stdout.trim(), '1')
  await runCandidateBoundaryParser('detail', JSON.stringify({
    bggId: 1,
    description: 'Fixture',
    descriptionTranslated: false,
    categories: [],
    mechanics: [],
  }))

  const rejected = [
    ['release', JSON.stringify({ releaseId, commitSha: 'b'.repeat(40) }), /commit identity mismatch/],
    ['release', JSON.stringify({ releaseId: `${releaseSha}-999-9`, commitSha: releaseSha }), /release identity mismatch/],
    ['release', '{', /invalid JSON/],
    ['model', JSON.stringify({
      recommendationModel: { provider: 'qwen', model: 'wrong-model' },
    }), /recommendation model mismatch/],
    ['csrf', JSON.stringify({ token: '', headerName: 'X-CSRF-TOKEN' }), /token is absent/],
    ['recommendations', JSON.stringify([{ bggId: true, name: 'Invalid' }]), /first game id is invalid/],
    ['detail', JSON.stringify({
      bggId: 1,
      description: 'Fixture',
      descriptionTranslated: 'false',
      categories: [],
      mechanics: [],
    }), /translation marker is invalid/],
  ]
  for (const [contract, payload, expectedError] of rejected) {
    await assert.rejects(
      runCandidateBoundaryParser(contract, payload),
      (error) => {
        assert.equal(Number(error.code), 1)
        assert.match(error.stderr, expectedError)
        return true
      },
    )
  }
})

test('public observer distinguishes incomplete transport, transient HTTP, and local failures', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-observer-python.'))
  try {
    assert.equal(await publicObserverPythonExit('success', root), 0)
    assert.equal(await publicObserverPythonExit('connection-reset', root), 75)
    assert.equal(await publicObserverPythonExit('incomplete-read', root), 75)
    assert.equal(await publicObserverPythonExit('ssl-eof', root), 75)
    for (const status of [502, 503, 504]) {
      assert.equal(await publicObserverPythonExit(`http-${status}`, root), 75)
    }
    for (const status of [400, 401, 404, 500]) {
      assert.equal(await publicObserverPythonExit(`http-${status}`, root), 1)
    }
    assert.equal(await publicObserverPythonExit('success', root, {
      RULEPILOT_HTTPS_BODY_PATH: join(root, 'missing', 'observer.body'),
    }), 1)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('public observer retry state machine follows its three-state truth table', async () => {
  const transientThenSuccess = await runPublicObserverRetry([75, 75, 0])
  assert.equal(transientThenSuccess.status, 0)
  assert.deepEqual(transientThenSuccess.calls, [75, 75, 0])
  assert.match(transientThenSuccess.stdout, /verified the exact candidate release/)

  const unreachable = await runPublicObserverRetry([75, 75, 75])
  assert.equal(unreachable.status, 0)
  assert.deepEqual(unreachable.calls, [75, 75, 75])
  assert.match(unreachable.stdout, /Independent public observer unreachable/)

  const deterministic = await runPublicObserverRetry([1, 0, 0])
  assert.equal(deterministic.status, 1)
  assert.deepEqual(deterministic.calls, [1])
  assert.match(deterministic.stderr, /deterministic contract failure/)

  const transientThenDeterministic = await runPublicObserverRetry([75, 1, 0])
  assert.equal(transientThenDeterministic.status, 1)
  assert.deepEqual(transientThenDeterministic.calls, [75, 1])
})

test('deployment classifies independent public observation without invoking a paid Agent', () => {
  const publicAvailability = workflowRunBlock(
    deploymentWorkflow,
    'Classify independent public observation without repository code or production SSH authority',
  )
  assert.match(deploymentWorkflow, /name: Classify independent public observation/)
  assert.match(deploymentWorkflow, /::add-mask::\$preflight_username/)
  assert.match(deploymentWorkflow, /::add-mask::\$preflight_password/)
  assert.doesNotMatch(publicAvailability, /\bcurl\b/)
  assert.match(publicAvailability, /python3 - <<'PY'/)
  assert.match(publicAvailability, /TIMEOUT_SECONDS = 6/)
  assert.match(publicAvailability,
    /signal\.setitimer\(signal\.ITIMER_REAL, TIMEOUT_SECONDS\)[\s\S]*?signal\.setitimer\(signal\.ITIMER_REAL, 0\)/)
  assert.match(publicAvailability, /MAX_RESPONSE_BYTES = 1024 \* 1024/)
  assert.match(publicAvailability, /response\.read\(MAX_RESPONSE_BYTES \+ 1\)/)
  assert.match(publicAvailability,
    /parsed\.scheme != "https"[\s\S]*?URL credentials are not allowed/)
  assert.match(publicAvailability,
    /class RejectRedirectHandler[\s\S]*?production availability probes do not accept redirects/)
  assert.match(publicAvailability,
    /RULEPILOT_HTTPS_BASIC_USERNAME="\$preflight_username"[\s\S]{0,160}?RULEPILOT_HTTPS_BASIC_PASSWORD="\$preflight_password"[\s\S]{0,240}?\/api\/v1\/model-configuration/)
  assert.match(publicAvailability,
    /probe_headers\["Authorization"\] = f"Basic \{token\}"/)
  assert.equal(
    [...publicAvailability.matchAll(/^\s*https_get(?:\s|\\)/gm)].length,
    6,
    'production availability must perform exactly the six bounded HTTPS GET probes',
  )
  assert.match(deploymentWorkflow,
    /\.recommendationModel\.provider == "qwen"[\s\S]*?\.recommendationModel\.model == "qwen3\.8-flash"/)
  assert.match(deploymentWorkflow,
    /verify_public_once\(\)[\s\S]*?\/api\/public\/release[\s\S]*?\.commitSha == \$deploy_sha[\s\S]*?\.releaseId == \$deploy_release_id[\s\S]*?tolower\(directives\[index_value\]\) == "no-store"/)
  assert.match(publicAvailability, /\[\[ -s "\$home_body" \]\]/)
  assert.match(publicAvailability,
    /\.token \| type == "string" and length > 0[\s\S]*?\.headerName \| type == "string" and length > 0/)
  assert.match(deploymentWorkflow,
    /for attempt in 1 2 3; do[\s\S]{0,180}?if verify_public_once; then/)
  assert.match(publicAvailability,
    /except error\.HTTPError[\s\S]*?failure\.code in \(502, 503, 504\)[\s\S]*?except \([\s\S]*?http\.client\.HTTPException[\s\S]*?ssl\.SSLError[\s\S]*?OSError[\s\S]*?raise SystemExit\(75\)/)
  assert.match(publicAvailability,
    /observer_unreachable=true[\s\S]*?verification_status=\$\?[\s\S]*?"\$verification_status" != 75[\s\S]*?observer_unreachable=false/)
  assert.match(publicAvailability,
    /Independent public observer unreachable[\s\S]*?Candidate eligibility will be decided by the production gateway boundary/)
  assert.match(publicAvailability,
    /\/api\/v1\/bgg\/recommendations[\s\S]*?\.bggId > 0[\s\S]*?\.name \| type\) == "string"/)
  assert.match(publicAvailability,
    /\/api\/v1\/bgg\/games\/\$\{first_bgg_id\}\?locale=zh-CN[\s\S]*?\.bggId == \$bgg_id[\s\S]*?\.descriptionTranslated \| type\) == "boolean"/)
  assert.doesNotMatch(deploymentWorkflow, /recommendation-agent\/stream/)
  assert.doesNotMatch(deploymentWorkflow,
    /verify-production-(?:model-configuration|availability)\.mjs/)
  assert.doesNotMatch(deploymentWorkflow,
    /RULEPILOT_VERIFY_USERNAME|RULEPILOT_VERIFY_PASSWORD/)
  assert.doesNotMatch(deploymentWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$preflight_(?:username|password)"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$preflight_password"/)
  assert.match(deploymentWorkflow,
    /commit_release_once\(\)[\s\S]*?for attempt in 1 2 3; do[\s\S]{0,180}?if commit_release_once; then/)
  assert.match(deploymentWorkflow,
    /commit_status=\$\?[\s\S]*?"\$commit_status" != 75 && "\$commit_status" != 255[\s\S]*?same request will not be retried/)

  const rollbackStep = workflowRunBlock(
    deploymentWorkflow,
    'Roll back any uncommitted production mutation',
  )
  assert.match(rollbackStep, /"\$guard_script" rollback/)
  assert.doesNotMatch(rollbackStep, /RULEPILOT_PUBLIC_URL|curl|api\/public\/release|api\/auth\/csrf/)
})

test('release guard owns the exact candidate publication boundary before commit', () => {
  const boundary = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('verify_candidate_publication_boundary()'),
    productionReleaseGuard.indexOf('\nrequire_running_image()'),
  )
  assert.match(boundary,
    /--proto '=https'[\s\S]*?--noproxy '\*'[\s\S]*?--resolve 'rulepilot\.cn:443:127\.0\.0\.1'/)
  assert.match(boundary,
    /--connect-timeout 3[\s\S]*?--max-time 6[\s\S]*?--max-filesize 1048576[\s\S]*?--max-redirs 0/)
  assert.match(boundary,
    /read_environment_value RULEPILOT_USER_USERNAME[\s\S]*?read_environment_value RULEPILOT_USER_PASSWORD/)
  assert.doesNotMatch(boundary,
    /read_environment_value BGG_RECOMMENDATION_MODEL_(?:PROVIDER|MODEL)/)
  assert.match(productionReleaseGuard,
    /readonly EXPECTED_RECOMMENDATION_PROVIDER=qwen[\s\S]*?readonly EXPECTED_RECOMMENDATION_MODEL=qwen3\.8-flash/)
  assert.match(boundary,
    /RULEPILOT_EXPECTED_PROVIDER=\$EXPECTED_RECOMMENDATION_PROVIDER[\s\S]*?RULEPILOT_EXPECTED_MODEL=\$EXPECTED_RECOMMENDATION_MODEL/)
  assert.match(boundary,
    /umask 077[\s\S]*?user = "%s:%s"[\s\S]*?> "\$auth_config"/)
  assert.doesNotMatch(boundary, /source "?\$active_release\/\.env|--user|Authorization:/)
  assert.equal(
    [...boundary.matchAll(/^\s*candidate_https_get\s/gm)].length,
    6,
    'commit must verify the six existing deterministic publication surfaces',
  )
  for (const contract of ['release', 'model', 'csrf', 'recommendations', 'detail']) {
    assert.match(boundary, new RegExp(`contract == "${contract}"`))
  }
  assert.match(boundary,
    /releaseId[\s\S]*?RULEPILOT_EXPECTED_RELEASE_ID[\s\S]*?commitSha[\s\S]*?RULEPILOT_EXPECTED_COMMIT_SHA/)
  assert.match(boundary,
    /tolower\(directives\[index_value\]\) == "no-store"/)

  const commitGuard = productionReleaseGuard.slice(
    productionReleaseGuard.indexOf('commit_release()'),
    productionReleaseGuard.indexOf('\nrecord_watchdog_failure()'),
  )
  const publicationBoundary = commitGuard.indexOf('verify_candidate_publication_boundary')
  const apiIdentity = commitGuard.indexOf('require_running_image "$active_release" api', publicationBoundary)
  const workerIdentity = commitGuard.indexOf('require_running_image "$active_release" worker', apiIdentity)
  const frontendIdentity = commitGuard.indexOf('require_running_image "$active_release" frontend', workerIdentity)
  const workerHealth = commitGuard.indexOf('"$worker_health" == healthy', frontendIdentity)
  const committed = commitGuard.indexOf('atomic_write "$state_dir/committed"', workerHealth)
  assert.ok(publicationBoundary >= 0 && publicationBoundary < apiIdentity)
  assert.ok(apiIdentity < workerIdentity && workerIdentity < frontendIdentity)
  assert.ok(frontendIdentity < workerHealth && workerHealth < committed)
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

test('deployment removes only the exact failed release staging artifacts', async () => {
  const cleanupStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Remove exact remote staging artifacts'),
    deploymentWorkflow.indexOf('name: Remove runner-side production credentials'),
  )
  assert.match(cleanupStep,
    /if: always\(\) && steps\.configure_ssh\.outcome == 'success' && steps\.commit_release\.outcome != 'success'/)
  assert.match(cleanupStep,
    /'bash -s' -- "\$DEPLOY_RELEASE_ID" <<'REMOTE'/)
  assert.match(cleanupStep,
    /\[\[ ! "\$release_id" =~ \^\[0-9a-f\]\{40\}-\[0-9\]\+-\[0-9\]\+\$ \]\]/)
  assert.match(cleanupStep,
    /"\/tmp\/rulepilot-\$\{release_id\}\.tar\.gz"[\s\S]*?"\/tmp\/rulepilot-backend-\$\{release_id\}\.tar\.gz"[\s\S]*?"\/tmp\/rulepilot-backend-\$\{release_id\}\.tar\.gz\.sha256"[\s\S]*?"\/tmp\/rulepilot-frontend-\$\{release_id\}\.tar\.gz"[\s\S]*?"\/tmp\/rulepilot-frontend-\$\{release_id\}\.tar\.gz\.sha256"[\s\S]*?"\/tmp\/rulepilot-bgg-token-\$\{release_id\}"/)
  assert.match(cleanupStep,
    /printf '%s\\n' "\$DEPLOY_SSH_PRIVATE_KEY" > "\$HOME\/\.ssh\/id_ed25519"/)
  assert.doesNotMatch(cleanupStep,
    /cleanup-production-staging|tar -xOf|cleanup_source|release_bundle|\bscp\b|remote_cleanup|cleanup_script=|rm -rf|find \/tmp|rulepilot-\*/)

  const remoteCleanupMatch = cleanupStep.match(
    /'bash -s' -- "\$DEPLOY_RELEASE_ID" <<'REMOTE'\n([\s\S]*?)\n\s+REMOTE/,
  )
  assert.notEqual(remoteCleanupMatch, null)
  const remoteCleanupLines = remoteCleanupMatch[1].split('\n')
  const remoteIndent = Math.min(...remoteCleanupLines
    .filter((line) => line.trim() !== '')
    .map((line) => line.search(/\S/)))
  const remoteCleanup = `${remoteCleanupLines.map((line) => line.slice(remoteIndent)).join('\n')}\n`

  const releaseSha = process.pid.toString(16).padStart(40, '0')
  const release = `${releaseSha}-987654321-99`
  const exactFiles = [
    `rulepilot-${release}.tar.gz`,
    `rulepilot-backend-${release}.tar.gz`,
    `rulepilot-backend-${release}.tar.gz.sha256`,
    `rulepilot-frontend-${release}.tar.gz`,
    `rulepilot-frontend-${release}.tar.gz.sha256`,
    `rulepilot-bgg-token-${release}`,
  ]
  const decoys = [
    `rulepilot-${release}-other.tar.gz`,
    `rulepilot-backend-${'b'.repeat(40)}-102-1.tar.gz`,
    `rulepilot-staging-cleanup-${release}.sh`,
    `unrelated-production-data-${release}`,
  ]
  const exactPaths = exactFiles.map((file) => join('/tmp', file))
  const decoyPaths = decoys.map((file) => join('/tmp', file))
  try {
    await Promise.all([...exactPaths, ...decoyPaths]
      .map((path) => writeFile(path, 'bounded-test\n')))
    await execFileAsync('bash', ['-c', remoteCleanup, 'workflow-owned-cleanup', release])
    for (const path of exactPaths) await assert.rejects(access(path))
    for (const path of decoyPaths) await access(path)
  } finally {
    await Promise.all([...exactPaths, ...decoyPaths].map((path) => rm(path, { force: true })))
  }
})

test('deployment retains prior hashed frontend assets for open tabs', () => {
  assert.match(deploymentWorkflow, /for release_distance in 1 2/)
  assert.match(deploymentWorkflow,
    /git archive --format=tar --prefix=\.\/ "\$previous_sha" -- frontend/)
  assert.match(deploymentWorkflow,
    /tar -xzf "\$previous_archive" -C "\$previous_root"/)
  assert.match(deploymentWorkflow,
    /bash "\$RULEPILOT_BUILD_SOURCE\/scripts\/retain-frontend-release-assets\.sh"/)
  assert.match(deploymentWorkflow, /"\$previous_root\/frontend\/dist\/assets"/)
  assert.doesNotMatch(deploymentWorkflow,
    /cp -[A-Za-z]*f[^\n]*previous.*frontend/i)
})

test('deployment activates immutable backend and frontend runtime images', () => {
  assert.match(deploymentWorkflow, /name: Verify and seal the exact qualified Git tree/)
  assert.match(deploymentWorkflow,
    /deploy_sha=\$\(git rev-parse HEAD\)[\s\S]*?release_id="\$\{deploy_sha\}-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}"/)
  assert.match(deploymentWorkflow,
    /outputs:[\s\S]{0,300}?deploy_sha: \$\{\{ steps\.release_identity\.outputs\.deploy_sha \}\}[\s\S]{0,180}?release_id: \$\{\{ steps\.release_identity\.outputs\.release_id \}\}/)
  assert.match(deploymentWorkflow, /name: Build immutable backend runtime image/)
  assert.match(deploymentWorkflow, /name: Build immutable frontend runtime image/)
  assert.match(deploymentWorkflow, /docker save "\$backend_image" \| gzip -1/)
  assert.match(deploymentWorkflow, /docker save "\$frontend_image" \| gzip -1/)
  assert.match(deploymentWorkflow,
    /sha256sum "\$backend_image_archive" > "\$\{backend_image_archive\}\.sha256"/)
  assert.match(deploymentWorkflow,
    /sha256sum "\$frontend_image_archive" > "\$\{frontend_image_archive\}\.sha256"/)
  assert.match(deploymentWorkflow,
    /actions\/upload-artifact@v7[\s\S]*?^  deploy:[\s\S]*?needs: \[seal_source, build\][\s\S]*?actions\/download-artifact@v8/m)
  assert.match(deploymentWorkflow, /gzip -dc "\$backend_image_archive" \| docker load/)
  assert.match(deploymentWorkflow, /gzip -dc "\$frontend_image_archive" \| docker load/)
  assert.match(deploymentWorkflow, /RULEPILOT_PREBUILT_BACKEND_IMAGE=true \\/)
  assert.match(deploymentWorkflow, /RULEPILOT_PREBUILT_FRONTEND_IMAGE=true \\/)
  assert.match(deploymentWorkflow, /RULEPILOT_FRONTEND_IMAGE="\$frontend_image" \\/)
  assert.match(deploymentCompose,
    /worker:[\s\S]*?healthcheck:[\s\S]*?rulepilot-worker-ready/)
  assert.equal(
    productionCompose.match(/image: \$\{RULEPILOT_BACKEND_IMAGE:-rulepilot-backend:local\}/g)?.length,
    2,
  )
})
