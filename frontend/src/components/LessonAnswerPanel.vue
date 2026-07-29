<script setup lang="ts">
import { computed } from 'vue'

import VoiceQuestionCapture from '@/components/VoiceQuestionCapture.vue'
import { useLocale } from '@/lib/locale'
import type {
  AnswerTurn,
  ConfirmedRuling,
  LearningIntent,
  StructuredRuleAnswer,
} from '@/composables/useLessonAnswers'

interface LessonAnswerSection {
  position: number
  title: string
}

const props = defineProps<{
  currentSection: LessonAnswerSection | null
  question: string
  answer: StructuredRuleAnswer | null
  answeredQuestion: string
  answerTurns: AnswerTurn[]
  activeLearningIntent: LearningIntent | null
  answerLoading: boolean
  answerError: string
  online: boolean
  ruling: ConfirmedRuling | null
  rulingSaving: boolean
  rulingError: string
  rulingConflict: boolean
  editingRuling: boolean
  editedVerdict: string
  editedExplanation: string
}>()

const emit = defineEmits<{
  'update:question': [value: string]
  'update:editing-ruling': [value: boolean]
  'update:edited-verdict': [value: string]
  'update:edited-explanation': [value: string]
  ask: []
  requestHelp: [intent: LearningIntent]
  openCardOcr: []
  voiceTranscript: [text: string]
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
const previousAnswerTurns = computed(() => props.answerTurns.slice(0, -1))
const currentAnswerTurn = computed(() => props.answerTurns.at(-1) ?? null)
const { t } = useLocale()

function learningIntentLabel(intent: LearningIntent | null) {
  if (intent === null) return t('lesson.answer.eyebrow')
  if (intent === 'SIMPLIFY') return t('lesson.answer.intent.simplify')
  if (intent === 'EXAMPLE') return t('lesson.answer.intent.example')
  if (intent === 'WHY') return t('lesson.answer.intent.why')
  return t('lesson.answer.intent.exceptions')
}

function confidenceLabel(confidence: StructuredRuleAnswer['confidence']) {
  if (confidence === 'HIGH') return t('public.answer.high')
  if (confidence === 'MEDIUM') return t('public.answer.medium')
  return t('public.answer.low')
}

function answerBasisLabel(answerBasis: StructuredRuleAnswer['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION' ? t('public.answer.groundedBasis') : t('public.answer.directBasis')
}

function answerBasisDescription(answerBasis: StructuredRuleAnswer['answerBasis']) {
  return answerBasis === 'GROUNDED_APPLICATION'
    ? t('public.answer.groundedDescription')
    : t('public.answer.directDescription')
}

function citationPages(citation: StructuredRuleAnswer['citations'][number]) {
  return citation.pageFrom === citation.pageTo
    ? t('lesson.answer.pageSingle', { page: citation.pageFrom })
    : t('lesson.answer.pageRange', { from: citation.pageFrom, to: citation.pageTo })
}

function answerFailureMessage(status: StructuredRuleAnswer['status']) {
  return {
    ANSWERED: '',
    CLARIFICATION_REQUIRED: '',
    INSUFFICIENT_EVIDENCE: t('lesson.answer.failure.insufficient'),
    MODEL_TIMEOUT: t('public.answer.timeout'),
    INVALID_MODEL_OUTPUT: t('lesson.answer.failure.invalid'),
    VERSION_CONFLICT: t('lesson.answer.failure.version'),
  }[status]
}
</script>

<template>
  <section id="lesson-question-panel" class="mt-8 scroll-mt-6 border-t border-ink/10 pt-7" aria-labelledby="lesson-question-title">
    <div class="rounded-3xl border border-indigo/20 bg-indigo/[0.035] p-4 sm:p-6">
      <div class="mt-2 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p class="text-xs font-semibold uppercase tracking-[0.14em] text-indigo">{{ t('lesson.answer.eyebrow') }}</p>
          <h3 id="lesson-question-title" class="mt-1 font-display text-2xl font-semibold">{{ t('lesson.answer.title') }}</h3>
          <p class="mt-2 text-sm leading-6 text-ink/55">{{ t('lesson.answer.description', { section: currentSection?.title ?? t('lesson.answer.sectionFallback') }) }}</p>
        </div>
        <span v-if="currentSection" class="rounded-full bg-indigo/8 px-3 py-1.5 text-xs font-semibold text-indigo">{{ t('lesson.answer.sectionContext', { position: currentSection.position }) }}</span>
      </div>

      <ol v-if="previousAnswerTurns.length" class="mt-5 space-y-3" :aria-label="t('lesson.answer.thread')">
        <li v-for="(turn, index) in previousAnswerTurns" :key="`${index}-${turn.question}`" class="rounded-2xl border border-ink/8 bg-canvas p-4">
          <p class="text-xs font-semibold text-ink/45">{{ turn.learningIntent ? learningIntentLabel(turn.learningIntent) : t('lesson.answer.youAsked') }}</p>
          <p class="mt-1 text-sm leading-6">{{ turn.question }}</p>
          <p class="mt-3 border-l-2 border-copper pl-3 text-sm font-semibold leading-6">{{ turn.answer.shortVerdict }}</p>
        </li>
      </ol>

      <div class="mt-5 rounded-2xl bg-copper/[0.07] p-4">
        <p class="text-sm font-semibold">{{ t('lesson.answer.helpTitle') }}</p>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('lesson.answer.helpDescription') }}</p>
        <div class="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'SIMPLIFY')">{{ t('lesson.answer.intent.simplify') }}</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'EXAMPLE')">{{ t('lesson.answer.intent.example') }}</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'WHY')">{{ t('lesson.answer.intent.why') }}</button>
          <button type="button" :disabled="answerLoading || !online" class="min-h-11 rounded-xl border border-copper/20 bg-paper px-3 text-sm font-semibold disabled:opacity-40" @click="emit('requestHelp', 'EXCEPTIONS')">{{ t('lesson.answer.intent.exceptions') }}</button>
        </div>
      </div>

      <form class="mt-5" @submit.prevent="emit('ask')">
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
          v-model="questionModel"
          rows="3"
          maxlength="800"
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

      <p v-if="answerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ answerError }}</p>
      <div v-else-if="answerLoading" class="mt-5 space-y-3 rounded-2xl border border-ink/8 p-5" aria-live="polite">
        <div class="flex items-center gap-3">
          <span class="size-3 animate-pulse rounded-full bg-indigo" aria-hidden="true" />
          <p class="text-sm font-semibold">{{ t('lesson.answer.working', { intent: activeLearningIntent ? learningIntentLabel(activeLearningIntent) : t('lesson.answer.workingDefault') }) }}</p>
        </div>
        <ol class="grid gap-2 text-xs leading-5 text-ink/55 sm:grid-cols-3">
          <li>{{ t('lesson.answer.stageOne') }}</li>
          <li>{{ t('lesson.answer.stageTwo') }}</li>
          <li>{{ t('lesson.answer.stageThree') }}</li>
        </ol>
        <p class="text-xs leading-5 text-ink/50">{{ t('lesson.answer.foreignLanguage') }}</p>
        <div class="h-4 w-4/5 animate-pulse rounded bg-ink/10" />
        <div class="h-4 w-3/5 animate-pulse rounded bg-ink/10" />
      </div>

      <article v-else-if="answer" class="mt-5 overflow-hidden rounded-3xl border border-ink/10 bg-canvas" aria-live="polite">
        <div class="p-5 sm:p-6">
          <p class="text-xs font-semibold text-ink/45">{{ currentAnswerTurn?.learningIntent ? learningIntentLabel(currentAnswerTurn.learningIntent) : t('lesson.answer.youAsked') }}：{{ answeredQuestion }}</p>
          <div class="flex flex-wrap items-center gap-2 text-xs font-semibold">
            <span :class="answer.confidence === 'LOW' ? 'bg-red-50 text-red-700' : 'bg-emerald-50 text-emerald-700'" class="rounded-full px-3 py-1.5">{{ confidenceLabel(answer.confidence) }}</span>
            <span v-if="answer.status === 'ANSWERED'" class="rounded-full bg-copper/[0.12] px-3 py-1.5 text-copper">{{ answerBasisLabel(answer.answerBasis) }}</span>
            <span class="rounded-full bg-ink/6 px-3 py-1.5 text-ink/60">{{ answer.confirmedRulingId ? t('lesson.answer.source.confirmed') : answer.official ? t('lesson.answer.source.official') : t('lesson.answer.source.uploaded') }}</span>
          </div>
          <p class="mt-4 font-display text-xl font-semibold leading-8">{{ answer.shortVerdict }}</p>

          <p v-if="answer.status === 'CLARIFICATION_REQUIRED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answer.clarification }}</p>
          <p v-else-if="answer.status !== 'ANSWERED'" class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">{{ answerFailureMessage(answer.status) }}</p>

          <div v-if="answer.status === 'ANSWERED'" class="mt-5 border-t border-ink/10 pt-4">
            <p class="text-sm font-semibold text-indigo">{{ t('public.answer.trace') }}</p>
            <ol class="mt-3 space-y-3 text-sm leading-6 text-ink/70">
              <li class="rounded-2xl bg-indigo/[0.045] p-3"><span class="font-semibold text-ink">{{ t('public.answer.ruleBasis') }}：</span>{{ answerBasisDescription(answer.answerBasis) }}</li>
              <li class="rounded-2xl bg-paper p-3"><span class="font-semibold text-ink">{{ t('public.answer.application') }}：</span>{{ answer.explanation }}</li>
              <li v-if="answer.exceptions.length" class="rounded-2xl bg-copper/[0.07] p-3"><span class="font-semibold text-ink">{{ t('lesson.answer.intent.exceptions') }}：</span><ul class="mt-1 list-disc space-y-1 pl-5"><li v-for="exception in answer.exceptions" :key="exception">{{ exception }}</li></ul></li>
            </ol>
          </div>

          <div v-if="answer.status === 'ANSWERED'" class="mt-5 flex flex-wrap gap-2 border-t border-ink/10 pt-4" :aria-label="t('lesson.answer.followUps')">
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'WHY')">{{ t('lesson.answer.intent.why') }}</button>
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXAMPLE')">{{ t('lesson.answer.intent.example') }}</button>
            <button type="button" :disabled="answerLoading" class="min-h-10 rounded-xl border border-ink/12 px-3 text-sm font-semibold hover:bg-paper disabled:opacity-40" @click="emit('requestHelp', 'EXCEPTIONS')">{{ t('lesson.answer.intent.exceptions') }}</button>
          </div>
        </div>

        <details v-if="answer.citations.length" class="border-t border-indigo/15 bg-indigo/5 p-5 sm:p-6">
          <summary class="cursor-pointer font-semibold text-indigo">{{ t('lesson.answer.citations', { count: answer.citations.length }) }}</summary>
          <ol class="mt-4 space-y-3">
            <li v-for="citation in answer.citations" :key="citation.chunkId" class="rounded-2xl border border-indigo/15 bg-paper p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <p class="font-semibold">{{ citation.heading }}</p>
                <span class="text-xs font-semibold text-indigo">{{ citationPages(citation) }}</span>
              </div>
              <p class="mt-2 text-sm leading-6 text-ink/65">{{ citation.excerpt }}</p>
            </li>
          </ol>
        </details>

        <div v-if="answer.status === 'ANSWERED'" class="border-t border-ink/10 p-5 sm:p-6">
          <p v-if="rulingError" class="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ rulingError }}</p>
          <div v-if="rulingConflict" class="rounded-2xl border border-amber-300 bg-amber-50 p-4" role="alert">
            <p class="font-semibold text-amber-950">{{ t('lesson.answer.ruling.conflictTitle') }}</p>
            <p class="mt-1 text-sm leading-6 text-amber-900">{{ t('lesson.answer.ruling.conflictDescription') }}</p>
            <button class="mt-3 min-h-11 rounded-xl bg-amber-900 px-4 text-sm font-semibold text-white" :disabled="rulingSaving" @click="emit('reloadRuling')">{{ t('lesson.answer.ruling.reload') }}</button>
          </div>

          <div v-else-if="ruling && editingRuling" class="space-y-4">
            <div>
              <label for="ruling-verdict" class="text-sm font-semibold">{{ t('lesson.answer.ruling.verdict') }}</label>
              <textarea id="ruling-verdict" v-model="editedVerdictModel" rows="2" maxlength="2000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
            </div>
            <div>
              <label for="ruling-explanation" class="text-sm font-semibold">{{ t('lesson.answer.ruling.explanation') }}</label>
              <textarea id="ruling-explanation" v-model="editedExplanationModel" rows="5" maxlength="20000" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3 outline-none focus:border-indigo" />
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
  </section>
</template>
