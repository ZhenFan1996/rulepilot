<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import RulebookPageViewer from '@/components/RulebookPageViewer.vue'
import { useModalFocus } from '@/composables/useModalFocus'
import LessonVisualEvidence from '@/components/LessonVisualEvidence.vue'
import LessonReadingFlow from '@/components/LessonReadingFlow.vue'
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
  readingBlocks?: import('@/composables/lessonSupportingContent').LessonReadingBlock[]
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
  focusedPageImageUrl: (focus: VisualFocus) => string
  stepTestId?: string
}>(), { stepTestId: '' })

const { locale, t } = useLocale()
const sourcePage = ref<number | null>(null)
const sourceDialog = ref<HTMLElement | null>(null)
const sourcePages = computed(() => [...new Set(props.sections.flatMap(section => section.steps.flatMap(step => [
  ...step.sourcePages, ...stepVisuals(step).map(focus => focus.pageNumber),
])))].sort((a, b) => a - b).map(pageNumber => ({ pageNumber, characterCount: 0 })))
function openSource(page: number) { sourcePage.value = page }
function openSourceLink(event: MouseEvent, page: number) {
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  openSource(page)
}
useModalFocus({ dialog: sourceDialog, open: () => sourcePage.value !== null, requestClose: () => { sourcePage.value = null } })

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

const chapterVisuals = computed(() => {
  const result = new Map<string, VisualFocus[]>()
  for (const section of props.sections) {
    const shown = new Set<string>()
    for (const step of section.steps) {
      result.set(`${section.position}:${step.position}`, stepVisuals(step).filter(focus => {
        const identity = `${focus.pageNumber}:${focus.x}:${focus.y}:${focus.width}:${focus.height}`
        if (shown.has(identity)) return false
        shown.add(identity)
        return true
      }))
    }
  }
  return result
})
function visibleVisuals(section: LessonReaderSection, step: LessonReaderStep) {
  return chapterVisuals.value.get(`${section.position}:${step.position}`) ?? []
}

