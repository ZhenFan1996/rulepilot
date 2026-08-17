<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'

import AgentWorkspaceHeader from '@/components/AgentWorkspaceHeader.vue'
import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import VoiceQuestionCapture from '@/components/VoiceQuestionCapture.vue'
import { useLocale } from '@/lib/locale'
import { playerFacingExplanation, playerFacingWalkthroughSteps } from '@/lib/playerFacingAnswer'
import { playerFacingCitationExcerpt } from '@/lib/playerFacingCitation'
import { playerTurnLocale } from '@/lib/playerTurnLanguage'
import { playerWorkStatus } from '@/lib/playerWorkStatus'
import type {
  AnswerTurn,
  ConfirmedRuling,
  LearningIntent,
  StructuredRuleAnswer,
} from '@/composables/useLessonAnswers'
import type { AnswerAgentTraceItem } from '@/lib/answerAgentTrace'

const props = withDefaults(defineProps<{
  question: string
  answer: StructuredRuleAnswer | null
  answeredQuestion: string
  answerTurns: AnswerTurn[]
  activeLearningIntent: LearningIntent | null
  answerLoading: boolean
  answerError: string
  answerOutcome: 'none' | 'failed' | 'cancelled'
  answerElapsedSeconds?: number
  answerSoftBudgetReached?: boolean
  agentTrace?: AnswerAgentTraceItem[]
  online: boolean
  ruling: ConfirmedRuling | null
  rulingSaving: boolean
  rulingError: string
  rulingConflict: boolean
  editingRuling: boolean
  editedVerdict: string
  editedExplanation: string
  clearThreadDisabled?: boolean
  showHeader?: boolean
}>(), {
  agentTrace: () => [],
  answerElapsedSeconds: 0,
  answerSoftBudgetReached: false,
  clearThreadDisabled: false,
  showHeader: true,
})

const emit = defineEmits<{
  'update:question': [value: string]
  'update:editing-ruling': [value: boolean]
  'update:edited-verdict': [value: string]
  'update:edited-explanation': [value: string]
  ask: []
  cancelAnswer: []
  requestHelp: [intent: LearningIntent]
  openCardOcr: []
  voiceTranscript: [text: string]
  clearThread: []
  confirmRuling: []
  reloadRuling: []
  saveRulingRevision: []
}>()

const questionModel = computed({
  get: () => props.question,
  set: (value: string) => emit('update:question', value),
})
const editedVerdictModel = computed({
  get: () => props.editedVerdict,
  set: (value: string) => emit('update:edited-verdict', value),
})
const editedExplanationModel = computed({
  get: () => props.editedExplanation,
  set: (value: string) => emit('update:edited-explanation', value),
})
const previousAnswerTurns = computed(() => props.answer ? props.answerTurns.slice(0, -1) : props.answerTurns)
const currentAnswerTurn = computed(() => props.answer ? props.answerTurns.at(-1) ?? null : null)
const primaryCitation = computed(() => props.answer?.citations.at(0) ?? null)
const additionalCitations = computed(() => props.answer?.citations.slice(1) ?? [])
const questionInput = ref<HTMLTextAreaElement | null>(null)
defineExpose({
  focusQuestion: () => questionInput.value?.focus({ preventScroll: true }),
})
const resolvedQuestion = ref('')
const answerResolved = computed(() => resolvedQuestion.value === props.answeredQuestion && !!props.answeredQuestion)
const { locale, t } = useLocale()
const answerWorkStatus = computed(() => playerWorkStatus('CHECKING_ANSWER', {
  capability: props.answerTurns.length > 0 ? 'answer' : 'rulebook',
  readiness: props.answerTurns.length > 0 ? 'usable' : 'unavailable',
  terminality: 'active',
  outcome: 'none',
}, locale.value))
const answerErrorStatus = computed(() => playerWorkStatus(
  props.answerOutcome === 'cancelled' ? 'CANCELLED' : 'NEEDS_ACTION',
  {
    capability: props.answerTurns.length > 0 ? 'answer' : 'rulebook',
    readiness: props.answerTurns.length > 0 ? 'usable' : 'unavailable',
    terminality: 'terminal',
    outcome: props.answerOutcome === 'cancelled' ? 'cancelled' : 'failed',
  },
  locale.value,
))
const latestPriorAnswer = computed(() => props.answerTurns.at(-1) ?? null)
const softBudgetCopy = computed(() => {
  const responseLocale = playerTurnLocale(props.question, locale.value)
  const elapsed = responseLocale === 'en'
    ? `${props.answerElapsedSeconds} seconds`
    : `${props.answerElapsedSeconds} 秒`
  if (latestPriorAnswer.value) {
    return responseLocale === 'en'
      ? `The previous verified answer and citations remain above. This question is still checking the rule text and conclusion (${elapsed}); it will not replace them before the full check passes.`
      : `上一条已核对答案和引用仍保留在上方；当前问题还在核对原文与结论（${elapsed}）。通过完整性检查前不会替换它。`
  }
  return responseLocale === 'en'
    ? `${elapsed}: there is not yet enough verified evidence to show. The rule text and conclusion are still being checked; unfinished text will not appear as an answer.`
    : `${elapsed}：目前还没有足以显示的已核对引用；仍在核对原文与结论，未完成文字不会显示成答案。`
})

async function focusQuestionForMoreDetail() {
  await nextTick()
  questionInput.value?.focus()
}

async function prepareFeedbackFollowUp(intent: 'SIMPLIFY' | 'VERIFY') {
  emit('requestHelp', intent)
  await focusQuestionForMoreDetail()
}

async function prepareRecoveryReply() {
  const draft = props.answer?.recovery?.draft?.trim() ?? ''
  if (draft && (!props.question.trim() || props.question.trim() === props.answeredQuestion.trim())) {
    emit('update:question', draft)
  }
  await focusQuestionForMoreDetail()
}

async function prepareSituationReply(requirement: string) {
  emit('update:question', t('lesson.answer.situation.replyPrefix', { requirement }))
  await focusQuestionForMoreDetail()
}

function situationStatusLabel(status: NonNullable<StructuredRuleAnswer['situationChecks']>[number]['status']) {
  if (status === 'CONFIRMED') return t('lesson.answer.situation.confirmed')
  if (status === 'CONTRADICTED') return t('lesson.answer.situation.contradicted')
  return t('lesson.answer.situation.notProvided')
}

function situationStatusClasses(status: NonNullable<StructuredRuleAnswer['situationChecks']>[number]['status']) {
  if (status === 'CONFIRMED') return 'bg-emerald-50 text-emerald-700'
  if (status === 'CONTRADICTED') return 'bg-red-50 text-red-700'
  return 'bg-amber-50 text-amber-800'
}

