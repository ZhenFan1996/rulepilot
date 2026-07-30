export interface ShelfCatalogEntry {
  game: { id: string; name: string }
  editions: Array<{ id: string; gameId: string; name: string; language: string; publicationYear: number | null }>
  expansions: Array<{ id: string; gameId: string; name: string }>
  bggMetadata: {
    thumbnailUrl: string
    bggUrl?: string
    minPlayers: number | null
    maxPlayers: number | null
    playingTimeMinutes: number | null
    minimumAge: number | null
  } | null
}

import { playerFacingTitle } from '@/lib/lessonPresentation'

export interface ShelfDocument {
  document: { id: string; gameEditionId: string | null; title: string; officialCoverUrl?: string | null }
  latestVersion: { id: string; status: string }
}

export interface ShelfPlan {
  id: string
  documentVersionId: string
  gameTitle: string
  createdAt: string
}

export interface ShelfItem {
  id: string
  title: string
  coverUrl: string | null
  coverAttributionUrl: string | null
  editionLabel: string | null
  players: { min: number; max: number } | null
  playtimeMinutes: number | null
  minimumAge: number | null
  documentCount: number
  lessonCount: number
  latestPlanId: string | null
  documentStatus: 'READY' | 'READING' | 'NEEDS_ATTENTION'
  expansionCount: number
}

const ready = new Set(['READY'])
const reading = new Set(['UPLOADED', 'EXTRACTING'])

export function buildPersonalShelf(
  catalog: ShelfCatalogEntry[],
  documents: ShelfDocument[],
  plans: ShelfPlan[],
): ShelfItem[] {
  const editionLookup = new Map<string, { entry: ShelfCatalogEntry; edition: ShelfCatalogEntry['editions'][number] }>()
  for (const entry of catalog) {
    for (const edition of entry.editions) editionLookup.set(edition.id, { entry, edition })
  }

  const items = new Map<string, ShelfItem>()
  const seenPlanIds = new Set<string>()
  for (const document of documents) {
    const assignment = document.document.gameEditionId ? editionLookup.get(document.document.gameEditionId) : undefined
    const key = assignment ? `game:${assignment.entry.game.id}` : `document:${document.document.id}`
    const planMatches = plans.filter((plan) => plan.documentVersionId === document.latestVersion.id)
    planMatches.forEach((plan) => seenPlanIds.add(plan.id))
    const existing = items.get(key)
    const title = playerFacingTitle(assignment?.entry.game.name ?? planMatches[0]?.gameTitle ?? document.document.title)
    const metadata = assignment?.entry.bggMetadata ?? null
    const next: ShelfItem = existing ?? {
      id: key,
      title,
      coverUrl: metadata?.thumbnailUrl ?? document.document.officialCoverUrl ?? null,
      coverAttributionUrl: metadata?.bggUrl ?? null,
      editionLabel: assignment ? editionLabel(assignment.edition) : null,
      players: playerLabel(metadata),
      playtimeMinutes: metadata?.playingTimeMinutes ?? null,
      minimumAge: metadata?.minimumAge ?? null,
      documentCount: 0,
      lessonCount: 0,
      latestPlanId: null,
      documentStatus: statusFor(document.latestVersion.status),
      expansionCount: assignment?.entry.expansions.length ?? 0,
    }
    next.documentCount += 1
    next.lessonCount += planMatches.length
    next.documentStatus = strongestStatus(next.documentStatus, statusFor(document.latestVersion.status))
    const latest = latestPlan([...planMatches, ...plans.filter((plan) => plan.id === next.latestPlanId)])
    next.latestPlanId = latest?.id ?? null
    items.set(key, next)
  }

  for (const plan of plans.filter((candidate) => !seenPlanIds.has(candidate.id))) {
    const key = `plan:${plan.id}`
    items.set(key, {
      id: key,
      title: playerFacingTitle(plan.gameTitle),
      coverUrl: null,
      coverAttributionUrl: null,
      editionLabel: null,
      players: null,
      playtimeMinutes: null,
      minimumAge: null,
      documentCount: 0,
      lessonCount: 1,
      latestPlanId: plan.id,
      documentStatus: 'READY',
      expansionCount: 0,
    })
  }

  return [...items.values()].sort((left, right) => {
    if (Boolean(left.latestPlanId) !== Boolean(right.latestPlanId)) return left.latestPlanId ? -1 : 1
    return left.title.localeCompare(right.title, 'zh-CN')
  })
}

function editionLabel(edition: ShelfCatalogEntry['editions'][number]) {
  const year = edition.publicationYear ? ` · ${edition.publicationYear}` : ''
  return `${edition.name} · ${edition.language}${year}`
}

function playerLabel(metadata: ShelfCatalogEntry['bggMetadata']) {
  if (!metadata?.minPlayers || !metadata.maxPlayers) return null
  return { min: metadata.minPlayers, max: metadata.maxPlayers }
}

function statusFor(status: string): ShelfItem['documentStatus'] {
  if (ready.has(status)) return 'READY'
  if (reading.has(status)) return 'READING'
  return 'NEEDS_ATTENTION'
}

function strongestStatus(left: ShelfItem['documentStatus'], right: ShelfItem['documentStatus']) {
  const rank = { READY: 3, READING: 2, NEEDS_ATTENTION: 1 }
  return rank[left] >= rank[right] ? left : right
}

function latestPlan(plans: ShelfPlan[]) {
  return [...plans].sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0]
}
