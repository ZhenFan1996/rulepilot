<script setup lang="ts">
import { useLocale } from '@/lib/locale'
import LessonRuleFacts, { type LessonRuleFact } from '@/components/LessonRuleFacts.vue'

interface VisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
}

interface ReaderStep {
  position: number
  heading: string
  kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER' | 'REFERENCE_CARD' | 'LIMIT'
  text: string
  ruleFacts?: LessonRuleFact[]
  sourcePages: number[]
  visualFocus: VisualFocus | null
}

interface ReaderSection {
  position: number
  title: string
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  visualSourcePages: number[]
}

interface MoveMeta {
  label: string
  tone: string
}

defineProps<{
  section: ReaderSection
  leadStep: ReaderStep | null
  pathSteps: ReaderStep[]
  supportSteps: ReaderStep[]
  checkSteps: ReaderStep[]
  visualStepCount: number
  pathTitle: string
  currentVisualPageNumber: number | undefined
  visualFeedbackSaving: string | null
  online: boolean
  pageImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
  stepSourceLabel: (step: ReaderStep) => string
  moveMeta: (kind: ReaderStep['kind'] | undefined) => MoveMeta
  visualKindLabel: (kind: ReaderSection['visualKind']) => string
  hasVisualAid: (sectionPosition: number, stepPosition: number) => boolean
  visualAidResult: (sectionPosition: number, stepPosition: number) => 'NOT_RATED' | 'HELPFUL' | 'NOT_HELPFUL'
}>()

const emit = defineEmits<{
  rateVisualAid: [sectionPosition: number, stepPosition: number, result: 'HELPFUL' | 'NOT_HELPFUL']
}>()

const { locale, t } = useLocale()

function moveLabel(kind: ReaderStep['kind'] | undefined) {
  switch (kind) {
    case 'UNDERSTAND': return t('lesson.chapter.move.understand')
    case 'DO': return t('lesson.chapter.move.do')
    case 'EXAMPLE': return t('lesson.chapter.move.example')
    case 'WATCH': return t('lesson.chapter.move.watch')
    case 'CHECK': return t('lesson.chapter.move.check')
    case 'VISUAL': return t('lesson.chapter.move.visual')
    case 'FLOW': return t('lesson.chapter.move.flow')
    case 'LEDGER': return t('lesson.chapter.move.ledger')
    case 'REFERENCE_CARD': return t('lesson.chapter.move.referenceCard')
    case 'LIMIT': return t('lesson.chapter.move.limit')
  }
}

function stepCountLabel(count: number) {
  return t(count === 1 ? 'lesson.chapter.stepCount.one' : 'lesson.chapter.stepCount.many', { count })
}

function pageList(pages: number[]) {
  return pages.join(locale.value === 'en' ? ', ' : '、')
}
</script>

