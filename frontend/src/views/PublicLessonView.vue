<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import RulebookIconGlossaryPanel from '@/components/RulebookIconGlossaryPanel.vue'
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
    clarification: string | null
    warnings: Array<{ type: 'INDIRECT_CITATION' | 'LOW_CONFIDENCE' | 'REVIEW_UNRESOLVED' | 'REVIEW_UNAVAILABLE' }>
  }
  visualAids: Array<{ visualFocus: VisualFocus; relatedStep: string }>
  examples: Array<{ heading: string; text: string; sourcePages: number[] }>
}

interface PublicAnswerTurn { question: string; answer: PublicAnswer }

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
const readerScope = ref<string | null>(null)
const readerScopeReady = ref(false)
const coverUnavailable = ref(false)
let latestLoadRequest = 0
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
  return isPublicAnswer(value.answer)
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

async function submitPublicQuestion() {
  const question = publicQuestion.value.trim()
  if (!question || publicAnswerLoading.value || !planId.value || !readerScopeReady.value) return
  publicAnswerLoading.value = true
  publicAnswerError.value = ''
  try {
    const previousTurn = publicAnswerTurns.value.at(-1)
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}/answers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question,
        previousQuestion: previousTurn?.question ?? null,
        language: locale.value,
      }),
    })
    if (response.status === 404) throw new Error(t('public.answer.missing'))
    if (!response.ok) throw new Error(t('public.answer.failed'))
    const received = await response.json() as PublicAnswer
    publicAnswerTurns.value = [...publicAnswerTurns.value, { question, answer: received }].slice(-PUBLIC_ANSWER_HISTORY_LIMIT)
    rememberPublicAnswerTurns()
    publicQuestion.value = ''
    await nextTick()
    const answerElement = document.getElementById(`public-answer-${publicAnswerTurns.value.length - 1}`)
    answerElement?.focus({ preventScroll: true })
    answerElement?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  } catch (error) {
    publicAnswerError.value = error instanceof Error ? error.message : t('public.answer.fallback')
  } finally {
    publicAnswerLoading.value = false
  }
}

onMounted(() => {
  void load()
  void initializeReaderScope()
})

watch([locale, planId], () => {
  if (readerScopeReady.value) restorePublicAnswerTurns()
  else publicAnswerTurns.value = []
  publicQuestion.value = ''
  publicAnswerError.value = ''
  void load()
})
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
          <div v-else-if="publicAnswerLoading" class="mt-5 rounded-2xl border border-indigo/12 bg-paper p-5" role="status" aria-live="polite">
            <div class="flex items-center gap-3"><span class="size-3 animate-pulse rounded-full bg-copper" /><p class="text-sm font-semibold">{{ t('public.question.stage') }}</p></div>
            <div class="mt-4 grid gap-2 text-xs text-ink/50 sm:grid-cols-3"><span>{{ t('public.question.stageOne') }}</span><span>{{ t('public.question.stageTwo') }}</span><span>{{ t('public.question.stageThree') }}</span></div>
          </div>

          <ol v-if="publicAnswerTurns.length" class="mt-6 space-y-5" :aria-label="t('public.question.thread')">
            <li v-for="(turn, index) in publicAnswerTurns" :key="`${index}-${turn.question}`" class="space-y-3">
              <div class="ml-auto max-w-[92%] rounded-2xl rounded-tr-md bg-copper px-4 py-3 text-sm font-medium leading-6 text-white sm:max-w-[78%]">{{ turn.question }}</div>
              <article :id="`public-answer-${index}`" tabindex="-1" class="max-w-[96%] overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm outline-none focus:ring-4 focus:ring-indigo/15 sm:max-w-[88%]">
                <div class="p-5 sm:p-6">
                  <div class="flex flex-wrap items-center gap-2"><span class="rounded-full bg-indigo/8 px-3 py-1 text-xs font-semibold text-indigo">{{ confidenceLabel(turn.answer.answer.confidence) }}</span><span v-if="publishesConclusion(turn.answer.answer.status)" class="rounded-full bg-copper/[0.1] px-3 py-1 text-xs font-semibold text-copper">{{ answerBasisLabel(turn.answer.answer.answerBasis) }}</span><span class="text-xs font-semibold text-ink/40">{{ t('public.question.answer') }}</span></div>
                  <p class="mt-4 font-display text-xl font-semibold leading-8">{{ turn.answer.answer.shortVerdict }}</p>
                  <div v-if="turn.answer.answer.warnings.length" class="mt-4 rounded-2xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status"><p class="font-semibold">{{ t('lesson.answer.warning.title') }}</p><ul class="mt-1 list-disc pl-5"><li v-for="warning in turn.answer.answer.warnings" :key="warning.type">{{ answerWarningMessage(warning) }}</li></ul></div>
                  <div v-if="publishesConclusion(turn.answer.answer.status) && turn.answer.answer.explanation" class="mt-4 rounded-2xl bg-canvas p-4 text-sm leading-6 text-ink/70"><p class="font-semibold text-indigo">{{ t('public.answer.trace') }}</p><p class="mt-2"><span class="font-semibold text-ink">{{ t('public.answer.ruleBasis') }}：</span>{{ answerBasisDescription(turn.answer.answer.answerBasis) }}</p><p class="mt-2"><span class="font-semibold text-ink">{{ t('public.answer.application') }}：</span>{{ turn.answer.answer.explanation }}</p></div>
                  <p v-else class="mt-3 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{{ answerFailureMessage(turn.answer.answer) }}</p>
                  <ul v-if="turn.answer.answer.exceptions.length" class="mt-4 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65"><li v-for="exception in turn.answer.answer.exceptions" :key="exception">{{ exception }}</li></ul>

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
