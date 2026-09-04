import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
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
const productionLauncher = await readFile(new URL('./run-production.sh', import.meta.url), 'utf8')
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

function shellFunction(script, functionName) {
  const startMarker = `${functionName}() {\n`
  const start = script.indexOf(startMarker)
  assert.notEqual(start, -1, `Shell function was not found: ${functionName}`)
  const end = script.indexOf('\n}\n', start)
  assert.notEqual(end, -1, `Shell function ending was not found: ${functionName}`)
  return script.slice(start, end + 2)
}

async function runStatefulDependencyWait({
  rabbitHealthyAfterRounds = 0,
  rabbitFailureRound = -1,
  historyGapAfterRound = -1,
  readyTimeoutSeconds = 90,
  timeoutRuntimeQueries = false,
  driftService = '',
  tagMovedService = '',
  runtimeImageChangeService = '',
  runtimeRestartService = '',
  runtimeChangeAfterRound = 2,
  containerChangeService = '',
  stoppedService = '',
} = {}) {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-stateful-health.'))
  const clock = join(root, 'clock')
  const round = join(root, 'round')
  await writeFile(clock, '0\n')
  await writeFile(round, '0\n')
  const composeWithTimeout = shellFunction(productionLauncher, 'compose_with_timeout')
  const dockerWithTimeout = shellFunction(productionLauncher, 'docker_with_timeout')
  const queryTimeoutFunction = shellFunction(productionLauncher, 'stateful_query_timeout')
  const boundedCompose = shellFunction(productionLauncher, 'bounded_stateful_compose')
  const boundedDocker = shellFunction(productionLauncher, 'bounded_stateful_docker')
  const waitFunction = shellFunction(productionLauncher, 'wait_for_stateful_dependencies')
  const harness = `
set -eu
ROOT_DIR="$RULEPILOT_TEST_ROOT"
BASE_FILE="$ROOT_DIR/compose.yml"
DEPLOYMENT_FILE="$ROOT_DIR/compose.deployment.yml"
PRODUCTION_FILE="$ROOT_DIR/compose.production.yml"
${composeWithTimeout}
${dockerWithTimeout}
${queryTimeoutFunction}
${boundedCompose}
${boundedDocker}
${waitFunction}
timeout() {
  [ "$1" = -k ]
  shift 2
  duration=\${1%s}
  shift
  if [ "$RULEPILOT_TEST_TIMEOUT_RUNTIME_QUERIES" = true ]; then
    case " $* " in
      *' docker inspect --format '*)
        current_time=$(sed -n '1p' "$RULEPILOT_TEST_CLOCK")
        printf '%s\\n' "$((current_time + duration))" > "$RULEPILOT_TEST_CLOCK"
        return 124
        ;;
    esac
  fi
  "$@"
}
docker() {
  if [ "$1" = compose ]; then
    shift
    compose_command=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        config|ps)
          compose_command=$1
          shift
          break
          ;;
        *) shift ;;
      esac
    done
    if [ "$compose_command" = ps ]; then
      if [ "$1" = --all ]; then
        shift
      fi
      [ "$1" = -q ]
      service=$2
      current_round=$(sed -n '1p' "$RULEPILOT_TEST_ROUND")
      if [ "$service" = "$RULEPILOT_TEST_CONTAINER_CHANGE_SERVICE" ] \
        && [ "$current_round" -ge "$RULEPILOT_TEST_RUNTIME_CHANGE_AFTER_ROUND" ]; then
        printf '%s-replacement-container\\n' "$service"
      else
        printf '%s-container\\n' "$service"
      fi
      return 0
    fi
    if [ "$1" = --hash ]; then
      printf '%s %s-config-hash\\n' "$2" "$2"
      return 0
    fi
    [ "$1" = --images ]
    printf '%s:image\\n' "$2"
    return 0
  fi
  if [ "$1" = image ]; then
    [ "$2" = inspect ] && [ "$3" = --format ]
    image_name=$5
    service=\${image_name%:image}
    if [ "$service" = "$RULEPILOT_TEST_TAG_MOVED_SERVICE" ]; then
      printf '%s-new-tag-image-id\\n' "$service"
    else
      printf '%s-image-id\\n' "$service"
    fi
    return 0
  fi
  [ "$1" = inspect ] && [ "$2" = --format ]
  inspection_format=$3
  container=$4
  service=\${container%-container}
  current_round=$(sed -n '1p' "$RULEPILOT_TEST_ROUND")
  config_hash="$service-config-hash"
  if [ "$service" = "$RULEPILOT_TEST_DRIFT_SERVICE" ]; then
    config_hash="$service-drifted-config-hash"
  fi
  runtime_state=running
  if [ "$service" = "$RULEPILOT_TEST_STOPPED_SERVICE" ]; then
    runtime_state=exited
  fi
  runtime_image_id="$service-image-id"
  runtime_started_at="$service-started-at-0"
  runtime_restart_count=0
  if [ "\${inspection_format%%|*}" = baseline ]; then
    printf 'baseline|%s|%s|%s|%s|%s\\n' \
      "$runtime_state" "$runtime_image_id" "$runtime_started_at" \
      "$runtime_restart_count" "$config_hash"
    return 0
  fi
  if [ "$service" = "$RULEPILOT_TEST_RUNTIME_IMAGE_CHANGE_SERVICE" ] \
    && [ "$current_round" -ge "$RULEPILOT_TEST_RUNTIME_CHANGE_AFTER_ROUND" ]; then
    runtime_image_id="$service-replaced-image-id"
  fi
  if [ "$service" = "$RULEPILOT_TEST_RUNTIME_RESTART_SERVICE" ] \
    && [ "$current_round" -ge "$RULEPILOT_TEST_RUNTIME_CHANGE_AFTER_ROUND" ]; then
    runtime_started_at="$service-started-at-1"
    runtime_restart_count=1
  fi
  printf 'container|%s|healthy|%s|%s|%s|%s\\n' \
    "$runtime_state" "$runtime_image_id" "$runtime_started_at" \
    "$runtime_restart_count" "$config_hash"
  first_retained_round=$((current_round - 4))
  if [ "$first_retained_round" -lt 0 ]; then
    first_retained_round=0
  fi
  log_round=$first_retained_round
  while [ "$log_round" -le "$current_round" ]; do
    probe_exit=0
    if [ "$service" = rabbitmq ] \
      && { [ "$log_round" -lt "$RULEPILOT_TEST_RABBIT_HEALTHY_AFTER" ] \
        || [ "$log_round" -eq "$RULEPILOT_TEST_RABBIT_FAILURE_ROUND" ]; }; then
      probe_exit=1
    fi
    printf 'probe|round-%06d|%s\\n' "$log_round" "$probe_exit"
    log_round=$((log_round + 1))
  done
  if [ "$service" = minio ]; then
    next_round=$((current_round + 1))
    if [ "$current_round" -eq "$RULEPILOT_TEST_HISTORY_GAP_AFTER_ROUND" ]; then
      next_round=$((current_round + 6))
    fi
    printf '%s\\n' "$next_round" > "$RULEPILOT_TEST_ROUND"
  fi
}
date() {
  sed -n '1p' "$RULEPILOT_TEST_CLOCK"
}
sleep() {
  current_time=$(sed -n '1p' "$RULEPILOT_TEST_CLOCK")
  printf '%s\\n' "$((current_time + $1))" > "$RULEPILOT_TEST_CLOCK"
}
wait_for_stateful_dependencies
`
  try {
    return await execFileAsync('sh', ['-c', harness], {
      env: {
        ...process.env,
        PRODUCTION_INFRASTRUCTURE_READY_TIMEOUT_SECONDS: String(readyTimeoutSeconds),
        PRODUCTION_DOCKER_QUERY_TIMEOUT_SECONDS: '5',
        PRODUCTION_INFRASTRUCTURE_OBSERVATION_INTERVAL_SECONDS: '3',
        RULEPILOT_TEST_CLOCK: clock,
        RULEPILOT_TEST_RABBIT_HEALTHY_AFTER: String(rabbitHealthyAfterRounds),
        RULEPILOT_TEST_RABBIT_FAILURE_ROUND: String(rabbitFailureRound),
        RULEPILOT_TEST_HISTORY_GAP_AFTER_ROUND: String(historyGapAfterRound),
        RULEPILOT_TEST_ROOT: root,
        RULEPILOT_TEST_ROUND: round,
        RULEPILOT_TEST_TIMEOUT_RUNTIME_QUERIES: String(timeoutRuntimeQueries),
        RULEPILOT_TEST_DRIFT_SERVICE: driftService,
        RULEPILOT_TEST_TAG_MOVED_SERVICE: tagMovedService,
        RULEPILOT_TEST_RUNTIME_IMAGE_CHANGE_SERVICE: runtimeImageChangeService,
        RULEPILOT_TEST_RUNTIME_RESTART_SERVICE: runtimeRestartService,
        RULEPILOT_TEST_RUNTIME_CHANGE_AFTER_ROUND: String(runtimeChangeAfterRound),
        RULEPILOT_TEST_CONTAINER_CHANGE_SERVICE: containerChangeService,
        RULEPILOT_TEST_STOPPED_SERVICE: stoppedService,
      },
    })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
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

function mergeProductionReport(target, overrides) {
  for (const [key, value] of Object.entries(overrides)) {
    if (value !== null && typeof value === 'object' && !Array.isArray(value)
      && target[key] !== null && typeof target[key] === 'object' && !Array.isArray(target[key])) {
      mergeProductionReport(target[key], value)
    } else {
      target[key] = value
    }
  }
  return target
}

function productionRecommendationRawReport(overrides = {}) {
  const testedSha = 'a'.repeat(40)
  const activeReleaseId = `${testedSha}-101-1`
  const model = { provider: 'qwen', model: 'qwen3.8-flash' }
  const report = {
    reportSchemaVersion: 2,
    generatedAt: '2026-08-29T00:00:00.000Z',
    completed: true,
    stage: 'completed',
    failedStage: null,
    fatalFailure: null,
    rawModelOutputCaptured: false,
    deployment: {
      testedSha,
      activeReleaseId,
      before: { releaseId: activeReleaseId, commitSha: testedSha, noStore: true },
      after: { releaseId: activeReleaseId, commitSha: testedSha, noStore: true },
      exactAndStable: true,
    },
    model: {
      expected: { ...model },
      before: { ...model },
      after: { ...model },
      stable: true,
    },
    naturalReply: {
      promptSha256: '1'.repeat(64),
      requestMatched: true,
      outcome: 'conversation',
      assistantMessageSha256: '2'.repeat(64),
      noExternalWork: true,
      persistedMatched: true,
      domMatched: true,
      agentElapsedMs: 1_200,
      modelCallElapsedMs: [1_150],
      failure: null,
    },
    recommendation: {
      promptSha256: '3'.repeat(64),
      requestedCardCount: 3,
      expectedPlayerCount: 5,
      maximumDurationMinutes: 90,
      maximumComplexity: 2.5,
      expectedGameType: 'party',
      requestMatched: true,
      outcome: 'recommendations',
      assistantMessageSha256: '4'.repeat(64),
      cards: [101, 102, 103].map((bggId, index) => ({
        bggId,
        nameSha256: String(5 + index).repeat(64),
        originalNameSha256: String(8 + index).slice(-1).repeat(64),
        replyPartsSha256: String(index).repeat(64),
      })),
      shortfallCount: 0,
      publicationErrors: [],
      persistedMatched: true,
      domMatched: true,
      agentElapsedMs: 3_400,
      modelCallElapsedMs: [900, 2_300],
      failure: null,
    },
    handoff: {
      selectedBggId: 101,
      actionClicked: true,
      importResponseStatus: 200,
      importedBggId: 101,
      importedGameId: '22222222-2222-4222-8222-222222222222',
      importedEditionId: '33333333-3333-4333-8333-333333333333',
      editionBelongsToGame: true,
      existingJobId: null,
      discoveryEditionMatched: true,
      sourceCount: 1,
      terminal: 'SOURCE_REVIEW',
      surfaceState: 'review',
      canReadRulebook: false,
      canReadLesson: false,
      failureClassification: null,
      blockedMutationPaths: [],
    },
    credentialLeak: 'player-secret-marker',
  }
  return mergeProductionReport(report, overrides)
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
  }
}

async function productionRecommendationSanitizerFixture(rawReport) {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-recommendation-sanitizer.'))
  const home = join(root, 'home')
  const artifactDirectory = join(root, 'production-recommendation-journey')
  const rawReportPath = join(artifactDirectory, 'journey.raw.json')
  const sanitizedReportPath = join(artifactDirectory, 'journey.json')
  const credentialPath = join(root, 'rulepilot-recommendation-player-credentials')
  await mkdir(join(home, '.ssh'), { recursive: true })
  await mkdir(artifactDirectory, { recursive: true })
  await writeFile(join(home, '.ssh', 'id_ed25519'), 'deployment-secret-marker')
  await writeFile(join(home, '.ssh', 'known_hosts'), 'production-host-marker')
  await writeFile(credentialPath, 'player-secret-marker')
  if (rawReport !== undefined) {
    await writeFile(rawReportPath,
      typeof rawReport === 'string' ? rawReport : JSON.stringify(rawReport))
  }
  return { root, home, rawReportPath, sanitizedReportPath, credentialPath }
}

async function runProductionRecommendationSanitizer(root) {
  await execFileAsync('bash', ['-c', productionRecommendationSanitizer], {
    env: productionRecommendationSanitizerEnvironment(root),
  })
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
    'if [[ -n "${RULEPILOT_TEST_GIT_FAILURE_COUNTER:-}" ]]; then',
    '  remaining=$(<"$RULEPILOT_TEST_GIT_FAILURE_COUNTER")',
    '  if (( remaining > 0 )); then',
    '    printf "%s\\n" "$((remaining - 1))" > "$RULEPILOT_TEST_GIT_FAILURE_COUNTER"',
    '    exit 128',
    '  fi',
    'fi',
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
      ...(command === 'checkpoint' ? [fixture.release.slice(0, 40)] : [fixture.previous]),
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
  assert.match(ciWorkflow,
    /node-version:\s*'24'[\s\S]*?make production-dependency-test[\s\S]*?make frontend-test/)
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

test('production recommendation keeps only the deployed user-visible journey contract', () => {
  assert.match(productionRecommendationWorkflow,
    /tested_sha:[\s\S]*?required: true[\s\S]*?type: string/)
  assert.match(productionRecommendationWorkflow,
    /uses:\s*actions\/checkout@v6[\s\S]*?ref:\s*main[\s\S]*?fetch-depth:\s*0/)
  assert.match(productionRecommendationWorkflow,
    /git merge-base --is-ancestor "\$tested_sha" origin\/main/)
  assert.match(productionRecommendationWorkflow, /git checkout --detach "\$tested_sha"/)
  assert.match(productionRecommendationWorkflow, /environment:\s*\n\s+name:\s*production/)

  const prepareProbeStart = productionRecommendationWorkflow.indexOf('  prepare_probe:')
  const journeyStart = productionRecommendationWorkflow.indexOf('  journey:')
  const prepareProbeJob = productionRecommendationWorkflow.slice(prepareProbeStart, journeyStart)
  const journeyJob = productionRecommendationWorkflow.slice(journeyStart)
  assert.ok(prepareProbeStart >= 0 && journeyStart > prepareProbeStart)
  assert.match(prepareProbeJob, /actions\/upload-artifact@v7/)
  assert.doesNotMatch(prepareProbeJob,
    /environment:\s*\n\s+name:\s*production|secrets\.|DEPLOY_SSH_PRIVATE_KEY|\bssh\s|\bscp\s/)
  assert.match(journeyJob, /needs: prepare_probe/)
  assert.match(journeyJob,
    /mcr\.microsoft\.com\/playwright:v1\.61\.1-noble@sha256:[0-9a-f]{64}/)
  assert.doesNotMatch(journeyJob,
    /actions\/checkout|actions\/setup-node|npm (?:ci|install|exec)|npx playwright/)
  assert.match(journeyJob,
    /\/usr\/bin\/env -i[\s\S]{0,1800}?RULEPILOT_RECOMMENDATION_REPORT="\$raw_report"/)
  assert.match(journeyJob,
    /deployment-guards\/active-transaction[\s\S]{0,180}?Production has an active deployment transaction/)

  const credentialRead = journeyJob.indexOf(
    'name: Read exact active release and bounded player credentials',
  )
  const keyRemoval = journeyJob.indexOf('rm -f "$HOME/.ssh/id_ed25519"', credentialRead)
  const exercise = journeyJob.indexOf(
    'name: Exercise one production recommendation with only player authority',
  )
  const cleanup = journeyJob.indexOf(
    'name: Prove credentials are absent and rebuild the allowlisted journey report',
  )
  const upload = journeyJob.indexOf('name: Upload sanitized journey report')
  assert.ok(credentialRead >= 0 && credentialRead < keyRemoval
    && keyRemoval < exercise && exercise < cleanup && cleanup < upload)
  assert.match(journeyJob,
    /name: Prove credentials are absent and rebuild the allowlisted journey report[\s\S]*?if: always\(\)/)
  assert.match(journeyJob,
    /name: Upload sanitized journey report\s+if: always\(\) && steps\.cleanup_credentials\.outcome == 'success'/)
  assert.match(journeyJob,
    /path: \$\{\{ runner\.temp \}\}\/production-recommendation-journey\/journey\.json/)
  assert.doesNotMatch(journeyJob,
    /name: Upload sanitized journey report[\s\S]{0,500}?journey\.raw\.json/)
  assert.doesNotMatch(productionRecommendationWorkflow, /echo "\$player_password"/)

  assert.match(productionRecommendationSanitizer, /reportSchemaVersion: 2/)
  assert.match(productionRecommendationSanitizer,
    /def completed_acceptance\(\$raw\):[\s\S]*?exact_release\(\$raw\.deployment\.before\)[\s\S]*?exact_model\(\$raw\.model\.after\)/)
  assert.match(productionRecommendationSanitizer,
    /naturalReply:[\s\S]*?noExternalWork:[\s\S]*?persistedMatched:[\s\S]*?domMatched:/)
  assert.match(productionRecommendationSanitizer,
    /naturalReply:[\s\S]*?agentElapsedMs:[\s\S]*?modelCallElapsedMs:/)
  assert.match(productionRecommendationSanitizer,
    /recommendation:[\s\S]*?publicationErrors:[\s\S]*?cards:/)
  assert.match(productionRecommendationSanitizer,
    /recommendation:[\s\S]*?agentElapsedMs:[\s\S]*?modelCallElapsedMs:/)
  assert.match(productionRecommendationSanitizer,
    /accepted_handoff[\s\S]*?editionBelongsToGame == true[\s\S]*?blockedMutationPaths \| length/)
  const handoffAcceptance = productionRecommendationSanitizer.match(
    /def accepted_handoff\([\s\S]*?\n\s+def completed_acceptance/,
  )
  assert.notEqual(handoffAcceptance, null)
  assert.doesNotMatch(handoffAcceptance[0],
    /sourceCount|surfaceState|canReadRulebook|canReadLesson/)
  assert.match(productionRecommendationSanitizer,
    /fallback_report\("missing_or_invalid_raw_report"\)/)
  assert.match(productionRecommendationSanitizer,
    /mv "\$temporary_report" "\$sanitized_report"[\s\S]*?rm -f "\$raw_report"/)
  assert.doesNotMatch(productionRecommendationSanitizer,
    /progress|sse|slo|modelCalls|catalogCalls|webResearchCalls|characterCount|contentDigest|handoffFreshness|handoffRestored|handoffDiscoveryCandidate/i)

  assert.match(productionRecommendationSpec, /reportSchemaVersion: 2/)
  assert.match(productionRecommendationSpec, /rawModelOutputCaptured: false/)
  assert.match(productionRecommendationSpec,
    /production publishes natural and grounded recommendation replies before the exact-card handoff/)
  assert.match(productionRecommendationSpec,
    /deployment: \{[\s\S]*?before: ReleaseIdentity \| null, after: ReleaseIdentity \| null/)
  assert.match(productionRecommendationSpec,
    /naturalReply: \{[\s\S]*?noExternalWork:[\s\S]*?persistedMatched:[\s\S]*?domMatched:/)
  assert.match(productionRecommendationSpec,
    /recommendation: \{[\s\S]*?publicationErrors: string\[\][\s\S]*?cards:/)
  assert.match(productionRecommendationSpec,
    /recommendation: \{[\s\S]*?agentElapsedMs: number \| null[\s\S]*?modelCallElapsedMs: number\[\]/)
  assert.match(productionRecommendationSpec,
    /handoff: \{[\s\S]*?editionBelongsToGame:[\s\S]*?blockedMutationPaths: string\[\]/)
})

test('production recommendation sanitizer publishes a nested allowlisted success report', async () => {
  const fixture = await productionRecommendationSanitizerFixture(
    productionRecommendationRawReport(),
  )
  try {
    await runProductionRecommendationSanitizer(fixture.root)

    const sanitizedText = await readFile(fixture.sanitizedReportPath, 'utf8')
    const sanitized = JSON.parse(sanitizedText)
    assert.equal(sanitized.reportSchemaVersion, 2)
    assert.equal(sanitized.completed, true)
    assert.deepEqual(Object.keys(sanitized).sort(), [
      'completed', 'deployment', 'failedStage', 'fatalFailure', 'generatedAt', 'handoff',
      'model', 'naturalReply', 'rawModelOutputCaptured', 'recommendation',
      'reportSchemaVersion', 'stage',
    ])
    assert.deepEqual(sanitized.deployment.before, {
      releaseId: `${'a'.repeat(40)}-101-1`,
      commitSha: 'a'.repeat(40),
      noStore: true,
    })
    assert.equal(sanitized.model.stable, true)
    assert.equal(sanitized.naturalReply.noExternalWork, true)
    assert.equal(sanitized.naturalReply.agentElapsedMs, 1_200)
    assert.deepEqual(sanitized.naturalReply.modelCallElapsedMs, [1_150])
    assert.equal(sanitized.recommendation.cards.length, 3)
    assert.equal(sanitized.recommendation.agentElapsedMs, 3_400)
    assert.deepEqual(sanitized.recommendation.modelCallElapsedMs, [900, 2_300])
    assert.deepEqual(sanitized.recommendation.publicationErrors, [])
    assert.equal(sanitized.handoff.terminal, 'SOURCE_REVIEW')
    assert.equal(sanitized.handoff.editionBelongsToGame, true)
    assert.equal(sanitized.rawModelOutputCaptured, false)
    assert.equal(Object.hasOwn(sanitized, 'credentialLeak'), false)
    assert.doesNotMatch(sanitizedText, /player-secret-marker|deployment-secret-marker/)

    await execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
      env: productionRecommendationSanitizerEnvironment(fixture.root),
    })
    await assert.rejects(access(fixture.rawReportPath))
    await assert.rejects(access(fixture.credentialPath))
    await assert.rejects(access(join(fixture.home, '.ssh', 'id_ed25519')))
    await assert.rejects(access(join(fixture.home, '.ssh', 'known_hosts')))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('production recommendation accepts each explicit source or readable handoff terminal', async () => {
  const existingJobId = '11111111-1111-4111-8111-111111111111'
  const terminals = [
    {
      terminal: 'SOURCE_UNAVAILABLE',
      surfaceState: 'unavailable',
      sourceCount: null,
    },
    {
      terminal: 'RULEBOOK_READABLE',
      surfaceState: 'journey',
      existingJobId,
      discoveryEditionMatched: null,
      sourceCount: null,
      canReadRulebook: true,
    },
    {
      terminal: 'LESSON_READABLE',
      surfaceState: 'journey',
      existingJobId,
      discoveryEditionMatched: null,
      sourceCount: null,
      canReadRulebook: true,
      canReadLesson: true,
    },
  ]
  for (const handoff of terminals) {
    const fixture = await productionRecommendationSanitizerFixture(
      productionRecommendationRawReport({ handoff }),
    )
    try {
      await runProductionRecommendationSanitizer(fixture.root)
      const sanitized = JSON.parse(await readFile(fixture.sanitizedReportPath, 'utf8'))
      assert.equal(sanitized.completed, true, handoff.terminal)
      assert.equal(sanitized.handoff.terminal, handoff.terminal)
      await execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
        env: productionRecommendationSanitizerEnvironment(fixture.root),
      })
    } finally {
      await rm(fixture.root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer independently downgrades false acceptance claims', async () => {
  const falseClaims = [
    ['natural projection', { naturalReply: { noExternalWork: false } }],
    ['publication boundary', {
      recommendation: { publicationErrors: ['hard-facts:101'] },
    }],
    ['exact handoff identity', { handoff: { importedBggId: 999 } }],
    ['blocked handoff mutation', {
      handoff: { blockedMutationPaths: ['POST /api/v1/documents/official-imports'] },
    }],
  ]
  for (const [label, override] of falseClaims) {
    const fixture = await productionRecommendationSanitizerFixture(
      productionRecommendationRawReport(override),
    )
    try {
      await runProductionRecommendationSanitizer(fixture.root)
      const sanitized = JSON.parse(await readFile(fixture.sanitizedReportPath, 'utf8'))
      assert.equal(sanitized.completed, false, label)
      assert.equal(sanitized.stage, 'failed-acceptance', label)
      assert.equal(sanitized.fatalFailure?.code, 'acceptance_contract_failed', label)
      await assert.rejects(
        execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
          env: productionRecommendationSanitizerEnvironment(fixture.root),
        }),
      )
      await assert.rejects(access(fixture.rawReportPath))
    } finally {
      await rm(fixture.root, { recursive: true, force: true })
    }
  }
})

test('production recommendation sanitizer preserves bounded fatal diagnostics', async () => {
  const failure = {
    classification: 'product_terminal',
    boundary: 'service_failure',
    reason: 'service_failure',
    code: 'recommendation_unavailable',
  }
  const fixture = await productionRecommendationSanitizerFixture(
    productionRecommendationRawReport({
      completed: false,
      stage: 'failed',
      failedStage: 'recommendation',
      fatalFailure: failure,
      recommendation: { failure },
    }),
  )
  try {
    await runProductionRecommendationSanitizer(fixture.root)
    const sanitizedText = await readFile(fixture.sanitizedReportPath, 'utf8')
    const sanitized = JSON.parse(sanitizedText)
    assert.equal(sanitized.completed, false)
    assert.equal(sanitized.stage, 'failed')
    assert.equal(sanitized.failedStage, 'recommendation')
    assert.deepEqual(sanitized.fatalFailure, failure)
    assert.deepEqual(sanitized.recommendation.failure, failure)
    assert.doesNotMatch(sanitizedText, /player-secret-marker/)
    await assert.rejects(access(fixture.rawReportPath))
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
})

test('production recommendation sanitizer emits a safe fallback without a valid raw report', async () => {
  for (const rawReport of [undefined, '{not-json']) {
    const fixture = await productionRecommendationSanitizerFixture(rawReport)
    try {
      await runProductionRecommendationSanitizer(fixture.root)
      const sanitizedText = await readFile(fixture.sanitizedReportPath, 'utf8')
      const sanitized = JSON.parse(sanitizedText)
      assert.equal(sanitized.reportSchemaVersion, 2)
      assert.equal(sanitized.completed, false)
      assert.equal(sanitized.stage, 'preflight-failed')
      assert.equal(sanitized.failedStage, 'preflight')
      assert.deepEqual(sanitized.fatalFailure, {
        classification: 'observer_failure',
        boundary: null,
        reason: null,
        code: 'missing_or_invalid_raw_report',
      })
      assert.equal(sanitized.deployment.testedSha, 'a'.repeat(40))
      assert.equal(sanitized.model.expected.model, 'qwen3.8-flash')
      assert.equal(sanitized.rawModelOutputCaptured, false)
      assert.doesNotMatch(sanitizedText, /player-secret-marker|deployment-secret-marker/)
      await assert.rejects(
        execFileAsync('bash', ['-c', productionRecommendationSuccessGate], {
          env: productionRecommendationSanitizerEnvironment(fixture.root),
        }),
      )
      await assert.rejects(access(fixture.rawReportPath))
      await assert.rejects(access(fixture.credentialPath))
    } finally {
      await rm(fixture.root, { recursive: true, force: true })
    }
  }
})

test('ordinary-user success gate accepts readable degradation and rejects inconsistent artifacts', async () => {
  const root = await mkdtemp(join(tmpdir(), 'rulepilot-ordinary-user-success-gate.'))
  const directory = join(root, 'production-ordinary-user-smoke')
  try {
    await mkdir(directory, { recursive: true })
    await writeFile(join(directory, 'summary.json'), JSON.stringify({
      outcome: 'FAILED',
      exitCode: 1,
      lastCompletedStage: 'summary-unavailable',
    }))
    await assert.rejects(execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    }))

    await writeFile(join(directory, 'summary.json'), JSON.stringify({
      execution: { outcome: 'SUCCEEDED', exitCode: 0 },
      preparationState: 'COMPLETED',
      lessonState: 'COMPLETED',
      lessonStatus: 'COMPLETE',
      answerStatus: 'ANSWERED',
    }))
    await execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    })

    await writeFile(join(directory, 'summary.json'), JSON.stringify({
      execution: { outcome: 'SUCCEEDED', exitCode: 0 },
      preparationState: 'COMPLETED',
      lessonState: 'DEGRADED',
      lessonStatus: 'DRAFT_READY',
      answerStatus: 'ANSWERED',
    }))
    await execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    })

    await writeFile(join(directory, 'summary.json'), JSON.stringify({
      execution: { outcome: 'SUCCEEDED', exitCode: 0 },
      preparationState: 'COMPLETED',
      lessonState: 'DEGRADED',
      lessonStatus: 'COMPLETE',
      answerStatus: 'ANSWERED',
    }))
    await assert.rejects(execFileAsync('bash', ['-c', productionOrdinaryUserSuccessGate], {
      env: { ...process.env, RUNNER_TEMP: root },
    }))
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
    'name: Synchronize protected integration credentials and managed runtime configuration',
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
      'name: Synchronize protected integration credentials and managed runtime configuration',
    ),
  )
  const synchronizationStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf(
      'name: Synchronize protected integration credentials and managed runtime configuration',
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
    /start_watchdog\(\)[\s\S]*?watchdog_ready_matches "\$state_dir" "\$generation"[\s\S]*?watchdog_process_matches "\$process_id" "\$generation"[\s\S]*?watchdog_process_alive "\$process_id"[\s\S]*?Rollback watchdog did not become ready/)
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
    ...(command === 'checkpoint' ? [release.slice(0, 40)] : [fixture.previous]),
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
    ...(command === 'checkpoint' ? [release.slice(0, 40)] : [fixture.previous]),
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
      execFileAsync('bash', [
        productionReleaseGuardPath,
        'checkpoint',
        fixture.root,
        following,
        following.slice(0, 40),
      ], {
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
      execFileAsync('bash', [
        productionReleaseGuardPath,
        'checkpoint',
        fixture.root,
        following,
        following.slice(0, 40),
      ], {
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
  const checkpointStep = deploymentWorkflow.slice(
    deploymentWorkflow.indexOf('name: Preserve the exact rollback checkpoint'),
    deploymentWorkflow.indexOf('name: Synchronize protected integration credentials'),
  )
  const lock = checkpointGuard.indexOf('flock -x 9')
  const currentMainGate = checkpointGuard.indexOf(
    'require_qualified_main_proof "$release_id" "$qualified_main_sha"',
    lock,
  )
  const firstMutation = checkpointGuard.indexOf('claim_active_transaction_held', currentMainGate)
  assert.match(checkpointStep,
    /GH_TOKEN: \$\{\{ github\.token \}\}[\s\S]*?gh api "repos\/\$\{GITHUB_REPOSITORY\}\/git\/ref\/heads\/main"[\s\S]*?"\$qualified_main_sha" == "\$DEPLOY_SHA"[\s\S]*?"\$qualified_main_sha" <<'REMOTE'/)
  assert.match(productionReleaseGuard,
    /require_qualified_main_proof\(\)[\s\S]*?Qualified main revision proof is invalid[\s\S]*?Candidate release is no longer/)
  assert.doesNotMatch(checkpointGuard, /git ls-remote|github\.com/)
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
        [productionReleaseGuardPath, 'checkpoint', fixture.root, fixture.release, 'c'.repeat(40)],
        { env: fixture.processEnvironment },
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

test('qualified main proof remains fail-closed when the deploy runner handoff is malformed', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('qualified main proof validation is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  try {
    await assert.rejects(
      execFileAsync(
        'bash',
        [productionReleaseGuardPath, 'checkpoint', fixture.root, fixture.release, 'not-a-sha'],
        { env: fixture.processEnvironment },
      ),
      (error) => {
        assert.match(error.stderr, /Qualified main revision proof is invalid/)
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
    ...(command === 'checkpoint' ? [release.slice(0, 40)] : [fixture.previous]),
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

test('watchdog startup tolerates the detached child before it execs into its generation', async (context) => {
  if (process.platform !== 'linux') {
    context.skip('watchdog exec startup is exercised on the Linux CI runner')
    return
  }
  const fixture = await createReleaseGuardFixture()
  const guardPath = join(fixture.root, 'production-release-guard.slow-exec.sh')
  const ordinarySpawn = `\tnohup bash "$0" watchdog "$application_root" "$release_id" "$previous_release_id" \\
\t\t"$generation" \\
\t\t>>"$log_file" 2>&1 </dev/null &`
  const delayedSpawn = `\tRULEPILOT_TEST_GUARD="$0" \\
\t\tRULEPILOT_TEST_ROOT="$application_root" \\
\t\tRULEPILOT_TEST_RELEASE="$release_id" \\
\t\tRULEPILOT_TEST_PREVIOUS="$previous_release_id" \\
\t\tRULEPILOT_TEST_GENERATION="$generation" \\
\t\tnohup bash -c 'sleep 0.2; exec bash "$RULEPILOT_TEST_GUARD" watchdog "$RULEPILOT_TEST_ROOT" "$RULEPILOT_TEST_RELEASE" "$RULEPILOT_TEST_PREVIOUS" "$RULEPILOT_TEST_GENERATION"' \\
\t\t>>"$log_file" 2>&1 </dev/null &`
  const slowExecGuard = productionReleaseGuard.replace(ordinarySpawn, delayedSpawn)
  assert.notEqual(slowExecGuard, productionReleaseGuard)
  try {
    await writeFile(guardPath, slowExecGuard, { mode: 0o755 })
    await mkdir(fixture.guardState, { recursive: true })
    await writeFile(
      join(fixture.root, 'deployment-guards', 'active-transaction'),
      `${fixture.release}\n`,
      { mode: 0o600 },
    )
    await writeFile(join(fixture.guardState, 'previous-release'), `${fixture.previous}\n`, { mode: 0o600 })
    await writeFile(join(fixture.guardState, 'environment.snapshot'), 'CHECKPOINT=true\n', { mode: 0o600 })

    await execFileAsync('bash', [
      guardPath,
      'start',
      fixture.root,
      fixture.release,
      fixture.previous,
    ], { env: fixture.processEnvironment })

    const pid = Number.parseInt(
      await readFile(join(fixture.guardState, 'watchdog.pid'), 'utf8'),
      10,
    )
    const generation = (await readFile(
      join(fixture.guardState, 'watchdog-generation'),
      'utf8',
    )).trim()
    assert.equal(
      (await readFile(join(fixture.guardState, 'watchdog-ready'), 'utf8')).trim(),
      generation,
    )
    process.kill(pid, 0)
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

test('production dependency health ignores stale healthy state and proves new successful probes', async () => {
  const result = await runStatefulDependencyWait({ rabbitHealthyAfterRounds: 2 })
  assert.match(result.stdout, /rabbitmq\(healthcheck-exit-1\)/)
  assert.match(result.stdout, /rabbitmq=1/)
  assert.match(result.stdout, /rabbitmq=2/)
  assert.match(result.stdout, /completed at least 12 new successful healthchecks/)
  assert.match(result.stdout, /remained healthy for 60 second\(s\)/)
})

test('production dependency health remains fail closed when probes keep failing behind stale healthy state', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ rabbitHealthyAfterRounds: 999, readyTimeoutSeconds: 8 }),
    (error) => {
      assert.match(error.stdout, /did not prove stable health within 8 second\(s\)/)
      assert.match(error.stdout, /rabbitmq\(healthcheck-exit-1\)/)
      return true
    },
  )
})

test('production dependency health bounds unavailable Docker runtime queries', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ readyTimeoutSeconds: 8, timeoutRuntimeQueries: true }),
    (error) => {
      assert.match(error.stdout, /Could not inspect the existing postgres container within the bounded Docker query window/)
      return true
    },
  )
})

test('production dependency health resets when retained probe history cannot prove continuity', async () => {
  await assert.rejects(
    runStatefulDependencyWait({
      historyGapAfterRound: 10,
      rabbitFailureRound: 11,
      readyTimeoutSeconds: 65,
    }),
    (error) => {
      assert.match(error.stdout, /healthcheck-history-gap/)
      assert.match(error.stdout, /did not prove stable health within 65 second\(s\)/)
      return true
    },
  )
})

test('production dependency health rejects stateful configuration drift without recreating it', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ driftService: 'rabbitmq' }),
    (error) => {
      assert.match(error.stdout, /rabbitmq has configuration drift/)
      assert.match(error.stdout, /explicit stateful maintenance path/)
      return true
    },
  )
})

test('production dependency health ignores a moved mutable tag when the observed runtime stays fixed', async () => {
  const result = await runStatefulDependencyWait({ tagMovedService: 'postgres' })
  assert.match(result.stdout, /completed at least 12 new successful healthchecks/)
  const dependencyWait = shellFunction(productionLauncher, 'wait_for_stateful_dependencies')
  assert.doesNotMatch(dependencyWait, /config --images|docker image inspect/)
})

test('production dependency health rejects a runtime image change behind the same container identity', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ runtimeImageChangeService: 'postgres' }),
    (error) => {
      assert.match(error.stdout, /postgres changed runtime image during the readiness gate/)
      return true
    },
  )
})