function scrollVisualGroup(event: KeyboardEvent, direction: -1 | 1) {
  if (!(event.currentTarget instanceof HTMLElement)) return
  event.currentTarget.scrollBy({
    left: direction * Math.max(240, event.currentTarget.clientWidth * 0.8),
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}

function stepNarrationId(sectionPosition: number, stepPosition: number) {
  return `${props.idPrefix}-${sectionPosition}-step-${stepPosition}-narration`
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
  return 'border-ink/10'
}

</script>

<template>
  <div class="lesson-chapters mt-7 xl:grid xl:grid-cols-[13rem_minmax(0,1fr)] xl:items-start xl:gap-10">
    <nav data-testid="mobile-chapter-directory" class="app-sticky-below-mobile-header sticky z-10 -mx-2 flex gap-2 overflow-x-auto border border-ink/10 bg-canvas/90 p-2 elevation-sm backdrop-blur xl:hidden" :aria-label="t('lesson.reader.chapterDirectory')">
      <a
        v-for="section in sections"
        :key="section.position"
        :ref="(element) => registerNavigationItem(element, section.position)"
        data-testid="mobile-chapter-link"
        :href="`#${chapterId(section.position)}`"
        class="inline-flex min-h-11 shrink-0 items-center gap-2 rounded-xl px-3 text-sm font-semibold transition focus:outline-none focus:ring-4 focus:ring-indigo/15"
        :class="section.position === activePosition ? 'bg-ink-panel text-panel-text elevation-sm' : 'text-muted hover:bg-paper hover:text-indigo'"
        :aria-current="section.position === activePosition ? 'location' : undefined"
        @click="activateChapter(section.position)"
      >
        <span class="text-xs tabular-nums opacity-70">{{ section.position }}</span>
        {{ section.title }}
      </a>
    </nav>

    <aside data-testid="desktop-chapter-directory" class="hidden min-w-0 xl:sticky xl:top-24 xl:block xl:max-h-[calc(100vh-7rem)] xl:overflow-y-auto" :aria-label="t('lesson.reader.chapterDirectory')">
      <nav class="border-l border-ink/15 pl-3">
        <div class="flex items-end justify-between gap-3 border-b border-ink/10 px-2 pb-3 pt-1">
          <div>
            <p class="mt-1 text-sm font-semibold text-ink">{{ t('lesson.reader.chapterDirectory') }}</p>
          </div>
          <span class="shrink-0 text-xs font-semibold text-muted">{{ activeChapterNumber }} / {{ sections.length }}</span>
        </div>
        <ol class="mt-3 stack-y-s">
          <li v-for="section in sections" :key="section.position">
            <a
              :href="`#${chapterId(section.position)}`"
              data-testid="desktop-chapter-link"
              class="group flex min-h-14 w-full items-start gap-3 rounded-2xl px-3 py-3 text-left text-sm transition focus:outline-none focus:ring-4 focus:ring-indigo/15"
              :class="section.position === activePosition ? 'bg-ink-panel text-panel-text elevation-sm' : 'text-muted hover:bg-canvas hover:text-ink'"
              :aria-current="section.position === activePosition ? 'location' : undefined"
              @click="activateChapter(section.position)"
            >
              <span class="shrink-0 text-xs tabular-nums opacity-70">{{ section.position }}</span>
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
        class="scroll-mt-36 border-b border-ink/10 py-10 first:pt-8 last:border-b-0 lg:py-12 lg:first:pt-1"
      >
        <p class="mb-3 text-sm font-semibold text-indigo">{{ t('public.chapter', { position: section.position }) }}</p>

        <div class="min-w-0">
          <h2 class="lesson-chapter-title font-display text-3xl font-semibold tracking-tight sm:text-4xl">{{ section.title }}</h2>
          <p v-if="section.visualCaption && section.visualCaption !== section.title" class="mt-3 max-w-2xl leading-7 text-muted">{{ section.visualCaption }}</p>

          <ol class="mt-7 divide-y divide-ink/10">
            <li
              v-for="step in section.steps"
              :key="step.position"
              :data-testid="stepTestId || undefined"
              class="min-w-0 py-7 first:pt-0"
              :class="stepTone(step.kind)"
            >
              <div>
                <div
                  :data-testid="visibleVisuals(section, step).length ? 'lesson-step-paired' : undefined"
                  class="min-w-0 flex-1"
                >
                  <div
                    :id="stepNarrationId(section.position, step.position)"
                    data-testid="lesson-step-narrative"
                    class="min-w-0"
                  >
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="text-sm font-semibold tabular-nums text-muted">{{ step.position }}</span>
                      <h3 class="lesson-step-title text-xl font-semibold leading-snug sm:text-2xl">{{ step.heading }}</h3>
                    </div>
                    <LessonReadingFlow v-if="step.readingBlocks?.length" class="mt-4"
                      :blocks="step.readingBlocks" :narration-id="stepNarrationId(section.position, step.position)"
                      :open-page="openSource" :page-image-url="props.pageImageUrl" :focused-page-image-url="props.focusedPageImageUrl" />
                    <!-- Retained pre-flow lessons and offline snapshots have no authored block order. -->
                    <div v-else
                      class="mt-4"
                      :class="visibleVisuals(section, step).length ? 'grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:items-start lg:gap-7' : ''"
                    >
                      <div class="min-w-0">
                        <p class="lesson-step-copy max-w-[70ch] whitespace-pre-line text-base leading-8 text-ink sm:text-[1.0625rem]">{{ step.text }}</p>
                      </div>

                      <ul
                        v-if="visibleVisuals(section, step).length"
                        data-testid="lesson-step-visuals"
                        :aria-label="t('lesson.visualEvidence.group', { total: visibleVisuals(section, step).length })"
                        :tabindex="visibleVisuals(section, step).length > 1 ? 0 : undefined"
                        class="min-w-0"
                        :class="visibleVisuals(section, step).length > 1
                          ? 'flex snap-x snap-mandatory gap-3 overflow-x-auto overscroll-x-contain rounded-xl pb-3 focus:outline-none focus:ring-4 focus:ring-indigo/15'
                          : 'grid'"
                        @keydown.left.prevent="scrollVisualGroup($event, -1)"
                        @keydown.right.prevent="scrollVisualGroup($event, 1)"
                      >
                        <li
                          v-for="(focus, visualIndex) in visibleVisuals(section, step)"
                          :key="`${focus.pageNumber}-${focus.x}-${focus.y}-${focus.width}-${focus.height}`"
                          class="min-w-0"
                          :class="visibleVisuals(section, step).length > 1
                            ? 'w-[90%] shrink-0 snap-start'
                            : ''"
                        >
                          <LessonVisualEvidence
                            class="h-full"
                            :focus="focus"
                            :ordinal="visualIndex + 1"
                            :total="visibleVisuals(section, step).length"
                            :compact="visibleVisuals(section, step).length > 1"
                            :narration-id="stepNarrationId(section.position, step.position)"
                            :open-page="openSource"
                            :page-image-url="props.pageImageUrl"
                            :focused-page-image-url="props.focusedPageImageUrl"
                          />
                        </li>
                      </ul>
                    </div>
                      <details v-if="step.ruleFacts?.length" class="mt-4 border-l-2 border-ink/10 pl-3">
                        <summary class="cursor-pointer py-2 text-sm font-semibold text-muted">{{ locale === 'en' ? 'Rule highlights' : '规则要点' }}</summary>
                        <LessonRuleFacts :facts="step.ruleFacts" />
                      </details>
                      <div v-if="step.sourcePages.length" class="mt-4 flex flex-wrap gap-2">
                        <a v-for="page in step.sourcePages" :key="page" :href="props.pageImageUrl(page)" class="text-xs text-muted underline underline-offset-4" @click="openSourceLink($event, page)">{{ sourceLabel([page]) }}</a>
                      </div>
                  </div>
                </div>
              </div>
            </li>
          </ol>
        </div>
      </section>
    </div>
    <Teleport to="body">
      <div v-if="sourcePage !== null" class="fixed inset-0 z-[80] bg-black/50 p-2 backdrop-blur-sm sm:p-6" @click.self="sourcePage = null">
        <section ref="sourceDialog" role="dialog" aria-modal="true" :aria-label="locale === 'en' ? 'Source rulebook' : '原规则书'" tabindex="-1" class="mx-auto flex h-full max-w-6xl flex-col overflow-hidden rounded-2xl border border-ink/10 bg-canvas shadow-2xl">
          <header class="flex shrink-0 items-center justify-between gap-4 border-b border-ink/10 px-4 py-2">
            <h2 class="font-semibold text-ink">{{ locale === 'en' ? 'Source rulebook' : '原规则书' }}</h2>
            <button type="button" data-modal-initial-focus class="min-h-11 rounded-lg px-4 text-sm font-semibold text-indigo" @click="sourcePage = null">{{ locale === 'en' ? 'Back to guide' : '返回讲解' }} ×</button>
          </header>
          <div class="min-h-0 overflow-auto p-2 sm:p-4">
            <RulebookPageViewer :version-id="idPrefix" :pages="sourcePages" :initial-page="sourcePage" :page-image-url="pageImageUrl" dialog-mode />
          </div>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.lesson-chapters:lang(en) .lesson-chapter-title {
  font-size: clamp(1.5rem, 2.2vw, 2rem);
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.lesson-chapters:lang(en) .lesson-step-title {
  font-size: 1.125rem;
  line-height: 1.4;
}

.lesson-chapters:lang(en) .lesson-step-copy {
  max-width: 65ch;
  line-height: 1.7;
}
</style>
