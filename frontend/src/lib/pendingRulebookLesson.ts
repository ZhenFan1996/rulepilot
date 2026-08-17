export interface PendingRulebookLesson {
  versionId: string
  editionId?: string
  learningGoal?: string
}

export function readPendingRulebookLessons(storage: Storage, username: string) {
  try {
    const parsed = JSON.parse(storage.getItem(storageKey(username)) ?? '[]') as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.flatMap((item): PendingRulebookLesson[] => {
      if (!item || typeof item !== 'object') return []
      const candidate = item as Partial<PendingRulebookLesson>
      if (!boundedId(candidate.versionId)) return []
      if (candidate.editionId !== undefined && !boundedId(candidate.editionId)) return []
      if (candidate.learningGoal !== undefined && typeof candidate.learningGoal !== 'string') return []
      return [normalizePendingLesson({
        versionId: candidate.versionId,
        ...(candidate.editionId ? { editionId: candidate.editionId } : {}),
        ...(candidate.learningGoal ? { learningGoal: candidate.learningGoal } : {}),
      })]
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
  const normalized = normalizePendingLesson(pending)
  const existing = readPendingRulebookLessons(storage, username)
    .filter((item) => item.versionId !== normalized.versionId)
  storage.setItem(storageKey(username), JSON.stringify([normalized, ...existing]))
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
  return typeof value === 'string' && value.trim().length > 0
}

function normalizePendingLesson(pending: PendingRulebookLesson): PendingRulebookLesson {
  const { learningGoal, ...rest } = pending
  const normalizedGoal = learningGoal?.trim()
  return normalizedGoal ? { ...rest, learningGoal: normalizedGoal } : rest
}
