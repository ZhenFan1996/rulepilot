import type { RecommendationProfile } from '@/components/gameRecommendationTypes'
import type { AppLocale } from '@/lib/locale'
import { canonicalRecommendationProfile, emptyRecommendationProfile } from '@/lib/recommendationProfile'

const STORAGE_PREFIX = 'rulepilot:recommendation-conversation:v1:'
const STORAGE_VERSION = 3
const MAX_RAW_LENGTH = 200_000
const MAX_TRANSCRIPT = 24
const MAX_KNOWN_GAMES = 60
const MAX_SHOWN_GAMES = 60

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
  clientTurnId: string | null
  responseLocale: AppLocale | null
}

export type RecommendationConversationSnapshot = {
  conversationId: string | null
  revision: number
  responseLocale: AppLocale | null
  profile: RecommendationProfile
  transcript: RecommendationConversationTurn[]
  knownGames: RecommendationConversationGame[]
  shownBggIds: number[]
  selectedBggId: number | null
  failed: boolean
  pending: RecommendationConversationPending | null
}

type StoredRecommendationConversation = Omit<RecommendationConversationSnapshot, 'selectedBggId'> & {
  version: 3
  selectedBggId?: number | null
}
type PreviousRecommendationPending = Omit<RecommendationConversationPending, 'responseLocale'>
type PreviousRecommendationConversation = Omit<RecommendationConversationSnapshot, 'responseLocale' | 'pending' | 'selectedBggId'> & {
  version: 2
  pending: PreviousRecommendationPending | null
}
type LegacyRecommendationConversation = Omit<
  PreviousRecommendationConversation,
  'conversationId' | 'revision' | 'version' | 'pending'
> & {
  version: 1
  pending: Omit<PreviousRecommendationPending, 'clientTurnId'> | null
}

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
    const stored = storedConversation(parsed)
    if (!stored) {
      storage.removeItem(key)
      return null
    }
    return withoutVersion(stored)
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
    .map(turn => ({ role: turn.role, text: turn.text }))
  const knownGames = uniqueGames(snapshot.knownGames.filter(isConversationGame)).slice(0, MAX_KNOWN_GAMES)
  const shownBggIds = uniquePositiveIntegers(snapshot.shownBggIds).slice(-MAX_SHOWN_GAMES)
  const pending = isConversationPending(snapshot.pending)
      ? {
        message: snapshot.pending.message,
        excludedBggIds: uniquePositiveIntegers(snapshot.pending.excludedBggIds).slice(-MAX_SHOWN_GAMES),
        focusedBggId: snapshot.pending.focusedBggId,
        clientTurnId: snapshot.pending.clientTurnId,
        responseLocale: validLocale(snapshot.pending.responseLocale) ? snapshot.pending.responseLocale : null,
      }
    : null
  return {
    conversationId: validUuid(snapshot.conversationId) ? snapshot.conversationId : null,
    revision: validRevision(snapshot.revision) ? snapshot.revision : 0,
    responseLocale: validLocale(snapshot.responseLocale) ? snapshot.responseLocale : null,
    profile: isProfile(snapshot.profile)
      ? canonicalRecommendationProfile(snapshot.profile)
      : emptyRecommendationProfile(),
    transcript,
    knownGames,
    shownBggIds,
    selectedBggId: isPositiveInteger(snapshot.selectedBggId) ? snapshot.selectedBggId : null,
    failed: snapshot.failed === true,
    pending,
  }
}

function storedConversation(value: unknown): StoredRecommendationConversation | null {
  if (isStoredConversation(value)) return value
  if (isPreviousConversation(value)) {
    return {
      ...value,
      version: STORAGE_VERSION,
      responseLocale: null,
      pending: value.pending ? { ...value.pending, responseLocale: null } : null,
    }
  }
  if (!isLegacyConversation(value)) return null
  return {
    ...value,
    version: STORAGE_VERSION,
    conversationId: null,
    revision: 0,
    responseLocale: null,
    pending: value.pending ? { ...value.pending, clientTurnId: null, responseLocale: null } : null,
  }
}

function isStoredConversation(value: unknown): value is StoredRecommendationConversation {
  return isRecord(value)
    && value.version === STORAGE_VERSION
    && (value.conversationId === null || validUuid(value.conversationId))
    && validRevision(value.revision)
    && (value.responseLocale === null || validLocale(value.responseLocale))
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
    && (value.selectedBggId === undefined || value.selectedBggId === null || isPositiveInteger(value.selectedBggId))
    && typeof value.failed === 'boolean'
    && (value.pending === null || isConversationPending(value.pending))
}

function isPreviousConversation(value: unknown): value is PreviousRecommendationConversation {
  return isRecord(value)
    && value.version === 2
    && (value.conversationId === null || validUuid(value.conversationId))
    && validRevision(value.revision)
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
    && (value.pending === null || isPreviousPending(value.pending))
}

function isLegacyConversation(value: unknown): value is LegacyRecommendationConversation {
  return isRecord(value)
    && value.version === 1
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
    && (value.pending === null || isLegacyPending(value.pending))
}

