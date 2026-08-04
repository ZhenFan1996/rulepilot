import type { AnswerTurn, StructuredRuleAnswer } from '@/composables/useLessonAnswers'
import type { AppLocale } from '@/lib/locale'

const STORAGE_PREFIX = 'rulepilot:lesson-answer-thread:v1:'
const HISTORY_LIMIT = 12

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
    return parsed.filter(isAnswerTurn).slice(-HISTORY_LIMIT)
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
    storage.setItem(key, JSON.stringify(turns.filter(isAnswerTurn).slice(-HISTORY_LIMIT)))
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

function isAnswerTurn(value: unknown): value is AnswerTurn {
  if (!isRecord(value)
    || !boundedString(value.question, 1, 800)
    || !isLearningIntent(value.learningIntent)
    || !isStructuredAnswer(value.answer)) return false
  return true
}

function isStructuredAnswer(value: unknown): value is StructuredRuleAnswer {
  if (!isRecord(value)
    || !isAnswerStatus(value.status)
    || !boundedString(value.shortVerdict, 0, 2_000)
    || !boundedString(value.explanation, 0, 12_000)
    || !isConfidence(value.confidence)
    || typeof value.official !== 'boolean'
    || !nullableBoundedString(value.confirmedRulingId, 200)
    || !(value.confirmedRulingVersion === null || Number.isSafeInteger(value.confirmedRulingVersion))
    || !nullableBoundedString(value.clarification, 2_000)
    || !Array.isArray(value.citations) || value.citations.length > 20 || !value.citations.every(isCitation)
    || !Array.isArray(value.exceptions) || value.exceptions.length > 20
    || !value.exceptions.every(item => boundedString(item, 0, 2_000))
    || !Array.isArray(value.warnings) || value.warnings.length > 8 || !value.warnings.every(isWarning)
    || !(value.calculations === undefined || Array.isArray(value.calculations)
      && value.calculations.length <= 3 && value.calculations.every(isCalculation))
    || !(value.situationChecks === undefined || Array.isArray(value.situationChecks)
      && value.situationChecks.length <= 6 && value.situationChecks.every(isSituationCheck))
    || !(value.walkthroughSteps === undefined || Array.isArray(value.walkthroughSteps)
      && value.walkthroughSteps.length <= 6 && value.walkthroughSteps.every(isWalkthroughStep))
    || !(value.decisionBranches === undefined || Array.isArray(value.decisionBranches)
      && value.decisionBranches.length <= 6 && value.decisionBranches.every(isDecisionBranch))
    || !(value.exceptionClauses === undefined || Array.isArray(value.exceptionClauses)
      && value.exceptionClauses.length <= 6 && value.exceptionClauses.every(isExceptionClause))
    || !(value.termDefinitions === undefined || Array.isArray(value.termDefinitions)
      && value.termDefinitions.length <= 4 && value.termDefinitions.every(isTermDefinition))
    || !(value.workedExamples === undefined || Array.isArray(value.workedExamples)
      && value.workedExamples.length <= 3 && value.workedExamples.every(isWorkedExample))
    || !(value.priorityResolutions === undefined || Array.isArray(value.priorityResolutions)
      && value.priorityResolutions.length <= 3 && value.priorityResolutions.every(isPriorityResolution))
    || !(value.timingResolutions === undefined || Array.isArray(value.timingResolutions)
      && value.timingResolutions.length <= 3 && value.timingResolutions.every(isTimingResolution))
    || !(value.tieResolutions === undefined || Array.isArray(value.tieResolutions)
      && value.tieResolutions.length <= 3 && value.tieResolutions.every(isTieResolution))
    || !(value.scopeResolutions === undefined || Array.isArray(value.scopeResolutions)
      && value.scopeResolutions.length <= 3 && value.scopeResolutions.every(isScopeResolution))
    || !(value.conceptComparisons === undefined || Array.isArray(value.conceptComparisons)
      && value.conceptComparisons.length <= 3 && value.conceptComparisons.every(isConceptComparison))
    || !(value.ruleOptions === undefined || Array.isArray(value.ruleOptions)
      && value.ruleOptions.length >= 2 && value.ruleOptions.length <= 8
      && value.ruleOptions.every(isRuleOption))) return false
  return value.answerBasis === undefined || value.answerBasis === null
    || value.answerBasis === 'DIRECT_RULE' || value.answerBasis === 'GROUNDED_APPLICATION'
}

