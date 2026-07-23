<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { useLocale } from '@/lib/locale'
import { publicLessonTitle } from '@/lib/lessonPresentation'

interface VisualFocus {
  pageNumber: number
  label: string
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
    status: 'ANSWERED' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'INVALID_MODEL_OUTPUT' | 'MODEL_TIMEOUT'
    shortVerdict: string
    explanation: string | null
    citations: RuleCitation[]
    exceptions: string[]
    confidence: 'LOW' | 'MEDIUM' | 'HIGH'
    clarification: string | null
  }
  visualAids: Array<{ visualFocus: VisualFocus; relatedStep: string }>
  examples: Array<{ heading: string; text: string; sourcePages: number[] }>
}

interface PublicAnswerTurn { question: string; answer: PublicAnswer }

const route = useRoute()
const { locale, t } = useLocale()
const loading = ref(true)
const errorMessage = ref('')
const publicLesson = ref<PublicLessonResponse | null>(null)
const publicQuestion = ref('')
const selectedSectionPosition = ref<number | null>(null)
const publicAnswerTurns = ref<PublicAnswerTurn[]>([])
const publicAnswerLoading = ref(false)
const publicAnswerError = ref('')
const planId = computed(() => typeof route.params.planId === 'string' ? route.params.planId : '')
const displayTitle = computed(() => publicLesson.value ? publicLessonTitle(publicLesson.value) : '')
const englishGuidePending = computed(() => locale.value === 'en' && publicLesson.value?.contentLanguage !== 'en')
const englishGuideFailed = computed(() => englishGuidePending.value && publicLesson.value?.localizationStatus === 'FAILED')

function sourcePageUrl(pageNumber: number) {
  return `/api/public/lessons/${encodeURIComponent(planId.value)}/pages/${pageNumber}/image`
}

