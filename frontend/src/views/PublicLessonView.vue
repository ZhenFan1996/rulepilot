<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import RulebookIconGlossaryPanel from '@/components/RulebookIconGlossaryPanel.vue'
import type { LearningIntent } from '@/composables/useLessonAnswers'
import { groundedLearningPrompt } from '@/lib/groundedLearningPrompt'
import { useLocale } from '@/lib/locale'
import { publicLessonTitle } from '@/lib/lessonPresentation'
import { publicCoverUrl } from '@/lib/publicCover'
import {
  parseRulebookIconGlossary,
  type RulebookIconGlossary,
} from '@/lib/rulebookIconGlossary'

interface VisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
}

interface LessonStep {
  position: number
  heading: string
  kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER'
  text: string
  sourcePages: number[]
  visualFocus: VisualFocus | null
}

interface LessonSection {
  position: number
  title: string
  visualCaption: string
  steps: LessonStep[]
}

interface PublicLessonResponse {
  teachingPlanId: string
  documentVersionId: string
  rulebookTitle: string
  officialSourceUrl: string | null
  gameCover: { gameName: string; imageUrl: string; attributionUrl: string; attributionLabel: string } | null
  lesson: { id: string; status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'; sections: LessonSection[] }
  contentLanguage?: 'zh-CN' | 'en'
  localizationStatus?: 'NOT_PREPARED' | 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'
}

interface RuleCitation { heading: string; pageFrom: number; pageTo: number }
interface PublicAnswer {
  answer: {
    status: 'ANSWERED' | 'ANSWERED_WITH_WARNING' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'INVALID_MODEL_OUTPUT' | 'MODEL_TIMEOUT'
    shortVerdict: string
    explanation: string | null
    citations: RuleCitation[]
    exceptions: string[]
    confidence: 'LOW' | 'MEDIUM' | 'HIGH'
    answerBasis?: 'DIRECT_RULE' | 'GROUNDED_APPLICATION' | null
    calculations?: Array<{ expression: string; result: string }>
    situationChecks?: Array<{ requirement: string; status: 'CONFIRMED' | 'CONTRADICTED' | 'NOT_PROVIDED'; playerFact: string }>
    walkthroughSteps?: Array<{ instruction: string; explanation: string; orderBasis: 'RULE_ORDER' | 'EXPLANATION_ORDER' }>
    decisionBranches?: Array<{ condition: string; outcome: string; basis: 'EXPLICIT_RULE' | 'RULEBOOK_EXAMPLE' }>
    exceptionClauses?: Array<{ condition: string; effect: string }>
    termDefinitions?: Array<{ term: string; definition: string; boundary: string }>
    workedExamples?: Array<{ setup: string; action: string; outcome: string; basis: 'RULEBOOK_EXAMPLE' | 'EVIDENCE_BOUND_ILLUSTRATION' }>
    priorityResolutions?: Array<{ baseRule: string; competingRule: string; resolution: string; basis: 'EXPLICIT_OVERRIDE' | 'IMPOSSIBILITY_PRIORITY' | 'CONFLICT_ONLY_OVERRIDE' }>
    timingResolutions?: Array<{ timingContext: string; resolutionOrder: string; orderSource: string; basis: 'CURRENT_PLAYER_CHOOSES' | 'PRINTED_TOP_TO_BOTTOM' | 'NORMAL_TURN_ORDER' }>
    tieResolutions?: Array<{ tieContext: string; resolutionSteps: string[]; finalOutcome: string; basis: 'SINGLE_TIEBREAKER' | 'ORDERED_TIEBREAKERS' | 'RANK_REWARD_SHIFT' | 'POSITIONAL_PRIORITY' }>
    scopeResolutions?: Array<{ ruleContext: string; governingCondition: string; currentSituation: string; matchStatus: 'MATCHES_SCOPE' | 'OUTSIDE_SCOPE' | 'NEEDS_CONTEXT'; effect: string; basis: 'PLAYER_COUNT' | 'ROLE_PRESENCE' | 'GAME_MODE' | 'VARIANT_SELECTION' | 'PLAYER_COUNT_EXCEPTION' }>
    conceptComparisons?: Array<{ leftConcept: string; leftDefinition: string; rightConcept: string; rightDefinition: string; commonGround: string; keyDifference: string; practicalBoundary: string; basis: 'ACTION_WINDOW' | 'RESOURCE_FUNCTION' | 'STORAGE_STATUS' | 'RULE_SCOPE' | 'DEFINITION_BOUNDARY' }>
    ruleOptions?: Array<{ decisionContext: string; selectionRule: string; optionName: string; availabilityCondition: string; result: string; basis: 'SOURCE_SELECTION' | 'TIMING_CATALOG' | 'ALTERNATIVE_ACTION' | 'EXCLUSIVE_CHOICE' }>
    clarification: string | null
    warnings: Array<{ type: 'INDIRECT_CITATION' | 'LOW_CONFIDENCE' | 'REVIEW_UNRESOLVED' | 'REVIEW_UNAVAILABLE' }>
  }
  visualAids: Array<{ visualFocus: VisualFocus; relatedStep: string }>
  examples: Array<{ heading: string; text: string; sourcePages: number[] }>
}

interface PublicAnswerTurn {
  question: string
  answer: PublicAnswer
  learningIntent?: LearningIntent | null
}

const PUBLIC_ANSWER_HISTORY_LIMIT = 6
const PUBLIC_ANSWER_STORAGE_PREFIX = 'rulepilot:public-answer-thread:'
const PUBLIC_ANSWER_READER_KEY = 'rulepilot:public-answer-reader'

const route = useRoute()
const { locale, t } = useLocale()
const loading = ref(true)
const errorMessage = ref('')
const publicLesson = ref<PublicLessonResponse | null>(null)
const iconGlossary = ref<RulebookIconGlossary | null>(null)
const iconGlossaryLoading = ref(true)
const iconGlossaryError = ref('')
const publicQuestion = ref('')
const publicAnswerTurns = ref<PublicAnswerTurn[]>([])
const publicAnswerLoading = ref(false)
const publicAnswerError = ref('')
const publicAnswerNotice = ref('')
const readerScope = ref<string | null>(null)
const readerScopeReady = ref(false)
const coverUnavailable = ref(false)
let latestLoadRequest = 0
let latestPublicAnswerRequest = 0
let activePublicAnswerController: AbortController | null = null
const planId = computed(() => typeof route.params.planId === 'string' ? route.params.planId : '')
const displayTitle = computed(() => publicLesson.value ? publicLessonTitle(publicLesson.value) : '')
const englishGuidePending = computed(() => locale.value === 'en' && publicLesson.value?.contentLanguage !== 'en')
const englishGuideFailed = computed(() => englishGuidePending.value && publicLesson.value?.localizationStatus === 'FAILED')

function answerThreadStorageKey() {
  if (!readerScope.value || !planId.value) return null
  return `${PUBLIC_ANSWER_STORAGE_PREFIX}${readerScope.value}:${planId.value}:${locale.value}`
}

function anonymousReaderScope() {
  try {
    const stored = sessionStorage.getItem(PUBLIC_ANSWER_READER_KEY)
    if (stored?.startsWith('guest:') && stored.length <= 96) return stored
    const randomId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
    const generated = `guest:${randomId}`
    sessionStorage.setItem(PUBLIC_ANSWER_READER_KEY, generated)
    return generated
  } catch {
    return null
  }
}

async function initializeReaderScope() {
  let resolvedScope = anonymousReaderScope()
  try {
    const response = await fetch('/api/auth/session', { credentials: 'include' })
    if (response.ok) {
      const session = await response.json() as { username?: unknown }
      if (typeof session.username === 'string' && session.username.trim()) {
        resolvedScope = `account:${encodeURIComponent(session.username.trim().toLowerCase())}`
        sessionStorage.removeItem(PUBLIC_ANSWER_READER_KEY)
      }
    }
  } catch {
    // Public readers can still ask without an account; their browser-session scope remains in use.
  } finally {
    readerScope.value = resolvedScope
    readerScopeReady.value = true
    restorePublicAnswerTurns()
  }
}

function restorePublicAnswerTurns() {
  const storageKey = answerThreadStorageKey()
  if (!storageKey) {
    publicAnswerTurns.value = []
    return
  }
  try {
    const stored = sessionStorage.getItem(storageKey)
    const parsed = stored ? JSON.parse(stored) : []
    publicAnswerTurns.value = Array.isArray(parsed)
      ? parsed.filter(isPublicAnswerTurn).slice(-PUBLIC_ANSWER_HISTORY_LIMIT)
      : []
  } catch {
    publicAnswerTurns.value = []
  }
}

function rememberPublicAnswerTurns() {
  const storageKey = answerThreadStorageKey()
  if (!storageKey) return
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(publicAnswerTurns.value))
  } catch {
    // A private browser mode may not expose storage; the current on-page thread remains usable.
  }
}

