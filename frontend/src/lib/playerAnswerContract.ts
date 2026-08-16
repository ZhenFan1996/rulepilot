import type { AppLocale } from '@/lib/locale'

/** The only rule-source fields allowed inside player-visible answer content. */
export interface PlayerRuleCitation {
  heading: string
  excerpt: string
  pageFrom: number
  pageTo: number
}

/** Player-visible answer content; operational identities live in AnswerRulingReference instead. */
export interface PlayerFacingRuleAnswer {
  status: 'ANSWERED' | 'ANSWERED_WITH_WARNING' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'MODEL_TIMEOUT' | 'INVALID_MODEL_OUTPUT' | 'VERSION_CONFLICT'
  shortVerdict: string
  explanation: string
  citations: PlayerRuleCitation[]
  exceptions: string[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  answerBasis?: 'DIRECT_RULE' | 'GROUNDED_APPLICATION' | null
  language: AppLocale
  source: 'CONFIRMED' | 'OFFICIAL' | 'UPLOADED'
  clarification: string | null
  recovery: {
    message: string
    actionLabel: string
    draft: string
  } | null
  warnings: Array<{
    type: 'INDIRECT_CITATION' | 'LOW_CONFIDENCE' | 'REVIEW_UNRESOLVED' | 'REVIEW_UNAVAILABLE'
  }>
  calculations?: Array<{
    expression: string
    result: string
  }>
  situationChecks?: Array<{
    requirement: string
    status: 'CONFIRMED' | 'CONTRADICTED' | 'NOT_PROVIDED'
    playerFact: string
  }>
  walkthroughSteps?: Array<{
    instruction: string
    explanation: string
    orderBasis: 'RULE_ORDER' | 'EXPLANATION_ORDER'
  }>
  decisionBranches?: Array<{
    condition: string
    outcome: string
    basis: 'EXPLICIT_RULE' | 'RULEBOOK_EXAMPLE'
  }>
  exceptionClauses?: Array<{
    condition: string
    effect: string
  }>
  termDefinitions?: Array<{
    term: string
    definition: string
    boundary: string
  }>
  workedExamples?: Array<{
    setup: string
    action: string
    outcome: string
    basis: 'RULEBOOK_EXAMPLE' | 'EVIDENCE_BOUND_ILLUSTRATION'
  }>
  priorityResolutions?: Array<{
    baseRule: string
    competingRule: string
    resolution: string
    basis: 'EXPLICIT_OVERRIDE' | 'IMPOSSIBILITY_PRIORITY' | 'CONFLICT_ONLY_OVERRIDE'
  }>
  timingResolutions?: Array<{
    timingContext: string
    resolutionOrder: string
    orderSource: string
    basis: 'CURRENT_PLAYER_CHOOSES' | 'PRINTED_TOP_TO_BOTTOM' | 'NORMAL_TURN_ORDER'
  }>
  tieResolutions?: Array<{
    tieContext: string
    resolutionSteps: string[]
    finalOutcome: string
    basis: 'SINGLE_TIEBREAKER' | 'ORDERED_TIEBREAKERS' | 'RANK_REWARD_SHIFT' | 'POSITIONAL_PRIORITY'
  }>
  scopeResolutions?: Array<{
    ruleContext: string
    governingCondition: string
    currentSituation: string
    matchStatus: 'MATCHES_SCOPE' | 'OUTSIDE_SCOPE' | 'NEEDS_CONTEXT'
    effect: string
    basis: 'PLAYER_COUNT' | 'ROLE_PRESENCE' | 'GAME_MODE' | 'VARIANT_SELECTION' | 'PLAYER_COUNT_EXCEPTION'
  }>
  conceptComparisons?: Array<{
    leftConcept: string
    leftDefinition: string
    rightConcept: string
    rightDefinition: string
    commonGround: string
    keyDifference: string
    practicalBoundary: string
    basis: 'ACTION_WINDOW' | 'RESOURCE_FUNCTION' | 'STORAGE_STATUS' | 'RULE_SCOPE' | 'DEFINITION_BOUNDARY'
  }>
  ruleOptions?: Array<{
    decisionContext: string
    selectionRule: string
    optionName: string
    availabilityCondition: string
    result: string
    basis: 'SOURCE_SELECTION' | 'TIMING_CATALOG' | 'ALTERNATIVE_ACTION' | 'EXCLUSIVE_CHOICE'
  }>
}

/** UUID-bearing metadata used only by explicit confirm/edit operations. */
export interface AnswerRulingReference {
  citationIds: string[]
  confirmedRulingId: string | null
  confirmedRulingVersion: number | null
}

export function isPlayerFacingRuleAnswer(value: unknown): value is PlayerFacingRuleAnswer {
  if (!isRecord(value)
    || !isAnswerStatus(value.status)
    || !boundedString(value.shortVerdict, 1, 2_000)
    || !boundedString(value.explanation, 0, 12_000)
    || !isConfidence(value.confidence)
    || !(value.language === 'zh-CN' || value.language === 'en')
    || !(value.source === 'CONFIRMED'
      || value.source === 'OFFICIAL' || value.source === 'UPLOADED')
    || !nullableBoundedString(value.clarification, 2_000)
    || !(value.recovery === null || isRecovery(value.recovery))
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
      && value.ruleOptions.every(isRuleOption))
    || !(value.answerBasis === undefined || value.answerBasis === null
      || value.answerBasis === 'DIRECT_RULE' || value.answerBasis === 'GROUNDED_APPLICATION')) return false
  const answer = value as unknown as PlayerFacingRuleAnswer
  return hasValidOutcomeShape(answer)
}

