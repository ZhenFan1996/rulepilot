export interface PendingRulebookLesson {
  versionId: string
  editionId?: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
}

export function readPendingRulebookLessons(storage: Storage, username: string) {
  try {
    const parsed = JSON.parse(storage.getItem(storageKey(username)) ?? '[]') as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.slice(0, 5).filter((item): item is PendingRulebookLesson => {
      if (!item || typeof item !== 'object') return false
      const candidate = item as Partial<PendingRulebookLesson>
      if (!boundedId(candidate.versionId) || !integerBetween(candidate.playerCount, 1, 20)) return false
      if (candidate.editionId !== undefined && !boundedId(candidate.editionId)) return false
      return integerBetween(candidate.beginnerCount, 0, candidate.playerCount)
        && integerBetween(candidate.durationMinutes, 2, 180)
    })
  } catch {
    return []
  }
}

export function rememberPendingRulebookLesson(
  storage: Storage,
  username: string,
  pending: PendingRulebookLesson,
) {
  const existing = readPendingRulebookLessons(storage, username)
    .filter((item) => item.versionId !== pending.versionId)
  storage.setItem(storageKey(username), JSON.stringify([pending, ...existing].slice(0, 5)))
}

export function forgetPendingRulebookLesson(storage: Storage, username: string, versionId: string) {
  const remaining = readPendingRulebookLessons(storage, username)
    .filter((item) => item.versionId !== versionId)
  if (remaining.length) storage.setItem(storageKey(username), JSON.stringify(remaining))
  else storage.removeItem(storageKey(username))
}

function storageKey(username: string) {
  return `rulepilot:pending-rulebook-lessons:${encodeURIComponent(username.slice(0, 120))}`
}

function boundedId(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 64
}

function integerBetween(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= minimum && value <= maximum
}
