import type {
  RulebookDiscoveryCompletion,
  RulebookDiscoveryProvider,
  RulebookDiscoveryProviderProgress,
  RulebookDiscoveryProviderState,
  RulebookDiscoverySummary,
} from '@/components/documents/types'

const COMPLETIONS: RulebookDiscoveryCompletion[] = ['COMPLETE', 'PARTIAL', 'TIMED_OUT', 'FAILED']
const PROVIDERS: RulebookDiscoveryProvider[] = ['CATALOG', 'SOURCE_INSPECTION', 'WEB_SEARCH']
const PROVIDER_STATES: RulebookDiscoveryProviderState[] = [
  'FINISHED',
  'TIMED_OUT',
  'FAILED',
  'SKIPPED',
  'UNAVAILABLE',
]

export function monotonicElapsedSeconds(startedAt: number, now = performance.now()) {
  if (!Number.isFinite(startedAt) || !Number.isFinite(now)) return 0
  return Math.max(0, Math.floor((now - startedAt) / 1_000))
}

export function normalizeRulebookDiscoverySummary(value: unknown): RulebookDiscoverySummary | null {
  if (!isRecord(value)
    || !COMPLETIONS.includes(value.completion as RulebookDiscoveryCompletion)
    || !isNonNegativeNumber(value.elapsedMs)
    || !isPositiveNumber(value.totalBudgetMs)
    || !Array.isArray(value.providers)) return null

  const providers = value.providers
    .map(normalizeProviderProgress)
    .filter((provider): provider is RulebookDiscoveryProviderProgress => provider !== null)
  if (providers.length !== PROVIDERS.length
    || providers.length !== value.providers.length
    || new Set(providers.map(provider => provider.provider)).size !== PROVIDERS.length) return null

  return {
    completion: value.completion as RulebookDiscoveryCompletion,
    elapsedMs: value.elapsedMs,
    totalBudgetMs: value.totalBudgetMs,
    providers,
  }
}

function normalizeProviderProgress(value: unknown): RulebookDiscoveryProviderProgress | null {
  if (!isRecord(value)
    || !PROVIDERS.includes(value.provider as RulebookDiscoveryProvider)
    || !PROVIDER_STATES.includes(value.state as RulebookDiscoveryProviderState)
    || !isNonNegativeNumber(value.elapsedMs)) return null
  return {
    provider: value.provider as RulebookDiscoveryProvider,
    state: value.state as RulebookDiscoveryProviderState,
    elapsedMs: value.elapsedMs,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNonNegativeNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isPositiveNumber(value: unknown): value is number {
  return isNonNegativeNumber(value) && value > 0
}
