import { pathToFileURL } from 'node:url'

const MAX_PAGE_BYTES = 2_000_000

function usage() {
  console.log('Usage: node scripts/discover-publisher-cover.mjs --source <publisher-page> --title <game-title>')
  console.log('Prints one high-confidence product-cover candidate found on the cited publisher page.')
}

export function parseArguments(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help') options.help = true
    else if (argument === '--source') options.source = argv[++index]
    else if (argument === '--title') options.title = argv[++index]
    else throw new Error(`Unknown argument: ${argument}`)
  }
  return options
}

function httpsUrl(value, label) {
  if (!value?.trim()) throw new Error(`${label} is required`)
  const url = new URL(value)
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw new Error(`${label} must be an HTTPS URL without credentials`)
  }
  return url
}

function attributes(tag) {
  const values = new Map()
  for (const match of tag.matchAll(/([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g)) {
    values.set(match[1].toLowerCase(), match[2] ?? match[3] ?? match[4] ?? '')
  }
  return values
}

function titleTokens(title) {
  return [...new Set(title.normalize('NFKD').toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ').split(' ').filter(token => token.length >= 3))]
}

function candidateUrl(value, source, allowExternal = false) {
  if (!value || value.startsWith('data:')) return null
  let url
  try {
    url = new URL(value, source)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.username || url.password) return null
  if (!allowExternal && url.hostname !== source.hostname && !url.hostname.endsWith(`.${source.hostname}`)) return null
  if (!/\.(?:avif|gif|jpe?g|png|webp)(?:$|[?#])/i.test(url.pathname)) return null
  if (requestsTinyImage(url)) return null
  return url.toString()
}

function requestsTinyImage(url) {
  const requested = ['resize', 'fit', 'width', 'height', 'w', 'h']
    .map(name => url.searchParams.get(name))
    .filter(Boolean)
    .flatMap(value => [...value.matchAll(/\d+/g)].map(match => Number(match[0])))
  return requested.length >= 2 && Math.max(...requested) < 180
}

function hasTinyTagDimensions(values) {
  const width = Number(values.get('width'))
  const height = Number(values.get('height'))
  return Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
    && Math.max(width, height) < 180
}

function metaValue(html, name) {
  for (const match of html.matchAll(/<meta\b[^>]*>/gi)) {
    const values = attributes(match[0])
    if (values.get('property')?.toLowerCase() === name || values.get('name')?.toLowerCase() === name) {
      return values.get('content') ?? ''
    }
  }
  return ''
}

function titleMatchCount(value, tokens) {
  const descriptor = value.normalize('NFKD').toLowerCase()
  return tokens.filter(token => descriptor.includes(token)).length
}

function selectOpenGraphCover(html, source, tokens) {
  const pageTitle = metaValue(html, 'og:title')
  const requiredMatches = Math.max(1, Math.ceil(tokens.length * 0.6))
  const tokenMatches = titleMatchCount(pageTitle, tokens)
  if (tokenMatches < requiredMatches) return null

  const url = candidateUrl(metaValue(html, 'og:image'), source, true)
  if (!url) return null
  return { url, score: 90 + tokenMatches, tokenMatches }
}

export function discoverCoverCandidates(html, sourceUrl, title) {
  const source = httpsUrl(sourceUrl, '--source')
  const tokens = titleTokens(title)
  if (!tokens.length) throw new Error('--title must contain searchable letters or numbers')
  const candidates = []
  for (const match of html.matchAll(/<img\b[^>]*>/gi)) {
    const values = attributes(match[0])
    const url = candidateUrl(values.get('src') ?? values.get('data-src'), source)
    if (!url || hasTinyTagDimensions(values)) continue
    const descriptor = [url, values.get('alt'), values.get('title'), values.get('class')]
      .filter(Boolean).join(' ').toLowerCase()
    const tokenMatches = tokens.filter(token => descriptor.includes(token)).length
    const score = tokenMatches * 25
      + (/\bbox\b|box[-_ ]?art|game[-_ ]?box/i.test(descriptor) ? 20 : 0)
      + (/\bcover\b/i.test(descriptor) ? 15 : 0)
      - (/\blogo\b|signature[-_ ]?mark|evergreen[_-]?tag|\bbanner\b|\bicon\b/i.test(descriptor) ? 80 : 0)
    if (tokenMatches > 0 && score > 0) candidates.push({ url, score, tokenMatches })
  }
  return candidates.sort((left, right) => right.score - left.score || right.tokenMatches - left.tokenMatches)
}

export function selectPublisherCover(html, sourceUrl, title) {
  const source = httpsUrl(sourceUrl, '--source')
  const tokens = titleTokens(title)
  if (!tokens.length) throw new Error('--title must contain searchable letters or numbers')
  return discoverCoverCandidates(html, sourceUrl, title).at(0)
    ?? selectOpenGraphCover(html, source, tokens)
}

export async function discoverPublisherCover(sourceUrl, title, fetchImpl = fetch) {
  const source = httpsUrl(sourceUrl, '--source')
  const response = await fetchImpl(source, {
    headers: { Accept: 'text/html', 'User-Agent': 'RulePilot/0.1 official-cover discovery' },
    redirect: 'follow',
  })
  if (!response.ok) throw new Error(`publisher page request failed with HTTP ${response.status}`)
  const length = Number(response.headers.get('content-length') ?? 0)
  if (Number.isFinite(length) && length > MAX_PAGE_BYTES) throw new Error('publisher page is too large to inspect safely')
  const html = await response.text()
  if (Buffer.byteLength(html, 'utf8') > MAX_PAGE_BYTES) throw new Error('publisher page is too large to inspect safely')
  const selected = selectPublisherCover(html, response.url, title)
  if (!selected) throw new Error('no title-matching publisher cover image was found')
  return { sourceUrl: response.url, title, ...selected }
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) return usage()
  console.log(JSON.stringify(await discoverPublisherCover(options.source, options.title), null, 2))
}

const executedDirectly = process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url
if (executedDirectly) {
  main().catch(error => {
    console.error(`COVER DISCOVERY FAILED: ${error.message}`)
    process.exitCode = 2
  })
}
