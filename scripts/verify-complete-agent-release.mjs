#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const PLAYER_NEEDS = Object.freeze([
  'START_PLAYING', 'RESOLVE_EXCEPTION', 'MATCH_TABLE_STATE', 'IDENTIFY_SYMBOL', 'ASK_NATURALLY',
])

function requireInvariant(condition, message) {
  if (!condition) throw new Error(message)
}

export function verifyRealRelease({ security, baseline, providerReports, notBefore, now = new Date() }) {
  const reports = [security, baseline, ...providerReports]
  if (notBefore) {
    const lowerBound = new Date(notBefore)
    requireInvariant(!Number.isNaN(lowerBound.getTime()), 'release evidence lower bound is invalid')
    requireInvariant(now instanceof Date && !Number.isNaN(now.getTime()), 'release verification time is invalid')
    for (const report of reports) {
      const generatedAt = new Date(report?.generatedAt)
      requireInvariant(!Number.isNaN(generatedAt.getTime()), 'release evidence generatedAt is missing or invalid')
      requireInvariant(generatedAt >= lowerBound, 'release evidence is stale')
      requireInvariant(generatedAt <= new Date(now.getTime() + 5 * 60 * 1000), 'release evidence is future-dated')
    }
  }
  requireInvariant(security?.hardInvariants === 'PASSED', 'security hard invariants did not pass')
  requireInvariant(Array.isArray(security.coveredFamilies) && security.coveredFamilies.length === 5,
    'five real corpus families are required')
  const cases = Array.isArray(baseline?.cases) ? baseline.cases : []
  for (const need of PLAYER_NEEDS) {
    requireInvariant(cases.some((case_) => case_.playerNeed === need && case_.baseline?.terminalState),
      `player journey evidence missing: ${need}`)
  }
  const providers = new Set(providerReports.flatMap((report) =>
    (report?.semanticResults ?? report?.results ?? []).map((result) => result.provider).filter(Boolean)))
  requireInvariant(providers.size >= 2, 'at least two paid providers are required')
  const answerProviders = new Set(providerReports.flatMap((report) =>
    (report?.results ?? [])
      .filter((result) => result.citationPublished === true)
      .map((result) => result.provider)
      .filter(Boolean)))
  requireInvariant(answerProviders.size >= 2, 'product Answer evidence must publish citations with two paid providers')
  const teachingProviders = new Set(providerReports.flatMap((report) =>
    (report?.semanticResults ?? report?.results ?? [])
      .filter((result) => result.citationAccepted === true)
      .map((result) => result.provider)
      .filter(Boolean)))
  requireInvariant(teachingProviders.size >= 2, 'product Teaching evidence must accept citations with two paid providers')
  requireInvariant(cases.some((case_) => case_.taskKind === 'ANSWER'
      && case_.baseline?.terminalState === 'ANSWERED'
      && case_.baseline?.citationCount > 0),
  'product Answer player journey must terminate with a citation')
  requireInvariant(cases.some((case_) => ['TEACHING', 'VISUAL_TEACHING'].includes(case_.taskKind)
      && ['DRAFT_READY', 'COMPLETE'].includes(case_.baseline?.terminalState)
      && (case_.baseline?.evidenceStatuses ?? []).length > 0),
  'product Teaching player journey must terminate with evidence')
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    realCorpusFamilies: 5,
    playerNeeds: [...PLAYER_NEEDS],
    providers: [...providers].sort(),
    evidenceNotBefore: notBefore ?? null,
    desktopMobileGate: 'REQUIRES_PLAYWRIGHT_SUCCESS',
    status: 'PASSED',
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const load = (path) => JSON.parse(readFileSync(resolve(root, path), 'utf8'))
    if (!process.argv.includes('--real')) {
      throw new Error('complete Agent release verification requires fresh real evidence; pass --real')
    }
    const result = verifyRealRelease({
      security: load('.local/agent-evaluation/security-agent-release-gate.json'),
      baseline: load('.local/agent-evaluation/application-harness-baseline.json'),
      providerReports: [
        load('.local/agent-evaluation/answer-agent-real-rulebooks.json'),
        load('.local/agent-evaluation/teaching-agent-real-rulebooks.json'),
        load('.local/agent-evaluation/visual-agent-real-rulebooks.json'),
        load('.local/agent-evaluation/context-agent-real-rulebooks.json'),
      ],
      notBefore: process.argv.includes('--not-before')
        ? process.argv[process.argv.indexOf('--not-before') + 1]
        : undefined,
    })
    const output = resolve(root, '.local/agent-evaluation/complete-agent-release.json')
    mkdirSync(dirname(output), { recursive: true })
    writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`, { mode: 0o600 })
    console.log(`Complete Agent real gate passed: ${result.realCorpusFamilies} families, ${result.providers.length} providers, ${result.playerNeeds.length} player needs.`)
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
