export type IconGlossaryStatus =
  | 'NOT_STARTED'
  | 'GENERATING'
  | 'READY'
  | 'PARTIAL'
  | 'UNAVAILABLE'

export type IconGlossaryWarning =
  | 'INCOMPLETE_PAGE_SCAN'
  | 'UNEXPLAINED_ICONS'
  | 'CONFLICTING_EXPLANATIONS'

export interface RulebookIconOccurrence {
  id: string
  pageNumber: number
  x: number
  y: number
  width: number
  height: number
}

export interface RulebookIconEntry {
  id: string
  name: string
  visualDescription: string
  explanation: string | null
  evidenceText: string | null
  meaningStatus: 'EXPLICIT' | 'UNEXPLAINED'
  representativeOccurrenceId: string
  occurrences: RulebookIconOccurrence[]
}

export interface RulebookIconGlossary {
  status: IconGlossaryStatus
  totalPages: number
  inspectedPages: number
  completePages: number
  icons: RulebookIconEntry[]
  warnings: IconGlossaryWarning[]
}

export function parseRulebookIconGlossary(value: unknown): RulebookIconGlossary {
  if (!isRecord(value)
    || !isStatus(value.status)
    || !isNonNegativeInteger(value.totalPages)
    || !isNonNegativeInteger(value.inspectedPages)
    || !isNonNegativeInteger(value.completePages)
    || !Array.isArray(value.icons)
    || !value.icons.every(isIconEntry)
    || !Array.isArray(value.warnings)
    || !value.warnings.every(isWarning)) {
    throw new Error('invalid rulebook icon glossary response')
  }
  return value as unknown as RulebookIconGlossary
}

function isIconEntry(value: unknown): value is RulebookIconEntry {
  if (!isRecord(value)
    || !isNonBlankString(value.id)
    || !isNonBlankString(value.name)
    || !isNonBlankString(value.visualDescription)
    || !(value.explanation === null || isNonBlankString(value.explanation))
    || !(value.evidenceText === null || isNonBlankString(value.evidenceText))
    || (value.meaningStatus !== 'EXPLICIT' && value.meaningStatus !== 'UNEXPLAINED')
    || !isNonBlankString(value.representativeOccurrenceId)
    || !Array.isArray(value.occurrences)
    || value.occurrences.length === 0
    || !value.occurrences.every(isOccurrence)) {
    return false
  }
  if (!value.occurrences.some((occurrence) => occurrence.id === value.representativeOccurrenceId)) return false
  return value.meaningStatus === 'UNEXPLAINED'
    ? value.explanation === null && value.evidenceText === null
    : value.explanation !== null && value.evidenceText !== null
}

function isOccurrence(value: unknown): value is RulebookIconOccurrence {
  return isRecord(value)
    && isNonBlankString(value.id)
    && isPositiveInteger(value.pageNumber)
    && [value.x, value.y, value.width, value.height].every(isNonNegativeInteger)
    && Number(value.width) > 0
    && Number(value.height) > 0
}

function isStatus(value: unknown): value is IconGlossaryStatus {
  return value === 'NOT_STARTED'
    || value === 'GENERATING'
    || value === 'READY'
    || value === 'PARTIAL'
    || value === 'UNAVAILABLE'
}

function isWarning(value: unknown): value is IconGlossaryWarning {
  return value === 'INCOMPLETE_PAGE_SCAN'
    || value === 'UNEXPLAINED_ICONS'
    || value === 'CONFLICTING_EXPLANATIONS'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 0
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) > 0
}
