export interface BackgroundTeachingItem {
  runId: string
  planId: string
  gameTitle: string
  terminalState?: 'COMPLETED' | 'INSUFFICIENT_EVIDENCE' | 'DEGRADED' | 'FAILED'
}

export interface BackgroundTeachingTransition {
  active: BackgroundTeachingItem[]
  finished: BackgroundTeachingItem[]
}

const STORAGE_KEY_PREFIXES = {
  activeTeaching: 'rulepilot:active-teaching-runs',
  completedTeaching: 'rulepilot:completed-teaching-runs',
  dismissedTeachingRuns: 'rulepilot:dismissed-teaching-runs',
  dismissedImports: 'rulepilot:dismissed-official-imports',
  dismissedUploadHandoffs: 'rulepilot:dismissed-upload-teaching-handoffs',
} as const

export type BackgroundWorkStorageKeys = Record<keyof typeof STORAGE_KEY_PREFIXES, string>

export function backgroundWorkStorageKeys(username: string): BackgroundWorkStorageKeys {
  const account = username.trim()
  const suffix = encodeURIComponent(account)
  return Object.fromEntries(Object.entries(STORAGE_KEY_PREFIXES)
    .map(([name, prefix]) => [name, `${prefix}:${suffix}`])) as BackgroundWorkStorageKeys
}

export function clearBackgroundWorkStorage(storage: Storage, username: string) {
  for (const key of Object.values(backgroundWorkStorageKeys(username))) storage.removeItem(key)
  clearLegacyBackgroundWorkStorage(storage)
}

export function clearLegacyBackgroundWorkStorage(storage: Storage) {
  for (const key of Object.values(STORAGE_KEY_PREFIXES)) storage.removeItem(key)
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
    return parsed.filter((item): item is BackgroundTeachingItem => {
      if (!item || typeof item !== 'object') return false
      const candidate = item as Partial<BackgroundTeachingItem>
      return bounded(candidate.runId, 64)
        && bounded(candidate.planId, 64)
        && bounded(candidate.gameTitle, 160)
        && (candidate.terminalState === undefined
          || ['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'].includes(candidate.terminalState))
    })
  } catch {
    return []
  }
}

function bounded(value: unknown, _maxLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0
}