function cropUrl(focus: VisualFocus) {
  const query = new URLSearchParams({
    x: String(focus.x), y: String(focus.y), width: String(focus.width), height: String(focus.height),
  })
  return `${sourcePageUrl(focus.pageNumber)}/crop?${query.toString()}`
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (!planId.value) throw new Error(t('public.error.missing'))
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}?language=${encodeURIComponent(locale.value)}`)
    if (response.status === 404) throw new Error(t('public.error.unpublished'))
    if (!response.ok) throw new Error(t('public.error.open'))
    publicLesson.value = await response.json() as PublicLessonResponse
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('public.error.open')
  } finally {
    loading.value = false
  }
}

function confidenceLabel(confidence: PublicAnswer['answer']['confidence']) {
  return { LOW: t('public.answer.low'), MEDIUM: t('public.answer.medium'), HIGH: t('public.answer.high') }[confidence]
}

function answerFailureMessage(answer: PublicAnswer['answer']) {
  if (answer.status === 'CLARIFICATION_REQUIRED') return answer.clarification ?? t('public.answer.clarify')
  if (answer.status === 'MODEL_TIMEOUT') return t('public.answer.timeout')
  return answer.shortVerdict
}

function askAboutSection(section: LessonSection) {
  selectedSectionPosition.value = section.position
  publicQuestion.value = locale.value === 'en'
    ? `Please explain the step people most often get wrong in “${section.title}”, then walk through one rulebook-supported example.`
    : `请把“${section.title}”这一章最容易弄错的步骤讲清楚，并走一个规则书允许的例子。`
  document.getElementById('public-question')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => (document.getElementById('public-question') as HTMLTextAreaElement | null)?.focus(), 250)
}

async function submitPublicQuestion() {
  const question = publicQuestion.value.trim()
  if (!question || publicAnswerLoading.value || !planId.value) return
  publicAnswerLoading.value = true
  publicAnswerError.value = ''
  try {
    const previousTurn = publicAnswerTurns.value.at(-1)
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}/answers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question,
        sectionPosition: selectedSectionPosition.value,
        previousQuestion: previousTurn?.question ?? null,
        language: locale.value,
      }),
    })
    if (response.status === 404) throw new Error(t('public.answer.missing'))
    if (!response.ok) throw new Error(t('public.answer.failed'))
    const received = await response.json() as PublicAnswer
    publicAnswerTurns.value.push({ question, answer: received })
    publicQuestion.value = ''
  } catch (error) {
    publicAnswerError.value = error instanceof Error ? error.message : t('public.answer.fallback')
  } finally {
    publicAnswerLoading.value = false
  }
}

onMounted(() => { void load() })

watch(locale, () => {
  publicAnswerTurns.value = []
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
            <a v-if="publicLesson.gameCover" :href="publicLesson.gameCover.attributionUrl" target="_blank" rel="noopener noreferrer" class="w-24 shrink-0 overflow-hidden rounded-lg border border-ink/10 bg-paper shadow-sm sm:w-32" :aria-label="t('public.cover.open', { title: displayTitle, source: publicLesson.gameCover.attributionLabel })">
              <img :src="publicLesson.gameCover.imageUrl" :alt="t('public.cover.alt', { title: displayTitle })" class="aspect-[3/4] h-full w-full object-cover" referrerpolicy="no-referrer">
            </a>
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

        <section class="mt-8 rounded-3xl border border-indigo/20 bg-indigo/[0.045] p-5 shadow-[0_18px_50px_-36px_rgba(40,57,128,0.75)] sm:p-7" aria-labelledby="public-question-title">
          <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <p class="text-xs font-semibold uppercase tracking-[0.14em] text-indigo">{{ t('public.question.eyebrow') }}</p>
              <h2 id="public-question-title" class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ t('public.question.title') }}</h2>
              <p class="mt-2 max-w-2xl leading-7 text-ink/60">{{ t('public.question.description') }}</p>
            </div>
            <span class="w-fit rounded-full bg-paper px-3 py-1.5 text-xs font-semibold text-indigo">{{ t('public.question.noLogin') }}</span>
          </div>

          <div class="mt-5 grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
            <label class="text-sm font-semibold text-ink/70">{{ t('public.question.section') }} <span class="font-normal text-ink/40">{{ t('public.question.optional') }}</span>
              <select v-model="selectedSectionPosition" :disabled="publicAnswerLoading" class="mt-2 min-h-11 w-full rounded-xl border border-ink/15 bg-paper px-3 font-normal outline-none focus:border-indigo disabled:opacity-50">
                <option :value="null">{{ t('public.question.all') }}</option>
                <option v-for="section in publicLesson.lesson.sections" :key="section.position" :value="section.position">{{ t('public.question.chapter', { position: section.position, title: section.title }) }}</option>
              </select>
            </label>
            <div class="self-end rounded-xl border border-indigo/10 bg-paper px-4 py-3 text-xs leading-5 text-ink/50">{{ t('public.question.exampleLabel') }}<br><span class="font-semibold text-ink/65">{{ t('public.question.example') }}</span></div>
          </div>

          <form class="mt-4" @submit.prevent="submitPublicQuestion">
            <label for="public-question" class="sr-only">{{ t('public.question.submit') }}</label>
            <textarea id="public-question" v-model="publicQuestion" rows="3" maxlength="800" :disabled="publicAnswerLoading" :placeholder="t('public.question.placeholder')" class="w-full resize-y rounded-2xl border border-ink/15 bg-paper px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-55" />
            <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
              <p class="text-xs text-ink/45">{{ t('public.question.counter', { count: publicQuestion.length }) }}</p>
              <button type="submit" :disabled="publicAnswerLoading || !publicQuestion.trim()" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40">{{ publicAnswerLoading ? t('public.question.loading') : t('public.question.submit') }}</button>
            </div>
          </form>

          <p v-if="publicAnswerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ publicAnswerError }}</p>
          <div v-else-if="publicAnswerLoading" class="mt-5 rounded-2xl border border-indigo/12 bg-paper p-5" role="status" aria-live="polite">
            <div class="flex items-center gap-3"><span class="size-3 animate-pulse rounded-full bg-copper" /><p class="text-sm font-semibold">{{ t('public.question.stage') }}</p></div>
            <div class="mt-4 grid gap-2 text-xs text-ink/50 sm:grid-cols-3"><span>{{ t('public.question.stageOne') }}</span><span>{{ t('public.question.stageTwo') }}</span><span>{{ t('public.question.stageThree') }}</span></div>
          </div>

          <ol v-if="publicAnswerTurns.length" class="mt-6 space-y-5" :aria-label="t('public.question.thread')">
            <li v-for="(turn, index) in publicAnswerTurns" :key="`${index}-${turn.question}`" class="space-y-3">
              <div class="ml-auto max-w-[92%] rounded-2xl rounded-tr-md bg-copper px-4 py-3 text-sm font-medium leading-6 text-white sm:max-w-[78%]">{{ turn.question }}</div>
              <article class="max-w-[96%] overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm sm:max-w-[88%]">
                <div class="p-5 sm:p-6">
                  <div class="flex flex-wrap items-center gap-2"><span class="rounded-full bg-indigo/8 px-3 py-1 text-xs font-semibold text-indigo">{{ confidenceLabel(turn.answer.answer.confidence) }}</span><span class="text-xs font-semibold text-ink/40">{{ t('public.question.answer') }}</span></div>
                  <p class="mt-4 font-display text-xl font-semibold leading-8">{{ turn.answer.answer.shortVerdict }}</p>
                  <p v-if="turn.answer.answer.status === 'ANSWERED' && turn.answer.answer.explanation" class="mt-3 leading-7 text-ink/75">{{ turn.answer.answer.explanation }}</p>
                  <p v-else class="mt-3 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{{ answerFailureMessage(turn.answer.answer) }}</p>
                  <ul v-if="turn.answer.answer.exceptions.length" class="mt-4 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65"><li v-for="exception in turn.answer.answer.exceptions" :key="exception">{{ exception }}</li></ul>

                  <div v-if="turn.answer.answer.citations.length" class="mt-5 border-t border-ink/10 pt-4">
                    <p class="text-xs font-semibold uppercase tracking-[0.12em] text-ink/40">{{ t('public.question.evidence') }}</p>
                    <ul class="mt-2 flex flex-wrap gap-2"><li v-for="citation in turn.answer.answer.citations" :key="`${citation.heading}-${citation.pageFrom}`" class="rounded-xl bg-canvas px-3 py-2 text-xs font-semibold text-indigo">{{ citation.heading }} · {{ locale === 'en' ? `p. ${citation.pageFrom}${citation.pageTo !== citation.pageFrom ? `–${citation.pageTo}` : ''}` : `第 ${citation.pageFrom}${citation.pageTo !== citation.pageFrom ? `–${citation.pageTo}` : ''} 页` }}</li></ul>
                  </div>

                  <div v-if="turn.answer.visualAids.length || turn.answer.examples.length" class="mt-5 border-t border-ink/10 pt-4">
                    <p class="text-sm font-semibold text-indigo">{{ t('public.question.withSource') }}</p>
                    <div v-if="turn.answer.visualAids.length" class="mt-3 grid gap-3 sm:grid-cols-2">
                      <a v-for="aid in turn.answer.visualAids" :key="`${aid.visualFocus.pageNumber}-${aid.visualFocus.x}-${aid.visualFocus.y}`" :href="sourcePageUrl(aid.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="overflow-hidden rounded-2xl border border-ink/10 bg-canvas transition hover:border-indigo/35">
                        <img :src="cropUrl(aid.visualFocus)" :alt="t('public.step.openSource', { label: aid.visualFocus.label })" class="aspect-[4/3] w-full object-contain">
                        <span class="block border-t border-ink/10 px-3 py-2 text-xs font-semibold text-indigo">{{ t('public.question.aid', { step: aid.relatedStep }) }}</span>
                      </a>
                    </div>
                    <ul v-if="turn.answer.examples.length" class="mt-3 space-y-2"><li v-for="example in turn.answer.examples" :key="`${example.heading}-${example.text}`" class="rounded-2xl bg-copper/[0.07] px-4 py-3"><p class="text-sm font-semibold text-copper">{{ t('public.question.exampleWalkthrough', { heading: example.heading }) }}</p><p class="mt-1 text-sm leading-6 text-ink/70">{{ example.text }}</p><p v-if="example.sourcePages.length" class="mt-2 text-xs text-ink/45">{{ t('public.question.samePages', { pages: example.sourcePages.join(locale === 'en' ? ', ' : '、') }) }}</p></li></ul>
                  </div>
                </div>
              </article>
            </li>
          </ol>
        </section>

        <section v-for="section in publicLesson.lesson.sections" :key="section.position" class="border-b border-ink/10 py-10">
          <div class="flex flex-wrap items-center justify-between gap-3"><p class="text-sm font-semibold text-copper">{{ t('public.chapter', { position: section.position }) }}</p><button type="button" class="rounded-lg border border-indigo/20 px-3 py-2 text-xs font-semibold text-indigo hover:bg-indigo/5" @click="askAboutSection(section)">{{ t('public.question.askChapter') }}</button></div>
          <h2 class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ section.title }}</h2>
          <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

          <ol class="mt-7 space-y-5">
            <li v-for="step in section.steps" :key="step.position" class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-6">
              <div class="flex gap-4">
                <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-copper/15 text-sm font-bold text-copper">{{ step.position }}</span>
                <div class="min-w-0 flex-1">
                  <h3 class="font-display text-xl font-semibold">{{ step.heading }}</h3>
                  <p class="mt-2 leading-7 text-ink/75">{{ step.text }}</p>
                  <a v-if="step.visualFocus" :href="sourcePageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="mt-5 block overflow-hidden rounded-lg border border-ink/10 bg-canvas">
                    <img :src="cropUrl(step.visualFocus)" :alt="t('public.step.openSource', { label: step.visualFocus.label })" class="max-h-96 w-full object-contain">
                    <span class="block border-t border-ink/10 px-3 py-2 text-sm font-semibold text-indigo">{{ t('public.step.openSource', { label: step.visualFocus.label }) }}</span>
                  </a>
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