<template>
  <div class="mt-7 grid items-start gap-8 2xl:grid-cols-[minmax(0,1fr)_19rem]">
    <div class="min-w-0">
      <section v-if="leadStep" class="rounded-2xl bg-ink-panel p-4 text-panel-text sm:p-6" aria-labelledby="chapter-core-title">
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-copper">{{ t('lesson.chapter.core') }}</p>
        <h3 id="chapter-core-title" class="mt-2 font-display text-xl font-semibold leading-7 sm:text-2xl sm:leading-8">{{ leadStep.heading || section.title }}</h3>
        <p class="mt-3 text-sm leading-6 text-panel-text/80 sm:text-base sm:leading-8">{{ leadStep.text }}</p>
        <LessonRuleFacts v-if="leadStep.ruleFacts?.length" :facts="leadStep.ruleFacts" />
        <figure v-if="leadStep.visualFocus" class="mt-5 overflow-hidden rounded-xl border border-panel-text/15 bg-canvas text-ink sm:max-w-2xl">
          <figcaption class="border-b border-ink/10 bg-indigo/[0.045] px-3 py-2">
            <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
            <p class="mt-1 text-sm leading-6 text-ink/70">{{ leadStep.visualFocus.visibleDescription || leadStep.visualFocus.label }}</p>
          </figcaption>
          <a :href="pageImageUrl(leadStep.visualFocus.pageNumber)" target="_blank" rel="noopener" :title="t('lesson.chapter.openFullPage')">
            <img :src="focusedPageImageUrl(leadStep.visualFocus)" :alt="t('lesson.chapter.visual.alt', { label: leadStep.visualFocus.label, page: leadStep.visualFocus.pageNumber })" class="block max-h-[30rem] w-full object-contain" loading="lazy">
          </a>
          <figcaption class="border-t border-ink/10 px-3 py-2 text-xs font-semibold text-copper">{{ t('lesson.chapter.visual.coreCaption', { label: leadStep.visualFocus.label }) }}</figcaption>
        </figure>
        <a v-if="stepSourceLabel(leadStep)" :href="pageImageUrl(leadStep.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-4 inline-flex text-xs font-semibold text-panel-text/60 hover:text-panel-text">
          {{ stepSourceLabel(leadStep) }} ↗
        </a>
      </section>

      <nav v-if="pathSteps.length || supportSteps.length || checkSteps.length" class="mt-5 flex flex-wrap gap-2 text-xs font-semibold" :aria-label="t('lesson.chapter.navAria')">
        <a v-if="pathSteps.length" href="#chapter-path" class="rounded-full bg-copper/10 px-3 py-2 text-copper">{{ pathTitle }}</a>
        <a v-if="supportSteps.length" href="#chapter-support" class="rounded-full bg-amber-100 px-3 py-2 text-amber-900">{{ t('lesson.chapter.navSupport') }}</a>
        <a v-if="checkSteps.length" href="#chapter-check" class="rounded-full bg-ink/8 px-3 py-2 text-ink/65">{{ t('lesson.chapter.navCheck') }}</a>
      </nav>

      <section v-if="pathSteps.length" id="chapter-path" class="mt-8 scroll-mt-28" aria-labelledby="chapter-path-title">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-xs font-semibold text-copper">{{ t('lesson.chapter.pathEyebrow') }}</p>
            <h3 id="chapter-path-title" class="mt-1 font-display text-2xl font-semibold">{{ pathTitle }}</h3>
          </div>
          <span class="text-xs font-semibold text-ink/40">{{ stepCountLabel(pathSteps.length) }}</span>
        </div>
        <ol class="relative mt-5 space-y-0 before:absolute before:bottom-5 before:left-[1.15rem] before:top-5 before:w-px before:bg-ink/15">
          <li v-for="(step, index) in pathSteps" :key="step.position" class="relative grid grid-cols-[2.4rem_1fr] gap-3 py-4 first:pt-1">
            <span class="relative z-[1] grid size-9 place-items-center rounded-full border border-copper/30 bg-paper text-sm font-bold text-copper">{{ index + 1 }}</span>
            <div class="min-w-0 pb-1">
              <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                <h4 class="font-display text-xl font-semibold leading-7">{{ step.heading || t('lesson.chapter.stepFallback', { position: index + 1 }) }}</h4>
                <span class="text-xs font-semibold" :class="moveMeta(step.kind).tone.split(' ')[1]">{{ moveLabel(step.kind) }}</span>
              </div>
              <div v-if="step.kind === 'VISUAL' && step.visualFocus" class="mt-4 min-w-0" data-testid="lesson-visual-step">
                <div>
                  <p class="max-w-3xl text-[0.95rem] leading-7 text-ink/72">{{ step.text }}</p>
                  <LessonRuleFacts v-if="step.ruleFacts?.length" :facts="step.ruleFacts" />
                  <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo hover:underline">{{ t('lesson.chapter.visual.openContext', { page: step.visualFocus.pageNumber }) }} ↗</a>
                </div>
                <figure class="mt-4 max-w-3xl overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
                  <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-3 py-2">
                    <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
                    <p class="mt-1 text-sm leading-6 text-ink/70">{{ step.visualFocus.visibleDescription || step.visualFocus.label }}</p>
                  </figcaption>
                  <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" :title="t('lesson.chapter.openFullPage')">
                    <img :src="focusedPageImageUrl(step.visualFocus)" :alt="t('lesson.chapter.visual.alt', { label: step.visualFocus.label, page: step.visualFocus.pageNumber })" class="block max-h-[30rem] w-full object-contain" loading="lazy">
                  </a>
                  <figcaption class="border-t border-indigo/10 px-3 py-2 text-xs font-semibold text-copper">{{ t('lesson.chapter.visual.caption', { label: step.visualFocus.label, page: step.visualFocus.pageNumber }) }}</figcaption>
                  <div v-if="hasVisualAid(section.position, step.position)" class="border-t border-indigo/10 px-3 py-2">
                    <p class="text-xs font-semibold text-ink/55">{{ t('lesson.chapter.visual.feedback') }}</p>
                    <div class="mt-2 grid grid-cols-2 gap-2">
                      <button type="button" class="min-h-9 rounded-lg border px-2 text-xs font-semibold disabled:opacity-40" :class="visualAidResult(section.position, step.position) === 'HELPFUL' ? 'border-indigo bg-indigo/8 text-indigo' : 'border-ink/15'" :disabled="visualFeedbackSaving !== null || !online" @click="emit('rateVisualAid', section.position, step.position, 'HELPFUL')">{{ t('lesson.chapter.visual.helpful') }}</button>
                      <button type="button" class="min-h-9 rounded-lg border px-2 text-xs font-semibold disabled:opacity-40" :class="visualAidResult(section.position, step.position) === 'NOT_HELPFUL' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="visualFeedbackSaving !== null || !online" @click="emit('rateVisualAid', section.position, step.position, 'NOT_HELPFUL')">{{ t('lesson.chapter.visual.notHelpful') }}</button>
                    </div>
                  </div>
                </figure>
              </div>
              <template v-else>
                <p class="mt-2 text-[0.95rem] leading-7 text-ink/72">{{ step.text }}</p>
                <LessonRuleFacts v-if="step.ruleFacts?.length" :facts="step.ruleFacts" />
                <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo hover:underline">{{ stepSourceLabel(step) }} ↗</a>
              </template>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="supportSteps.length" id="chapter-support" class="mt-8 scroll-mt-28" aria-labelledby="chapter-support-title">
        <p class="text-xs font-semibold text-amber-800">{{ t('lesson.chapter.supportEyebrow') }}</p>
        <h3 id="chapter-support-title" class="mt-1 font-display text-2xl font-semibold">{{ t('lesson.chapter.supportTitle') }}</h3>
        <div class="mt-4 grid gap-3 sm:grid-cols-2">
          <article v-for="step in supportSteps" :key="step.position" class="rounded-2xl border p-4" :class="step.kind === 'WATCH' ? 'border-amber-300 bg-amber-50' : 'border-emerald-200 bg-emerald-50/70'">
            <p class="text-xs font-bold" :class="step.kind === 'WATCH' ? 'text-amber-900' : 'text-emerald-800'">{{ moveLabel(step.kind) }}</p>
            <h4 class="mt-1 font-display text-lg font-semibold leading-6">{{ step.heading }}</h4>
            <p class="mt-2 text-sm leading-6 text-ink/70">{{ step.text }}</p>
            <LessonRuleFacts v-if="step.ruleFacts?.length" :facts="step.ruleFacts" />
            <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo">{{ stepSourceLabel(step) }} ↗</a>
          </article>
        </div>
      </section>

      <section v-if="checkSteps.length" id="chapter-check" class="mt-8 scroll-mt-28 rounded-2xl border border-copper/25 bg-copper/[0.07] p-5" aria-labelledby="chapter-check-title">
        <p class="text-xs font-semibold text-copper">{{ t('lesson.chapter.checkEyebrow') }}</p>
        <h3 id="chapter-check-title" class="mt-1 font-display text-2xl font-semibold">{{ t('lesson.chapter.checkTitle') }}</h3>
        <article v-for="step in checkSteps" :key="step.position" class="mt-4 border-t border-copper/15 pt-4 first:mt-3">
          <h4 class="font-semibold">{{ step.heading }}</h4>
          <p class="mt-2 text-sm leading-7 text-ink/70">{{ step.text }}</p>
          <LessonRuleFacts v-if="step.ruleFacts?.length" :facts="step.ruleFacts" />
          <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo">{{ t('lesson.chapter.checkSource', { pages: pageList(step.sourcePages) }) }} ↗</a>
        </article>
      </section>
    </div>

    <aside class="min-w-0 rounded-2xl border border-indigo/12 bg-indigo/[0.035] p-4 2xl:sticky 2xl:top-28" :aria-label="t('lesson.chapter.sourceRailAria')">
      <div class="flex items-start justify-between gap-3">
        <div>
          <p class="text-xs font-semibold text-indigo">{{ t('lesson.chapter.sourceEyebrow') }}</p>
          <h3 class="mt-1 font-display text-lg font-semibold">{{ visualKindLabel(section.visualKind) }}</h3>
        </div>
        <span v-if="currentVisualPageNumber" class="shrink-0 rounded-full bg-paper px-2.5 py-1 text-xs font-semibold text-ink/55">{{ t('lesson.chapter.page', { page: currentVisualPageNumber }) }}</span>
      </div>
      <p class="mt-3 text-sm leading-6 text-ink/65">{{ section.visualCaption }}</p>
      <p v-if="visualStepCount" class="mt-3 rounded-xl bg-paper px-3 py-2 text-xs leading-5 text-ink/55">{{ t('lesson.chapter.visual.available') }}</p>
      <p v-else class="mt-3 text-xs leading-5 text-ink/50">{{ t('lesson.chapter.visual.unavailable') }}</p>
      <div v-if="section.visualSourcePages.length" class="mt-4 flex flex-wrap gap-2">
        <a v-for="page in section.visualSourcePages" :key="page" :href="pageImageUrl(page)" target="_blank" rel="noopener" class="rounded-full border border-indigo/15 bg-paper px-3 py-2 text-xs font-semibold text-indigo">{{ t('lesson.chapter.openSourcePage', { page }) }}</a>
      </div>
    </aside>
  </div>
</template>
