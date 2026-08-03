#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const REQUIRED_CORPUS_FAMILIES = Object.freeze([
  'TEXT_LAYER',
  'LONG_EXCEPTION_HEAVY',
  'IMAGE_LAYOUT_DEPENDENT',
  'ICON_TABLE_DENSE',
  'CROSS_LANGUAGE_QUESTION',
])

const TASK_KINDS = new Set(['TEACHING', 'VISUAL_TEACHING', 'ICON_GLOSSARY', 'ANSWER'])
const PLAYER_NEEDS = new Set([
  'START_PLAYING',
  'RESOLVE_EXCEPTION',
  'MATCH_TABLE_STATE',
  'IDENTIFY_SYMBOL',
  'ASK_NATURALLY',
])

export function manifestIssues(manifest) {
  const issues = []
  if (manifest?.schemaVersion !== 1) issues.push('schemaVersion must be 1')
  if (!Array.isArray(manifest?.cases) || manifest.cases.length === 0) return [...issues, 'cases must be non-empty']

  const ids = new Set()
  const families = new Set()
  for (const case_ of manifest.cases) {
    if (!/^rr-[a-z0-9-]+$/.test(case_?.caseId ?? '')) issues.push('caseId must be opaque and start with rr-')
    if (ids.has(case_?.caseId)) issues.push('caseId must be unique')
    ids.add(case_?.caseId)
    if (!REQUIRED_CORPUS_FAMILIES.includes(case_?.family)) issues.push('case family is invalid')
    families.add(case_?.family)
    if (!TASK_KINDS.has(case_?.taskKind)) issues.push('taskKind is invalid')
    if (!PLAYER_NEEDS.has(case_?.playerNeed)) issues.push('playerNeed is invalid')
    if (!/^[a-f0-9]{64}$/i.test(case_?.sourceSha256 ?? '')) issues.push('sourceSha256 must be a SHA-256 digest')
    if (typeof case_?.baselineArtifact !== 'string' || !case_.baselineArtifact.startsWith('.local/')) {
      issues.push('baselineArtifact must stay under ignored .local storage')
    }
    if (!Array.isArray(case_?.capabilityTags) || case_.capabilityTags.length === 0) {
      issues.push('capabilityTags must be non-empty')
    }
  }
  for (const family of REQUIRED_CORPUS_FAMILIES) {
    if (!families.has(family)) issues.push(`missing corpus family: ${family}`)
  }
  return [...new Set(issues)]
}

export function protocolIssues(protocol) {
  const expected = new Map([
    ['REQUIRED_TOOL', 'VALID_TOOL_CALL'],
    ['NO_TOOL', 'DIRECT_RESPONSE'],
    ['INVALID_ARGUMENT', 'REJECTED_BEFORE_EXECUTION'],
    ['TIMEOUT', 'BOUNDED_TERMINATION'],
    ['PROVIDER_FAILURE', 'DETERMINISTIC_FALLBACK'],
    ['TOOL_SELECTION', 'ONLY_ALLOW_LISTED_TOOL'],
    ['IRRELEVANT_TOOL', 'ZERO_TOOL_CALLS'],
    ['MISSING_PARAMETER', 'REJECTED_BEFORE_EXECUTION'],
    ['EXTRA_PARAMETER', 'REJECTED_BEFORE_EXECUTION'],
    ['MULTIPLE_CALLS', 'ORDERED_CORRELATED_RESULTS'],
    ['REPEATED_FAILURE', 'CIRCUIT_OPEN'],
  ])
  const issues = []
  if (protocol?.schemaVersion !== 1) issues.push('protocol schemaVersion must be 1')
  const cases = Array.isArray(protocol?.cases) ? protocol.cases : []
  for (const [scenario, outcome] of expected) {
    const matches = cases.filter((case_) => case_?.scenario === scenario && case_?.expectedOutcome === outcome)
    if (matches.length !== 1) issues.push(`protocol must define exactly one ${scenario} -> ${outcome} case`)
  }
  return issues
}

