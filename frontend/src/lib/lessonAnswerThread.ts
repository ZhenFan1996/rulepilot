import type { AnswerTurn } from '@/composables/useLessonAnswers'
import type { AppLocale } from '@/lib/locale'
import {
  isAnswerRulingReference,
  parsePlayerFacingRuleAnswer,
} from '@/lib/playerAnswerContract'

const STORAGE_PREFIX = 'rulepilot:lesson-answer-thread:v2:'

export interface LessonAnswerThreadScope {
  username: string
  planId: string
  documentVersionId: string
  locale: AppLocale
}

export function readLessonAnswerThread(storage: Storage, scope: LessonAnswerThreadScope) {
  const key = storageKey(scope)
  if (!key) return []
  try {
    const parsed = JSON.parse(storage.getItem(key) ?? '[]') as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .map(parseAnswerTurn)
      .filter((turn): turn is AnswerTurn => turn !== null)
  } catch {
    return []
  }
}

export function rememberLessonAnswerThread(
  storage: Storage,
  scope: LessonAnswerThreadScope,
  turns: AnswerTurn[],
) {
  const key = storageKey(scope)
  if (!key) return
  try {
    const safeTurns = turns
      .map(parseAnswerTurn)
      .filter((turn): turn is AnswerTurn => turn !== null)
    storage.setItem(key, JSON.stringify(safeTurns))
  } catch {
    // The visible thread remains usable when browser-session storage is unavailable.
  }
}

export function forgetLessonAnswerThread(storage: Storage, scope: LessonAnswerThreadScope) {
  const key = storageKey(scope)
  if (!key) return
  try {
    storage.removeItem(key)
  } catch {
    // Clearing the visible thread does not depend on browser storage.
  }
}

function storageKey(scope: LessonAnswerThreadScope) {
  const username = scope.username.trim().toLowerCase()
  const planId = scope.planId.trim()
  const documentVersionId = scope.documentVersionId.trim()
  if (!username || !planId || !documentVersionId) return null
  return `${STORAGE_PREFIX}${encodeURIComponent(username.slice(0, 120))}:${encodeURIComponent(planId)}:${encodeURIComponent(documentVersionId)}:${scope.locale}`
}

function parseAnswerTurn(value: unknown): AnswerTurn | null {
  if (!isRecord(value)
    || !hasText(value.question)
    || !isLearningIntent(value.learningIntent)
    || !(value.rulingReference === undefined || value.rulingReference === null
      || isAnswerRulingReference(value.rulingReference))) return null
  const answer = parsePlayerFacingRuleAnswer(value.answer)
  if (!answer) return null
  return {
    question: value.question,
    answer,
    learningIntent: value.learningIntent,
    rulingReference: value.rulingReference
      ? {
          citationIds: [...value.rulingReference.citationIds],
          confirmedRulingId: value.rulingReference.confirmedRulingId,
          confirmedRulingVersion: value.rulingReference.confirmedRulingVersion,
        }
      : value.rulingReference,
  }
}

function isLearningIntent(value: unknown) {
  return value === null || value === 'SIMPLIFY' || value === 'EXAMPLE'
    || value === 'DEFINE' || value === 'WHY' || value === 'EXCEPTIONS' || value === 'SOURCE' || value === 'VERIFY'
}

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
