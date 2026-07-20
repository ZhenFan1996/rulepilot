export interface BackgroundTeachingItem {
  runId: string
  planId: string
  gameTitle: string
}

export interface BackgroundTeachingTransition {
  active: BackgroundTeachingItem[]
  finished: BackgroundTeachingItem[]
}

export function reconcileBackgroundTeaching(
  previous: BackgroundTeachingItem[],
  active: BackgroundTeachingItem[],
): BackgroundTeachingTransition {
  const activePlanIds = new Set(active.map((item) => item.planId))
  return {
    active,
    finished: previous.filter((item) => !activePlanIds.has(item.planId)),
  }
}

export function parseBackgroundTeachingItems(value: string | null) {
  if (!value) return []
  try {
    const parsed = JSON.parse(value) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.slice(0, 20).filter((item): item is BackgroundTeachingItem => {
      if (!item || typeof item !== 'object') return false
      const candidate = item as Partial<BackgroundTeachingItem>
      return bounded(candidate.runId, 64)
        && bounded(candidate.planId, 64)
        && bounded(candidate.gameTitle, 160)
    })
  } catch {
    return []
  }
}

function bounded(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength
}
