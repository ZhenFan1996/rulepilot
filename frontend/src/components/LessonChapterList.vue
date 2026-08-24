<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import LessonVisualEvidence from '@/components/LessonVisualEvidence.vue'
import LessonRuleFacts, { type LessonRuleFact } from '@/components/LessonRuleFacts.vue'
import type { VisualFocus } from '@/composables/lessonSupportingContent'
import { useLocale } from '@/lib/locale'

interface LessonReaderStep {
  position: number
  heading: string
  kind: string
  text: string
  ruleFacts?: LessonRuleFact[]
  sourcePages: number[]
  visualFocus: VisualFocus | null
  visualFoci?: VisualFocus[]
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
  pagePreviewImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
  stepTestId?: string
}>(), { stepTestId: '' })

const { locale, t } = useLocale()
const activePosition = ref<number | null>(props.sections[0]?.position ?? null)
const activeChapterNumber = computed(() => {
  const index = props.sections.findIndex((section) => section.position === activePosition.value)
  return index < 0 ? 0 : index + 1
})
const sectionElements = new Map<number, HTMLElement>()
const navigationElements = new Map<number, HTMLElement>()
const visibleSections = new Map<number, { top: number; visible: boolean }>()
let sectionObserver: IntersectionObserver | null = null

function chapterId(position: number) {
  return `${props.idPrefix}-${position}`
}

function activateChapter(position: number) {
  activePosition.value = position
}

function stepVisuals(step: LessonReaderStep) {
  return step.visualFoci?.length ? step.visualFoci : step.visualFocus ? [step.visualFocus] : []
}

function registerSection(element: unknown, position: number) {
  const previous = sectionElements.get(position)
  if (!(element instanceof HTMLElement)) {
    if (previous) sectionObserver?.unobserve(previous)
    sectionElements.delete(position)
    visibleSections.delete(position)
    return
  }
  if (previous && previous !== element) sectionObserver?.unobserve(previous)
  sectionElements.set(position, element)
  sectionObserver?.observe(element)
}

function registerNavigationItem(element: unknown, position: number) {
  if (element instanceof HTMLElement) navigationElements.set(position, element)
  else navigationElements.delete(position)
}

function updateVisibleChapter(entries: IntersectionObserverEntry[]) {
  for (const entry of entries) {
    const position = props.sections.find((section) => chapterId(section.position) === entry.target.id)?.position
    if (position === undefined) continue
    visibleSections.set(position, { top: entry.boundingClientRect.top, visible: entry.isIntersecting })
  }
  const closest = [...visibleSections.entries()]
    .filter(([, state]) => state.visible)
    .sort((left, right) => Math.abs(left[1].top) - Math.abs(right[1].top))[0]
  if (closest) activePosition.value = closest[0]
}

watch(activePosition, async (position) => {
  if (position === null) return
  await nextTick()
  if (window.matchMedia?.('(min-width: 1280px)').matches) return
  navigationElements.get(position)?.scrollIntoView?.({
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: 'nearest',
    inline: 'center',
  })
})

watch(() => props.sections.map((section) => section.position), (positions) => {
  if (!positions.includes(activePosition.value ?? Number.NaN)) activePosition.value = positions[0] ?? null
})

onMounted(async () => {
  const hashSection = props.sections.find((section) => window.location.hash === `#${chapterId(section.position)}`)
  if (hashSection) {
    activePosition.value = hashSection.position
    await nextTick()
    sectionElements.get(hashSection.position)?.scrollIntoView?.({ behavior: 'auto', block: 'start' })
  }
  if (!('IntersectionObserver' in window)) return
  sectionObserver = new IntersectionObserver(updateVisibleChapter, {
    rootMargin: '-18% 0px -68% 0px',
    threshold: 0,
  })
  sectionElements.forEach((element) => sectionObserver?.observe(element))
})

onUnmounted(() => sectionObserver?.disconnect())

function sourceLabel(pages: number[]) {
  return t('public.step.source', { pages: pages.join(locale.value === 'en' ? ', ' : '、') })
}

function stepTone(kind: string) {
  if (kind === 'WATCH' || kind === 'LIMIT') return 'border-amber-300/70 bg-amber-50/60'
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
    case 'REFERENCE_CARD': return t('lesson.chapter.move.referenceCard')
    case 'LIMIT': return t('lesson.chapter.move.limit')
    default: return kind
  }
}
</script>