function withoutVersion(value: StoredRecommendationConversation): RecommendationConversationSnapshot {
  return {
    conversationId: value.conversationId,
    revision: value.revision,
    responseLocale: value.responseLocale,
    profile: canonicalRecommendationProfile(value.profile),
    transcript: value.transcript.map(turn => ({ ...turn })),
    knownGames: value.knownGames.map(game => ({ ...game })),
    shownBggIds: [...value.shownBggIds],
    selectedBggId: isPositiveInteger(value.selectedBggId) ? value.selectedBggId : null,
    failed: value.failed,
    pending: value.pending ? {
      message: value.pending.message,
      excludedBggIds: [...value.pending.excludedBggIds],
      focusedBggId: value.pending.focusedBggId,
      clientTurnId: value.pending.clientTurnId,
      responseLocale: value.pending.responseLocale,
    } : null,
  }
}

function isEmptySnapshot(snapshot: RecommendationConversationSnapshot) {
  const profileEmpty = !snapshot.profile.playerCount
    && !snapshot.profile.durationMinutes
    && !snapshot.profile.complexity
    && snapshot.profile.type === 'all'
    && snapshot.profile.interaction === 'any'
  return !snapshot.transcript.some(turn => turn.role === 'user')
    && snapshot.knownGames.length === 0
    && snapshot.shownBggIds.length === 0
    && snapshot.selectedBggId === null
    && !snapshot.failed
    && snapshot.pending === null
    && snapshot.conversationId === null
    && snapshot.revision === 0
    && profileEmpty
}

function isProfile(value: unknown): value is RecommendationProfile {
  return isRecord(value)
    && (value.players === undefined || nullableIntegerInRange(value.players, 1, 20))
    && (value.maxMinutes === undefined || nullableIntegerInRange(value.maxMinutes, 0, 1_440))
    && (value.maxMinutes === undefined
      || value.maxMinutes === null
      || value.maxMinutes === 0
      || Number(value.maxMinutes) >= 5)
    && (value.maxWeight === undefined || nullableNumberInRange(value.maxWeight, 0, 5))
    && hasText(value.type)
    && hasText(value.interaction)
    && optionalConstraintRange(value.playerCount, 1, 20, true)
    && optionalConstraintRange(value.durationMinutes, 5, 1_440, true)
    && optionalConstraintRange(value.complexity, 0, 5, false)
}

function optionalConstraintRange(
  value: unknown,
  minimumAllowed: number,
  maximumAllowed: number,
  integers: boolean,
) {
  if (value === undefined || value === null) return true
  if (!isRecord(value)) return false
  const minimum = value.minimum
  const maximum = value.maximum
  const validBound = (bound: unknown) => bound === null
    || typeof bound === 'number'
      && Number.isFinite(bound)
      && (!integers || Number.isSafeInteger(bound))
      && bound >= minimumAllowed
      && bound <= maximumAllowed
  return validBound(minimum)
    && validBound(maximum)
    && (minimum !== null || maximum !== null)
    && (minimum === null || maximum === null || Number(minimum) <= Number(maximum))
    && (value.strength === 'hard' || value.strength === 'soft')
    && typeof value.sourceText === 'string'
    && Number.isSafeInteger(value.confirmedTurn)
    && Number(value.confirmedTurn) >= 0
}

function isConversationTurn(value: unknown): value is RecommendationConversationTurn {
  return isRecord(value)
    && (value.role === 'assistant' || value.role === 'user')
    && hasText(value.text)
}

function isConversationGame(value: unknown): value is RecommendationConversationGame {
  return isRecord(value)
    && isPositiveInteger(value.bggId)
    && hasText(value.name)
    && hasText(value.originalName)
}

function isConversationPending(value: unknown): value is RecommendationConversationPending {
  return isRecord(value)
    && hasText(value.message)
    && Array.isArray(value.excludedBggIds)
    && value.excludedBggIds.length <= MAX_SHOWN_GAMES
    && value.excludedBggIds.every(isPositiveInteger)
    && new Set(value.excludedBggIds).size === value.excludedBggIds.length
    && (value.focusedBggId === null || isPositiveInteger(value.focusedBggId))
    && (value.clientTurnId === null || validUuid(value.clientTurnId))
    && (value.responseLocale === null || validLocale(value.responseLocale))
}

function isPreviousPending(value: unknown): value is PreviousRecommendationPending {
  return isRecord(value)
    && hasText(value.message)
    && Array.isArray(value.excludedBggIds)
    && value.excludedBggIds.length <= MAX_SHOWN_GAMES
    && value.excludedBggIds.every(isPositiveInteger)
    && new Set(value.excludedBggIds).size === value.excludedBggIds.length
    && (value.focusedBggId === null || isPositiveInteger(value.focusedBggId))
    && (value.clientTurnId === null || validUuid(value.clientTurnId))
}

function isLegacyPending(value: unknown): value is Omit<PreviousRecommendationPending, 'clientTurnId'> {
  return isRecord(value)
    && !('clientTurnId' in value)
    && hasText(value.message)
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

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validRevision(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0
}

function validLocale(value: unknown): value is AppLocale {
  return value === 'zh-CN' || value === 'en'
}

function validUuid(value: unknown): value is string {
  if (typeof value !== 'string' || value.length !== 36) return false
  const separators = new Set([8, 13, 18, 23])
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]!
    if (separators.has(index)) {
      if (character !== '-') return false
      continue
    }
    const code = character.codePointAt(0) ?? -1
    const hexadecimal = code >= 48 && code <= 57 || code >= 65 && code <= 70 || code >= 97 && code <= 102
    if (!hexadecimal) return false
  }
  return true
}
