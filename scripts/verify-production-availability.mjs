const DEFAULT_ATTEMPTS = 3
const DEFAULT_TIMEOUT_MILLIS = 10_000
const DEFAULT_RETRY_DELAY_MILLIS = 3_000

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const readJson = async (response, label) => {
  try {
    return await response.json()
  } catch {
    throw new Error(`${label} returned HTTP ${response.status} with invalid JSON`)
  }
}

const fetchWithin = (fetchImpl, url, timeoutMillis, traceparent) => fetchImpl(url, {
  ...(traceparent ? { headers: { traceparent } } : {}),
  signal: AbortSignal.timeout(timeoutMillis),
})

function validatedTraceparent(traceparent) {
  if (traceparent === undefined) return undefined
  const match = traceparent.match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-01$/)
  if (!match || /^0{32}$/.test(match[1]) || /^0{16}$/.test(match[2])) {
    throw new Error('traceparent must be a sampled non-zero W3C version 00 value')
  }
  return traceparent
}

export async function verifyProductionAvailability({
  publicUrl,
  fetchImpl = globalThis.fetch,
  attempts = DEFAULT_ATTEMPTS,
  timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
  retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS,
  sleep = pause,
  traceparent,
} = {}) {
  if (typeof publicUrl !== 'string' || !publicUrl.startsWith('https://')) {
    throw new Error('RULEPILOT_PUBLIC_URL must be an HTTPS origin')
  }
  if (typeof fetchImpl !== 'function') throw new Error('fetch is unavailable')
  if (!Number.isInteger(attempts) || attempts < 1) throw new Error('attempts must be positive')
  const canaryTraceparent = validatedTraceparent(traceparent)

  const origin = publicUrl.replace(/\/$/, '')
  let lastFailure = 'availability verification did not run'

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const page = await fetchWithin(fetchImpl, `${origin}/`, timeoutMillis, canaryTraceparent)
      const html = await page.text()
      if (!page.ok || html.trim().length === 0) {
        throw new Error(`frontend returned an empty or unsuccessful HTTP ${page.status} response`)
      }

      const csrf = await fetchWithin(fetchImpl, `${origin}/api/auth/csrf`, timeoutMillis, canaryTraceparent)
      const csrfPayload = await readJson(csrf, 'CSRF endpoint')
      if (!csrf.ok
          || typeof csrfPayload.token !== 'string'
          || csrfPayload.token.length === 0
          || typeof csrfPayload.headerName !== 'string'
          || csrfPayload.headerName.length === 0) {
        throw new Error(`CSRF endpoint returned an invalid HTTP ${csrf.status} response`)
      }

      const recommendations = await fetchWithin(
        fetchImpl,
        `${origin}/api/v1/bgg/recommendations`,
        timeoutMillis,
        canaryTraceparent,
      )
      const games = await readJson(recommendations, 'BGG recommendations')
      if (!recommendations.ok || !Array.isArray(games) || games.length === 0) {
        throw new Error(`BGG recommendations returned an invalid HTTP ${recommendations.status} response`)
      }
      const firstGame = games[0]
      if (!Number.isInteger(firstGame?.bggId)
          || firstGame.bggId <= 0
          || typeof firstGame.name !== 'string'
          || firstGame.name.trim().length === 0) {
        throw new Error('BGG recommendations did not contain a valid game identity')
      }

      const detail = await fetchWithin(
        fetchImpl,
        `${origin}/api/v1/bgg/games/${firstGame.bggId}?locale=zh-CN`,
        timeoutMillis,
        canaryTraceparent,
      )
      const game = await readJson(detail, 'BGG game detail')
      if (!detail.ok
          || game.bggId !== firstGame.bggId
          || typeof game.description !== 'string'
          || typeof game.descriptionTranslated !== 'boolean'
          || !Array.isArray(game.categories)
          || !Array.isArray(game.mechanics)) {
        throw new Error(`BGG game detail returned an invalid HTTP ${detail.status} response`)
      }

      return {
        attempt,
        bggId: firstGame.bggId,
        gameName: firstGame.name,
      }
    } catch (error) {
      lastFailure = error instanceof Error ? error.message : String(error)
      if (attempt < attempts) await sleep(retryDelayMillis)
    }
  }

  throw new Error(`Public production availability verification failed: ${lastFailure}`)
}

const isEntrypoint = process.argv[1] != null
  && new URL(import.meta.url).pathname === new URL(`file://${process.argv[1]}`).pathname

if (isEntrypoint) {
  if (!process.env.RULEPILOT_CANARY_TRACEPARENT) {
    throw new Error('RULEPILOT_CANARY_TRACEPARENT is required for exact-release verification')
  }
  const result = await verifyProductionAvailability({
    publicUrl: process.env.RULEPILOT_PUBLIC_URL,
    traceparent: process.env.RULEPILOT_CANARY_TRACEPARENT,
  })
  console.log(
    `Verified public release availability on attempt ${result.attempt}; BGG ${result.bggId} ${result.gameName}`,
  )
}