test('production dependency health rejects a same-container restart during the readiness gate', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ runtimeRestartService: 'redis' }),
    (error) => {
      assert.match(error.stdout, /redis restarted during the readiness gate/)
      return true
    },
  )
})

test('production dependency health rejects a container replacement during the readiness gate', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ containerChangeService: 'rabbitmq' }),
    (error) => {
      assert.match(error.stdout, /rabbitmq changed container identity during the readiness gate/)
      return true
    },
  )
})

test('production dependency health rejects a failed probe on the would-be qualification round', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ rabbitFailureRound: 20, readyTimeoutSeconds: 65 }),
    (error) => {
      assert.match(error.stdout, /rabbitmq\(healthcheck-exit-1\)/)
      assert.match(error.stdout, /did not prove stable health within 65 second\(s\)/)
      return true
    },
  )
})

test('production dependency health rejects a stopped stateful container without starting it', async () => {
  await assert.rejects(
    runStatefulDependencyWait({ stoppedService: 'minio' }),
    (error) => {
      assert.match(error.stdout, /minio is exited; application deployment will not start or restart it/)
      return true
    },
  )
})

test('production activation observes existing dependencies after pressure without restarting or recreating them', () => {
  const upCase = productionLauncher.slice(
    productionLauncher.indexOf('\n\tup)\n'),
    productionLauncher.indexOf('\n\tdiagnose)\n'),
  )
  const dependencyBoundary = upCase.slice(0, upCase.indexOf('\t\twait_for_stateful_dependencies')
    + '\t\twait_for_stateful_dependencies'.length)
  assert.match(dependencyBoundary, /wait_for_stateful_dependencies$/)
  const dependencyWait = shellFunction(productionLauncher, 'wait_for_stateful_dependencies')
  const executableBoundary = dependencyBoundary
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*#.*$/, ''))
    .join('\n')
  const logicalUpCase = upCase
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*#.*$/, ''))
    .join('\n')
    .replace(/\\[ \t]*\r?\n/g, ' ')
  const statefulCommandLines = dependencyWait
    .replace(/\\[ \t]*\r?\n/g, ' ')
    .split(/\r?\n/)
    .filter((line) => !/^\s*#/.test(line))
    .filter((line) => /(?:compose|docker)/.test(line))
  const runtimeMutationLines = logicalUpCase
    .split(/\r?\n/)
    .filter((line) => /(?:compose|docker)/.test(line))
    .filter((line) => /\b(?:build|commit|create|down|kill|pause|pull|remove|restart|rm|run|start|stop|tag|unpause|up|update)\b/.test(line))
  assert.doesNotMatch(executableBoundary, /\b(?:postgres|redis|rabbitmq|minio)\b/)
  assert.doesNotMatch(executableBoundary, /(?:compose|docker)/)
  for (const line of statefulCommandLines) {
    assert.doesNotMatch(
      line,
      /\b(?:build|commit|create|down|kill|pause|pull|remove|restart|rm|run|start|stop|tag|unpause|up|update)\b|force-recreate/,
    )
  }
  for (const line of runtimeMutationLines) {
    assert.doesNotMatch(line, /\b(?:postgres|redis|rabbitmq|minio)\b/)
  }
  assert.match(dependencyWait, /bounded_stateful_compose ps --all -q "\$service"/)
  assert.match(dependencyWait, /\.State\.Health\.Log/)
  assert.match(dependencyWait, /\.State\.StartedAt/)
  assert.match(dependencyWait, /\.RestartCount/)
  assert.match(dependencyWait, /stable_duration_seconds=60/)
  assert.match(dependencyWait, /required_successful_healthchecks=12/)
  assert.doesNotMatch(dependencyWait, /PRODUCTION_INFRASTRUCTURE_STABLE_DURATION_SECONDS/)
  assert.doesNotMatch(dependencyWait, /PRODUCTION_INFRASTRUCTURE_REQUIRED_SUCCESSFUL_HEALTHCHECKS/)
  assert.match(productionLauncher, /timeout -k 2s "\$\{command_timeout_seconds\}s" docker/)

  const activation = workflowRunBlock(
    deploymentWorkflow,
    'Activate release and verify production health',
  )
  assert.match(activation,
    /Ensuring the current release remains available[\s\S]*?up -d --no-build --no-deps api worker/)
})

test('application deployment observes existing Tempo without owning observability lifecycle', () => {
  const tracingVerification = shellFunction(productionLauncher, 'verify_tracing_backend')
  const executableTracingVerification = tracingVerification
    .split(/\r?\n/)
    .map((line) => line.replace(/^\s*#.*$/, ''))
    .join('\n')
  const upCase = productionLauncher.slice(
    productionLauncher.indexOf('\n\tup)\n'),
    productionLauncher.indexOf('\n\tdiagnose)\n'),
  )

  assert.match(tracingVerification, /PRODUCTION_TRACING_EXPORT_OTLP_ENABLED/)
  assert.match(tracingVerification, /wait_for_tempo/)
  assert.doesNotMatch(executableTracingVerification, /\b(?:compose|docker)\b/)
  assert.doesNotMatch(executableTracingVerification,
    /\b(?:create|down|kill|pause|pull|recreate|restart|rm|start|stop|up)\b/)
  assert.match(upCase, /wait_for_stateful_dependencies[\s\S]*?verify_tracing_backend/)
  assert.doesNotMatch(productionLauncher, /configure_tracing_backend/)
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

  const boundarySnapshot = activation.indexOf('Production container state at the failure boundary:')
  const expensiveDiskScan = activation.indexOf('Production filesystem usage:', boundarySnapshot)
  assert.ok(boundarySnapshot >= 0 && boundarySnapshot < expensiveDiskScan)
  assert.match(activation,
    /for service in postgres redis rabbitmq minio api worker frontend gateway; do/)
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
    /\(keys \| sort\) == \["answerDiagnostic", "cleanupOutcome", "exitCode",[\s\S]{0,120}?"failureCode", "lastCompletedStage", "outcome"\]/)
  assert.match(productionOrdinaryUserWorkflow,
    /\(keys \| sort\) == \["assistantRunId", "completionRejectionCode",[\s\S]{0,140}?"lastErrorCode", "ownerVerified", "runState", "status", "stopReason"\]/)
  assert.match(productionOrdinaryUserWorkflow,
    /answerDiagnostic: \(if \.answerDiagnostic == null then null else \{[\s\S]{0,400}?stopReason: \.answerDiagnostic\.stopReason,[\s\S]{0,120}?completionRejectionCode: \.answerDiagnostic\.completionRejectionCode,[\s\S]{0,100}?ownerVerified: \.answerDiagnostic\.ownerVerified/)
  assert.match(productionOrdinaryUserWorkflow,
    /\.completionRejectionCode == null[\s\S]{0,160}?test\("\^\[A-Z\]\[A-Z0-9_\]\{2,63\}\$"\)/)
  assert.match(productionOrdinaryUserWorkflow,
    /\.ownerVerified == true/)
  assert.match(productionOrdinaryUserWorkflow,
    /\^\[0-9a-f\]\{8\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{12\}\$/)
  assert.match(productionOrdinaryUserWorkflow,
    /MODEL_CAPABILITY_UNAVAILABLE[\s\S]{0,160}?MODEL_REQUEST_TIMEOUT[\s\S]{0,160}?MODEL_REQUEST_UNAVAILABLE[\s\S]{0,600}?OBSERVATION_NO_PROGRESS/)
  assert.ok(productionOrdinaryUserWorkflow.includes(
    'and (.preparationState | IN("COMPLETED", "DEGRADED"))'))
  assert.ok(productionOrdinaryUserWorkflow.includes(
    'and (.answerCitationCount | integer_between(1; 10000))'))
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /pageAttempts|semanticAttempts/)
  assert.match(productionOrdinaryUserWorkflow,
    /raw_summary_size > 0 && raw_summary_size <= 1048576/)
  assert.match(productionOrdinaryUserWorkflow,
    /artifact_size > 0 && artifact_size <= 1048576/)
  assert.match(productionOrdinaryUserWorkflow, /exit "\$smoke_exit"/)
  assert.match(productionOrdinaryUserWorkflow, /PUBLIC_RELEASE_UNAVAILABLE/)
  assert.match(productionOrdinaryUserWorkflow, /PUBLIC_RELEASE_MISMATCH/)
  assert.match(productionOrdinaryUserWorkflow, /for attempt in 1 2 3/)
  assert.match(productionOrdinaryUserWorkflow,
    /if \(\( verification_status == 11 \)\); then\s+return 11/)
  assert.match(productionOrdinaryUserWorkflow,
    /navigation_file="\$raw_dir\/navigation\.raw\.tsv"/)
  assert.match(productionOrdinaryUserWorkflow,
    /--navigation-file "\$navigation_file"/)
  assert.match(productionOrdinaryUserWorkflow,
    /install -m 0644 scripts\/smoke-production-http\.mjs/)
  assert.match(productionOrdinaryUserWorkflow,
    /node "\$probe_dir\/smoke-production-http\.mjs"[\s\S]{0,180}?--max-filesize 26214400/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /\bcurl\b/)
  assert.match(productionOrdinaryUserWorkflow,
    /--http-client "\$http_client"/)
  assert.match(productionOrdinaryUserWorkflow,
    /\.navigation\.requestCount \| integer_between\(1; 100000\)/)
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
  const sanitizer = productionOrdinaryUserWorkflow.slice(
    productionOrdinaryUserWorkflow.indexOf('name: Prove credentials are absent and rebuild the allowlisted journey artifact'),
    productionOrdinaryUserWorkflow.indexOf('name: Upload sanitized journey output'),
  )
  assert.doesNotMatch(sanitizer,
    /\.(?:ownerUsername|subjectId|question|shortVerdict|explanation|citations|steps|activities|operation|path|reason|rawCandidate|evidence)\b/)
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
  assert.match(smokeJob,
    /release_snapshot=\$\("\$node_binary" "\$http_client"/)
  assert.doesNotMatch(smokeJob, /release_snapshot=\$\(curl/)
  assert.ok((smokeJob.match(/verify_public_release/g) ?? []).length >= 3)
  assert.match(smokeJob,
    /deployment-guards\/active-transaction[\s\S]{0,180}?Production has an active deployment transaction/)
  assert.match(smokeJob,
    /\/usr\/bin\/env -i[\s\S]{0,180}?PATH=\/usr\/bin:\/bin[\s\S]{0,260}?\/bin\/bash "\$probe_dir\/smoke-production-ordinary-user\.sh"/)
  assert.match(smokeJob,
    /RULEPILOT_SMOKE_NODE_BINARY="\$node_binary"/)
  assert.match(smokeJob,
    /reset_runner_command_file "\$runner_env_file"[\s\S]{0,80}?reset_runner_command_file "\$runner_path_file"/)
  assert.match(smokeJob, /\/usr\/bin\/truncate -s 0 -- "\$target"/)
  assert.doesNotMatch(smokeJob, /\/usr\/bin\/install -m 600 \/dev\/null "\$target"/)
  assert.match(smokeJob,
    /raw_dir="\$RUNNER_TEMP\/production-ordinary-user-smoke-raw"/)
  assert.match(smokeJob,
    /verify_public_release >> "\$raw_diagnostics" 2>&1 \|\| public_release_status=\$\?/)
  assert.doesNotMatch(smokeJob,
    /verify_public_release >> "\$artifact_dir\/diagnostics\.log"/)
  const rawStatusInitialization = smokeJob.indexOf('printf \'%s\\n\' 1 > "$raw_exit"')
  const firstPublicReleaseProbe = smokeJob.indexOf(
    'verify_public_release >> "$raw_diagnostics" 2>&1',
  )
  assert.ok(rawStatusInitialization >= 0 && rawStatusInitialization < firstPublicReleaseProbe)
  assert.match(smokeJob,
    /if \(\( smoke_exit == 0 \)\); then[\s\S]{0,620}?smoke_exit=1\s+fi\s+fi/)
  assert.doesNotMatch(smokeJob,
    /fi\s+smoke_exit=1\s+fi\s+exit_tmp=/)
  assert.match(smokeJob,
    /rm -rf "\$raw_dir"[\s\S]{0,260}?test -f "\$artifact_dir\/summary\.json"/)
  assert.match(smokeJob, /id: cleanup_credentials/)
  assert.match(smokeJob,
    /name: Upload sanitized journey output\s+if: always\(\) && steps\.cleanup_credentials\.outcome == 'success'/)
})

test('official image-gallery production smoke requires explicit rights and positive source identity', () => {
  assert.match(productionOrdinaryUserWorkflow,
    /source_mode:[\s\S]*?options:\s*\n\s+- upload\s*\n\s+- official_image_gallery/)
  assert.match(productionOrdinaryUserWorkflow,
    /rights_confirmed:[\s\S]*?default: false[\s\S]*?type: boolean/)
  assert.match(productionOrdinaryUserWorkflow,
    /\[\[ "\$RULEBOOK_RIGHTS_CONFIRMED" == true \]\][\s\S]*?--rights-confirmed/)
  assert.match(productionOrdinaryUserWorkflow,
    /RULEBOOK_EXPECTED_PAGE_COUNT" =~ \^\[1-9\]\[0-9\]\*\$[\s\S]*?--source-mode official_image_gallery[\s\S]*?--bgg-id "\$RULEBOOK_BGG_ID"[\s\S]*?--expected-page-count "\$RULEBOOK_EXPECTED_PAGE_COUNT"[\s\S]*?--timeout-seconds 6600/)
  assert.doesNotMatch(productionOrdinaryUserWorkflow, /RULEBOOK_EXPECTED_PAGE_COUNT <=/)
  assert.match(productionOrdinaryUserWorkflow,
    /smoke:[\s\S]*?timeout-minutes: 135[\s\S]*?--timeout-seconds 6600/)
})

test('deployment keeps protected integration credentials out of packages and command arguments', () => {
  assert.match(deploymentWorkflow, /BGG_API_TOKEN: \$\{\{ secrets\.BGG_API_TOKEN \}\}/)
  assert.match(deploymentWorkflow,
    /git archive --format=tar --prefix=\.\/ "\$WORKFLOW_SHA"/)
  assert.doesNotMatch(deploymentWorkflow,
    /tar[\s\S]{0,240}?--exclude=\.env[\s\S]{0,240}?rulepilot-release-/)
  assert.match(deploymentWorkflow, /printf '%s' "\$BGG_API_TOKEN" > "\$local_token_file"/)
  assert.match(deploymentWorkflow,
    /remote_token_file="\/tmp\/rulepilot-bgg-token-\$\{DEPLOY_RELEASE_ID\}"/)
  assert.match(deploymentWorkflow,
    /name: Remove staged integration credentials[\s\S]*?if: always\(\)[^\n]*[\s\S]*?rm -f --[\s\S]*?"\/tmp\/rulepilot-bgg-token-\$\{release_id\}"/)
  assert.match(productionReleaseGuard,
    /discard_transaction_secrets\(\)[\s\S]*?staged_bgg_credential "\$release_id"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$BGG_API_TOKEN"/)
  assert.doesNotMatch(deploymentWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$BGG_API_TOKEN"/)
  assert.match(deploymentWorkflow, /DOCLING_API_KEY: \$\{\{ secrets\.DOCLING_API_KEY \}\}/)
  assert.match(deploymentWorkflow, /DOCLING_SERVICE_URL: \$\{\{ secrets\.DOCLING_SERVICE_URL \}\}/)
  assert.match(deploymentWorkflow,
    /printf '%s' "\$DOCLING_API_KEY" > "\$local_docling_key_file"/)
  assert.match(deploymentWorkflow,
    /printf '%s' "\$DOCLING_SERVICE_URL" > "\$local_docling_url_file"/)
  assert.doesNotMatch(deploymentWorkflow, /echo "\$DOCLING_(?:API_KEY|SERVICE_URL)"/)
  assert.doesNotMatch(deploymentWorkflow,
    /'bash -s' -- "\$DEPLOY_PATH" "\$DOCLING_(?:API_KEY|SERVICE_URL)"/)
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']* DOCLING_ENABLED DOCLING_SERVICE_URL DOCLING_API_KEY [^']*'/)
  assert.match(deploymentWorkflow,
    /if \[\[ -n "\$docling_service_url" \|\| -n "\$docling_api_key" \]\]; then[\s\S]*?docling_enabled=true[\s\S]*?printf 'DOCLING_ENABLED=%s\\n' "\$docling_enabled"/)
  assert.match(deploymentCompose, /DOCLING_ENABLED: \$\{DOCLING_ENABLED:-false\}/)
  assert.match(deploymentCompose, /DOCLING_API_KEY: \$\{DOCLING_API_KEY:-\}/)
  assert.match(productionReleaseGuard,
    /discard_transaction_secrets\(\)[\s\S]*?staged_docling_credential "\$release_id"/)
})

test('deployment isolates the recommendation startup model from shared Qwen roles', () => {
  assert.match(deploymentCompose, /EMBEDDING_PROVIDER: \$\{EMBEDDING_PROVIDER:-qwen\}/)
  assert.match(deploymentWorkflow, /managed_runtime_keys='[^']* EMBEDDING_PROVIDER [^']*'/)
  assert.match(deploymentWorkflow, /'EMBEDDING_PROVIDER=qwen'/)
  assert.match(applicationConfiguration,
    /recommendation-agent:[\s\S]{0,180}?model-provider: \$\{BGG_RECOMMENDATION_MODEL_PROVIDER:qwen\}[\s\S]{0,100}?model: \$\{BGG_RECOMMENDATION_MODEL:\}/)
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']* BGG_RECOMMENDATION_MODEL [^']*'/)
  assert.match(deploymentWorkflow,
    /managed_runtime_keys='[^']* BGG_RECOMMENDATION_MAX_OUTPUT_TOKENS [^']*'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MODEL=qwen3\.8-flash'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_MAX_OUTPUT_TOKENS=2000'/)
  assert.match(deploymentWorkflow, /'BGG_RECOMMENDATION_WEB_RESEARCH_TIMEOUT=PT5S'/)
  assert.match(deploymentWorkflow, /'WEB_SEARCH_MODEL=qwen3\.8-flash'/)
  assert.match(deploymentCompose,
    /BGG_RECOMMENDATION_MODEL: \$\{BGG_RECOMMENDATION_MODEL:-\}/)
  assert.match(deploymentWorkflow, /'VISUAL_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'ANSWER_MODEL_PROVIDER=qwen'/)
  assert.match(deploymentWorkflow, /'QWEN_MODEL=qwen3\.7-plus'/)
  assert.doesNotMatch(deploymentWorkflow, /'QWEN_MODEL=qwen3\.8-flash'/)
  assert.match(applicationConfiguration,
    /recommendation-agent:[\s\S]{0,600}?timeout: \$\{AGENT_TIMEOUT:PT2M\}/)
  assert.match(deploymentWorkflow, /managed_runtime_keys='[^']* AGENT_TIMEOUT [^']*'/)
  assert.match(deploymentWorkflow, /'AGENT_TIMEOUT=PT2M'/)
  assert.match(deploymentCompose, /AGENT_TIMEOUT: \$\{AGENT_TIMEOUT:-PT2M\}/)
  assert.doesNotMatch(deploymentWorkflow, /'BGG_RECOMMENDATION_AGENT_TIMEOUT=/)
  assert.doesNotMatch(deploymentCompose, /BGG_RECOMMENDATION_AGENT_TIMEOUT:/)
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
