#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const REQUIRED_ENTRYPOINTS = [
  'README.md',
  'AGENTS.md',
]

function trackedMarkdown(root) {
  return execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '--', '*.md'], {
    cwd: root,
    encoding: 'utf8',
  }).trim().split('\n').filter(Boolean).sort()
}

export function localMarkdownTargets(markdown) {
  return [...markdown.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)]
    .map((match) => match[1].trim().replace(/^<|>$/g, ''))
    .filter((target) => target && !target.startsWith('#'))
    .filter((target) => !/^(?:https?:|mailto:|app:)/.test(target))
    .map((target) => target.split('#', 1)[0])
    .filter(Boolean)
}

export function corpusManifestIssues(manifest) {
  const entries = manifest?.qualifiedRulebooks
  if (!Array.isArray(entries) || entries.length === 0) return ['qualifiedRulebooks must be a non-empty array']

  const issues = []
  const files = new Set()
  for (const entry of entries) {
    if (!entry || typeof entry.file !== 'string' || !entry.file.endsWith('.pdf')) issues.push('every entry needs a PDF file')
    if (!entry || typeof entry.source !== 'string' || !entry.source.startsWith('https://')) issues.push('every entry needs an HTTPS source')
    if (!entry || typeof entry.sha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(entry.sha256)) issues.push('every entry needs a SHA-256 digest')
    if (entry?.file && files.has(entry.file)) issues.push('manifest contains a duplicate PDF file')
    if (entry?.file) files.add(entry.file)
  }
  return [...new Set(issues)]
}

function privateCorpusSummary(root) {
  const relativeManifest = '.local/public-corpus/source-preflight.json'
  const manifestPath = resolve(root, relativeManifest)
  if (!existsSync(manifestPath)) throw new Error(`private corpus manifest is not discoverable at ${relativeManifest}`)

  try {
    execFileSync('git', ['check-ignore', '-q', relativeManifest], { cwd: root })
  } catch {
    throw new Error(`${relativeManifest} must remain ignored by Git`)
  }

  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
  const manifestIssues = corpusManifestIssues(manifest)
  if (manifestIssues.length) throw new Error(`private corpus manifest invalid: ${manifestIssues.join('; ')}`)

  const pdfDirectory = resolve(root, '.local/public-corpus/pdfs')
  const missingSources = manifest.qualifiedRulebooks.filter((entry) => !existsSync(resolve(pdfDirectory, entry.file)))
  if (missingSources.length) throw new Error(`private corpus is missing ${missingSources.length} manifest source file(s)`)

  const runDirectory = resolve(root, '.local/public-corpus/runs')
  const runCount = existsSync(runDirectory) ? readdirSync(runDirectory).filter((file) => file.endsWith('.json')).length : 0
  if (runCount === 0) throw new Error('no accepted real-corpus run artifacts are discoverable')

  const baselineDirectory = resolve(root, '.local/seti-qa')
  const baselineCount = existsSync(baselineDirectory)
    ? readdirSync(baselineDirectory).filter((file) => /^baseline-.*\.json$/.test(file)).length
    : 0
  if (baselineCount === 0) throw new Error('no ignored answer baseline artifacts are discoverable')

  return { sourceCount: manifest.qualifiedRulebooks.length, runCount, baselineCount }
}

export function verifyDocumentation({ root, requirePrivateCorpus = false } = {}) {
  const repositoryRoot = root ?? resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const failures = []

  for (const document of REQUIRED_ENTRYPOINTS) {
    if (!existsSync(resolve(repositoryRoot, document))) failures.push(`missing required document: ${document}`)
  }

  const maintainedDocuments = trackedMarkdown(repositoryRoot)
  for (const document of maintainedDocuments) {
    const documentPath = resolve(repositoryRoot, document)
    const markdown = readFileSync(documentPath, 'utf8')
    for (const target of localMarkdownTargets(markdown)) {
      if (!existsSync(resolve(dirname(documentPath), target))) failures.push(`broken local link in ${document}: ${target}`)
    }
  }

  const trackedSensitiveArtifacts = execFileSync('git', ['ls-files', '--', '.local/**', '*.pdf'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  }).trim()
  if (trackedSensitiveArtifacts) failures.push('private corpus data or PDF files are tracked by Git')

  let privateCorpus
  if (requirePrivateCorpus) {
    try {
      privateCorpus = privateCorpusSummary(repositoryRoot)
    } catch (error) {
      failures.push(error.message)
    }
  }

  return { failures, checkedDocuments: maintainedDocuments.length, privateCorpus }
}

function parseArguments(arguments_) {
  const unknown = arguments_.filter((argument) => argument !== '--require-private-corpus')
  if (unknown.length) throw new Error(`unknown argument: ${unknown[0]}`)
  return { requirePrivateCorpus: arguments_.includes('--require-private-corpus') }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const result = verifyDocumentation(parseArguments(process.argv.slice(2)))
    if (result.failures.length) {
      for (const failure of result.failures) console.error(`FAIL ${failure}`)
      process.exitCode = 1
    } else {
      console.log(`Documentation verification passed: ${result.checkedDocuments} maintained Markdown files.`)
      if (result.privateCorpus) {
        console.log(
          `Private corpus discoverable: ${result.privateCorpus.sourceCount} sources, ` +
          `${result.privateCorpus.runCount} runs, ${result.privateCorpus.baselineCount} baselines.`,
        )
      }
    }
  } catch (error) {
    console.error(`FAIL ${error.message}`)
    process.exitCode = 1
  }
}
