import type { RecommendationProfile } from '@/components/gameRecommendationTypes'
import type { AppLocale } from '@/lib/locale'
import { canonicalRecommendationProfile } from '@/lib/recommendationProfile'

const STORAGE_PREFIX = 'rulepilot:recommendation-conversation:v2:'

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

export function readRecommendationConversation(
  storage: Storage,
  username: string,
): RecommendationConversationSnapshot | null {
  const key = storageKey(username)
  if (!key) return null
  try {
    const raw = storage.getItem(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as unknown
    if (!isStoredConversation(parsed)) {
      storage.removeItem(key)
      return null
    }
    return copiedSnapshot(parsed)
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
    const canonical = canonicalSnapshot(snapshot)
    if (isEmptySnapshot(canonical)) {
      storage.removeItem(key)
      return
    }
    storage.setItem(key, JSON.stringify(canonical))
  } catch {
    // Browser storage writes are atomic: quota or security failures keep the previous v2 snapshot intact.
    // The server conversation remains authoritative whenever it is reachable.
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
  const owner = username.normalize('NFKC').trim().toLowerCase()
  if (!owner) return null
  return `${STORAGE_PREFIX}${encodeURIComponent(owner)}`
}

function canonicalSnapshot(snapshot: RecommendationConversationSnapshot): RecommendationConversationSnapshot {
  if ((snapshot.conversationId !== null && !validUuid(snapshot.conversationId))
    || !validRevision(snapshot.revision)
    || (snapshot.responseLocale !== null && !validLocale(snapshot.responseLocale))
    || !isProfile(snapshot.profile)
    || !Array.isArray(snapshot.transcript)
    || !snapshot.transcript.every(isConversationTurn)
    || !Array.isArray(snapshot.knownGames)
    || !snapshot.knownGames.every(isConversationGame)
    || !Array.isArray(snapshot.shownBggIds)
    || (snapshot.selectedBggId !== null && !isPositiveInteger(snapshot.selectedBggId))
    || typeof snapshot.failed !== 'boolean'
    || (snapshot.pending !== null && !isConversationPending(snapshot.pending))) {
    throw new Error('recommendation conversation snapshot is invalid')
  }
  const transcript = snapshot.transcript
    .map(turn => ({ role: turn.role, text: turn.text }))
  const knownGames = uniqueGames(snapshot.knownGames)
  const shownBggIds = uniquePositiveIntegers(snapshot.shownBggIds)
  const pending = isConversationPending(snapshot.pending)
      ? {
        message: snapshot.pending.message,
        excludedBggIds: uniquePositiveIntegers(snapshot.pending.excludedBggIds),
        focusedBggId: snapshot.pending.focusedBggId,
        clientTurnId: snapshot.pending.clientTurnId,
        responseLocale: snapshot.pending.responseLocale,
      }
    : null
  const canonical: RecommendationConversationSnapshot = {
    conversationId: snapshot.conversationId,
    revision: snapshot.revision,
    responseLocale: snapshot.responseLocale,
    profile: canonicalRecommendationProfile(snapshot.profile),
    transcript,
    knownGames,
    shownBggIds,
    selectedBggId: snapshot.selectedBggId,
    failed: snapshot.failed,
    pending,
  }
  if (!isStoredConversation(canonical)) {
    throw new Error('recommendation conversation snapshot is invalid')
  }
  return canonical
}

function isStoredConversation(value: unknown): value is RecommendationConversationSnapshot {
  return isRecord(value)
    && (value.conversationId === null || validUuid(value.conversationId))
    && validRevision(value.revision)
    && (value.responseLocale === null || validLocale(value.responseLocale))
    && isProfile(value.profile)
    && Array.isArray(value.transcript)
    && value.transcript.every(isConversationTurn)
    && Array.isArray(value.knownGames)
    && value.knownGames.every(isConversationGame)
    && hasUniqueGameIds(value.knownGames)
    && Array.isArray(value.shownBggIds)
    && value.shownBggIds.every(isPositiveInteger)
    && new Set(value.shownBggIds).size === value.shownBggIds.length
    && (value.selectedBggId === null || isPositiveInteger(value.selectedBggId))
    && typeof value.failed === 'boolean'
    && (value.pending === null || isConversationPending(value.pending))
}

function copiedSnapshot(value: RecommendationConversationSnapshot): RecommendationConversationSnapshot {
  return {
    conversationId: value.conversationId,
    revision: value.revision,
    responseLocale: value.responseLocale,
    profile: canonicalRecommendationProfile(value.profile),
    transcript: value.transcript.map(turn => ({ ...turn })),
    knownGames: value.knownGames.map(game => ({ ...game })),
    shownBggIds: [...value.shownBggIds],
    selectedBggId: value.selectedBggId,
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
    && hasText(value.type)
    && hasText(value.interaction)
    && constraintRange(value.playerCount, 1, 20, true)
    && constraintRange(value.durationMinutes, 5, 1_440, true)
    && constraintRange(value.complexity, 0, 5, false)
}

function constraintRange(
  value: unknown,
  minimumAllowed: number,
  maximumAllowed: number,
  integers: boolean,
) {
  if (value === null) return true
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
    && value.excludedBggIds.every(isPositiveInteger)
    && new Set(value.excludedBggIds).size === value.excludedBggIds.length
    && (value.focusedBggId === null || isPositiveInteger(value.focusedBggId))
    && (value.clientTurnId === null || validUuid(value.clientTurnId))
    && (value.responseLocale === null || validLocale(value.responseLocale))
}

function uniqueGames(games: RecommendationConversationGame[]) {
  const seen = new Set<number>()
  return games.filter(game => {
    if (seen.has(game.bggId)) return false
    seen.add(game.bggId)
    return true
  })
}

function hasUniqueGameIds(games: RecommendationConversationGame[]) {
  return new Set(games.map(game => game.bggId)).size === games.length
}

function uniquePositiveIntegers(values: number[]) {
  if (!values.every(isPositiveInteger)) {
    throw new Error('recommendation conversation identity is invalid')
  }
  return [...new Set(values)]
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0
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