function isCalculation(value: unknown) {
  return isRecord(value)
    && boundedString(value.expression, 1, 160)
    && boundedString(value.result, 1, 80)
}

function isSituationCheck(value: unknown) {
  if (!isRecord(value)
    || !boundedString(value.requirement, 1, 500)
    || !boundedString(value.playerFact, 0, 800)
    || !(value.status === 'CONFIRMED' || value.status === 'CONTRADICTED' || value.status === 'NOT_PROVIDED')) return false
  return value.status === 'NOT_PROVIDED' ? value.playerFact.length === 0 : value.playerFact.trim().length > 0
}

function isWalkthroughStep(value: unknown) {
  return isRecord(value)
    && boundedString(value.instruction, 1, 240)
    && boundedString(value.explanation, 1, 500)
    && (value.orderBasis === 'RULE_ORDER' || value.orderBasis === 'EXPLANATION_ORDER')
}

function isDecisionBranch(value: unknown) {
  return isRecord(value)
    && boundedString(value.condition, 1, 300)
    && boundedString(value.outcome, 1, 500)
    && (value.basis === 'EXPLICIT_RULE' || value.basis === 'RULEBOOK_EXAMPLE')
}

function isExceptionClause(value: unknown) {
  return isRecord(value)
    && boundedString(value.condition, 1, 300)
    && boundedString(value.effect, 1, 500)
}

function isTermDefinition(value: unknown) {
  return isRecord(value)
    && boundedString(value.term, 1, 120)
    && boundedString(value.definition, 1, 600)
    && boundedString(value.boundary, 0, 400)
}

function isWorkedExample(value: unknown) {
  return isRecord(value)
    && boundedString(value.setup, 1, 500)
    && boundedString(value.action, 1, 700)
    && boundedString(value.outcome, 1, 500)
    && (value.basis === 'RULEBOOK_EXAMPLE' || value.basis === 'EVIDENCE_BOUND_ILLUSTRATION')
}

function isPriorityResolution(value: unknown) {
  return isRecord(value)
    && boundedString(value.baseRule, 1, 500)
    && boundedString(value.competingRule, 1, 500)
    && boundedString(value.resolution, 1, 600)
    && (value.basis === 'EXPLICIT_OVERRIDE'
      || value.basis === 'IMPOSSIBILITY_PRIORITY'
      || value.basis === 'CONFLICT_ONLY_OVERRIDE')
}

function isTimingResolution(value: unknown) {
  return isRecord(value)
    && boundedString(value.timingContext, 1, 500)
    && boundedString(value.resolutionOrder, 1, 700)
    && boundedString(value.orderSource, 1, 400)
    && (value.basis === 'CURRENT_PLAYER_CHOOSES'
      || value.basis === 'PRINTED_TOP_TO_BOTTOM'
      || value.basis === 'NORMAL_TURN_ORDER')
}

function isTieResolution(value: unknown) {
  return isRecord(value)
    && boundedString(value.tieContext, 1, 500)
    && Array.isArray(value.resolutionSteps)
    && value.resolutionSteps.length >= 1
    && value.resolutionSteps.length <= 6
    && value.resolutionSteps.every(step => boundedString(step, 1, 500) && !step.includes('\n') && !step.includes('\r'))
    && boundedString(value.finalOutcome, 1, 500)
    && (value.basis === 'SINGLE_TIEBREAKER'
      || value.basis === 'ORDERED_TIEBREAKERS'
      || value.basis === 'RANK_REWARD_SHIFT'
      || value.basis === 'POSITIONAL_PRIORITY')
}