/** Validates and projects an untrusted payload so unknown/internal fields cannot enter UI or browser state. */
export function parsePlayerFacingRuleAnswer(value: unknown): PlayerFacingRuleAnswer | null {
  if (!isPlayerFacingRuleAnswer(value)) return null
  return {
    status: value.status,
    shortVerdict: value.shortVerdict,
    explanation: value.explanation,
    citations: value.citations.map(citation => ({
      heading: citation.heading,
      excerpt: citation.excerpt,
      pageFrom: citation.pageFrom,
      pageTo: citation.pageTo,
    })),
    exceptions: [...value.exceptions],
    confidence: value.confidence,
    answerBasis: value.answerBasis,
    language: value.language,
    source: value.source,
    clarification: value.clarification,
    recovery: value.recovery
      ? {
          message: value.recovery.message,
          actionLabel: value.recovery.actionLabel,
          draft: value.recovery.draft,
        }
      : value.recovery,
    warnings: value.warnings.map(warning => ({ type: warning.type })),
    calculations: value.calculations?.map(calculation => ({
      expression: calculation.expression,
      result: calculation.result,
    })),
    situationChecks: value.situationChecks?.map(check => ({
      requirement: check.requirement,
      status: check.status,
      playerFact: check.playerFact,
    })),
    walkthroughSteps: value.walkthroughSteps?.map(step => ({
      instruction: step.instruction,
      explanation: step.explanation,
      orderBasis: step.orderBasis,
    })),
    decisionBranches: value.decisionBranches?.map(branch => ({
      condition: branch.condition,
      outcome: branch.outcome,
      basis: branch.basis,
    })),
    exceptionClauses: value.exceptionClauses?.map(clause => ({
      condition: clause.condition,
      effect: clause.effect,
    })),
    termDefinitions: value.termDefinitions?.map(definition => ({
      term: definition.term,
      definition: definition.definition,
      boundary: definition.boundary,
    })),
    workedExamples: value.workedExamples?.map(example => ({
      setup: example.setup,
      action: example.action,
      outcome: example.outcome,
      basis: example.basis,
    })),
    priorityResolutions: value.priorityResolutions?.map(resolution => ({
      baseRule: resolution.baseRule,
      competingRule: resolution.competingRule,
      resolution: resolution.resolution,
      basis: resolution.basis,
    })),
    timingResolutions: value.timingResolutions?.map(resolution => ({
      timingContext: resolution.timingContext,
      resolutionOrder: resolution.resolutionOrder,
      orderSource: resolution.orderSource,
      basis: resolution.basis,
    })),
    tieResolutions: value.tieResolutions?.map(resolution => ({
      tieContext: resolution.tieContext,
      resolutionSteps: [...resolution.resolutionSteps],
      finalOutcome: resolution.finalOutcome,
      basis: resolution.basis,
    })),
    scopeResolutions: value.scopeResolutions?.map(resolution => ({
      ruleContext: resolution.ruleContext,
      governingCondition: resolution.governingCondition,
      currentSituation: resolution.currentSituation,
      matchStatus: resolution.matchStatus,
      effect: resolution.effect,
      basis: resolution.basis,
    })),
    conceptComparisons: value.conceptComparisons?.map(comparison => ({
      leftConcept: comparison.leftConcept,
      leftDefinition: comparison.leftDefinition,
      rightConcept: comparison.rightConcept,
      rightDefinition: comparison.rightDefinition,
      commonGround: comparison.commonGround,
      keyDifference: comparison.keyDifference,
      practicalBoundary: comparison.practicalBoundary,
      basis: comparison.basis,
    })),
    ruleOptions: value.ruleOptions?.map(option => ({
      decisionContext: option.decisionContext,
      selectionRule: option.selectionRule,
      optionName: option.optionName,
      availabilityCondition: option.availabilityCondition,
      result: option.result,
      basis: option.basis,
    })),
  }
}

