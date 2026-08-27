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

/** Validates the complete player-facing envelope before projecting operational fields away. */
export function parsePlayerFacingRuleAnswer(value: unknown): PlayerFacingRuleAnswer | null {
  if (!isRecord(value)
    || !isAnswerStatus(value.status)
    || !hasText(value.shortVerdict)
    || !isString(value.explanation)
    || !isConfidence(value.confidence)
    || !(value.language === 'zh-CN' || value.language === 'en')
    || !(value.source === 'CONFIRMED' || value.source === 'OFFICIAL' || value.source === 'UPLOADED')
    || !requiredArrayOf(value.citations, isCitation)
    || !requiredArrayOf(value.exceptions, isString)
    || !requiredArrayOf(value.warnings, isWarning)
    || !(value.answerBasis === undefined || value.answerBasis === null
      || value.answerBasis === 'DIRECT_RULE' || value.answerBasis === 'GROUNDED_APPLICATION')
    || !(isString(value.clarification) || value.clarification === null)
    || !(value.recovery === null || isRecovery(value.recovery))
    || !optionalArrayOf(value.calculations, isCalculation)
    || !optionalArrayOf(value.situationChecks, isSituationCheck)
    || !optionalArrayOf(value.walkthroughSteps, isWalkthroughStep)
    || !optionalArrayOf(value.decisionBranches, isDecisionBranch)
    || !optionalArrayOf(value.exceptionClauses, isExceptionClause)
    || !optionalArrayOf(value.termDefinitions, isTermDefinition)
    || !optionalArrayOf(value.workedExamples, isWorkedExample)
    || !optionalArrayOf(value.priorityResolutions, isPriorityResolution)
    || !optionalArrayOf(value.timingResolutions, isTimingResolution)
    || !optionalArrayOf(value.tieResolutions, isTieResolution)
    || !optionalArrayOf(value.scopeResolutions, isScopeResolution)
    || !optionalArrayOf(value.conceptComparisons, isConceptComparison)
    || !optionalArrayOf(value.ruleOptions, isRuleOption)) return null

  const answerBasis = value.answerBasis === 'DIRECT_RULE' || value.answerBasis === 'GROUNDED_APPLICATION'
    || value.answerBasis === null
    ? value.answerBasis
    : undefined
  const answer = {
    ...value,
    answerBasis,
    clarification: value.clarification,
    recovery: value.recovery,
    citations: value.citations,
    exceptions: value.exceptions,
    warnings: value.warnings,
    calculations: optionalItems(value.calculations),
    situationChecks: optionalItems(value.situationChecks),
    walkthroughSteps: optionalItems(value.walkthroughSteps),
    decisionBranches: optionalItems(value.decisionBranches),
    exceptionClauses: optionalItems(value.exceptionClauses),
    termDefinitions: optionalItems(value.termDefinitions),
    workedExamples: optionalItems(value.workedExamples),
    priorityResolutions: optionalItems(value.priorityResolutions),
    timingResolutions: optionalItems(value.timingResolutions),
    tieResolutions: optionalItems(value.tieResolutions),
    scopeResolutions: optionalItems(value.scopeResolutions),
    conceptComparisons: optionalItems(value.conceptComparisons),
    ruleOptions: optionalItems(value.ruleOptions),
  } as unknown as PlayerFacingRuleAnswer
  if (!hasValidOutcomeShape(answer)) return null

  return {
    status: answer.status,
    shortVerdict: answer.shortVerdict,
    explanation: answer.explanation,
    citations: answer.citations.map(citation => ({
      heading: citation.heading,
      excerpt: citation.excerpt,
      pageFrom: citation.pageFrom,
      pageTo: citation.pageTo,
    })),
    exceptions: [...answer.exceptions],
    confidence: answer.confidence,
    answerBasis: answer.answerBasis,
    language: answer.language,
    source: answer.source,
    clarification: answer.clarification,
    recovery: answer.recovery
      ? {
          message: answer.recovery.message,
          actionLabel: answer.recovery.actionLabel,
          draft: answer.recovery.draft,
        }
      : answer.recovery,
    warnings: answer.warnings.map(warning => ({ type: warning.type })),
    calculations: answer.calculations?.map(calculation => ({
      expression: calculation.expression,
      result: calculation.result,
    })),
    situationChecks: answer.situationChecks?.map(check => ({
      requirement: check.requirement,
      status: check.status,
      playerFact: check.playerFact,
    })),
    walkthroughSteps: answer.walkthroughSteps?.map(step => ({
      instruction: step.instruction,
      explanation: step.explanation,
      orderBasis: step.orderBasis,
    })),
    decisionBranches: answer.decisionBranches?.map(branch => ({
      condition: branch.condition,
      outcome: branch.outcome,
      basis: branch.basis,
    })),
    exceptionClauses: answer.exceptionClauses?.map(clause => ({
      condition: clause.condition,
      effect: clause.effect,
    })),
    termDefinitions: answer.termDefinitions?.map(definition => ({
      term: definition.term,
      definition: definition.definition,
      boundary: definition.boundary,
    })),
    workedExamples: answer.workedExamples?.map(example => ({
      setup: example.setup,
      action: example.action,
      outcome: example.outcome,
      basis: example.basis,
    })),
    priorityResolutions: answer.priorityResolutions?.map(resolution => ({
      baseRule: resolution.baseRule,
      competingRule: resolution.competingRule,
      resolution: resolution.resolution,
      basis: resolution.basis,
    })),
    timingResolutions: answer.timingResolutions?.map(resolution => ({
      timingContext: resolution.timingContext,
      resolutionOrder: resolution.resolutionOrder,
      orderSource: resolution.orderSource,
      basis: resolution.basis,
    })),
    tieResolutions: answer.tieResolutions?.map(resolution => ({
      tieContext: resolution.tieContext,
      resolutionSteps: [...resolution.resolutionSteps],
      finalOutcome: resolution.finalOutcome,
      basis: resolution.basis,
    })),
    scopeResolutions: answer.scopeResolutions?.map(resolution => ({
      ruleContext: resolution.ruleContext,
      governingCondition: resolution.governingCondition,
      currentSituation: resolution.currentSituation,
      matchStatus: resolution.matchStatus,
      effect: resolution.effect,
      basis: resolution.basis,
    })),
    conceptComparisons: answer.conceptComparisons?.map(comparison => ({
      leftConcept: comparison.leftConcept,
      leftDefinition: comparison.leftDefinition,
      rightConcept: comparison.rightConcept,
      rightDefinition: comparison.rightDefinition,
      commonGround: comparison.commonGround,
      keyDifference: comparison.keyDifference,
      practicalBoundary: comparison.practicalBoundary,
      basis: comparison.basis,
    })),
    ruleOptions: answer.ruleOptions?.map(option => ({
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
  if (publishesConclusion) {
    return answer.citations.length > 0
      && (answer.answerBasis === 'DIRECT_RULE' || answer.answerBasis === 'GROUNDED_APPLICATION')
      && answer.clarification === null
      && answer.recovery === null
  }
  if (answer.status === 'CLARIFICATION_REQUIRED') return Boolean(answer.clarification?.trim())
  return true
}

export function isAnswerRulingReference(value: unknown): value is AnswerRulingReference {
  if (!isRecord(value)
    || !Array.isArray(value.citationIds)
    || !value.citationIds.every(hasText)
    || new Set(value.citationIds).size !== value.citationIds.length
    || !(value.confirmedRulingId === null || isString(value.confirmedRulingId))
    || !(value.confirmedRulingVersion === null || Number.isSafeInteger(value.confirmedRulingVersion))) return false
  return (value.confirmedRulingId === null) === (value.confirmedRulingVersion === null)
}

function isCalculation(value: unknown) {
  return isRecord(value)
    && hasText(value.expression)
    && hasText(value.result)
}

function isSituationCheck(value: unknown) {
  if (!isRecord(value)
    || !hasText(value.requirement)
    || !isString(value.playerFact)
    || !(value.status === 'CONFIRMED' || value.status === 'CONTRADICTED' || value.status === 'NOT_PROVIDED')) return false
  return value.status === 'NOT_PROVIDED' ? value.playerFact.length === 0 : value.playerFact.trim().length > 0
}

function isWalkthroughStep(value: unknown) {
  return isRecord(value)
    && hasText(value.instruction)
    && hasText(value.explanation)
    && (value.orderBasis === 'RULE_ORDER' || value.orderBasis === 'EXPLANATION_ORDER')
}

function isDecisionBranch(value: unknown) {
  return isRecord(value)
    && hasText(value.condition)
    && hasText(value.outcome)
    && (value.basis === 'EXPLICIT_RULE' || value.basis === 'RULEBOOK_EXAMPLE')
}

function isExceptionClause(value: unknown) {
  return isRecord(value)
    && hasText(value.condition)
    && hasText(value.effect)
}

function isTermDefinition(value: unknown) {
  return isRecord(value)
    && hasText(value.term)
    && hasText(value.definition)
    && isString(value.boundary)
}

function isWorkedExample(value: unknown) {
  return isRecord(value)
    && hasText(value.setup)
    && hasText(value.action)
    && hasText(value.outcome)
    && (value.basis === 'RULEBOOK_EXAMPLE' || value.basis === 'EVIDENCE_BOUND_ILLUSTRATION')
}

function isPriorityResolution(value: unknown) {
  return isRecord(value)
    && hasText(value.baseRule)
    && hasText(value.competingRule)
    && hasText(value.resolution)
    && (value.basis === 'EXPLICIT_OVERRIDE'
      || value.basis === 'IMPOSSIBILITY_PRIORITY'
      || value.basis === 'CONFLICT_ONLY_OVERRIDE')
}

function isTimingResolution(value: unknown) {
  return isRecord(value)
    && hasText(value.timingContext)
    && hasText(value.resolutionOrder)
    && hasText(value.orderSource)
    && (value.basis === 'CURRENT_PLAYER_CHOOSES'
      || value.basis === 'PRINTED_TOP_TO_BOTTOM'
      || value.basis === 'NORMAL_TURN_ORDER')
}

function isTieResolution(value: unknown) {
  return isRecord(value)
    && hasText(value.tieContext)
    && Array.isArray(value.resolutionSteps)
    && value.resolutionSteps.length >= 1
    && value.resolutionSteps.every(hasText)
    && hasText(value.finalOutcome)
    && (value.basis === 'SINGLE_TIEBREAKER'
      || value.basis === 'ORDERED_TIEBREAKERS'
      || value.basis === 'RANK_REWARD_SHIFT'
      || value.basis === 'POSITIONAL_PRIORITY')
}

function isScopeResolution(value: unknown) {
  return isRecord(value)
    && hasText(value.ruleContext)
    && hasText(value.governingCondition)
    && hasText(value.currentSituation)
    && (value.matchStatus === 'MATCHES_SCOPE' || value.matchStatus === 'OUTSIDE_SCOPE' || value.matchStatus === 'NEEDS_CONTEXT')
    && hasText(value.effect)
    && (value.basis === 'PLAYER_COUNT' || value.basis === 'ROLE_PRESENCE' || value.basis === 'GAME_MODE'
      || value.basis === 'VARIANT_SELECTION' || value.basis === 'PLAYER_COUNT_EXCEPTION')
}

function isConceptComparison(value: unknown) {
  return isRecord(value)
    && hasText(value.leftConcept)
    && hasText(value.leftDefinition)
    && hasText(value.rightConcept)
    && hasText(value.rightDefinition)
    && hasText(value.commonGround)
    && hasText(value.keyDifference)
    && hasText(value.practicalBoundary)
    && (value.basis === 'ACTION_WINDOW' || value.basis === 'RESOURCE_FUNCTION'
      || value.basis === 'STORAGE_STATUS' || value.basis === 'RULE_SCOPE'
      || value.basis === 'DEFINITION_BOUNDARY')
}

function isRuleOption(value: unknown) {
  return isRecord(value)
    && hasText(value.decisionContext)
    && hasText(value.selectionRule)
    && hasText(value.optionName)
    && hasText(value.availabilityCondition)
    && hasText(value.result)
    && (value.basis === 'SOURCE_SELECTION' || value.basis === 'TIMING_CATALOG'
      || value.basis === 'ALTERNATIVE_ACTION' || value.basis === 'EXCLUSIVE_CHOICE')
}

function isCitation(value: unknown) {
  return isRecord(value)
    && isString(value.heading)
    && isString(value.excerpt)
    && Number.isSafeInteger(value.pageFrom) && Number(value.pageFrom) >= 1
    && Number.isSafeInteger(value.pageTo) && Number(value.pageTo) >= Number(value.pageFrom)
}

function isRecovery(value: unknown) {
  return isRecord(value)
    && hasText(value.message)
    && hasText(value.actionLabel)
    && isString(value.draft)
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

function requiredArrayOf(value: unknown, validator: (item: unknown) => boolean): value is unknown[] {
  return Array.isArray(value) && value.every(validator)
}

function optionalArrayOf(value: unknown, validator: (item: unknown) => boolean) {
  return value === undefined || requiredArrayOf(value, validator)
}

function optionalItems<T>(value: unknown): T[] | undefined {
  return value === undefined ? undefined : value as T[]
}

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isString(value: unknown): value is string {
  return typeof value === 'string'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
