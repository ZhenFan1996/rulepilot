import type { RecommendationProfile } from '@/components/gameRecommendationTypes'

const STORAGE_PREFIX = 'rulepilot:recommendation-conversation:v1:'
const STORAGE_VERSION = 1
const MAX_RAW_LENGTH = 50_000
const MAX_TRANSCRIPT = 24
const MAX_TRANSCRIPT_TEXT = 1_200
const MAX_KNOWN_GAMES = 60
const MAX_SHOWN_GAMES = 60
const MAX_GAME_NAME = 160
const MAX_PENDING_MESSAGE = 500
const MAX_PROFILE_TERM = 80

export type RecommendationConversationTurn = {
  role: 'assistant' | 'user'
  text: string
}

export type RecommendationConversationGame = {
  bggId: number
  name: string
  originalName: string
}

export type RecommendationConversationPending = {
  message: string
  excludedBggIds: number[]
  focusedBggId: number | null
}

export type RecommendationConversationSnapshot = {
  profile: RecommendationProfile
  transcript: RecommendationConversationTurn[]
  knownGames: RecommendationConversationGame[]
  shownBggIds: number[]
  failed: boolean
  pending: RecommendationConversationPending | null
}

type StoredRecommendationConversation = RecommendationConversationSnapshot & { version: number }

export function readRecommendationConversation(
  storage: Storage,
  username: string,
): RecommendationConversationSnapshot | null {
  const key = storageKey(username)
  if (!key) return null
  try {
    const raw = storage.getItem(key)
    if (!raw) return null
    if (raw.length > MAX_RAW_LENGTH) {
      storage.removeItem(key)
      return null
    }
    const parsed = JSON.parse(raw) as unknown
    if (!isStoredConversation(parsed)) {
      storage.removeItem(key)
      return null
    }
    return withoutVersion(parsed)
  } catch {
    try {
      storage.removeItem(key)
    } catch {
      // A corrupt snapshot can be ignored even when browser storage cannot be changed.
    }
    return null
  }
}

export function rememberRecommendationConversation(
  storage: Storage,
  username: string,
  snapshot: RecommendationConversationSnapshot,
) {
  const key = storageKey(username)
  if (!key) return
  try {
    const bounded = boundedSnapshot(snapshot)
    if (isEmptySnapshot(bounded)) {
      storage.removeItem(key)
      return
    }
    storage.setItem(key, JSON.stringify({ version: STORAGE_VERSION, ...bounded }))
  } catch {
    // The visible conversation remains authoritative when browser-session storage is unavailable.
  }
}

export function forgetRecommendationConversation(storage: Storage, username: string) {
  const key = storageKey(username)
  if (!key) return
  try {
    storage.removeItem(key)
  } catch {
    // Clearing the visible conversation does not depend on browser storage.
  }
}

function storageKey(username: string) {
  const owner = username.normalize('NFKC').trim().toLowerCase().slice(0, 320)
  if (!owner) return null
  return `${STORAGE_PREFIX}${encodeURIComponent(owner)}`
}

function boundedSnapshot(snapshot: RecommendationConversationSnapshot): RecommendationConversationSnapshot {
  const transcript = snapshot.transcript
    .filter(isConversationTurn)
    .slice(-MAX_TRANSCRIPT)
    .map(turn => ({ role: turn.role, text: turn.text.slice(0, MAX_TRANSCRIPT_TEXT) }))
  const knownGames = uniqueGames(snapshot.knownGames.filter(isConversationGame)).slice(0, MAX_KNOWN_GAMES)
  const shownBggIds = uniquePositiveIntegers(snapshot.shownBggIds).slice(-MAX_SHOWN_GAMES)
  const pending = isConversationPending(snapshot.pending)
    ? {
        message: snapshot.pending.message.slice(0, MAX_PENDING_MESSAGE),
        excludedBggIds: uniquePositiveIntegers(snapshot.pending.excludedBggIds).slice(-MAX_SHOWN_GAMES),
        focusedBggId: snapshot.pending.focusedBggId,
      }
    : null
  return {
    profile: isProfile(snapshot.profile) ? { ...snapshot.profile } : emptyProfile(),
    transcript,
    knownGames,
    shownBggIds,
    failed: snapshot.failed === true,
    pending: snapshot.failed === true ? pending : null,
  }
}

