import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import test from 'node:test'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const makefile = readFileSync(path.join(root, 'Makefile'), 'utf8')
const ci = readFileSync(path.join(root, '.github/workflows/ci.yml'), 'utf8')

const paidOrRealEntrypoints = readdirSync(path.join(root, 'scripts'))
  .filter((name) => (
    /^run-real-.*\.sh$/.test(name)
    || /^run-.*-canary\.sh$/.test(name)
    || [
      'run-complete-agent-release.sh',
      'run-paid-model-tool-probe.sh',
      'run-public-corpus-generation.sh',
    ].includes(name)
  ))
  .map((name) => `scripts/${name}`)
  .sort()
const scriptContents = Object.fromEntries(
  paidOrRealEntrypoints.map((script) => [
    script,
    readFileSync(path.join(root, script), 'utf8'),
  ]),
)
const recommendation = scriptContents['scripts/run-recommendation-paid-canary.sh']
const teachingPage = scriptContents['scripts/run-gstone-visual-page-canary.sh']
const historicalTeaching = scriptContents['scripts/run-real-teaching-agent.sh']
const formerTeachingCriticalPath = scriptContents['scripts/run-teaching-critical-path-canary.sh']
const visualCatalog = scriptContents['scripts/run-visual-teaching-catalog-canary.sh']
const completeRelease = scriptContents['scripts/run-complete-agent-release.sh']

function withoutPaidAuthorization() {
  const environment = { ...process.env }
  delete environment.RULEPILOT_ALLOW_PAID_CANARY
  return environment
}

function makeRecipe(target) {
  const match = makefile.match(new RegExp(`^${target}:[^\\n]*\\n((?:\\t[^\\n]*(?:\\n|$))*)`, 'm'))
  assert.ok(match, `Make target ${target} must exist`)
  return match[1]
}

test('Make routes paid and real public commands through guarded shell entrypoints', () => {
  const guardedTargets = {
    'corpus-generate': 'run-public-corpus-generation.sh',
    'agent-tool-probe': 'run-paid-model-tool-probe.sh',
    'agent-tool-loop-real': 'run-real-agent-tool-loop.sh',
    'agent-answer-real': 'run-real-answer-agent.sh',
    'agent-teaching-real': 'run-real-teaching-agent.sh',
    'agent-teaching-page-canary': 'run-gstone-visual-page-canary.sh',
    'agent-visual-real': 'run-real-visual-agent.sh',
    'agent-context-real': 'run-real-context-agent.sh',
    'agent-recommendation-real': 'run-real-recommendation-agent.sh',
    'agent-recommendation-canary': 'run-recommendation-paid-canary.sh',
    'agent-rulebook-acquisition-real': 'run-real-rulebook-acquisition.sh',
    'agent-security-real': 'run-real-agent-security.sh',
    'agent-release-real': 'run-complete-agent-release.sh',
  }
  for (const [target, script] of Object.entries(guardedTargets)) {
    assert.match(makeRecipe(target), new RegExp(script.replaceAll('.', '\\.')))
  }
  const realTargets = [...makefile.matchAll(/^([a-z0-9-]+-real):/gm)].map((match) => match[1]).sort()
  const coveredRealTargets = Object.keys(guardedTargets).filter((target) => target.endsWith('-real')).sort()
  assert.deepEqual(realTargets, coveredRealTargets)
  assert.match(makefile, /^agent-recommendation-canary:.*explicitly authorized paid recommendation/m)
  assert.match(makeRecipe('agent-recommendation-canary'), /RULEPILOT_RECOMMENDATION_CANARY_SCENARIO=critical-path/)
  assert.match(makefile, /^agent-teaching-page-canary:.*explicitly authorized paid Gstone image-page/m)
  assert.doesNotMatch(makeRecipe('agent-security-real'), /NativeAgentSecurityEvaluationTest/)
})

test('every retained paid or real shell entrypoint fails closed without explicit authorization', () => {
  for (const script of paidOrRealEntrypoints) {
    const result = spawnSync('sh', [path.join(root, script)], {
      cwd: root,
      encoding: 'utf8',
      env: withoutPaidAuthorization(),
    })
    assert.equal(result.status, 2, `${script} must reject an unapproved paid run`)
    assert.match(result.stdout + result.stderr, /RULEPILOT_ALLOW_PAID_CANARY=true/)
  }
})

test('the shared gate precedes credential loading, provider commands, and full verification', () => {
  const delegatedEntrypoints = new Set([
    'scripts/run-real-teaching-agent.sh',
    'scripts/run-teaching-critical-path-canary.sh',
  ])
  for (const [script, contents] of Object.entries(scriptContents)) {
    if (delegatedEntrypoints.has(script)) {
      assert.match(contents, /exec sh .*run-teaching-richness-canary\.sh/)
      continue
    }
    const gate = contents.indexOf('require-paid-canary-authorization.sh')
    assert.notEqual(gate, -1, `${script} must invoke the shared authorization gate`)
    for (const marker of ['.env', './mvnw', 'exec node', 'node scripts/', 'make verify']) {
      const operation = contents.indexOf(marker)
      if (operation !== -1) {
        assert.ok(gate < operation, `${script} must authorize before ${marker}`)
      }
    }
  }
  assert.ok(
    completeRelease.indexOf('require-paid-canary-authorization.sh') < completeRelease.indexOf('make verify'),
    'the full release must reject an unapproved run before make verify',
  )
})

test('recommendation scenarios are bounded and fail closed instead of silently running the full class', () => {
  assert.match(
    recommendation,
    /RULEPILOT_RECOMMENDATION_CANARY_SCENARIO:-critical-path/,
  )
  assert.match(
    recommendation,
    /publishesOneClaimScopedRecommendationAfterValidation/,
  )
  assert.match(recommendation, /\[ "\$scenario" = "all" \]/)
  assert.match(recommendation, /unsupported recommendation canary scenario/)
  assert.doesNotMatch(recommendation, /sanitized diagnostics/)
})

test('the teaching page canary stays on the single real image-page contract', () => {
  assert.match(teachingPage, /VisualTeachingCatalogPaidCanaryTest#catalogsOneRealGstonePageWithQuantityLineage/)
  assert.match(historicalTeaching, /exec sh .*run-teaching-richness-canary\.sh/)
  assert.match(formerTeachingCriticalPath, /exec sh .*run-teaching-richness-canary\.sh/)
  assert.match(visualCatalog, /require-paid-canary-authorization\.sh/)
  assert.match(visualCatalog, /VisualTeachingCatalogPaidCanaryTest/)
  for (const [script, contents] of Object.entries(scriptContents)) {
    assert.doesNotMatch(
      contents,
      /TeachingEvidenceAgentRealRulebookEvaluationTest/,
      `${script} must not invoke the retired teaching evaluation class`,
    )
  }
})

test('paid edit-loop canaries are never triggered by ordinary CI', () => {
  assert.doesNotMatch(ci, /agent-(recommendation|teaching-page)-canary/)
  assert.doesNotMatch(ci, /RULEPILOT_ALLOW_PAID_CANARY/)
})