function walkthroughBasisLabel(basis: NonNullable<StructuredRuleAnswer['walkthroughSteps']>[number]['orderBasis']) {
  return basis === 'RULE_ORDER'
    ? t('lesson.answer.walkthrough.ruleOrder')
    : t('lesson.answer.walkthrough.explanationOrder')
}

function decisionBasisLabel(basis: NonNullable<StructuredRuleAnswer['decisionBranches']>[number]['basis']) {
  return basis === 'EXPLICIT_RULE'
    ? t('lesson.answer.decision.explicitRule')
    : t('lesson.answer.decision.rulebookExample')
}

function workedExampleBasisLabel(basis: NonNullable<StructuredRuleAnswer['workedExamples']>[number]['basis']) {
  return basis === 'RULEBOOK_EXAMPLE'
    ? t('lesson.answer.example.rulebook')
    : t('lesson.answer.example.illustration')
}

function priorityBasisLabel(basis: NonNullable<StructuredRuleAnswer['priorityResolutions']>[number]['basis']) {
  if (basis === 'EXPLICIT_OVERRIDE') return t('lesson.answer.priority.explicit')
  if (basis === 'IMPOSSIBILITY_PRIORITY') return t('lesson.answer.priority.impossible')
  return t('lesson.answer.priority.conflictOnly')
}

function timingBasisLabel(basis: NonNullable<StructuredRuleAnswer['timingResolutions']>[number]['basis']) {
  if (basis === 'CURRENT_PLAYER_CHOOSES') return t('lesson.answer.timing.currentPlayer')
  if (basis === 'PRINTED_TOP_TO_BOTTOM') return t('lesson.answer.timing.printedOrder')
  return t('lesson.answer.timing.turnOrder')
}

function tieBasisLabel(basis: NonNullable<StructuredRuleAnswer['tieResolutions']>[number]['basis']) {
  if (basis === 'SINGLE_TIEBREAKER') return t('lesson.answer.tie.single')
  if (basis === 'ORDERED_TIEBREAKERS') return t('lesson.answer.tie.ordered')
  if (basis === 'RANK_REWARD_SHIFT') return t('lesson.answer.tie.rankShift')
  return t('lesson.answer.tie.positional')
}

function scopeBasisLabel(basis: NonNullable<StructuredRuleAnswer['scopeResolutions']>[number]['basis']) {
  if (basis === 'PLAYER_COUNT') return t('lesson.answer.scope.playerCount')
  if (basis === 'ROLE_PRESENCE') return t('lesson.answer.scope.rolePresence')
  if (basis === 'GAME_MODE') return t('lesson.answer.scope.gameMode')
  if (basis === 'VARIANT_SELECTION') return t('lesson.answer.scope.variant')
  return t('lesson.answer.scope.playerCountException')
}

function scopeStatusLabel(status: NonNullable<StructuredRuleAnswer['scopeResolutions']>[number]['matchStatus']) {
  if (status === 'MATCHES_SCOPE') return t('lesson.answer.scope.matches')
  if (status === 'OUTSIDE_SCOPE') return t('lesson.answer.scope.outside')
  return t('lesson.answer.scope.needsContext')
}

function comparisonBasisLabel(basis: NonNullable<StructuredRuleAnswer['conceptComparisons']>[number]['basis']) {
  if (basis === 'ACTION_WINDOW') return t('lesson.answer.comparison.actionWindow')
  if (basis === 'RESOURCE_FUNCTION') return t('lesson.answer.comparison.resourceFunction')
  if (basis === 'STORAGE_STATUS') return t('lesson.answer.comparison.storageStatus')
  if (basis === 'RULE_SCOPE') return t('lesson.answer.comparison.ruleScope')
  return t('lesson.answer.comparison.definitionBoundary')
}

function optionBasisLabel(basis: NonNullable<StructuredRuleAnswer['ruleOptions']>[number]['basis']) {
  if (basis === 'SOURCE_SELECTION') return t('lesson.answer.options.sourceSelection')
  if (basis === 'TIMING_CATALOG') return t('lesson.answer.options.timingCatalog')
  if (basis === 'ALTERNATIVE_ACTION') return t('lesson.answer.options.alternativeAction')
  return t('lesson.answer.options.exclusiveChoice')
}

function markAnswerResolved() {
  resolvedQuestion.value = props.answeredQuestion
}

function learningIntentLabel(intent: LearningIntent | null) {
  if (intent === null) return t('lesson.answer.eyebrow')
  if (intent === 'SIMPLIFY') return t('lesson.answer.intent.simplify')
  if (intent === 'EXAMPLE') return t('lesson.answer.intent.example')
  if (intent === 'DEFINE') return t('lesson.answer.intent.define')
  if (intent === 'WHY') return t('lesson.answer.intent.why')
  if (intent === 'SOURCE') return t('lesson.answer.intent.source')
  if (intent === 'VERIFY') return t('lesson.answer.intent.verify')
  return t('lesson.answer.intent.exceptions')
}

function confidenceLabel(confidence: StructuredRuleAnswer['confidence']) {
  if (confidence === 'HIGH') return t('public.answer.high')
  if (confidence === 'MEDIUM') return t('public.answer.medium')
  return t('public.answer.low')
}

function confidenceClasses(confidence: StructuredRuleAnswer['confidence']) {
  if (confidence === 'HIGH') return 'bg-emerald-50 text-emerald-700'
  if (confidence === 'MEDIUM') return 'bg-amber-50 text-amber-800'
  return 'bg-red-50 text-red-700'
}

