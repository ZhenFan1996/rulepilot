import { readFileSync, readdirSync, statSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const dist = resolve(root, 'frontend/dist')
const indexHtml = readFileSync(resolve(dist, 'index.html'), 'utf8')

const initialAssets = [...indexHtml.matchAll(/(?:src|href)="(\/assets\/[^\"]+\.(?:js|css))"/g)]
  .map((match) => match[1])
const initialJavaScript = initialAssets.filter((asset) => asset.endsWith('.js'))

if (initialJavaScript.length === 0) throw new Error('frontend entry JavaScript was not found')
if (initialAssets.some((asset) => !/-[A-Za-z0-9_-]{8}\.(?:js|css)$/.test(asset))) {
  throw new Error('initial frontend assets must use content-hashed filenames')
}

const sizeOf = (asset) => statSync(resolve(dist, asset.slice(1))).size
const gzipSizeOf = (asset) => gzipSync(readFileSync(resolve(dist, asset.slice(1)))).byteLength
const initialJavaScriptBytes = initialJavaScript.reduce((total, asset) => total + sizeOf(asset), 0)
const initialJavaScriptGzipBytes = initialJavaScript.reduce(
  (total, asset) => total + gzipSizeOf(asset),
  0,
)

if (initialJavaScriptBytes > 270_000 || initialJavaScriptGzipBytes > 95_000) {
  throw new Error(
    `initial JavaScript exceeds its delivery budget: ${initialJavaScriptBytes} bytes / ${initialJavaScriptGzipBytes} gzip bytes`,
  )
}

const routeChunks = readdirSync(resolve(dist, 'assets')).filter(
  (asset) => /View-[A-Za-z0-9_-]{8}\.js$/.test(asset) && !initialAssets.includes(`/assets/${asset}`),
)
if (routeChunks.length < 10) {
  throw new Error(`expected at least 10 lazy route chunks, found ${routeChunks.length}`)
}

const serviceWorker = readFileSync(resolve(dist, 'sw.js'), 'utf8')
const precachedAssets = [
  ...new Set([...serviceWorker.matchAll(/url:"([^"]+)"/g)].map((match) => match[1])),
].filter((asset) => !asset.startsWith('http'))
const precachedAssetSet = new Set(precachedAssets.map((asset) => asset.replace(/^\//, '')))
const initialAssetSet = new Set(initialAssets.map((asset) => asset.replace(/^\//, '')))
const lessonReaderChunk = precachedAssets.find((asset) => /assets\/LessonView-[A-Za-z0-9_-]{8}\.js$/.test(asset))
if (!lessonReaderChunk) throw new Error('offline lesson reader chunk is not precached')

const lessonReaderSource = readFileSync(resolve(dist, lessonReaderChunk), 'utf8')
const lessonReaderDependencies = [
  ...new Set(
    [...lessonReaderSource.matchAll(/from["']\.\/([^"']+\.js)["']/g)]
      .map((match) => `assets/${match[1]}`),
  ),
]
const unavailableOfflineDependencies = lessonReaderDependencies.filter(
  (asset) => !precachedAssetSet.has(asset) && !initialAssetSet.has(asset),
)
if (unavailableOfflineDependencies.length > 0) {
  throw new Error(
    `offline lesson reader dependencies are neither precached nor loaded by the controlled app shell: ${unavailableOfflineDependencies.join(', ')}`,
  )
}
const precacheBytes = precachedAssets.reduce((total, asset) => {
  const localPath = resolve(dist, asset)
  return total + statSync(localPath).size
}, 0)
if (precacheBytes > 180_000) {
  throw new Error(`service-worker install precache exceeds 180000 bytes: ${precacheBytes}`)
}

const nginx = readFileSync(resolve(root, 'frontend/nginx.conf'), 'utf8')
for (const expected of [
  /location \/assets\/ \{[^}]*max-age=31536000, immutable/s,
  /location \/ocr-assets\/ \{[^}]*max-age=31536000, immutable/s,
  /location = \/sw\.js \{[^}]*Cache-Control "no-cache"/s,
  /location \/ \{[^}]*Cache-Control "no-cache"/s,
]) {
  if (!expected.test(nginx)) throw new Error(`frontend Nginx delivery contract is missing: ${expected}`)
}

console.log(
  `Frontend delivery verification passed: ${initialJavaScriptBytes} initial JS bytes (${initialJavaScriptGzipBytes} gzip), `
    + `${routeChunks.length} lazy route chunks, ${precacheBytes} precache bytes.`,
)