<template>
  <div class="mt-7 xl:grid xl:grid-cols-[15rem_minmax(0,1fr)] xl:items-start xl:gap-12">
    <nav data-testid="mobile-chapter-directory" class="player-board app-sticky-below-mobile-header sticky z-10 -mx-2 flex gap-2 overflow-x-auto border border-ink/10 bg-canvas/90 p-2 elevation-sm backdrop-blur xl:hidden" :aria-label="t('lesson.reader.chapterDirectory')">
      <a
        v-for="section in sections"
        :key="section.position"
        :ref="(element) => registerNavigationItem(element, section.position)"
        data-testid="mobile-chapter-link"
        :href="`#${chapterId(section.position)}`"
        class="inline-flex min-h-11 shrink-0 items-center gap-2 rounded-xl px-3 text-sm font-semibold transition focus:outline-none focus:ring-4 focus:ring-indigo/15"
        :class="section.position === activePosition ? 'bg-ink-panel text-panel-text elevation-sm' : 'text-ink/65 hover:bg-paper hover:text-indigo'"
        :aria-current="section.position === activePosition ? 'location' : undefined"
        @click="activateChapter(section.position)"
      >
        <span class="hex-token size-7 text-copper"><span>{{ section.position }}</span></span>
        {{ section.title }}
      </a>
    </nav>

    <aside data-testid="desktop-chapter-directory" class="hidden min-w-0 xl:sticky xl:top-24 xl:block xl:max-h-[calc(100vh-7rem)] xl:overflow-y-auto" :aria-label="t('lesson.reader.chapterDirectory')">
      <nav class="tabletop-panel player-board bg-paper/75 p-3 backdrop-blur">
        <div class="flex items-end justify-between gap-3 border-b border-ink/10 px-2 pb-3 pt-1">
          <div>
            <p class="text-xs font-bold uppercase tracking-[0.14em] text-copper">{{ t('lesson.sidebar.directory') }}</p>
            <p class="mt-1 text-sm font-semibold text-ink">{{ t('lesson.reader.chapterDirectory') }}</p>
          </div>
          <span class="shrink-0 text-xs font-semibold text-ink/45">{{ activeChapterNumber }} / {{ sections.length }}</span>
        </div>
        <ol class="mt-3 stack-y-s">
          <li v-for="section in sections" :key="section.position">
            <a
              :href="`#${chapterId(section.position)}`"
              data-testid="desktop-chapter-link"
              class="group flex min-h-14 w-full items-start gap-3 rounded-2xl px-3 py-3 text-left text-sm transition focus:outline-none focus:ring-4 focus:ring-indigo/15"
              :class="section.position === activePosition ? 'bg-ink-panel text-panel-text elevation-sm' : 'text-ink/65 hover:bg-canvas hover:text-ink'"
              :aria-current="section.position === activePosition ? 'location' : undefined"
              @click="activateChapter(section.position)"
            >
              <span class="hex-token size-7 shrink-0 text-copper"><span class="text-xs font-bold">{{ section.position }}</span></span>
              <span class="min-w-0 break-words font-semibold leading-5">{{ section.title }}</span>
            </a>
          </li>
        </ol>
      </nav>
    </aside>

    <div data-testid="lesson-reading-column" class="min-w-0">
      <section
        v-for="section in sections"
        :id="chapterId(section.position)"
        :key="section.position"
        :ref="(element) => registerSection(element, section.position)"
        class="scroll-mt-32 border-b border-ink/10 py-10 first:pt-8 last:border-b-0 lg:grid lg:grid-cols-[5.5rem_minmax(0,1fr)] lg:gap-7 lg:py-14 lg:first:pt-1"
      >
        <div class="mb-4 lg:mb-0">
          <div class="hex-token size-14 text-copper elevation-sm"><span class="font-display text-xl font-bold">{{ section.position }}</span></div>
          <p class="mt-2 text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ t('public.chapter', { position: section.position }) }}</p>
        </div>

        <div class="min-w-0">
          <h2 class="font-display text-3xl font-semibold tracking-tight sm:text-4xl">{{ section.title }}</h2>
          <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

          <ol class="mt-7 grid gap-4">
            <li v-for="step in section.steps" :key="step.position" :data-testid="stepTestId || undefined" class="rounded-2xl border p-5 lesson-step-shadow sm:p-6" :class="stepTone(step.kind)">
              <div class="flex gap-4">
                <span class="grid size-8 shrink-0 place-items-center rounded-xl bg-ink text-sm font-bold text-canvas">{{ step.position }}</span>
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <h3 class="font-display text-xl font-semibold sm:text-2xl">{{ step.heading }}</h3>
                    <span class="rounded-full border border-ink/10 bg-canvas/70 px-2.5 py-1 text-[11px] font-bold tracking-[0.08em] text-ink/45">{{ stepKindLabel(step.kind) }}</span>
                  </div>
                  <p class="mt-3 text-[0.98rem] leading-7 text-ink/75">{{ step.text }}</p>
                  <LessonRuleFacts v-if="step.ruleFacts?.length" :facts="step.ruleFacts" />

                  <div v-if="stepVisuals(step).length" class="grid gap-3 sm:grid-cols-2" data-testid="lesson-step-visuals">
                    <LessonVisualEvidence
                      v-for="focus in stepVisuals(step)"
                      :key="`${focus.pageNumber}-${focus.x}-${focus.y}-${focus.width}-${focus.height}`"
                      :focus="focus"
                      :page-image-url="props.pageImageUrl"
                      :page-preview-image-url="props.pagePreviewImageUrl"
                      :focused-page-image-url="props.focusedPageImageUrl"
                    />
                  </div>

                  <a v-if="step.sourcePages.length" :href="props.pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener noreferrer" class="mt-4 inline-flex min-h-9 items-center rounded-full border border-ink/10 bg-canvas/70 px-3 text-xs font-semibold text-ink/50 transition hover:border-indigo/30 hover:text-indigo">{{ sourceLabel(step.sourcePages) }} ↗</a>
                </div>
              </div>
            </li>
          </ol>
        </div>
      </section>
    </div>
  </div>
</template>
