<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { VisualFocus } from '@/composables/lessonSupportingContent'
import { useLocale } from '@/lib/locale'

const props = defineProps<{
  focus: VisualFocus
  pageImageUrl: (page: number) => string
  pagePreviewImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
}>()

const { t } = useLocale()
const contextFailed = ref(false)
const detailFailed = ref(false)
const originalPageUrl = computed(() => props.pageImageUrl(props.focus.pageNumber))
const contextImageUrl = computed(() => props.pagePreviewImageUrl(props.focus.pageNumber))
const detailIsReliable = computed(() => isReliableDetailViewport(props.focus))
const detailImageUrl = computed(() => detailIsReliable.value ? props.focusedPageImageUrl(props.focus) : '')
const focusStyle = computed(() => {
  const left = boundedPercent(props.focus.x)
  const top = boundedPercent(props.focus.y)
  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${Math.min(boundedPercent(props.focus.width), 100 - left)}%`,
    height: `${Math.min(boundedPercent(props.focus.height), 100 - top)}%`,
  }
})

watch([contextImageUrl, detailImageUrl], () => {
  contextFailed.value = false
  detailFailed.value = false
})

function boundedPercent(value: number) {
  return Math.max(0, Math.min(100, value / 10))
}

function isReliableDetailViewport(focus: VisualFocus) {
  const touchesHorizontalTrim = focus.x <= 20 || focus.x + focus.width >= 980
  const touchesVerticalTrim = focus.y <= 20 || focus.y + focus.height >= 980
  const minorDimension = Math.max(1, Math.min(focus.width, focus.height))
  const aspectRatio = Math.max(focus.width, focus.height) / minorDimension

  // A thin strip clipped into a page corner is often a header, footer, or truncated model rectangle. The full-page
  // locator remains useful, but presenting that strip as a confident close-up would create false precision.
  return !(touchesHorizontalTrim && touchesVerticalTrim && minorDimension <= 140 && aspectRatio >= 4)
}
</script>

<template>
  <figure
    data-testid="lesson-visual-storyboard"
    class="mt-5 overflow-hidden rounded-2xl border border-indigo/15 bg-canvas"
  >
    <figcaption class="border-b border-indigo/10 bg-indigo/[0.045] px-4 py-3">
      <p class="text-xs font-bold uppercase tracking-[0.12em] text-indigo">{{ t('lesson.chapter.visual.observationEyebrow') }}</p>
      <p class="mt-1 text-sm leading-6 text-ink/70">{{ focus.visibleDescription || focus.label }}</p>
    </figcaption>

    <ol class="grid gap-3 p-3 md:p-4 lg:grid-cols-[12rem_minmax(0,1fr)] lg:items-start">
      <li class="min-w-0 rounded-xl border border-ink/10 bg-paper p-2.5">
        <div class="flex items-center justify-between gap-2 px-1 pb-2">
          <div>
            <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-copper">{{ t('lesson.visualStoryboard.context.step') }}</p>
            <p class="mt-0.5 text-sm font-semibold text-ink">{{ t(detailIsReliable ? 'lesson.visualStoryboard.context.title' : 'lesson.visualStoryboard.contextOnly.title') }}</p>
          </div>
          <span class="shrink-0 text-xs font-semibold text-ink/45">{{ t('lesson.chapter.page', { page: focus.pageNumber }) }}</span>
        </div>

        <a
          v-if="contextImageUrl && !contextFailed"
          data-testid="lesson-visual-context"
          :href="originalPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="relative block overflow-hidden rounded-lg border border-ink/10 bg-canvas focus:outline-none focus:ring-4 focus:ring-indigo/15"
        >
          <img
            :src="contextImageUrl"
            :alt="t(
              detailIsReliable
                ? 'lesson.visualStoryboard.context.alt'
                : 'lesson.visualStoryboard.contextOnly.alt',
              { page: focus.pageNumber, label: focus.label },
            )"
            class="block h-auto w-full"
            loading="lazy"
            decoding="async"
            @error="contextFailed = true"
          >
          <span
            v-if="detailIsReliable"
            data-testid="lesson-visual-context-focus"
            class="pointer-events-none absolute rounded border-2 border-copper bg-copper/10 ocr-focus-shadow"
            :style="focusStyle"
            aria-hidden="true"
          />
        </a>
        <div v-else class="rounded-lg border border-dashed border-ink/15 bg-canvas px-3 py-4 text-center">
          <p class="text-xs leading-5 text-ink/55">{{ t('lesson.visualStoryboard.context.unavailable') }}</p>
        </div>
      </li>

      <li v-if="detailIsReliable" class="min-w-0 rounded-xl border border-indigo/12 bg-paper p-2.5">
        <div class="px-1 pb-2">
          <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-indigo">{{ t('lesson.visualStoryboard.detail.step') }}</p>
          <p class="mt-0.5 text-sm font-semibold text-ink">{{ t('lesson.visualStoryboard.detail.title') }}</p>
        </div>

        <a
          v-if="detailImageUrl && !detailFailed"
          data-testid="lesson-visual-detail"
          :href="originalPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="block overflow-hidden rounded-lg border border-indigo/10 bg-canvas focus:outline-none focus:ring-4 focus:ring-indigo/15"
        >
          <img
            :src="detailImageUrl"
            :alt="t('lesson.visualStoryboard.detail.alt', { page: focus.pageNumber, label: focus.label })"
            class="block max-h-[28rem] w-full object-contain"
            loading="lazy"
            decoding="async"
            @error="detailFailed = true"
          >
        </a>
        <div v-else class="rounded-lg border border-dashed border-indigo/15 bg-canvas px-3 py-6 text-center">
          <p class="text-xs leading-5 text-ink/55">{{ t('lesson.visualStoryboard.detail.unavailable') }}</p>
        </div>
      </li>

      <li v-else data-testid="lesson-visual-detail-unreliable" class="min-w-0 rounded-xl border border-dashed border-copper/25 bg-paper p-4">
        <p class="text-[11px] font-bold uppercase tracking-[0.1em] text-copper">{{ t('lesson.visualStoryboard.detail.protectedStep') }}</p>
        <p class="mt-2 text-sm font-semibold text-ink">{{ t('lesson.visualStoryboard.detail.protectedTitle') }}</p>
        <p class="mt-2 text-sm leading-6 text-ink/60">{{ t('lesson.visualStoryboard.detail.protectedBody') }}</p>
      </li>
    </ol>

    <div class="border-t border-indigo/10 px-4 py-3 text-xs leading-5 text-ink/55">
      <p>{{ t(detailIsReliable ? 'lesson.visualStoryboard.boundary' : 'lesson.visualStoryboard.boundaryProtected') }}</p>
      <a :href="originalPageUrl" target="_blank" rel="noopener noreferrer" class="mt-2 inline-flex font-semibold text-indigo hover:underline">{{ t('lesson.visualStoryboard.openOriginal') }} ↗</a>
    </div>
  </figure>
</template>
