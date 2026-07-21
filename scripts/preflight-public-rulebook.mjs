import { readFile, stat } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const MINIMUM_BYTES = 100_000

function usage() {
  console.log('Usage: node scripts/preflight-public-rulebook.mjs --pdf <local.pdf> --source <publisher-url> --cover <publisher-image-url>')
  console.log('Checks that a local publisher-provided rulebook is a complete PDF before any model generation.')
}

function parseArguments(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help') options.help = true
    else if (argument === '--pdf') options.pdf = argv[++index]
    else if (argument === '--source') options.source = argv[++index]
    else if (argument === '--cover') options.cover = argv[++index]
    else throw new Error(`Unknown argument: ${argument}`)
  }
  return options
}

function publisherUrl(value, label) {
  if (!value) throw new Error(`${label} is required`)
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new Error(`${label} must be a valid HTTPS URL`)
  }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password) {
    throw new Error(`${label} must be an HTTPS URL without credentials`)
  }
  return parsed.toString()
}

function pageObjects(pdf) {
  return [...pdf.matchAll(/\/Type\s*\/Page(?:\s|\/|>>)/g)].length
}

export async function preflightPublicRulebook({ pdfPath, sourceUrl, coverUrl }) {
  const source = publisherUrl(sourceUrl, '--source')
  const cover = publisherUrl(coverUrl, '--cover')
  if (!pdfPath) throw new Error('--pdf is required')

  const absolutePath = resolve(pdfPath)
  const [metadata, content] = await Promise.all([stat(absolutePath), readFile(absolutePath)])
  if (metadata.size < MINIMUM_BYTES) throw new Error(`PDF is implausibly small (${metadata.size} bytes)`)
  if (!content.subarray(0, 8).toString('latin1').startsWith('%PDF-')) {
    throw new Error('Local file does not start with a PDF header')
  }
  if (!content.subarray(-1024).toString('latin1').includes('%%EOF')) {
    throw new Error('Local PDF is incomplete: missing final %%EOF marker')
  }

  const pages = pageObjects(content.toString('latin1'))
  if (pages === 0) throw new Error('Local PDF has no detectable page objects')

  return {
    pdf: absolutePath,
    bytes: metadata.size,
    sha256: createHash('sha256').update(content).digest('hex'),
    detectedPageObjects: pages,
    officialSourceUrl: source,
    officialCoverUrl: cover,
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) return usage()
  const report = await preflightPublicRulebook({
    pdfPath: options.pdf,
    sourceUrl: options.source,
    coverUrl: options.cover,
  })
  console.log(JSON.stringify(report, null, 2))
}

const executedDirectly = process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url
if (executedDirectly) {
  main().catch((error) => {
    console.error(`PRECHECK FAILED: ${error.message}`)
    process.exitCode = 2
  })
}