function isStoredConversation(value: unknown): value is StoredRecommendationConversation {
  return isRecord(value)
    && value.version === STORAGE_VERSION
    && isProfile(value.profile)
    && Array.isArray(value.transcript)
    && value.transcript.length <= MAX_TRANSCRIPT
    && value.transcript.every(isConversationTurn)
    && Array.isArray(value.knownGames)
    && value.knownGames.length <= MAX_KNOWN_GAMES
    && value.knownGames.every(isConversationGame)
    && hasUniqueGameIds(value.knownGames)
    && Array.isArray(value.shownBggIds)
    && value.shownBggIds.length <= MAX_SHOWN_GAMES
    && value.shownBggIds.every(isPositiveInteger)
    && new Set(value.shownBggIds).size === value.shownBggIds.length
    && typeof value.failed === 'boolean'
    && (value.pending === null || isConversationPending(value.pending))
    && (value.failed || value.pending === null)
}

function withoutVersion(value: StoredRecommendationConversation): RecommendationConversationSnapshot {
  return {
    profile: { ...value.profile },
    transcript: value.transcript.map(turn => ({ ...turn })),
    knownGames: value.knownGames.map(game => ({ ...game })),
    shownBggIds: [...value.shownBggIds],
    failed: value.failed,
    pending: value.pending ? {
      message: value.pending.message,
      excludedBggIds: [...value.pending.excludedBggIds],
      focusedBggId: value.pending.focusedBggId,
    } : null,
  }
}

function isEmptySnapshot(snapshot: RecommendationConversationSnapshot) {
  const profileEmpty = snapshot.profile.players === null
    && snapshot.profile.maxMinutes === null
    && snapshot.profile.maxWeight === null
    && snapshot.profile.type === 'all'
    && snapshot.profile.interaction === 'any'
  return !snapshot.transcript.some(turn => turn.role === 'user')
    && snapshot.knownGames.length === 0
    && snapshot.shownBggIds.length === 0
    && !snapshot.failed
    && snapshot.pending === null
    && profileEmpty
}

function emptyProfile(): RecommendationProfile {
  return { players: null, maxMinutes: null, maxWeight: null, type: 'all', interaction: 'any' }
}

function isProfile(value: unknown): value is RecommendationProfile {
  return isRecord(value)
    && nullableIntegerInRange(value.players, 1, 20)
    && nullableIntegerInRange(value.maxMinutes, 0, 1_440)
    && (value.maxMinutes === null || value.maxMinutes === 0 || Number(value.maxMinutes) >= 5)
    && nullableNumberInRange(value.maxWeight, 0, 5)
    && boundedString(value.type, 1, MAX_PROFILE_TERM)
    && boundedString(value.interaction, 1, MAX_PROFILE_TERM)
}

function isConversationTurn(value: unknown): value is RecommendationConversationTurn {
  return isRecord(value)
    && (value.role === 'assistant' || value.role === 'user')
    && boundedString(value.text, 1, MAX_TRANSCRIPT_TEXT)
}

function isConversationGame(value: unknown): value is RecommendationConversationGame {
  return isRecord(value)
    && isPositiveInteger(value.bggId)
    && boundedString(value.name, 1, MAX_GAME_NAME)
    && boundedString(value.originalName, 1, MAX_GAME_NAME)
}

function isConversationPending(value: unknown): value is RecommendationConversationPending {
  return isRecord(value)
    && boundedString(value.message, 1, MAX_PENDING_MESSAGE)
    && Array.isArray(value.excludedBggIds)
    && value.excludedBggIds.length <= MAX_SHOWN_GAMES
    && value.excludedBggIds.every(isPositiveInteger)
    && new Set(value.excludedBggIds).size === value.excludedBggIds.length
    && (value.focusedBggId === null || isPositiveInteger(value.focusedBggId))
}

function uniqueGames(games: RecommendationConversationGame[]) {
  return games.filter((game, index) => games.findIndex(candidate => candidate.bggId === game.bggId) === index)
}

function hasUniqueGameIds(games: RecommendationConversationGame[]) {
  return new Set(games.map(game => game.bggId)).size === games.length
}

function uniquePositiveIntegers(values: number[]) {
  return [...new Set(values.filter(isPositiveInteger))]
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0
}

function nullableIntegerInRange(value: unknown, minimum: number, maximum: number) {
  return value === null || Number.isSafeInteger(value) && Number(value) >= minimum && Number(value) <= maximum
}

function nullableNumberInRange(value: unknown, minimum: number, maximum: number) {
  return value === null || typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
}

function boundedString(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === 'string' && value.length >= minimum && value.length <= maximum
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