function clearPublicAnswerTurns() {
  const storageKey = answerThreadStorageKey()
  publicAnswerTurns.value = []
  if (!storageKey) return
  try {
    sessionStorage.removeItem(storageKey)
  } catch {
    // The visible thread was still cleared even if browser storage is unavailable.
  }
}

function isPublicAnswerTurn(value: unknown): value is PublicAnswerTurn {
  if (!isRecord(value) || typeof value.question !== 'string' || value.question.trim().length === 0 || value.question.length > 800) return false
  return isPublicAnswer(value.answer) && isPublicLearningIntent(value.learningIntent)
}

function isPublicLearningIntent(value: unknown) {
  return value === undefined || value === null || value === 'SIMPLIFY' || value === 'EXAMPLE'
    || value === 'DEFINE' || value === 'WHY' || value === 'EXCEPTIONS' || value === 'SOURCE' || value === 'VERIFY'
    || value === 'DEFINE' || value === 'WHY' || value === 'EXCEPTIONS' || value === 'VERIFY'
}

function isPublicAnswer(value: unknown): value is PublicAnswer {
  if (!isRecord(value) || !isRecord(value.answer)) return false
  const answer = value.answer
  return isAnswerStatus(answer.status)
    && typeof answer.shortVerdict === 'string'
    && (typeof answer.explanation === 'string' || answer.explanation === null)
    && Array.isArray(answer.citations) && answer.citations.every(isCitation)
    && Array.isArray(answer.exceptions) && answer.exceptions.every((exception) => typeof exception === 'string')
    && isConfidence(answer.confidence)
    && (answer.answerBasis === undefined || answer.answerBasis === null
      || answer.answerBasis === 'DIRECT_RULE' || answer.answerBasis === 'GROUNDED_APPLICATION')
    && (answer.calculations === undefined || Array.isArray(answer.calculations)
      && answer.calculations.every(calculation => typeof calculation?.expression === 'string'
        && calculation.expression.length > 0 && calculation.expression.length <= 160
        && typeof calculation?.result === 'string' && calculation.result.length > 0 && calculation.result.length <= 80))
    && (answer.situationChecks === undefined || Array.isArray(answer.situationChecks)
      && answer.situationChecks.length <= 6 && answer.situationChecks.every(check => typeof check?.requirement === 'string'
        && check.requirement.length > 0 && check.requirement.length <= 500
        && (check.status === 'CONFIRMED' || check.status === 'CONTRADICTED' || check.status === 'NOT_PROVIDED')
        && typeof check.playerFact === 'string' && check.playerFact.length <= 800
        && (check.status === 'NOT_PROVIDED' ? check.playerFact.length === 0 : check.playerFact.trim().length > 0)))
    && (answer.walkthroughSteps === undefined || Array.isArray(answer.walkthroughSteps)
      && answer.walkthroughSteps.length <= 6 && answer.walkthroughSteps.every(step => typeof step?.instruction === 'string'
        && step.instruction.length > 0 && step.instruction.length <= 240
        && typeof step?.explanation === 'string' && step.explanation.length > 0 && step.explanation.length <= 500
        && (step.orderBasis === 'RULE_ORDER' || step.orderBasis === 'EXPLANATION_ORDER')))
    && (answer.decisionBranches === undefined || Array.isArray(answer.decisionBranches)
      && answer.decisionBranches.length <= 6 && answer.decisionBranches.every(branch => typeof branch?.condition === 'string'
        && branch.condition.length > 0 && branch.condition.length <= 300
        && typeof branch?.outcome === 'string' && branch.outcome.length > 0 && branch.outcome.length <= 500
        && (branch.basis === 'EXPLICIT_RULE' || branch.basis === 'RULEBOOK_EXAMPLE')))
    && (answer.exceptionClauses === undefined || Array.isArray(answer.exceptionClauses)
      && answer.exceptionClauses.length <= 6 && answer.exceptionClauses.every(clause => typeof clause?.condition === 'string'
        && clause.condition.length > 0 && clause.condition.length <= 300
        && typeof clause?.effect === 'string' && clause.effect.length > 0 && clause.effect.length <= 500))
    && (answer.termDefinitions === undefined || Array.isArray(answer.termDefinitions)
      && answer.termDefinitions.length <= 4 && answer.termDefinitions.every(definition => typeof definition?.term === 'string'
        && definition.term.length > 0 && definition.term.length <= 120
        && typeof definition?.definition === 'string' && definition.definition.length > 0 && definition.definition.length <= 600
        && typeof definition?.boundary === 'string' && definition.boundary.length <= 400))
    && (answer.workedExamples === undefined || Array.isArray(answer.workedExamples)
      && answer.workedExamples.length <= 3 && answer.workedExamples.every(example => typeof example?.setup === 'string'
        && example.setup.length > 0 && example.setup.length <= 500
        && typeof example?.action === 'string' && example.action.length > 0 && example.action.length <= 700
        && typeof example?.outcome === 'string' && example.outcome.length > 0 && example.outcome.length <= 500
        && (example.basis === 'RULEBOOK_EXAMPLE' || example.basis === 'EVIDENCE_BOUND_ILLUSTRATION')))
    && (answer.priorityResolutions === undefined || Array.isArray(answer.priorityResolutions)
      && answer.priorityResolutions.length <= 3 && answer.priorityResolutions.every(resolution => typeof resolution?.baseRule === 'string'
        && resolution.baseRule.length > 0 && resolution.baseRule.length <= 500
        && typeof resolution?.competingRule === 'string' && resolution.competingRule.length > 0 && resolution.competingRule.length <= 500
        && typeof resolution?.resolution === 'string' && resolution.resolution.length > 0 && resolution.resolution.length <= 600
        && (resolution.basis === 'EXPLICIT_OVERRIDE' || resolution.basis === 'IMPOSSIBILITY_PRIORITY' || resolution.basis === 'CONFLICT_ONLY_OVERRIDE')))
    && (answer.timingResolutions === undefined || Array.isArray(answer.timingResolutions)
      && answer.timingResolutions.length <= 3 && answer.timingResolutions.every(resolution => typeof resolution?.timingContext === 'string'
        && resolution.timingContext.length > 0 && resolution.timingContext.length <= 500
        && typeof resolution?.resolutionOrder === 'string' && resolution.resolutionOrder.length > 0 && resolution.resolutionOrder.length <= 700
        && typeof resolution?.orderSource === 'string' && resolution.orderSource.length > 0 && resolution.orderSource.length <= 400
        && (resolution.basis === 'CURRENT_PLAYER_CHOOSES' || resolution.basis === 'PRINTED_TOP_TO_BOTTOM' || resolution.basis === 'NORMAL_TURN_ORDER')))
    && (answer.tieResolutions === undefined || Array.isArray(answer.tieResolutions)
      && answer.tieResolutions.length <= 3 && answer.tieResolutions.every(resolution => typeof resolution?.tieContext === 'string'
        && resolution.tieContext.length > 0 && resolution.tieContext.length <= 500
        && Array.isArray(resolution.resolutionSteps) && resolution.resolutionSteps.length >= 1 && resolution.resolutionSteps.length <= 6
        && resolution.resolutionSteps.every((step: unknown) => typeof step === 'string' && step.length > 0 && step.length <= 500 && !step.includes('\n') && !step.includes('\r'))
        && typeof resolution?.finalOutcome === 'string' && resolution.finalOutcome.length > 0 && resolution.finalOutcome.length <= 500
        && (resolution.basis === 'SINGLE_TIEBREAKER' || resolution.basis === 'ORDERED_TIEBREAKERS' || resolution.basis === 'RANK_REWARD_SHIFT' || resolution.basis === 'POSITIONAL_PRIORITY')))
    && (answer.scopeResolutions === undefined || Array.isArray(answer.scopeResolutions)
      && answer.scopeResolutions.length <= 3 && answer.scopeResolutions.every(resolution => typeof resolution?.ruleContext === 'string'
        && resolution.ruleContext.length > 0 && resolution.ruleContext.length <= 500
        && typeof resolution?.governingCondition === 'string' && resolution.governingCondition.length > 0 && resolution.governingCondition.length <= 500
        && typeof resolution?.currentSituation === 'string' && resolution.currentSituation.length > 0 && resolution.currentSituation.length <= 300
        && (resolution.matchStatus === 'MATCHES_SCOPE' || resolution.matchStatus === 'OUTSIDE_SCOPE' || resolution.matchStatus === 'NEEDS_CONTEXT')
        && typeof resolution?.effect === 'string' && resolution.effect.length > 0 && resolution.effect.length <= 600
        && (resolution.basis === 'PLAYER_COUNT' || resolution.basis === 'ROLE_PRESENCE' || resolution.basis === 'GAME_MODE'
          || resolution.basis === 'VARIANT_SELECTION' || resolution.basis === 'PLAYER_COUNT_EXCEPTION')))
    && (answer.conceptComparisons === undefined || Array.isArray(answer.conceptComparisons)
      && answer.conceptComparisons.length <= 3 && answer.conceptComparisons.every(item => typeof item?.leftConcept === 'string'
        && typeof item.leftDefinition === 'string' && typeof item.rightConcept === 'string'
        && typeof item.rightDefinition === 'string' && typeof item.commonGround === 'string'
        && typeof item.keyDifference === 'string' && typeof item.practicalBoundary === 'string'
        && (item.basis === 'ACTION_WINDOW' || item.basis === 'RESOURCE_FUNCTION'
          || item.basis === 'STORAGE_STATUS' || item.basis === 'RULE_SCOPE'
          || item.basis === 'DEFINITION_BOUNDARY')))
    && (answer.ruleOptions === undefined || Array.isArray(answer.ruleOptions)
      && answer.ruleOptions.length >= 2 && answer.ruleOptions.length <= 8
      && answer.ruleOptions.every(item => typeof item?.decisionContext === 'string' && item.decisionContext.length > 0 && item.decisionContext.length <= 240
        && typeof item.selectionRule === 'string' && item.selectionRule.length > 0 && item.selectionRule.length <= 400
        && typeof item.optionName === 'string' && item.optionName.length > 0 && item.optionName.length <= 160
        && typeof item.availabilityCondition === 'string' && item.availabilityCondition.length > 0 && item.availabilityCondition.length <= 500
        && typeof item.result === 'string' && item.result.length > 0 && item.result.length <= 700
        && (item.basis === 'SOURCE_SELECTION' || item.basis === 'TIMING_CATALOG'
          || item.basis === 'ALTERNATIVE_ACTION' || item.basis === 'EXCLUSIVE_CHOICE')))
    && (typeof answer.clarification === 'string' || answer.clarification === null)
    && Array.isArray(answer.warnings) && answer.warnings.every(isAnswerWarning)
    && Array.isArray(value.visualAids) && value.visualAids.every(isVisualAid)
    && Array.isArray(value.examples) && value.examples.every(isExample)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isAnswerStatus(value: unknown): value is PublicAnswer['answer']['status'] {
  return value === 'ANSWERED' || value === 'ANSWERED_WITH_WARNING'
    || value === 'CLARIFICATION_REQUIRED' || value === 'INSUFFICIENT_EVIDENCE'
    || value === 'INVALID_MODEL_OUTPUT' || value === 'MODEL_TIMEOUT'
}

function isAnswerWarning(value: unknown): value is PublicAnswer['answer']['warnings'][number] {
  return isRecord(value) && (value.type === 'INDIRECT_CITATION'
    || value.type === 'LOW_CONFIDENCE'
    || value.type === 'REVIEW_UNRESOLVED'
    || value.type === 'REVIEW_UNAVAILABLE')
}

function isConfidence(value: unknown): value is PublicAnswer['answer']['confidence'] {
  return value === 'LOW' || value === 'MEDIUM' || value === 'HIGH'
}

function isCitation(value: unknown): value is RuleCitation {
  return isRecord(value) && typeof value.heading === 'string' && Number.isInteger(value.pageFrom) && Number.isInteger(value.pageTo)
}

function isVisualAid(value: unknown): value is PublicAnswer['visualAids'][number] {
  return isRecord(value) && typeof value.relatedStep === 'string' && isVisualFocus(value.visualFocus)
}

function isVisualFocus(value: unknown): value is VisualFocus {
  return isRecord(value)
    && typeof value.label === 'string'
    && [value.pageNumber, value.x, value.y, value.width, value.height].every(Number.isFinite)
}

function isExample(value: unknown): value is PublicAnswer['examples'][number] {
  return isRecord(value)
    && typeof value.heading === 'string'
    && typeof value.text === 'string'
    && Array.isArray(value.sourcePages)
    && value.sourcePages.every(Number.isInteger)
}

function sourcePageUrl(pageNumber: number) {
  return `/api/public/lessons/${encodeURIComponent(planId.value)}/pages/${pageNumber}/image`
}

function cropUrl(focus: VisualFocus) {
  const query = new URLSearchParams({
    x: String(focus.x), y: String(focus.y), width: String(focus.width), height: String(focus.height),
  })
  return `${sourcePageUrl(focus.pageNumber)}/crop?${query.toString()}`
}

function publicIconGlossaryEndpoint(targetPlanId = planId.value) {
  return `/api/public/lessons/${encodeURIComponent(targetPlanId)}/icon-glossary`
}

function publicIconImageUrl(occurrenceId: string) {
  return `${publicIconGlossaryEndpoint()}/icons/${encodeURIComponent(occurrenceId)}/image`
}

async function optionalPublicFetch(url: string) {
  try {
    return await fetch(url)
  } catch {
    return null
  }
}

async function acceptPublicIconGlossary(response: Response | null, request: number) {
  if (request !== latestLoadRequest) return
  if (!response?.ok) {
    iconGlossaryError.value = t('iconGlossary.error.load')
    iconGlossaryLoading.value = false
    return
  }
  try {
    const received = parseRulebookIconGlossary(await response.json())
    if (request !== latestLoadRequest) return
    iconGlossary.value = received
    iconGlossaryError.value = ''
  } catch {
    if (request === latestLoadRequest) iconGlossaryError.value = t('iconGlossary.error.load')
  } finally {
    if (request === latestLoadRequest) iconGlossaryLoading.value = false
  }
}

async function reloadPublicIconGlossary() {
  const request = latestLoadRequest
  iconGlossaryLoading.value = iconGlossary.value === null
  iconGlossaryError.value = ''
  await acceptPublicIconGlossary(await optionalPublicFetch(publicIconGlossaryEndpoint()), request)
}

async function load() {
  const requestedPlanId = planId.value
  const requestedLocale = locale.value
  const request = ++latestLoadRequest
  loading.value = true
  errorMessage.value = ''
  iconGlossary.value = null
  iconGlossaryLoading.value = true
  iconGlossaryError.value = ''
  try {
    if (!requestedPlanId) throw new Error(t('public.error.missing'))
    const [response, glossaryResponse] = await Promise.all([
      fetch(`/api/public/lessons/${encodeURIComponent(requestedPlanId)}?language=${encodeURIComponent(requestedLocale)}`),
      optionalPublicFetch(publicIconGlossaryEndpoint(requestedPlanId)),
    ])
    if (response.status === 404) throw new Error(t('public.error.unpublished'))
    if (!response.ok) throw new Error(t('public.error.open'))
    const received = await response.json() as PublicLessonResponse
    if (request !== latestLoadRequest) return
    publicLesson.value = received
    coverUnavailable.value = false
    await acceptPublicIconGlossary(glossaryResponse, request)
  } catch (error) {
    if (request !== latestLoadRequest) return
    errorMessage.value = error instanceof Error ? error.message : t('public.error.open')
  } finally {
    if (request === latestLoadRequest) {
      loading.value = false
      iconGlossaryLoading.value = false
    }
  }
}

function confidenceLabel(confidence: PublicAnswer['answer']['confidence']) {
  return { LOW: t('public.answer.low'), MEDIUM: t('public.answer.medium'), HIGH: t('public.answer.high') }[confidence]
}

function confidenceClasses(confidence: PublicAnswer['answer']['confidence']) {
  if (confidence === 'HIGH') return 'bg-emerald-50 text-emerald-700'
  if (confidence === 'MEDIUM') return 'bg-amber-50 text-amber-800'
  return 'bg-red-50 text-red-700'
}

function answerBasisLabel(answerBasis: PublicAnswer['answer']['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION'
    ? t('public.answer.groundedBasis')
    : t('public.answer.directBasis')
}

function answerBasisDescription(answerBasis: PublicAnswer['answer']['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION'
    ? t('public.answer.groundedDescription')
    : t('public.answer.directDescription')
}

function citationPageLabel(citation: RuleCitation) {
  const pages = citation.pageFrom === citation.pageTo ? String(citation.pageFrom) : `${citation.pageFrom}–${citation.pageTo}`
  return locale.value === 'en' ? `p. ${pages}` : `第 ${pages} 页`
}

function answerFailureMessage(answer: PublicAnswer['answer']) {
  if (answer.status === 'CLARIFICATION_REQUIRED') return answer.clarification ?? t('public.answer.clarify')
  if (answer.status === 'MODEL_TIMEOUT') return t('public.answer.timeout')
  return answer.shortVerdict
}

function publishesConclusion(status: PublicAnswer['answer']['status']) {
  return status === 'ANSWERED' || status === 'ANSWERED_WITH_WARNING'
}

function answerWarningMessage(warning: PublicAnswer['answer']['warnings'][number]) {
  if (warning.type === 'INDIRECT_CITATION') return t('lesson.answer.warning.INDIRECT_CITATION')
  if (warning.type === 'LOW_CONFIDENCE') return t('lesson.answer.warning.LOW_CONFIDENCE')
  if (warning.type === 'REVIEW_UNRESOLVED') return t('lesson.answer.warning.REVIEW_UNRESOLVED')
  return t('lesson.answer.warning.REVIEW_UNAVAILABLE')
}

async function preparePublicAnswerReply(turn: PublicAnswerTurn) {
  if (publicAnswerLoading.value) return
  if (!publicQuestion.value.trim() || publicQuestion.value.trim() === turn.question.trim()) {
    if (turn.answer.answer.status === 'CLARIFICATION_REQUIRED') {
      publicQuestion.value = t('public.answer.clarificationPrefix')
    } else {
      const suffix = t('public.answer.refineSuffix')
      publicQuestion.value = `${turn.question.slice(0, Math.max(0, 800 - suffix.length))}${suffix}`
    }
  }
  publicAnswerNotice.value = ''
  await nextTick()
  const input = document.getElementById('public-question')
  input?.focus()
  input?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
}

async function submitPublicQuestion() {
  const question = publicQuestion.value.trim()
  await sendPublicQuestion(question, null)
}

async function sendPublicQuestion(question: string, learningIntent: LearningIntent | null) {
  if (!question || publicAnswerLoading.value || !planId.value || !readerScopeReady.value) return
  const requestedPlanId = planId.value
  const requestedLocale = locale.value
  const answerRequest = ++latestPublicAnswerRequest
  const controller = new AbortController()
  activePublicAnswerController = controller
  publicAnswerLoading.value = true
  publicAnswerError.value = ''
  publicAnswerNotice.value = ''
  try {
    const previousTurn = publicAnswerTurns.value.at(-1)
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}/answers`, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question,
        previousQuestion: previousTurn?.question ?? null,
        language: locale.value,
        learningIntent,
      }),
    })
    if (!isCurrentPublicAnswerRequest(answerRequest, requestedPlanId, requestedLocale)) return
    if (response.status === 404) throw new Error(t('public.answer.missing'))
    if (!response.ok) throw new Error(t('public.answer.failed'))
    const received = await response.json() as PublicAnswer
    if (!isCurrentPublicAnswerRequest(answerRequest, requestedPlanId, requestedLocale)) return
    publicAnswerTurns.value = [...publicAnswerTurns.value, { question, answer: received, learningIntent }].slice(-PUBLIC_ANSWER_HISTORY_LIMIT)
    rememberPublicAnswerTurns()
    publicQuestion.value = ''
    await nextTick()
    const answerElement = document.getElementById(`public-answer-${publicAnswerTurns.value.length - 1}`)
    answerElement?.focus({ preventScroll: true })
    answerElement?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    if (!isCurrentPublicAnswerRequest(answerRequest, requestedPlanId, requestedLocale)) return
    publicAnswerError.value = error instanceof Error ? error.message : t('public.answer.fallback')
  } finally {
    if (activePublicAnswerController === controller) activePublicAnswerController = null
    if (isCurrentPublicAnswerRequest(answerRequest, requestedPlanId, requestedLocale)) {
      publicAnswerLoading.value = false
    }
  }
}

function publicLearningAnchorQuestion() {
  for (let index = publicAnswerTurns.value.length - 1; index >= 0; index--) {
    const turn = publicAnswerTurns.value[index]
    if (turn && !turn.learningIntent) return turn.question
  }
  return publicAnswerTurns.value.at(-1)?.question ?? ''
}

async function requestPublicLearningHelp(intent: LearningIntent) {
  const prompt = groundedLearningPrompt(t, intent, publicLearningAnchorQuestion())
  publicQuestion.value = prompt
  await sendPublicQuestion(prompt, intent)
}

function isCurrentPublicAnswerRequest(request: number, requestedPlanId: string, requestedLocale: string) {
  return request === latestPublicAnswerRequest
    && requestedPlanId === planId.value
    && requestedLocale === locale.value
}

function abandonPublicAnswer(showNotice = false) {
  if (!publicAnswerLoading.value && !activePublicAnswerController) return
  latestPublicAnswerRequest++
  activePublicAnswerController?.abort()
  activePublicAnswerController = null
  publicAnswerLoading.value = false
  publicAnswerError.value = ''
  publicAnswerNotice.value = showNotice ? t('public.question.stopped') : ''
}

onMounted(() => {
  void load()
  void initializeReaderScope()
})

watch([locale, planId], () => {
  abandonPublicAnswer()
  if (readerScopeReady.value) restorePublicAnswerTurns()
  else publicAnswerTurns.value = []
  publicQuestion.value = ''
  publicAnswerError.value = ''
  publicAnswerNotice.value = ''
  void load()
})

onUnmounted(() => abandonPublicAnswer())
</script>

<template>
  <AppShell>
    <div class="min-h-screen bg-canvas text-ink">
      <section v-if="loading" class="mx-auto max-w-3xl px-5 py-20 text-center sm:px-8" role="status">
        <p class="font-display text-2xl font-semibold">{{ t('public.loading') }}</p>
        <p class="mt-3 text-ink/55">{{ t('public.hero.description') }}</p>
      </section>

      <section v-else-if="errorMessage" class="mx-auto max-w-2xl px-5 py-20 text-center sm:px-8">
        <p class="font-display text-2xl font-semibold">{{ t('public.error.title') }}</p>
        <p class="mt-3 text-ink/60">{{ errorMessage }}</p>
        <button type="button" class="mt-6 rounded-lg bg-ink px-4 py-2.5 font-semibold text-paper" @click="load">{{ t('public.error.retry') }}</button>
      </section>

      <article v-else-if="publicLesson" class="mx-auto max-w-4xl px-5 py-10 sm:px-8 lg:py-14">
        <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo hover:text-indigo/75">← {{ t('nav.library') }}</RouterLink>
        <div class="border-b border-ink/10 pb-8">
          <div class="flex items-start gap-5 sm:gap-7">
            <a v-if="publicLesson.gameCover && !coverUnavailable" :href="publicLesson.gameCover.attributionUrl" target="_blank" rel="noopener noreferrer" class="w-24 shrink-0 overflow-hidden rounded-lg border border-ink/10 bg-paper shadow-sm sm:w-32" :aria-label="t('public.cover.open', { title: displayTitle, source: publicLesson.gameCover.attributionLabel })">
              <img :src="publicCoverUrl(planId)" :alt="t('public.cover.alt', { title: displayTitle })" class="aspect-[3/4] h-full w-full object-cover" decoding="async" @error="coverUnavailable = true">
            </a>
            <div v-else-if="!coverUnavailable" class="w-24 shrink-0 overflow-hidden rounded-lg border border-ink/10 bg-paper shadow-sm sm:w-32">
              <img :src="publicCoverUrl(planId)" :alt="t('public.cover.alt', { title: displayTitle })" class="aspect-[3/4] h-full w-full object-cover" decoding="async" @error="coverUnavailable = true">
            </div>
            <div>
              <p class="text-sm font-semibold text-copper">{{ t('public.hero.eyebrow') }}</p>
              <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ displayTitle }}</h1>
              <p v-if="publicLesson.rulebookTitle !== displayTitle" class="mt-2 text-sm font-medium text-ink/50">{{ t('public.hero.rulebook', { title: publicLesson.rulebookTitle }) }}</p>
              <p class="mt-4 max-w-2xl leading-7 text-ink/60">{{ t('public.hero.description') }}</p>
            </div>
          </div>
          <a v-if="publicLesson.officialSourceUrl" :href="`/api/public/lessons/${encodeURIComponent(planId)}/rulebook`" target="_blank" rel="noopener noreferrer" class="mt-5 inline-flex min-h-11 items-center rounded-lg border border-indigo/30 px-4 font-semibold text-indigo hover:bg-indigo/5">{{ t('public.hero.openRulebook') }}</a>
          <p v-if="englishGuidePending" class="mt-5 rounded-2xl border border-indigo/15 bg-indigo/[0.045] px-4 py-3 text-sm leading-6 text-indigo" role="status">{{ englishGuideFailed ? t('public.locale.failed') : t('public.locale.preparing') }}</p>
        </div>

        <RulebookIconGlossaryPanel
          :glossary="iconGlossary"
          :loading="iconGlossaryLoading"
          :error-message="iconGlossaryError"
          :can-generate="false"
          :generating="iconGlossary?.status === 'GENERATING'"
          :online="true"
          :image-url="publicIconImageUrl"
          @retry="reloadPublicIconGlossary"
        />

        <section class="mt-8 rounded-3xl border border-indigo/20 bg-indigo/[0.045] p-5 shadow-[0_18px_50px_-36px_rgba(40,57,128,0.75)] sm:p-7" aria-labelledby="public-question-title">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p class="text-xs font-semibold uppercase tracking-[0.14em] text-indigo">{{ t('public.question.eyebrow') }}</p>
              <h2 id="public-question-title" class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ t('public.question.title') }}</h2>
              <p class="mt-2 max-w-2xl leading-7 text-ink/60">{{ t('public.question.description') }}</p>
              <p class="mt-2 text-xs leading-5 text-ink/45">{{ t('public.question.private') }}</p>
            </div>
            <div class="flex w-fit shrink-0 flex-wrap gap-2">
              <span class="rounded-full bg-paper px-3 py-1.5 text-xs font-semibold text-indigo">{{ t('public.question.noLogin') }}</span>
              <button v-if="publicAnswerTurns.length" type="button" :disabled="publicAnswerLoading" :aria-label="t('public.question.clear')" class="min-h-8 rounded-full border border-ink/15 bg-paper px-3 text-xs font-semibold text-ink/60 transition hover:border-copper/40 hover:text-copper disabled:cursor-not-allowed disabled:opacity-50" @click="clearPublicAnswerTurns">{{ t('public.question.clear') }}</button>
            </div>
          </div>

          <p v-if="publicAnswerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ publicAnswerError }}</p>
          <p v-else-if="publicAnswerNotice" class="mt-4 rounded-2xl bg-indigo/5 px-4 py-3 text-sm text-indigo" role="status">{{ publicAnswerNotice }}</p>
          <div v-else-if="publicAnswerLoading" class="mt-5 rounded-2xl border border-indigo/12 bg-paper p-5" role="status" aria-live="polite">
            <div class="flex items-center gap-3"><span class="size-3 animate-pulse rounded-full bg-copper" /><p class="text-sm font-semibold">{{ t('public.question.waiting') }}</p></div>
            <p class="mt-2 text-xs leading-5 text-ink/50">{{ t('public.question.waitingDetail') }}</p>
            <button type="button" class="mt-4 min-h-11 rounded-xl border border-ink/15 px-4 text-sm font-semibold text-ink/65" @click="abandonPublicAnswer(true)">{{ t('public.question.stop') }}</button>
          </div>

          <ol v-if="publicAnswerTurns.length" class="mt-6 space-y-5" :aria-label="t('public.question.thread')">
            <li v-for="(turn, index) in publicAnswerTurns" :key="`${index}-${turn.question}`" class="space-y-3">
              <div class="ml-auto max-w-[92%] rounded-2xl rounded-tr-md bg-copper px-4 py-3 text-sm font-medium leading-6 text-white sm:max-w-[78%]">{{ turn.question }}</div>
              <article :id="`public-answer-${index}`" tabindex="-1" class="max-w-[96%] overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm outline-none focus:ring-4 focus:ring-indigo/15 sm:max-w-[88%]">
                <div class="p-5 sm:p-6">
                  <div class="flex flex-wrap items-center gap-2"><span :class="confidenceClasses(turn.answer.answer.confidence)" :data-confidence="turn.answer.answer.confidence" class="rounded-full px-3 py-1 text-xs font-semibold">{{ confidenceLabel(turn.answer.answer.confidence) }}</span><span v-if="publishesConclusion(turn.answer.answer.status)" class="rounded-full bg-copper/[0.1] px-3 py-1 text-xs font-semibold text-copper">{{ answerBasisLabel(turn.answer.answer.answerBasis) }}</span><span class="text-xs font-semibold text-ink/40">{{ t('public.question.answer') }}</span></div>
                  <p class="mt-4 font-display text-xl font-semibold leading-8">{{ turn.answer.answer.shortVerdict }}</p>
                  <div v-if="turn.answer.answer.warnings.length" class="mt-4 rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status"><p class="font-semibold">{{ t('lesson.answer.warning.title') }}</p><ul class="mt-1 list-disc pl-5"><li v-for="warning in turn.answer.answer.warnings" :key="warning.type">{{ answerWarningMessage(warning) }}</li></ul></div>
                  <div v-if="publishesConclusion(turn.answer.answer.status) && turn.answer.answer.explanation" class="mt-4 rounded-2xl bg-canvas p-4 text-sm leading-6 text-ink/70">
                    <p class="font-semibold text-indigo">{{ t('public.answer.trace') }}</p>
                    <p class="mt-2"><span class="font-semibold text-ink">{{ t('public.answer.ruleBasis') }}：</span>{{ answerBasisDescription(turn.answer.answer.answerBasis) }}</p>
                    <p class="mt-2"><span class="font-semibold text-ink">{{ t('public.answer.application') }}：</span>{{ turn.answer.answer.explanation }}</p>
                    <div v-if="turn.answer.answer.calculations?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.calculationTitle') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.calculationDescription') }}</p><ul class="mt-2 space-y-1 font-mono text-sm text-indigo"><li v-for="calculation in turn.answer.answer.calculations" :key="`${calculation.expression}-${calculation.result}`">{{ calculation.expression }} = {{ calculation.result }}</li></ul></div>
                    <div v-if="turn.answer.answer.situationChecks?.length" class="mt-3 rounded-xl border border-ink/10 bg-paper px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.situation.title') }}</p><ul class="mt-2 space-y-2"><li v-for="check in turn.answer.answer.situationChecks" :key="`${check.requirement}-${check.status}`"><span class="font-semibold">{{ check.status === 'CONFIRMED' ? t('lesson.answer.situation.confirmed') : check.status === 'CONTRADICTED' ? t('lesson.answer.situation.contradicted') : t('lesson.answer.situation.notProvided') }}：</span>{{ check.requirement }}<p v-if="check.playerFact" class="text-xs text-ink/50">{{ check.playerFact }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.walkthroughSteps?.length" class="mt-3 rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.walkthrough.title') }}</p><ol class="mt-2 space-y-2"><li v-for="(step, stepIndex) in turn.answer.answer.walkthroughSteps" :key="`${stepIndex}-${step.instruction}`"><span class="font-semibold text-copper">{{ stepIndex + 1 }}. </span><span class="font-semibold text-ink">{{ step.instruction }}</span><p class="ml-5 text-xs text-ink/50">{{ step.explanation }} · {{ step.orderBasis === 'RULE_ORDER' ? t('lesson.answer.walkthrough.ruleOrder') : t('lesson.answer.walkthrough.explanationOrder') }}</p></li></ol></div>
                    <div v-if="turn.answer.answer.decisionBranches?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.decision.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.decision.description') }}</p><ul class="mt-2 space-y-2"><li v-for="branch in turn.answer.answer.decisionBranches" :key="`${branch.condition}-${branch.outcome}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-indigo">{{ branch.basis === 'EXPLICIT_RULE' ? t('lesson.answer.decision.explicitRule') : t('lesson.answer.decision.rulebookExample') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：</span>{{ branch.condition }}</p><p>→ {{ branch.outcome }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.exceptionClauses?.length" class="mt-3 rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.exception.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.exception.description') }}</p><ul class="mt-2 space-y-2"><li v-for="clause in turn.answer.answer.exceptionClauses" :key="`${clause.condition}-${clause.effect}`" class="rounded-lg bg-paper px-3 py-2"><p><span class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：</span>{{ clause.condition }}</p><p>→ {{ clause.effect }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.termDefinitions?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.definition.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.definition.description') }}</p><dl class="mt-2 space-y-2"><div v-for="definition in turn.answer.answer.termDefinitions" :key="definition.term" class="rounded-lg bg-paper px-3 py-2"><dt class="font-semibold text-indigo">{{ definition.term }}</dt><dd>{{ definition.definition }}</dd><dd v-if="definition.boundary" class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.definition.boundary') }}：{{ definition.boundary }}</dd></div></dl></div>
                    <div v-if="turn.answer.answer.workedExamples?.length" class="mt-3 rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.example.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.example.description') }}</p><ol class="mt-2 space-y-2"><li v-for="(example, exampleIndex) in turn.answer.answer.workedExamples" :key="`${exampleIndex}-${example.setup}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-copper">{{ example.basis === 'RULEBOOK_EXAMPLE' ? t('lesson.answer.example.rulebook') : t('lesson.answer.example.illustration') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.example.setup') }}：</span>{{ example.setup }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.example.action') }}：</span>{{ example.action }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.example.outcome') }}：</span>{{ example.outcome }}</p></li></ol></div>
                    <div v-if="turn.answer.answer.priorityResolutions?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.priority.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.priority.description') }}</p><ul class="mt-2 space-y-2"><li v-for="item in turn.answer.answer.priorityResolutions" :key="`${item.baseRule}-${item.competingRule}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-indigo">{{ item.basis === 'EXPLICIT_OVERRIDE' ? t('lesson.answer.priority.explicit') : item.basis === 'IMPOSSIBILITY_PRIORITY' ? t('lesson.answer.priority.impossible') : t('lesson.answer.priority.conflictOnly') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.base') }}：</span>{{ item.baseRule }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.competing') }}：</span>{{ item.competingRule }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.result') }}：</span>{{ item.resolution }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.timingResolutions?.length" class="mt-3 rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.timing.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.timing.description') }}</p><ul class="mt-2 space-y-2"><li v-for="item in turn.answer.answer.timingResolutions" :key="`${item.timingContext}-${item.resolutionOrder}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-copper">{{ item.basis === 'CURRENT_PLAYER_CHOOSES' ? t('lesson.answer.timing.currentPlayer') : item.basis === 'PRINTED_TOP_TO_BOTTOM' ? t('lesson.answer.timing.printedOrder') : t('lesson.answer.timing.turnOrder') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.context') }}：</span>{{ item.timingContext }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.order') }}：</span>{{ item.resolutionOrder }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.source') }}：</span>{{ item.orderSource }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.tieResolutions?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.tie.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.tie.description') }}</p><ul class="mt-2 space-y-2"><li v-for="item in turn.answer.answer.tieResolutions" :key="`${item.tieContext}-${item.finalOutcome}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-indigo">{{ item.basis === 'SINGLE_TIEBREAKER' ? t('lesson.answer.tie.single') : item.basis === 'ORDERED_TIEBREAKERS' ? t('lesson.answer.tie.ordered') : item.basis === 'RANK_REWARD_SHIFT' ? t('lesson.answer.tie.rankShift') : t('lesson.answer.tie.positional') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.tie.context') }}：</span>{{ item.tieContext }}</p><ol class="mt-1 list-decimal space-y-1 pl-5"><li v-for="step in item.resolutionSteps" :key="step">{{ step }}</li></ol><p><span class="font-semibold text-ink">{{ t('lesson.answer.tie.final') }}：</span>{{ item.finalOutcome }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.scopeResolutions?.length" class="mt-3 rounded-xl border border-emerald-200 bg-emerald-50/60 px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.scope.title') }}</p><p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.scope.description') }}</p><ul class="mt-2 space-y-2"><li v-for="item in turn.answer.answer.scopeResolutions" :key="`${item.ruleContext}-${item.currentSituation}`" class="rounded-lg bg-paper px-3 py-2"><span class="text-xs font-semibold text-emerald-700">{{ item.matchStatus === 'MATCHES_SCOPE' ? t('lesson.answer.scope.matches') : item.matchStatus === 'OUTSIDE_SCOPE' ? t('lesson.answer.scope.outside') : t('lesson.answer.scope.needsContext') }}</span><p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.rule') }}：</span>{{ item.ruleContext }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.condition') }}：</span>{{ item.governingCondition }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.situation') }}：</span>{{ item.currentSituation }}</p><p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.effect') }}：</span>{{ item.effect }}</p></li></ul></div>
                    <div v-if="turn.answer.answer.conceptComparisons?.length" class="mt-3 rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.comparison.title') }}</p><div v-for="item in turn.answer.answer.conceptComparisons" :key="`${item.leftConcept}-${item.rightConcept}`" class="mt-2 rounded-lg bg-paper px-3 py-2"><div class="grid gap-2 sm:grid-cols-2"><p><span class="font-semibold text-indigo">{{ item.leftConcept }}：</span>{{ item.leftDefinition }}</p><p><span class="font-semibold text-indigo">{{ item.rightConcept }}：</span>{{ item.rightDefinition }}</p></div><p class="mt-2"><span class="font-semibold">{{ t('lesson.answer.comparison.keyDifference') }}：</span>{{ item.keyDifference }}</p><p><span class="font-semibold">{{ t('lesson.answer.comparison.boundary') }}：</span>{{ item.practicalBoundary }}</p></div></div>
                    <div v-if="turn.answer.answer.ruleOptions?.length" class="mt-3 rounded-xl border border-copper/20 bg-copper/[0.05] px-3 py-2"><p class="font-semibold text-ink">{{ t('lesson.answer.options.title') }}</p><p class="mt-1 text-xs text-ink/55"><span class="font-semibold">{{ t('lesson.answer.options.selectionRule') }}：</span>{{ turn.answer.answer.ruleOptions[0]?.selectionRule }}</p><ol class="mt-2 grid gap-2 sm:grid-cols-2"><li v-for="(item, optionIndex) in turn.answer.answer.ruleOptions" :key="`${item.optionName}-${optionIndex}`" class="rounded-lg bg-paper px-3 py-2"><p class="font-semibold text-copper">{{ optionIndex + 1 }}. {{ item.optionName }}</p><p><span class="font-semibold">{{ t('lesson.answer.options.availability') }}：</span>{{ item.availabilityCondition }}</p><p><span class="font-semibold">{{ t('lesson.answer.options.result') }}：</span>{{ item.result }}</p></li></ol></div>
                  </div>
                  <p v-else class="mt-3 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{{ answerFailureMessage(turn.answer.answer) }}</p>
                  <div v-if="index === publicAnswerTurns.length - 1 && turn.answer.answer.status === 'CLARIFICATION_REQUIRED'" class="mt-3">
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-11 rounded-xl border border-amber-400 bg-amber-50 px-4 text-sm font-semibold text-amber-950 disabled:opacity-40" @click="preparePublicAnswerReply(turn)">{{ t('public.answer.clarificationAction') }}</button>
                  </div>
                  <div v-else-if="index === publicAnswerTurns.length - 1 && turn.answer.answer.status === 'INSUFFICIENT_EVIDENCE'" class="mt-3 rounded-2xl border border-amber-200 bg-amber-50/60 p-4 text-sm leading-6 text-amber-950">
                    <p>{{ t('public.answer.refineHint') }}</p>
                    <button type="button" :disabled="publicAnswerLoading" class="mt-3 min-h-11 rounded-xl border border-amber-400 bg-paper px-4 font-semibold disabled:opacity-40" @click="preparePublicAnswerReply(turn)">{{ t('public.answer.refineAction') }}</button>
                  </div>
                  <ul v-if="turn.answer.answer.exceptions.length" class="mt-4 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65"><li v-for="exception in turn.answer.answer.exceptions" :key="exception">{{ exception }}</li></ul>

                  <div v-if="index === publicAnswerTurns.length - 1 && publishesConclusion(turn.answer.answer.status)" class="mt-5 flex flex-wrap gap-2 border-t border-ink/10 pt-4" :aria-label="t('lesson.answer.followUps')">
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold text-ink/65 disabled:opacity-40" @click="requestPublicLearningHelp('SIMPLIFY')">{{ t('lesson.answer.intent.simplify') }}</button>
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold text-ink/65 disabled:opacity-40" @click="requestPublicLearningHelp('EXAMPLE')">{{ t('lesson.answer.intent.example') }}</button>
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold text-ink/65 disabled:opacity-40" @click="requestPublicLearningHelp('DEFINE')">{{ t('lesson.answer.intent.define') }}</button>
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold text-ink/65 disabled:opacity-40" @click="requestPublicLearningHelp('VERIFY')">{{ t('lesson.answer.intent.verify') }}</button>
                    <button type="button" :disabled="publicAnswerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold text-ink/65 disabled:opacity-40" @click="requestPublicLearningHelp('SOURCE')">{{ t('lesson.answer.intent.source') }}</button>
                  </div>

                  <div v-if="turn.answer.answer.citations.length" class="mt-5 border-t border-ink/10 pt-4">
                    <p class="text-xs font-semibold uppercase tracking-[0.12em] text-ink/40">{{ t('public.question.evidence') }}</p>
                    <ul class="mt-2 flex flex-wrap gap-2">
                      <li v-for="citation in turn.answer.answer.citations" :key="`${citation.heading}-${citation.pageFrom}`">
                        <a :href="sourcePageUrl(citation.pageFrom)" target="_blank" rel="noopener noreferrer" :aria-label="t('public.question.openEvidence', { heading: citation.heading, page: citationPageLabel(citation) })" class="inline-flex min-h-11 items-center rounded-xl bg-canvas px-3 text-xs font-semibold text-indigo transition hover:bg-indigo/10 focus:outline-none focus:ring-4 focus:ring-indigo/15">{{ citation.heading }} · {{ citationPageLabel(citation) }}</a>
                      </li>
                    </ul>
                  </div>

                  <div v-if="turn.answer.visualAids.length || turn.answer.examples.length" class="mt-5 border-t border-ink/10 pt-4">
                    <p class="text-sm font-semibold text-indigo">{{ t('public.question.withSource') }}</p>
                    <div v-if="turn.answer.visualAids.length" class="mt-3 grid gap-3 sm:grid-cols-2">
                      <a v-for="aid in turn.answer.visualAids" :key="`${aid.visualFocus.pageNumber}-${aid.visualFocus.x}-${aid.visualFocus.y}`" :href="sourcePageUrl(aid.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="overflow-hidden rounded-2xl border border-ink/10 bg-canvas transition hover:border-indigo/35">
                        <img :src="cropUrl(aid.visualFocus)" :alt="t('public.step.openSource', { label: aid.visualFocus.label })" class="aspect-[4/3] w-full object-contain">
                        <span class="block border-t border-ink/10 px-3 py-2">
                          <span class="block text-xs font-semibold text-indigo">{{ t('public.question.aid', { step: aid.relatedStep }) }}</span>
                          <span class="mt-1 block text-xs leading-5 text-ink/60">{{ aid.visualFocus.visibleDescription || aid.visualFocus.label }}</span>
                        </span>
                      </a>
                    </div>
                    <ul v-if="turn.answer.examples.length" class="mt-3 space-y-2"><li v-for="example in turn.answer.examples" :key="`${example.heading}-${example.text}`" class="rounded-2xl bg-copper/[0.07] px-4 py-3"><p class="text-sm font-semibold text-copper">{{ t('public.question.exampleWalkthrough', { heading: example.heading }) }}</p><p class="mt-1 text-sm leading-6 text-ink/70">{{ example.text }}</p><p v-if="example.sourcePages.length" class="mt-2 text-xs text-ink/45">{{ t('public.question.samePages', { pages: example.sourcePages.join(locale === 'en' ? ', ' : '、') }) }}</p></li></ul>
                  </div>
                </div>
              </article>
            </li>
          </ol>

          <form class="mt-5" @submit.prevent="submitPublicQuestion">
            <label for="public-question" class="sr-only">{{ t('public.question.submit') }}</label>
            <textarea id="public-question" v-model="publicQuestion" rows="3" maxlength="800" :disabled="publicAnswerLoading || !readerScopeReady" :placeholder="t('public.question.placeholder')" class="w-full resize-y rounded-2xl border border-ink/15 bg-paper px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-55" />
            <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
              <p class="text-xs text-ink/45">{{ t('public.question.counter', { count: publicQuestion.length }) }}</p>
              <button type="submit" :disabled="publicAnswerLoading || !readerScopeReady || !publicQuestion.trim()" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40">{{ publicAnswerLoading ? t('public.question.loading') : t('public.question.submit') }}</button>
            </div>
          </form>
        </section>

        <section v-for="section in publicLesson.lesson.sections" :key="section.position" class="border-b border-ink/10 py-10">
          <p class="text-sm font-semibold text-copper">{{ t('public.chapter', { position: section.position }) }}</p>
          <h2 class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ section.title }}</h2>
          <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

          <ol class="mt-7 space-y-5">
            <li v-for="step in section.steps" :key="step.position" class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-6">
              <div class="flex gap-4">
                <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-copper/15 text-sm font-bold text-copper">{{ step.position }}</span>
                <div class="min-w-0 flex-1">
                  <h3 class="font-display text-xl font-semibold">{{ step.heading }}</h3>
                  <p class="mt-2 leading-7 text-ink/75">{{ step.text }}</p>
                  <figure v-if="step.visualFocus" class="mt-5 overflow-hidden rounded-2xl border border-indigo/15 bg-canvas">
                    <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-4 py-3">
                      <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
                      <p class="mt-1 text-sm leading-6 text-ink/70">{{ step.visualFocus.visibleDescription || step.visualFocus.label }}</p>
                    </figcaption>
                    <a :href="sourcePageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="block">
                      <img :src="cropUrl(step.visualFocus)" :alt="t('public.step.openSource', { label: step.visualFocus.label })" class="max-h-96 w-full object-contain">
                      <span class="block border-t border-ink/10 px-3 py-2 text-sm font-semibold text-indigo">{{ t('public.step.openSource', { label: step.visualFocus.label }) }}</span>
                    </a>
                  </figure>
                  <p v-if="step.sourcePages.length" class="mt-4 text-sm text-ink/45">{{ t('public.step.source', { pages: step.sourcePages.join(locale === 'en' ? ', ' : '、') }) }}</p>
                </div>
              </div>
            </li>
          </ol>
        </section>
      </article>
    </div>
  </AppShell>
</template>