export function summarizeBaseline(case_, artifact) {
  if (case_.taskKind === 'ANSWER') {
    const answer = artifact?.answer
    if (!answer || typeof answer.status !== 'string') throw new Error(`${case_.caseId} answer baseline is invalid`)
    return {
      terminalState: answer.status,
      confidence: answer.confidence ?? null,
      citationCount: Array.isArray(answer.citations) ? answer.citations.length : 0,
    }
  }

  const result = artifact?.result
  if (!result || typeof result.status !== 'string') throw new Error(`${case_.caseId} lesson baseline is invalid`)
  return {
    terminalState: result.status,
    sectionCount: result.sectionCount ?? 0,
    visualStepCount: result.visualStepCount ?? 0,
    elapsedSeconds: result.attemptElapsedSeconds ?? result.elapsedSeconds ?? null,
    teachingActivityCount: artifact.teaching?.activityCount ?? 0,
    visualActivityCount: artifact.visual?.activityCount ?? result.visualActivityCount ?? 0,
    evidenceStatuses: Array.isArray(result.evidenceStatuses)
      ? [...new Set(result.evidenceStatuses)].sort()
      : [],
  }
}

export function evaluateBaseline({ root, manifestPath, inventoryPath, protocolPath }) {
  const manifest = JSON.parse(readFileSync(resolve(root, manifestPath), 'utf8'))
  const issues = manifestIssues(manifest)
  if (issues.length) throw new Error(`agent baseline manifest invalid: ${issues.join('; ')}`)

  const protocol = JSON.parse(readFileSync(resolve(root, protocolPath), 'utf8'))
  const callableIssues = protocolIssues(protocol)
  if (callableIssues.length) throw new Error(`agent tool protocol invalid: ${callableIssues.join('; ')}`)

  const inventory = JSON.parse(readFileSync(resolve(root, inventoryPath), 'utf8'))
  const inventoryByDigest = new Map((inventory.qualifiedRulebooks ?? []).map((entry) => [entry.sha256, entry]))

  const cases = manifest.cases.map((case_) => {
    const source = inventoryByDigest.get(case_.sourceSha256)
    if (!source) throw new Error(`${case_.caseId} source digest is absent from the ignored corpus inventory`)
    if (!existsSync(resolve(root, '.local/public-corpus/pdfs', source.file))) {
      throw new Error(`${case_.caseId} real rulebook is not readable`)
    }
    const artifactPath = resolve(root, case_.baselineArtifact)
    if (!existsSync(artifactPath)) throw new Error(`${case_.caseId} baseline artifact is not readable`)
    return {
      caseId: case_.caseId,
      family: case_.family,
      taskKind: case_.taskKind,
      playerNeed: case_.playerNeed,
      sourceSha256: case_.sourceSha256,
      capabilityTags: [...case_.capabilityTags].sort(),
      baseline: summarizeBaseline(case_, JSON.parse(readFileSync(artifactPath, 'utf8'))),
    }
  })

  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    harness: 'APPLICATION_DIRECTED',
    protocolSchemaVersion: protocol.schemaVersion,
    cases,
  }
}

function parseArguments(arguments_) {
  const options = {
    manifest: '.local/agent-evaluation/manifest.json',
    inventory: '.local/public-corpus/source-preflight.json',
    protocol: 'examples/evaluation/agent-tool-protocol-v1.json',
    output: '.local/agent-evaluation/application-harness-baseline.json',
  }
  for (let index = 0; index < arguments_.length; index += 1) {
    const argument = arguments_[index]
    if (argument === '--manifest') options.manifest = arguments_[++index]
    else if (argument === '--inventory') options.inventory = arguments_[++index]
    else if (argument === '--protocol') options.protocol = arguments_[++index]
    else if (argument === '--output') options.output = arguments_[++index]
    else throw new Error(`unknown argument: ${argument}`)
  }
  return options
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const options = parseArguments(process.argv.slice(2))
    const result = evaluateBaseline({
      root,
      manifestPath: options.manifest,
      inventoryPath: options.inventory,
      protocolPath: options.protocol,
    })
    const output = resolve(root, options.output)
    mkdirSync(dirname(output), { recursive: true })
    writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    console.log(`Agent baseline validated: ${result.cases.length} opaque real-rulebook cases across ${new Set(result.cases.map((case_) => case_.family)).size} families.`)
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