function answerBasisLabel(answerBasis: StructuredRuleAnswer['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION' ? t('public.answer.groundedBasis') : t('public.answer.directBasis')
}

function citationPages(citation: StructuredRuleAnswer['citations'][number]) {
  return citation.pageFrom === citation.pageTo
    ? t('lesson.answer.pageSingle', { page: citation.pageFrom })
    : t('lesson.answer.pageRange', { from: citation.pageFrom, to: citation.pageTo })
}

function answerFailureMessage(status: StructuredRuleAnswer['status']) {
  return {
    ANSWERED: '',
    ANSWERED_WITH_WARNING: '',
    CLARIFICATION_REQUIRED: '',
    INSUFFICIENT_EVIDENCE: t('lesson.answer.failure.insufficient'),
    MODEL_TIMEOUT: t('public.answer.timeout'),
    INVALID_MODEL_OUTPUT: t('lesson.answer.failure.invalid'),
    VERSION_CONFLICT: t('lesson.answer.failure.version'),
  }[status]
}

function publishesConclusion(status: StructuredRuleAnswer['status']) {
  return status === 'ANSWERED' || status === 'ANSWERED_WITH_WARNING'
}

function warningMessage(warning: StructuredRuleAnswer['warnings'][number]) {
  return t(`lesson.answer.warning.${warning.type}` as const)
}

function hasStructuredAnswerDetails(answer: StructuredRuleAnswer) {
  return !!(
    answer.calculations?.length
    || answer.situationChecks?.length
    || playerFacingWalkthroughSteps(answer).length
    || answer.decisionBranches?.length
    || answer.exceptionClauses?.length
    || answer.termDefinitions?.length
    || answer.workedExamples?.length
    || answer.priorityResolutions?.length
    || answer.timingResolutions?.length
    || answer.tieResolutions?.length
    || answer.scopeResolutions?.length
    || answer.conceptComparisons?.length
    || answer.ruleOptions?.length
    || answer.exceptions.length
  )
}
</script>

<template>
  <section id="lesson-question-panel" class="mt-8 scroll-mt-6" aria-labelledby="lesson-question-title">
    <div class="tabletop-panel player-board p-4 sm:p-7">
      <AgentWorkspaceHeader
        v-if="showHeader"
        :eyebrow="t('lesson.answer.eyebrow')"
        :title="t('lesson.answer.title')"
        :description="t('lesson.answer.description')"
        :status="online ? t('lesson.answer.agentReady') : ''"
      />

      <div v-if="answerTurns.length" class="mt-5 flex flex-wrap items-center justify-between gap-2">
        <p class="text-xs leading-5 text-ink/45">{{ t('lesson.answer.threadPrivacy') }}</p>
        <button type="button" :disabled="answerLoading || clearThreadDisabled" class="min-h-11 rounded-xl px-3 text-sm font-semibold text-ink/55 hover:bg-ink/5 disabled:opacity-40" @click="emit('clearThread')">{{ t('lesson.answer.clearThread') }}</button>
      </div>

      <ol v-if="previousAnswerTurns.length" class="mt-3 stack-y-md" :aria-label="t('lesson.answer.thread')">
        <li v-for="(turn, index) in previousAnswerTurns" :key="`${index}-${turn.question}`" class="rounded-2xl border border-ink/8 bg-canvas p-4">
          <div class="flex flex-wrap items-center gap-2">
            <p class="text-xs font-semibold text-ink/45">{{ turn.learningIntent ? learningIntentLabel(turn.learningIntent) : t('lesson.answer.youAsked') }}</p>
            <span v-if="turn.answer.status === 'ANSWERED_WITH_WARNING'" class="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-semibold text-amber-800">{{ t('lesson.answer.warning.badge') }}</span>
          </div>
          <p class="mt-1 text-sm leading-6">{{ turn.question }}</p>
          <p class="mt-3 border-l-2 border-copper pl-3 text-sm font-semibold leading-6">{{ turn.answer.shortVerdict }}</p>
          <details
            v-if="turn.answer.explanation || turn.answer.clarification || turn.answer.exceptions.length || turn.answer.exceptionClauses?.length || turn.answer.termDefinitions?.length || turn.answer.workedExamples?.length || turn.answer.priorityResolutions?.length || turn.answer.timingResolutions?.length || turn.answer.tieResolutions?.length || turn.answer.scopeResolutions?.length || turn.answer.conceptComparisons?.length || turn.answer.ruleOptions?.length || turn.answer.citations.length || turn.answer.warnings.length || turn.answer.calculations?.length || turn.answer.situationChecks?.length || turn.answer.walkthroughSteps?.length || turn.answer.decisionBranches?.length"
            class="mt-3 border-t border-ink/8 pt-3"
          >
            <summary class="cursor-pointer text-sm font-semibold text-indigo">{{ t('lesson.answer.history.open') }}</summary>
            <div class="mt-3 stack-y-md text-sm leading-6 text-ink/65">
              <p v-if="turn.answer.clarification" class="rounded-xl bg-amber-50 px-3 py-2 text-amber-950">{{ turn.answer.clarification }}</p>
              <p v-else-if="playerFacingExplanation(turn.answer)">{{ playerFacingExplanation(turn.answer) }}</p>
              <div v-if="turn.answer.calculations?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.calculationTitle') }}</p>
                <ul class="mt-1 stack-y-xs font-mono text-xs text-indigo">
                  <li v-for="calculation in turn.answer.calculations" :key="`${calculation.expression}-${calculation.result}`">{{ calculation.expression }} = {{ calculation.result }}</li>
                </ul>
              </div>
              <div v-if="turn.answer.situationChecks?.length" class="rounded-xl border border-ink/10 bg-paper px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.situation.title') }}</p>
                <ul class="mt-2 stack-y-sm">
                  <li v-for="check in turn.answer.situationChecks" :key="`${check.requirement}-${check.status}`">
                    <span :class="situationStatusClasses(check.status)" class="rounded-full px-2 py-0.5 text-xs font-semibold">{{ situationStatusLabel(check.status) }}</span>
                    <span class="ml-2">{{ check.requirement }}</span>
                    <p v-if="check.playerFact" class="mt-1 text-xs text-ink/50">{{ check.playerFact }}</p>
                  </li>
                </ul>
              </div>
              <div v-if="playerFacingWalkthroughSteps(turn.answer).length" class="rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.walkthrough.title') }}</p>
                <ol class="mt-2 stack-y-sm">
                  <li v-for="(step, stepIndex) in playerFacingWalkthroughSteps(turn.answer)" :key="`${stepIndex}-${step.instruction}`" class="flex gap-2">
                    <span class="font-semibold text-copper">{{ stepIndex + 1 }}.</span>
                    <div><p class="font-medium text-ink">{{ step.instruction }}</p><p class="text-xs text-ink/50">{{ step.explanation }}</p></div>
                  </li>
                </ol>
              </div>
              <div v-if="turn.answer.decisionBranches?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.decision.title') }}</p>
                <ul class="mt-2 stack-y-sm">
                  <li v-for="branch in turn.answer.decisionBranches" :key="`${branch.condition}-${branch.outcome}`" class="rounded-lg bg-canvas px-3 py-2">
                    <span class="rounded-full bg-indigo/10 px-2 py-0.5 text-[11px] font-semibold text-indigo">{{ decisionBasisLabel(branch.basis) }}</span>
                    <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：</span>{{ branch.condition }}</p>
                    <p class="text-ink/60">→ {{ branch.outcome }}</p>
                  </li>
                </ul>
              </div>
              <div v-if="turn.answer.exceptionClauses?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.exception.title') }}</p>
                <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.exception.description') }}</p>
                <ul class="mt-2 stack-y-sm">
                  <li v-for="clause in turn.answer.exceptionClauses" :key="`${clause.condition}-${clause.effect}`" class="rounded-lg bg-canvas px-3 py-2">
                    <p><span class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：</span>{{ clause.condition }}</p>
                    <p class="text-ink/60">→ {{ clause.effect }}</p>
                  </li>
                </ul>
              </div>
              <div v-if="turn.answer.termDefinitions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.definition.title') }}</p>
                <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.definition.description') }}</p>
                <dl class="mt-2 stack-y-sm">
                  <div v-for="definition in turn.answer.termDefinitions" :key="definition.term" class="rounded-lg bg-canvas px-3 py-2">
                    <dt class="font-semibold text-indigo">{{ definition.term }}</dt><dd>{{ definition.definition }}</dd>
                    <dd v-if="definition.boundary" class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.definition.boundary') }}：{{ definition.boundary }}</dd>
                  </div>
                </dl>
              </div>
              <div v-if="turn.answer.workedExamples?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.example.title') }}</p>
                <div v-for="example in turn.answer.workedExamples" :key="`${example.setup}-${example.outcome}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-copper">{{ workedExampleBasisLabel(example.basis) }}</span>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.example.setup') }}：</span>{{ example.setup }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.example.action') }}：</span>{{ example.action }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.example.outcome') }}：</span>{{ example.outcome }}</p>
                </div>
              </div>
              <div v-if="turn.answer.priorityResolutions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.priority.title') }}</p>
                <div v-for="item in turn.answer.priorityResolutions" :key="`${item.baseRule}-${item.competingRule}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-indigo">{{ priorityBasisLabel(item.basis) }}</span>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.base') }}：</span>{{ item.baseRule }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.competing') }}：</span>{{ item.competingRule }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.priority.result') }}：</span>{{ item.resolution }}</p>
                </div>
              </div>
              <div v-if="turn.answer.timingResolutions?.length" class="rounded-xl border border-copper/20 bg-copper/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.timing.title') }}</p>
                <div v-for="item in turn.answer.timingResolutions" :key="`${item.timingContext}-${item.resolutionOrder}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-copper">{{ timingBasisLabel(item.basis) }}</span>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.context') }}：</span>{{ item.timingContext }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.order') }}：</span>{{ item.resolutionOrder }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.timing.source') }}：</span>{{ item.orderSource }}</p>
                </div>
              </div>
              <div v-if="turn.answer.tieResolutions?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.tie.title') }}</p>
                <div v-for="item in turn.answer.tieResolutions" :key="`${item.tieContext}-${item.finalOutcome}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-indigo">{{ tieBasisLabel(item.basis) }}</span>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.tie.context') }}：</span>{{ item.tieContext }}</p>
                  <ol class="mt-1 list-decimal stack-y-xs pl-5"><li v-for="step in item.resolutionSteps" :key="step">{{ step }}</li></ol>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.tie.final') }}：</span>{{ item.finalOutcome }}</p>
                </div>
              </div>
              <div v-if="turn.answer.scopeResolutions?.length" class="rounded-xl border border-emerald-200 bg-emerald-50/60 px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.scope.title') }}</p>
                <div v-for="item in turn.answer.scopeResolutions" :key="`${item.ruleContext}-${item.currentSituation}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-emerald-700">{{ scopeStatusLabel(item.matchStatus) }} · {{ scopeBasisLabel(item.basis) }}</span>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.rule') }}：</span>{{ item.ruleContext }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.condition') }}：</span>{{ item.governingCondition }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.situation') }}：</span>{{ item.currentSituation }}</p>
                  <p><span class="font-semibold text-ink">{{ t('lesson.answer.scope.effect') }}：</span>{{ item.effect }}</p>
                </div>
              </div>
              <div v-if="turn.answer.conceptComparisons?.length" class="rounded-xl border border-indigo/15 bg-indigo/[0.04] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.comparison.title') }}</p>
                <div v-for="item in turn.answer.conceptComparisons" :key="`${item.leftConcept}-${item.rightConcept}`" class="mt-2 rounded-lg bg-canvas px-3 py-2">
                  <span class="text-xs font-semibold text-indigo">{{ comparisonBasisLabel(item.basis) }}</span>
                  <div class="mt-2 grid gap-2 sm:grid-cols-2"><p><span class="font-semibold text-ink">{{ item.leftConcept }}：</span>{{ item.leftDefinition }}</p><p><span class="font-semibold text-ink">{{ item.rightConcept }}：</span>{{ item.rightDefinition }}</p></div>
                  <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.common') }}：</span>{{ item.commonGround }}</p>
                  <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.keyDifference') }}：</span>{{ item.keyDifference }}</p>
                  <p class="text-ink/60"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.boundary') }}：</span>{{ item.practicalBoundary }}</p>
                </div>
              </div>
              <div v-if="turn.answer.ruleOptions?.length" class="rounded-xl border border-copper/20 bg-copper/[0.05] px-3 py-2">
                <p class="font-semibold text-ink">{{ t('lesson.answer.options.title') }}</p>
                <p class="mt-1 text-xs text-ink/55"><span class="font-semibold">{{ t('lesson.answer.options.selectionRule') }}：</span>{{ turn.answer.ruleOptions[0]?.selectionRule }}</p>
                <ol class="mt-2 stack-y-sm">
                  <li v-for="(item, optionIndex) in turn.answer.ruleOptions" :key="`${item.optionName}-${optionIndex}`" class="rounded-lg bg-canvas px-3 py-2">
                    <span class="text-xs font-semibold text-copper">{{ optionBasisLabel(item.basis) }}</span>
                    <p class="font-semibold text-ink">{{ optionIndex + 1 }}. {{ item.optionName }}</p>
                    <p><span class="font-semibold text-ink">{{ t('lesson.answer.options.availability') }}：</span>{{ item.availabilityCondition }}</p>
                    <p><span class="font-semibold text-ink">{{ t('lesson.answer.options.result') }}：</span>{{ item.result }}</p>
                  </li>
                </ol>
              </div>
              <div v-if="turn.answer.warnings.length" class="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-amber-950">
                <p class="font-semibold">{{ t('lesson.answer.warning.title') }}</p>
                <ul class="mt-1 list-disc stack-y-xs pl-5">
                  <li v-for="warning in turn.answer.warnings" :key="warning.type">{{ warningMessage(warning) }}</li>
                </ul>
              </div>
              <div v-if="turn.answer.exceptions.length">
                <p class="font-semibold text-ink">{{ t('lesson.answer.intent.exceptions') }}</p>
                <ul class="mt-1 list-disc stack-y-xs pl-5">
                  <li v-for="exception in turn.answer.exceptions" :key="exception">{{ exception }}</li>
                </ul>
              </div>
              <div v-if="turn.answer.citations.length">
                <p class="font-semibold text-ink">{{ t('lesson.answer.history.sources') }}</p>
                <ol class="mt-2 stack-y-sm">
                  <li v-for="(citation, citationIndex) in turn.answer.citations" :key="`${citation.heading}-${citation.pageFrom}-${citation.pageTo}-${citationIndex}`" class="rounded-xl bg-paper px-3 py-2">
                    <p class="font-semibold text-indigo">{{ citation.heading }} · {{ citationPages(citation) }}</p>
                    <p class="mt-1 text-xs leading-5 text-ink/55">{{ playerFacingCitationExcerpt(citation.excerpt) }}</p>
                  </li>
                </ol>
              </div>
            </div>
          </details>
        </li>
      </ol>

      <div
        class="mt-5 gap-5"
        :class="answer ? 'grid lg:grid-cols-[minmax(17rem,0.68fr)_minmax(0,1.32fr)] lg:items-start lg:gap-6' : 'mx-auto max-w-3xl'"
      >
        <div class="min-w-0 lg:sticky lg:top-24">
          <form class="rounded-2xl border border-ink/10 bg-canvas p-4" @submit.prevent="emit('ask')">
            <div class="mb-3 flex flex-wrap items-start gap-3">
              <button
                type="button"
                class="min-h-11 rounded-xl border border-indigo/25 bg-indigo/5 px-4 text-sm font-semibold text-indigo transition hover:bg-indigo/10 disabled:cursor-not-allowed disabled:opacity-40"
                :disabled="answerLoading || !online"
                @click="emit('openCardOcr')"
              >
                {{ t('lesson.answer.cardOcr') }}
              </button>
              <VoiceQuestionCapture :disabled="answerLoading || !online" @transcript="emit('voiceTranscript', $event)" />
            </div>
            <label for="lesson-question" class="sr-only">{{ t('lesson.answer.questionLabel') }}</label>
            <textarea
              id="lesson-question"
              ref="questionInput"
              v-model="questionModel"
              rows="3"
              :disabled="answerLoading || !online"
              :placeholder="t('lesson.answer.placeholder')"
              class="w-full resize-y rounded-2xl border border-ink/15 bg-canvas px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:cursor-not-allowed disabled:opacity-55"
            />
            <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
              <p class="text-xs text-ink/45">{{ t('lesson.answer.counter', { count: question.length }) }}</p>
              <button
                type="submit"
                :disabled="answerLoading || !online || !question.trim()"
                class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {{ answerLoading ? t('lesson.answer.loading') : online ? t('lesson.answer.submit') : t('lesson.answer.offline') }}
              </button>
            </div>
          </form>

          <div
            v-if="answerError"
            class="mt-4 rounded-2xl px-4 py-3"
            :class="answerOutcome === 'cancelled' ? 'bg-amber-50 text-amber-900' : 'bg-red-50 text-red-700'"
            :role="answerOutcome === 'cancelled' ? 'status' : 'alert'"
          >
            <PlayerWorkStatusText
              :status="answerErrorStatus"
              class="text-sm font-semibold"
            />
            <p class="mt-1 text-xs leading-5">{{ answerError }}</p>
          </div>
          <div v-else-if="answerLoading" class="mt-5 stack-y-md rounded-2xl border border-ink/8 p-5" aria-live="polite">
            <div class="flex items-center gap-3">
              <span class="size-3 animate-pulse rounded-full bg-indigo" aria-hidden="true" />
              <div>
                <PlayerWorkStatusText
                  :status="answerWorkStatus"
                  class="text-sm font-semibold"
                />
                <p class="mt-0.5 text-xs leading-5 text-ink/55">{{ activeLearningIntent ? t('lesson.answer.workingIntent', { intent: learningIntentLabel(activeLearningIntent) }) : t('lesson.answer.working') }}</p>
              </div>
            </div>
            <p v-if="answerSoftBudgetReached" data-testid="answer-soft-budget" class="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-950">{{ softBudgetCopy }}</p>
            <ol v-if="agentTrace.length" class="stack-y-sm text-xs leading-5 text-ink/60" :aria-label="t('lesson.answer.agentTrace')">
              <li v-for="item in agentTrace" :key="item.sequence" class="flex items-start gap-2">
                <span :class="item.status === 'running' ? 'animate-pulse bg-copper' : item.status === 'done' ? 'bg-emerald-500' : 'bg-amber-500'" class="mt-1.5 size-2 shrink-0 rounded-full" aria-hidden="true" />
                <span>{{ item.label }}</span>
              </li>
            </ol>
            <p v-else class="rounded-xl bg-paper px-3 py-2 text-xs leading-5 text-ink/55">{{ t('lesson.answer.waitingForTrace') }}</p>
            <p class="text-xs leading-5 text-ink/50">{{ t('lesson.answer.foreignLanguage') }}</p>
            <div class="h-4 w-4/5 animate-pulse rounded bg-ink/10" />
            <div class="h-4 w-3/5 animate-pulse rounded bg-ink/10" />
            <button type="button" class="min-h-11 justify-self-start rounded-xl border border-ink/15 bg-canvas px-4 text-sm font-semibold text-ink/65 hover:bg-paper" @click="emit('cancelAnswer')">{{ t('lesson.answer.cancel') }}</button>
          </div>
        </div>
        <div v-if="answer" class="min-w-0">
          <article v-if="answer" class="overflow-hidden rounded-3xl border border-ink/10 bg-canvas" aria-live="polite">
            <div class="p-5 sm:p-6">
              <p class="text-xs font-semibold text-ink/45">{{ currentAnswerTurn?.learningIntent ? learningIntentLabel(currentAnswerTurn.learningIntent) : t('lesson.answer.youAsked') }}：{{ answeredQuestion }}</p>
              <div v-if="publishesConclusion(answer.status)" class="flex flex-wrap items-center gap-2 text-xs font-semibold">
                <span :class="confidenceClasses(answer.confidence)" :data-confidence="answer.confidence" class="rounded-full px-3 py-1.5">{{ confidenceLabel(answer.confidence) }}</span>
                <span class="rounded-full bg-copper/[0.12] px-3 py-1.5 text-copper">{{ answerBasisLabel(answer.answerBasis) }}</span>
                <span class="rounded-full bg-ink/6 px-3 py-1.5 text-ink/60">{{ answer.source === 'CONFIRMED' ? t('lesson.answer.source.confirmed') : answer.source === 'OFFICIAL' ? t('lesson.answer.source.official') : t('lesson.answer.source.uploaded') }}</span>
              </div>
              <p class="mt-4 font-display text-xl font-semibold leading-8">{{ answer.shortVerdict }}</p>
              <p v-if="publishesConclusion(answer.status) && playerFacingExplanation(answer)" class="mt-3 text-sm leading-7 text-ink/70">{{ playerFacingExplanation(answer) }}</p>

              <div v-if="!publishesConclusion(answer.status)" class="mt-4 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">
                <p>{{ answer.recovery?.message || answer.clarification || answerFailureMessage(answer.status) }}</p>
                <button v-if="answer.recovery" type="button" class="mt-3 min-h-10 rounded-xl border border-amber-400 bg-paper px-3 font-semibold" @click="prepareRecoveryReply">{{ answer.recovery.actionLabel }}</button>
              </div>

              <div v-if="answer.warnings.length" class="mt-4 rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
                <p class="font-semibold">{{ t('lesson.answer.warning.title') }}</p>
                <ul class="mt-1 list-disc stack-y-xs pl-5">
                  <li v-for="warning in answer.warnings" :key="warning.type">{{ warningMessage(warning) }}</li>
                </ul>
              </div>

              <div v-if="publishesConclusion(answer.status) && hasStructuredAnswerDetails(answer)" class="mt-5 border-t border-ink/10 pt-4">
                <ol class="stack-y-md text-sm leading-6 text-ink/70">
                  <li v-if="answer.calculations?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <span class="font-semibold text-ink">{{ t('lesson.answer.calculationTitle') }}：</span>
                    <span class="ml-1 text-xs text-ink/50">{{ t('lesson.answer.calculationDescription') }}</span>
                    <ul class="mt-2 stack-y-xs font-mono text-sm text-indigo">
                      <li v-for="calculation in answer.calculations" :key="`${calculation.expression}-${calculation.result}`">{{ calculation.expression }} = {{ calculation.result }}</li>
                    </ul>
                  </li>
                  <li v-if="answer.situationChecks?.length" class="rounded-2xl border border-ink/10 bg-paper p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.situation.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.situation.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="check in answer.situationChecks" :key="`${check.requirement}-${check.status}`" class="rounded-xl bg-canvas px-3 py-2">
                        <div class="flex flex-wrap items-center gap-2">
                          <span :class="situationStatusClasses(check.status)" class="rounded-full px-2 py-0.5 text-xs font-semibold">{{ situationStatusLabel(check.status) }}</span>
                          <span class="font-medium text-ink">{{ check.requirement }}</span>
                        </div>
                        <p v-if="check.playerFact" class="mt-1 text-xs text-ink/55">{{ t('lesson.answer.situation.playerFact') }}：{{ check.playerFact }}</p>
                        <button v-else type="button" class="mt-2 min-h-9 rounded-lg border border-amber-300 bg-amber-50 px-3 text-xs font-semibold text-amber-900" @click="prepareSituationReply(check.requirement)">{{ t('lesson.answer.situation.addFact') }}</button>
                      </li>
                    </ul>
                  </li>
                  <li v-if="playerFacingWalkthroughSteps(answer).length" class="rounded-2xl border border-copper/20 bg-copper/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.walkthrough.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.walkthrough.description') }}</p>
                    <ol class="mt-3 stack-y-md">
                      <li v-for="(step, stepIndex) in playerFacingWalkthroughSteps(answer)" :key="`${stepIndex}-${step.instruction}`" class="flex gap-3 rounded-xl bg-canvas px-3 py-3">
                        <span class="flex size-7 shrink-0 items-center justify-center rounded-full bg-copper/15 text-sm font-bold text-copper">{{ stepIndex + 1 }}</span>
                        <div>
                          <div class="flex flex-wrap items-center gap-2"><p class="font-semibold text-ink">{{ step.instruction }}</p><span class="rounded-full bg-ink/6 px-2 py-0.5 text-[11px] font-semibold text-ink/55">{{ walkthroughBasisLabel(step.orderBasis) }}</span></div>
                          <p class="mt-1 text-sm text-ink/60">{{ step.explanation }}</p>
                        </div>
                      </li>
                    </ol>
                  </li>
                  <li v-if="answer.decisionBranches?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.decision.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.decision.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="branch in answer.decisionBranches" :key="`${branch.condition}-${branch.outcome}`" class="rounded-xl bg-canvas px-3 py-3">
                        <div class="flex flex-wrap items-center gap-2"><span class="rounded-full bg-indigo/10 px-2 py-0.5 text-[11px] font-semibold text-indigo">{{ decisionBasisLabel(branch.basis) }}</span><p class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：{{ branch.condition }}</p></div>
                        <p class="mt-2 border-l-2 border-indigo/30 pl-3 text-sm text-ink/65">{{ branch.outcome }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.exceptionClauses?.length" class="rounded-2xl border border-copper/20 bg-copper/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.exception.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.exception.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="clause in answer.exceptionClauses" :key="`${clause.condition}-${clause.effect}`" class="rounded-xl bg-canvas px-3 py-3">
                        <p class="font-semibold text-ink">{{ t('lesson.answer.decision.when') }}：{{ clause.condition }}</p>
                        <p class="mt-2 border-l-2 border-copper/30 pl-3 text-sm text-ink/65">{{ clause.effect }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.termDefinitions?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.definition.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.definition.description') }}</p>
                    <dl class="mt-3 stack-y-md">
                      <div v-for="definition in answer.termDefinitions" :key="definition.term" class="rounded-xl bg-canvas px-3 py-3">
                        <dt class="font-semibold text-indigo">{{ definition.term }}</dt><dd class="mt-1 text-sm text-ink/70">{{ definition.definition }}</dd>
                        <dd v-if="definition.boundary" class="mt-2 border-l-2 border-indigo/30 pl-3 text-xs text-ink/55">{{ t('lesson.answer.definition.boundary') }}：{{ definition.boundary }}</dd>
                      </div>
                    </dl>
                  </li>
                  <li v-if="answer.workedExamples?.length" class="rounded-2xl border border-copper/20 bg-copper/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.example.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.example.description') }}</p>
                    <ol class="mt-3 stack-y-md">
                      <li v-for="(example, exampleIndex) in answer.workedExamples" :key="`${exampleIndex}-${example.setup}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-copper/10 px-2 py-0.5 text-[11px] font-semibold text-copper">{{ workedExampleBasisLabel(example.basis) }}</span>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.example.setup') }}：</span>{{ example.setup }}</p>
                        <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.example.action') }}：</span>{{ example.action }}</p>
                        <p class="mt-1 border-l-2 border-copper/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.example.outcome') }}：</span>{{ example.outcome }}</p>
                      </li>
                    </ol>
                  </li>
                  <li v-if="answer.priorityResolutions?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.priority.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.priority.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="item in answer.priorityResolutions" :key="`${item.baseRule}-${item.competingRule}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-indigo/10 px-2 py-0.5 text-[11px] font-semibold text-indigo">{{ priorityBasisLabel(item.basis) }}</span>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.priority.base') }}：</span>{{ item.baseRule }}</p>
                        <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.priority.competing') }}：</span>{{ item.competingRule }}</p>
                        <p class="mt-2 border-l-2 border-indigo/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.priority.result') }}：</span>{{ item.resolution }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.timingResolutions?.length" class="rounded-2xl border border-copper/20 bg-copper/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.timing.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.timing.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="item in answer.timingResolutions" :key="`${item.timingContext}-${item.resolutionOrder}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-copper/10 px-2 py-0.5 text-[11px] font-semibold text-copper">{{ timingBasisLabel(item.basis) }}</span>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.timing.context') }}：</span>{{ item.timingContext }}</p>
                        <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.timing.order') }}：</span>{{ item.resolutionOrder }}</p>
                        <p class="mt-2 border-l-2 border-copper/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.timing.source') }}：</span>{{ item.orderSource }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.tieResolutions?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.tie.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.tie.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="item in answer.tieResolutions" :key="`${item.tieContext}-${item.finalOutcome}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-indigo/10 px-2 py-0.5 text-[11px] font-semibold text-indigo">{{ tieBasisLabel(item.basis) }}</span>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.tie.context') }}：</span>{{ item.tieContext }}</p>
                        <ol class="mt-2 list-decimal stack-y-xs pl-5"><li v-for="step in item.resolutionSteps" :key="step">{{ step }}</li></ol>
                        <p class="mt-2 border-l-2 border-indigo/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.tie.final') }}：</span>{{ item.finalOutcome }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.scopeResolutions?.length" class="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.scope.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.scope.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="item in answer.scopeResolutions" :key="`${item.ruleContext}-${item.currentSituation}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] font-semibold text-emerald-700">{{ scopeStatusLabel(item.matchStatus) }} · {{ scopeBasisLabel(item.basis) }}</span>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.scope.rule') }}：</span>{{ item.ruleContext }}</p>
                        <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.scope.condition') }}：</span>{{ item.governingCondition }}</p>
                        <p class="mt-1"><span class="font-semibold text-ink">{{ t('lesson.answer.scope.situation') }}：</span>{{ item.currentSituation }}</p>
                        <p class="mt-2 border-l-2 border-emerald-300 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.scope.effect') }}：</span>{{ item.effect }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.conceptComparisons?.length" class="rounded-2xl border border-indigo/15 bg-indigo/[0.04] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.comparison.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.comparison.description') }}</p>
                    <ul class="mt-3 stack-y-md">
                      <li v-for="item in answer.conceptComparisons" :key="`${item.leftConcept}-${item.rightConcept}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-indigo/10 px-2 py-0.5 text-[11px] font-semibold text-indigo">{{ comparisonBasisLabel(item.basis) }}</span>
                        <div class="mt-3 grid gap-3 sm:grid-cols-2"><div class="rounded-lg bg-paper p-3"><p class="font-semibold text-indigo">{{ item.leftConcept }}</p><p class="mt-1">{{ item.leftDefinition }}</p></div><div class="rounded-lg bg-paper p-3"><p class="font-semibold text-indigo">{{ item.rightConcept }}</p><p class="mt-1">{{ item.rightDefinition }}</p></div></div>
                        <p class="mt-3"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.common') }}：</span>{{ item.commonGround }}</p>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.keyDifference') }}：</span>{{ item.keyDifference }}</p>
                        <p class="mt-2 border-l-2 border-indigo/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.comparison.boundary') }}：</span>{{ item.practicalBoundary }}</p>
                      </li>
                    </ul>
                  </li>
                  <li v-if="answer.ruleOptions?.length" class="rounded-2xl border border-copper/20 bg-copper/[0.05] p-3">
                    <p class="font-semibold text-ink">{{ t('lesson.answer.options.title') }}</p>
                    <p class="mt-1 text-xs text-ink/50">{{ t('lesson.answer.options.description') }}</p>
                    <p class="mt-3 rounded-xl bg-paper px-3 py-2"><span class="font-semibold text-ink">{{ t('lesson.answer.options.selectionRule') }}：</span>{{ answer.ruleOptions[0]?.selectionRule }}</p>
                    <ol class="mt-3 grid gap-3 sm:grid-cols-2">
                      <li v-for="(item, index) in answer.ruleOptions" :key="`${item.optionName}-${index}`" class="rounded-xl bg-canvas px-3 py-3">
                        <span class="rounded-full bg-copper/10 px-2 py-0.5 text-[11px] font-semibold text-copper">{{ optionBasisLabel(item.basis) }}</span>
                        <p class="mt-2 font-semibold text-ink">{{ index + 1 }}. {{ item.optionName }}</p>
                        <p class="mt-2"><span class="font-semibold text-ink">{{ t('lesson.answer.options.availability') }}：</span>{{ item.availabilityCondition }}</p>
                        <p class="mt-2 border-l-2 border-copper/30 pl-3"><span class="font-semibold text-ink">{{ t('lesson.answer.options.result') }}：</span>{{ item.result }}</p>
                      </li>
                    </ol>
                  </li>
                  <li v-if="answer.exceptions.length" class="rounded-2xl bg-copper/[0.07] p-3"><span class="font-semibold text-ink">{{ t('lesson.answer.intent.exceptions') }}：</span><ul class="mt-1 list-disc stack-y-xs pl-5"><li v-for="exception in answer.exceptions" :key="exception">{{ exception }}</li></ul></li>
                </ol>
              </div>

              <div v-if="publishesConclusion(answer.status)" class="mt-5 flex flex-wrap gap-2 border-t border-ink/10 pt-4" :aria-label="t('lesson.answer.followUps')">
                <button type="button" :disabled="answerLoading || !online" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'DEFINE')">{{ t('lesson.answer.intent.define') }}</button>
                <button type="button" :disabled="answerLoading || !online" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'WHY')">{{ t('lesson.answer.intent.why') }}</button>
                <button type="button" :disabled="answerLoading || !online" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXAMPLE')">{{ t('lesson.answer.intent.example') }}</button>
                <button type="button" :disabled="answerLoading || !online" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXCEPTIONS')">{{ t('lesson.answer.intent.exceptions') }}</button>
                <button type="button" :disabled="answerLoading || !online" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'SOURCE')">{{ t('lesson.answer.intent.source') }}</button>
              </div>

              <div v-if="publishesConclusion(answer.status)" class="mt-4 rounded-2xl border border-ink/10 bg-paper p-4">
                <p class="text-sm font-semibold text-ink">{{ t('lesson.answer.feedback.title') }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('lesson.answer.feedback.description') }}</p>
                <p v-if="answerResolved" class="mt-3 rounded-xl bg-emerald-50 px-3 py-2 text-sm font-semibold text-emerald-800" role="status">{{ t('lesson.answer.feedback.resolvedStatus') }}</p>
                <div v-else class="mt-3 flex flex-wrap gap-2">
                  <button type="button" class="min-h-11 rounded-xl border border-emerald-300 bg-emerald-50 px-3 text-sm font-semibold text-emerald-800" @click="markAnswerResolved">{{ t('lesson.answer.feedback.resolved') }}</button>
                  <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/30 bg-copper/[0.06] px-3 text-sm font-semibold text-copper disabled:opacity-40" @click="prepareFeedbackFollowUp('SIMPLIFY')">{{ t('lesson.answer.feedback.unclear') }}</button>
                  <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-amber-400 bg-amber-50 px-3 text-sm font-semibold text-amber-950 disabled:opacity-40" @click="prepareFeedbackFollowUp('VERIFY')">{{ t('lesson.answer.feedback.incorrect') }}</button>
                </div>
              </div>
            </div>

            <section v-if="primaryCitation" class="border-t border-indigo/15 bg-indigo/5 p-5 sm:p-6" aria-labelledby="lesson-answer-evidence-title">
              <p id="lesson-answer-evidence-title" class="font-semibold text-indigo">{{ t('lesson.answer.evidence.title') }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('lesson.answer.evidence.description') }}</p>
              <article class="mt-4 rounded-2xl border border-indigo/20 bg-paper p-4">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <p class="font-semibold">{{ primaryCitation.heading }}</p>
                  <span class="text-xs font-semibold text-indigo">{{ citationPages(primaryCitation) }}</span>
                </div>
                <p class="mt-2 text-sm leading-6 text-ink/65">{{ playerFacingCitationExcerpt(primaryCitation.excerpt) }}</p>
              </article>
              <details v-if="additionalCitations.length" class="mt-4">
                <summary class="cursor-pointer text-sm font-semibold text-indigo">{{ t('lesson.answer.evidence.more', { count: additionalCitations.length }) }}</summary>
                <ol class="mt-3 stack-y-md">
                  <li v-for="(citation, citationIndex) in additionalCitations" :key="`${citation.heading}-${citation.pageFrom}-${citation.pageTo}-${citationIndex}`" class="rounded-2xl border border-indigo/15 bg-paper p-4">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                      <p class="font-semibold">{{ citation.heading }}</p>
                      <span class="text-xs font-semibold text-indigo">{{ citationPages(citation) }}</span>
                    </div>
                    <p class="mt-2 text-sm leading-6 text-ink/65">{{ playerFacingCitationExcerpt(citation.excerpt) }}</p>
                  </li>
                </ol>
              </details>
            </section>

            <div v-if="answer.status === 'ANSWERED'" class="border-t border-ink/10 p-5 sm:p-6">
              <p v-if="rulingError" class="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ rulingError }}</p>
              <div v-if="rulingConflict" class="rounded-2xl border border-amber-300 bg-amber-50 p-4" role="alert">
                <p class="font-semibold text-amber-950">{{ t('lesson.answer.ruling.conflictTitle') }}</p>
                <p class="mt-1 text-sm leading-6 text-amber-900">{{ t('lesson.answer.ruling.conflictDescription') }}</p>
                <button class="mt-3 min-h-11 rounded-xl bg-amber-900 px-4 text-sm font-semibold text-white" :disabled="rulingSaving" @click="emit('reloadRuling')">{{ t('lesson.answer.ruling.reload') }}</button>
              </div>

              <div v-else-if="ruling && editingRuling" class="stack-y-lg">
                <div>
                  <label for="ruling-verdict" class="text-sm font-semibold">{{ t('lesson.answer.ruling.verdict') }}</label>
                  <textarea id="ruling-verdict" v-model="editedVerdictModel" rows="2" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
                </div>
                <div>
                  <label for="ruling-explanation" class="text-sm font-semibold">{{ t('lesson.answer.ruling.explanation') }}</label>
                  <textarea id="ruling-explanation" v-model="editedExplanationModel" rows="5" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
                </div>
                <div class="flex flex-wrap gap-3">
                  <button class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40" :disabled="rulingSaving || !editedVerdict.trim() || !editedExplanation.trim()" @click="emit('saveRulingRevision')">{{ rulingSaving ? t('lesson.answer.ruling.saving') : t('lesson.answer.ruling.save') }}</button>
                  <button class="min-h-11 rounded-xl border border-ink/15 px-5 text-sm font-semibold" :disabled="rulingSaving" @click="emit('update:editing-ruling', false)">{{ t('lesson.answer.ruling.cancel') }}</button>
                </div>
              </div>

              <div v-else-if="ruling" class="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-emerald-50 p-4">
                <div>
                  <p class="font-semibold text-emerald-900">{{ t('lesson.answer.ruling.confirmed') }}</p>
                  <p class="mt-1 text-xs text-emerald-800">{{ t('lesson.answer.ruling.version', { version: ruling.version, count: ruling.citations.length }) }}</p>
                </div>
                <button class="min-h-11 rounded-xl border border-emerald-700 px-4 text-sm font-semibold text-emerald-900" @click="emit('update:editing-ruling', true)">{{ t('lesson.answer.ruling.edit') }}</button>
              </div>

              <button v-else class="min-h-11 w-full rounded-xl border border-indigo/30 px-5 text-sm font-semibold text-indigo transition hover:bg-indigo/5 disabled:opacity-40" :disabled="rulingSaving" @click="emit('confirmRuling')">{{ rulingSaving ? t('lesson.answer.ruling.saving') : t('lesson.answer.ruling.confirm') }}</button>
            </div>
          </article>
        </div>
      </div>
    </div>
  </section>
</template>
