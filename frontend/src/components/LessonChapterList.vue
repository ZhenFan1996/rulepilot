<script setup lang="ts">
import { useLocale } from '@/lib/locale'
import TabletopGlyph from '@/components/TabletopGlyph.vue'

interface VisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
}

interface LessonReaderStep {
  position: number
  heading: string
  kind: string
  text: string
  sourcePages: number[]
  visualFocus: VisualFocus | null
}

interface LessonReaderSection {
  position: number
  title: string
  visualCaption: string
  steps: LessonReaderStep[]
}

const props = withDefaults(defineProps<{
  sections: LessonReaderSection[]
  idPrefix: string
  pageImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
  stepTestId?: string
}>(), { stepTestId: '' })

const { locale, t } = useLocale()

function sourceLabel(pages: number[]) {
  return t('public.step.source', { pages: pages.join(locale.value === 'en' ? ', ' : '、') })
}

function stepTone(kind: string) {
  if (kind === 'WATCH') return 'border-amber-300/70 bg-amber-50/60'
  if (kind === 'CHECK') return 'border-emerald-200 bg-emerald-50/55'
  if (kind === 'EXAMPLE') return 'border-copper/20 bg-copper/[0.045]'
  return 'border-ink/10 bg-paper'
}

function stepKindLabel(kind: string) {
  switch (kind) {
    case 'UNDERSTAND': return t('lesson.chapter.move.understand')
    case 'DO': return t('lesson.chapter.move.do')
    case 'EXAMPLE': return t('lesson.chapter.move.example')
    case 'WATCH': return t('lesson.chapter.move.watch')
    case 'CHECK': return t('lesson.chapter.move.check')
    case 'VISUAL': return t('lesson.chapter.move.visual')
    case 'FLOW': return t('lesson.chapter.move.flow')
    case 'LEDGER': return t('lesson.chapter.move.ledger')
    default: return kind
  }
}
</script>

<template>
  <div>
    <nav class="sticky top-16 z-10 -mx-2 mt-7 flex gap-2 overflow-x-auto rounded-2xl border border-ink/10 bg-canvas/90 p-2 shadow-sm backdrop-blur" :aria-label="t('lesson.reader.chapterDirectory')">
      <a v-for="section in sections" :key="section.position" :href="`#${idPrefix}-${section.position}`" class="inline-flex min-h-10 shrink-0 items-center gap-2 rounded-xl px-3 text-sm font-semibold text-ink/65 transition hover:bg-paper hover:text-indigo">
        <span class="grid size-6 place-items-center rounded-full bg-copper/12 text-xs text-copper">{{ section.position }}</span>
        {{ section.title }}
      </a>
    </nav>

    <section v-for="section in sections" :id="`${idPrefix}-${section.position}`" :key="section.position" class="scroll-mt-32 border-b border-ink/10 py-10 lg:grid lg:grid-cols-[5.5rem_minmax(0,1fr)] lg:gap-7 lg:py-14">
      <div class="mb-4 lg:mb-0">
        <div class="grid size-14 place-items-center rounded-2xl border border-copper/25 bg-copper/[0.08] text-copper shadow-sm">
          <TabletopGlyph name="meeple" :size="23" />
        </div>
        <p class="mt-2 text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ t('public.chapter', { position: section.position }) }}</p>
      </div>

      <div class="min-w-0">
        <h2 class="font-display text-3xl font-semibold tracking-tight sm:text-4xl">{{ section.title }}</h2>
        <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

        <ol class="mt-7 grid gap-4">
          <li v-for="step in section.steps" :key="step.position" :data-testid="stepTestId || undefined" class="rounded-2xl border p-5 shadow-[0_16px_45px_-40px_rgba(20,31,37,0.8)] sm:p-6" :class="stepTone(step.kind)">
            <div class="flex gap-4">
              <span class="grid size-8 shrink-0 place-items-center rounded-xl bg-ink text-sm font-bold text-canvas">{{ step.position }}</span>
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <h3 class="font-display text-xl font-semibold sm:text-2xl">{{ step.heading }}</h3>
                  <span class="rounded-full border border-ink/10 bg-canvas/70 px-2.5 py-1 text-[11px] font-bold tracking-[0.08em] text-ink/45">{{ stepKindLabel(step.kind) }}</span>
                </div>
                <p class="mt-3 text-[0.98rem] leading-7 text-ink/75">{{ step.text }}</p>

                <figure v-if="step.visualFocus" class="mt-5 overflow-hidden rounded-2xl border border-indigo/15 bg-canvas">
                  <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-4 py-3">
                    <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
                    <p class="mt-1 text-sm leading-6 text-ink/70">{{ step.visualFocus.visibleDescription || step.visualFocus.label }}</p>
                  </figcaption>
                  <a :href="props.pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="block">
                    <img :src="props.focusedPageImageUrl(step.visualFocus)" :alt="t('public.step.openSource', { label: step.visualFocus.label })" class="max-h-[28rem] w-full object-contain" loading="lazy" decoding="async">
                    <span class="block border-t border-ink/10 px-4 py-3 text-sm font-semibold text-indigo">{{ t('public.step.openSource', { label: step.visualFocus.label }) }} ↗</span>
                  </a>
                </figure>

                <a v-if="step.sourcePages.length" :href="props.pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener noreferrer" class="mt-4 inline-flex min-h-9 items-center rounded-full border border-ink/10 bg-canvas/70 px-3 text-xs font-semibold text-ink/50 transition hover:border-indigo/30 hover:text-indigo">{{ sourceLabel(step.sourcePages) }} ↗</a>
              </div>
            </div>
          </li>
        </ol>
      </div>
    </section>
  </div>
</template>
