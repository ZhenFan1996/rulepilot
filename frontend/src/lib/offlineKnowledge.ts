export interface OfflineCitation {
  chunkId: string
  sectionType: string
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

export interface OfflineAnswer {
  status: 'ANSWERED'
  shortVerdict: string
  explanation: string
  citations: OfflineCitation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  official: boolean
  confirmedRulingId: string | null
  confirmedRulingVersion: number | null
  clarification: null
}

export interface OfflineRuling {
  id: string
  shortVerdict: string
  explanation: string
  citations: OfflineCitation[]
  exceptions: string[]
  confidence: OfflineAnswer['confidence']
  status: 'CONFIRMED'
  version: number
}

export interface OfflineKnowledgeEntry {
  question: string
  sectionTitle: string
  cachedAt: string
  answer: OfflineAnswer
  ruling: OfflineRuling | null
}

const VERSION = 1
const MAX_ENTRIES = 12

function storageKey(planId: string) {
  return /^[a-zA-Z0-9-]{1,80}$/.test(planId) ? `rulepilot:offline-knowledge:${planId}` : ''
}

function isString(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max
}

function isCitation(value: unknown): value is OfflineCitation {
  if (!value || typeof value !== 'object') return false
  const citation = value as Record<string, unknown>
  return isString(citation.chunkId, 80)
    && isString(citation.sectionType, 80)
    && isString(citation.heading, 500)
    && isString(citation.excerpt, 10_000)
    && Number.isInteger(citation.pageFrom)
    && Number.isInteger(citation.pageTo)
    && Number(citation.pageFrom) > 0
    && Number(citation.pageTo) >= Number(citation.pageFrom)
}

function isAnswer(value: unknown): value is OfflineAnswer {
  if (!value || typeof value !== 'object') return false
  const answer = value as Record<string, unknown>
  return answer.status === 'ANSWERED'
    && isString(answer.shortVerdict, 2_000)
    && isString(answer.explanation, 20_000)
    && Array.isArray(answer.citations)
    && answer.citations.length > 0
    && answer.citations.length <= 20
    && answer.citations.every(isCitation)
    && Array.isArray(answer.exceptions)
    && answer.exceptions.length <= 20
    && answer.exceptions.every((item) => isString(item, 2_000))
    && ['HIGH', 'MEDIUM', 'LOW'].includes(String(answer.confidence))
    && typeof answer.official === 'boolean'
    && (answer.confirmedRulingId === null || isString(answer.confirmedRulingId, 80))
    && (answer.confirmedRulingVersion === null || Number.isInteger(answer.confirmedRulingVersion))
    && answer.clarification === null
}

function isRuling(value: unknown): value is OfflineRuling {
  if (!value || typeof value !== 'object') return false
  const ruling = value as Record<string, unknown>
  return isString(ruling.id, 80)
    && isString(ruling.shortVerdict, 2_000)
    && isString(ruling.explanation, 20_000)
    && Array.isArray(ruling.citations)
    && ruling.citations.length > 0
    && ruling.citations.length <= 20
    && ruling.citations.every(isCitation)
    && Array.isArray(ruling.exceptions)
    && ruling.exceptions.length <= 20
    && ruling.exceptions.every((item) => isString(item, 2_000))
    && ['HIGH', 'MEDIUM', 'LOW'].includes(String(ruling.confidence))
    && ruling.status === 'CONFIRMED'
    && Number.isInteger(ruling.version)
    && Number(ruling.version) > 0
}

function isEntry(value: unknown): value is OfflineKnowledgeEntry {
  if (!value || typeof value !== 'object') return false
  const entry = value as Record<string, unknown>
  return isString(entry.question, 800)
    && isString(entry.sectionTitle, 500)
    && isString(entry.cachedAt, 40)
    && !Number.isNaN(Date.parse(entry.cachedAt))
    && isAnswer(entry.answer)
    && (entry.ruling === null || isRuling(entry.ruling))
}

export function loadOfflineKnowledge(planId: string): OfflineKnowledgeEntry[] {
  const key = storageKey(planId)
  if (!key) return []
  try {
    const parsed = JSON.parse(localStorage.getItem(key) ?? 'null') as unknown
    if (!parsed || typeof parsed !== 'object') return []
    const record = parsed as { version?: unknown; entries?: unknown }
    if (record.version !== VERSION || !Array.isArray(record.entries)) return []
    return record.entries.filter(isEntry).slice(0, MAX_ENTRIES)
  } catch {
    return []
  }
}

function save(planId: string, entries: OfflineKnowledgeEntry[]) {
  const key = storageKey(planId)
  if (!key) return
  try {
    localStorage.setItem(key, JSON.stringify({ version: VERSION, entries: entries.slice(0, MAX_ENTRIES) }))
  } catch {
    // Offline storage may be unavailable or full; online product behavior must continue.
  }
}

export function cacheOfflineAnswer(
  planId: string,
  question: string,
  sectionTitle: string,
  answer: unknown,
) {
  const normalized = question.trim()
  if (!isString(normalized, 800) || !isString(sectionTitle, 500) || !isAnswer(answer)) return
  const entries = loadOfflineKnowledge(planId).filter((entry) => entry.question !== normalized)
  save(planId, [{ question: normalized, sectionTitle, cachedAt: new Date().toISOString(), answer, ruling: null }, ...entries])
}

export function cacheOfflineRuling(
  planId: string,
  question: string,
  sectionTitle: string,
  ruling: unknown,
) {
  const normalized = question.trim()
  if (!isString(normalized, 800) || !isString(sectionTitle, 500) || !isRuling(ruling)) return
  const entries = loadOfflineKnowledge(planId)
  const existing = entries.find((entry) => entry.question === normalized)
  const answer: OfflineAnswer = existing?.answer ?? {
    status: 'ANSWERED',
    shortVerdict: ruling.shortVerdict,
    explanation: ruling.explanation,
    citations: ruling.citations,
    exceptions: ruling.exceptions,
    confidence: ruling.confidence,
    official: false,
    confirmedRulingId: ruling.id,
    confirmedRulingVersion: ruling.version,
    clarification: null,
  }
  const updated = { question: normalized, sectionTitle, cachedAt: new Date().toISOString(), answer, ruling }
  save(planId, [updated, ...entries.filter((entry) => entry.question !== normalized)])
}