function hasValidOutcomeShape(answer: PlayerFacingRuleAnswer) {
  const publishesConclusion = answer.status === 'ANSWERED' || answer.status === 'ANSWERED_WITH_WARNING'
  const hasStructuredDetails = [
    answer.calculations,
    answer.situationChecks,
    answer.walkthroughSteps,
    answer.decisionBranches,
    answer.exceptionClauses,
    answer.termDefinitions,
    answer.workedExamples,
    answer.priorityResolutions,
    answer.timingResolutions,
    answer.tieResolutions,
    answer.scopeResolutions,
    answer.conceptComparisons,
    answer.ruleOptions,
  ].some(items => items !== undefined && items.length > 0)
  if (publishesConclusion) {
    return answer.citations.length > 0
      && (answer.answerBasis === 'DIRECT_RULE' || answer.answerBasis === 'GROUNDED_APPLICATION')
      && answer.clarification === null
      && answer.recovery === null
      && (answer.status === 'ANSWERED_WITH_WARNING') === (answer.warnings.length > 0)
  }
  return answer.recovery !== null
    && answer.confidence === 'LOW'
    && (answer.answerBasis === undefined || answer.answerBasis === null)
    && answer.explanation === ''
    && answer.exceptions.length === 0
    && answer.warnings.length === 0
    && !hasStructuredDetails
    && (answer.status === 'INSUFFICIENT_EVIDENCE' || answer.citations.length === 0)
    && (answer.status === 'CLARIFICATION_REQUIRED') === Boolean(answer.clarification?.trim())
}

export function isAnswerRulingReference(value: unknown): value is AnswerRulingReference {
  if (!isRecord(value)
    || !Array.isArray(value.citationIds)
    || value.citationIds.length > 20
    || !value.citationIds.every(item => boundedString(item, 1, 200))
    || new Set(value.citationIds).size !== value.citationIds.length
    || !nullableBoundedString(value.confirmedRulingId, 200)
    || !(value.confirmedRulingVersion === null || Number.isSafeInteger(value.confirmedRulingVersion))) return false
  return (value.confirmedRulingId === null) === (value.confirmedRulingVersion === null)
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
    && boundedString(value.heading, 0, 1_000)
    && boundedString(value.excerpt, 0, 4_000)
    && Number.isSafeInteger(value.pageFrom) && Number(value.pageFrom) >= 1
    && Number.isSafeInteger(value.pageTo) && Number(value.pageTo) >= Number(value.pageFrom)
}

function isRecovery(value: unknown) {
  return isRecord(value)
    && boundedString(value.message, 1, 2_000)
    && boundedString(value.actionLabel, 1, 120)
    && boundedString(value.draft, 0, 800)
}

function isWarning(value: unknown) {
  return isRecord(value) && (value.type === 'INDIRECT_CITATION'
    || value.type === 'LOW_CONFIDENCE'
    || value.type === 'REVIEW_UNRESOLVED'
    || value.type === 'REVIEW_UNAVAILABLE')
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
