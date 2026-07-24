<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'

const props = defineProps<{
  currentIndex: number
  sectionCount: number
  lessonStillGrowing: boolean
  readingCurrentLastChapter: boolean
  waitingForNextChapter: boolean
}>()

const emit = defineEmits<{
  previous: []
  skip: []
  complete: []
}>()

const { t } = useLocale()

const completionLabel = computed(() => {
  if (props.lessonStillGrowing && props.readingCurrentLastChapter) {
    return props.waitingForNextChapter
      ? t('lesson.reader.controls.waiting')
      : t('lesson.reader.controls.completeAndWait')
  }
  return props.currentIndex === props.sectionCount - 1
    ? t('lesson.reader.controls.complete')
    : t('lesson.reader.controls.next')
})
</script>

<template>
  <nav class="fixed inset-x-0 bottom-0 z-30 border-t border-ink/10 bg-canvas/95 p-3 backdrop-blur lg:sticky lg:mx-auto lg:max-w-4xl lg:rounded-2xl lg:border" :aria-label="t('lesson.reader.controls.aria')">
    <div class="mx-auto grid max-w-3xl grid-cols-[0.8fr_1fr_1.5fr] gap-2">
      <button :disabled="currentIndex === 0" class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold disabled:opacity-35" @click="emit('previous')">{{ t('lesson.reader.controls.previous') }}</button>
      <button class="min-h-12 rounded-xl border border-ink/15 px-3 text-sm font-semibold" @click="emit('skip')">{{ t('lesson.reader.controls.skip') }}</button>
      <button :disabled="waitingForNextChapter" class="min-h-12 rounded-xl bg-copper px-3 text-sm font-semibold text-white disabled:cursor-wait disabled:opacity-60" @click="emit('complete')">{{ completionLabel }}</button>
    </div>
  </nav>
</template>