function isScopeResolution(value: unknown) {
  return isRecord(value)
    && boundedString(value.ruleContext, 1, 500)
    && boundedString(value.governingCondition, 1, 500)
    && boundedString(value.currentSituation, 1, 300)
    && (value.matchStatus === 'MATCHES_SCOPE' || value.matchStatus === 'OUTSIDE_SCOPE' || value.matchStatus === 'NEEDS_CONTEXT')
    && boundedString(value.effect, 1, 600)
    && (value.basis === 'PLAYER_COUNT' || value.basis === 'ROLE_PRESENCE' || value.basis === 'GAME_MODE'
      || value.basis === 'VARIANT_SELECTION' || value.basis === 'PLAYER_COUNT_EXCEPTION')
}

function isConceptComparison(value: unknown) {
  return isRecord(value)
    && boundedString(value.leftConcept, 1, 120)
    && boundedString(value.leftDefinition, 1, 600)
    && boundedString(value.rightConcept, 1, 120)
    && boundedString(value.rightDefinition, 1, 600)
    && boundedString(value.commonGround, 1, 500)
    && boundedString(value.keyDifference, 1, 700)
    && boundedString(value.practicalBoundary, 1, 600)
    && (value.basis === 'ACTION_WINDOW' || value.basis === 'RESOURCE_FUNCTION'
      || value.basis === 'STORAGE_STATUS' || value.basis === 'RULE_SCOPE'
      || value.basis === 'DEFINITION_BOUNDARY')
}

function isRuleOption(value: unknown) {
  return isRecord(value)
    && boundedString(value.decisionContext, 1, 240)
    && boundedString(value.selectionRule, 1, 400)
    && boundedString(value.optionName, 1, 160)
    && boundedString(value.availabilityCondition, 1, 500)
    && boundedString(value.result, 1, 700)
    && (value.basis === 'SOURCE_SELECTION' || value.basis === 'TIMING_CATALOG'
      || value.basis === 'ALTERNATIVE_ACTION' || value.basis === 'EXCLUSIVE_CHOICE')
}

function isCitation(value: unknown) {
  return isRecord(value)
    && boundedString(value.chunkId, 1, 200)
    && boundedString(value.sectionType, 0, 200)
    && boundedString(value.heading, 0, 1_000)
    && boundedString(value.excerpt, 0, 4_000)
    && Number.isSafeInteger(value.pageFrom) && Number(value.pageFrom) >= 1
    && Number.isSafeInteger(value.pageTo) && Number(value.pageTo) >= Number(value.pageFrom)
}

function isWarning(value: unknown) {
  return isRecord(value) && (value.type === 'INDIRECT_CITATION'
    || value.type === 'LOW_CONFIDENCE'
    || value.type === 'REVIEW_UNRESOLVED'
    || value.type === 'REVIEW_UNAVAILABLE')
}

function isLearningIntent(value: unknown) {
  return value === null || value === 'SIMPLIFY' || value === 'EXAMPLE'
    || value === 'DEFINE' || value === 'WHY' || value === 'EXCEPTIONS' || value === 'SOURCE' || value === 'VERIFY'
}

function isAnswerStatus(value: unknown) {
  return value === 'ANSWERED' || value === 'ANSWERED_WITH_WARNING'
    || value === 'CLARIFICATION_REQUIRED' || value === 'INSUFFICIENT_EVIDENCE'
    || value === 'MODEL_TIMEOUT' || value === 'INVALID_MODEL_OUTPUT' || value === 'VERSION_CONFLICT'
}

function isConfidence(value: unknown) {
  return value === 'HIGH' || value === 'MEDIUM' || value === 'LOW'
}

function nullableBoundedString(value: unknown, max: number) {
  return value === null || boundedString(value, 0, max)
}

function boundedString(value: unknown, min: number, max: number): value is string {
  return typeof value === 'string' && value.trim().length >= min && value.length <= max
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
