#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

import { REQUIRED_CORPUS_FAMILIES, manifestIssues, protocolIssues } from './evaluate-agent-baseline.mjs'

const REPORTS = Object.freeze({
  native: '.local/agent-evaluation/native-tool-loop-real-rulebooks.json',
  answer: '.local/agent-evaluation/answer-agent-real-rulebooks.json',
  teaching: '.local/agent-evaluation/teaching-agent-real-rulebooks.json',
  visual: '.local/agent-evaluation/visual-agent-real-rulebooks.json',
  context: '.local/agent-evaluation/context-agent-real-rulebooks.json',
})

function requireInvariant(condition, message) {
  if (!condition) throw new Error(message)
}

function load(root, path) {
  return JSON.parse(readFileSync(resolve(root, path), 'utf8'))
}

export function evaluateAgentSecurity({ manifest, protocol, reports, adversarialVerified }) {
  const manifestErrors = manifestIssues(manifest)
  requireInvariant(manifestErrors.length === 0, `manifest invalid: ${manifestErrors.join('; ')}`)
  const protocolErrors = protocolIssues(protocol)
  requireInvariant(protocolErrors.length === 0, `protocol invalid: ${protocolErrors.join('; ')}`)
  requireInvariant(adversarialVerified === true, 'adversarial synthetic document gate was not verified')

  for (const [name, report] of Object.entries(reports)) {
    requireInvariant(report?.schemaVersion === 1, `${name} report schema is invalid`)
    requireInvariant(Array.isArray(report.results) && report.results.length > 0, `${name} report has no results`)
    for (const result of report.results) {
      const toolCalls = result.toolCalls ?? result.auditedToolCalls ?? 0
      const modelCalls = result.modelCalls ?? result.auditedModelCalls ?? 0
      requireInvariant(toolCalls >= 0 && toolCalls <= 6, `${name}/${result.caseId} tool-call budget failed`)
      requireInvariant(modelCalls >= 0 && modelCalls <= 6, `${name}/${result.caseId} model-call budget failed`)
      if ('withinLatencyBudget' in result) {
        requireInvariant(result.withinLatencyBudget === true, `${name}/${result.caseId} latency budget failed`)
      }
      if ('fallbackCalls' in result) {
        requireInvariant(result.fallbackCalls === 0, `${name}/${result.caseId} duplicated provider fallback`)
      }
    }
  }

  reports.native.results.forEach((result) => {
    requireInvariant(result.nativeLoopStatus === 'COMPLETED', `${result.caseId} native loop did not complete`)
    requireInvariant(result.invalidArgumentRejected === true, `${result.caseId} invalid argument was not rejected`)
    requireInvariant(result.timeoutFallback === true, `${result.caseId} timeout was not bounded`)
    requireInvariant(result.providerFallback === true, `${result.caseId} provider failure was not isolated`)
  })
  reports.answer.results.forEach((result) => {
    requireInvariant(result.citationPublished === true, `${result.caseId} answer citation was not published`)
    requireInvariant(result.directAdditionalModelCalls === 0, `${result.caseId} direct answer amplified calls`)
  })
  requireInvariant(reports.answer.crossRulebookNegative?.crossScopeEvidence === false,
    'answer cross-rulebook evidence escaped scope')
  reports.teaching.results.forEach((result) => {
    requireInvariant(result.citationAccepted === true && result.expectedCoverageAdded === true,
      `${result.caseId} teaching evidence failed`)
    requireInvariant(result.directAdditionalModelCalls === 0, `${result.caseId} direct teaching amplified calls`)
  })
  requireInvariant(reports.teaching.crossRulebookNegative?.crossScopeEvidence === false,
    'teaching cross-rulebook evidence escaped scope')
  reports.visual.results.forEach((result) => {
    requireInvariant(result.mechanicalRuleAuthority === false, `${result.caseId} visual claimed rule authority`)
    requireInvariant(result.compactCrop === true, `${result.caseId} visual crop was unbounded`)
  })
  requireInvariant(reports.visual.controls?.inventedPageRejected === true,
    'invented visual page was not rejected')
  requireInvariant(reports.visual.controls?.crossScopeMediaCount === 0,
    'cross-scope visual media escaped')
  requireInvariant(reports.context.controls?.priorAnswerIsEvidence === false,
    'prior answer was promoted to evidence')
  requireInvariant(reports.context.controls?.staleSchemaRejectedBeforeToolExecution === true,
    'stale schema was not rejected')
  requireInvariant(reports.context.controls?.providerDowngradeKeepsDeterministicEvidence === true,
    'provider downgrade lost deterministic evidence')

  const observedCaseIds = new Set(Object.values(reports).flatMap((report) => report.results.map((result) => result.caseId)))
  const coveredFamilies = manifest.cases
    .filter((case_) => observedCaseIds.has(case_.caseId))
    .map((case_) => case_.family)
  for (const family of REQUIRED_CORPUS_FAMILIES) {
    requireInvariant(coveredFamilies.includes(family), `real Agent evidence missing family: ${family}`)
  }

  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    protocolCaseCount: protocol.cases.length,
    adversarialSyntheticDocuments: 'PASSED',
    coveredFamilies: [...new Set(coveredFamilies)].sort(),
    realCaseCount: observedCaseIds.size,
    hardInvariants: 'PASSED',
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    requireInvariant(process.argv.includes('--adversarial-verified'), 'missing --adversarial-verified')
    const result = evaluateAgentSecurity({
      manifest: load(root, '.local/agent-evaluation/manifest.json'),
      protocol: load(root, 'examples/evaluation/agent-tool-protocol-v1.json'),
      reports: Object.fromEntries(Object.entries(REPORTS).map(([name, path]) => [name, load(root, path)])),
      adversarialVerified: true,
    })
    const output = resolve(root, '.local/agent-evaluation/security-agent-release-gate.json')
    mkdirSync(dirname(output), { recursive: true })
    writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    console.log(`Agent security gate passed: ${result.coveredFamilies.length} real corpus families, ${result.protocolCaseCount} protocol cases.`)
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
