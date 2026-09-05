<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'

const props = withDefaults(defineProps<{
  bggId: number
  alt: string
  sourceThumbnail?: string
  upgrade?: boolean
  loading?: 'eager' | 'lazy'
  fetchPriority?: 'high' | 'low' | 'auto'
}>(), {
  upgrade: true,
  loading: 'eager',
  fetchPriority: 'high',
})

const thumbnailAttempt = ref(0)
const thumbnailLoaded = ref(false)
const thumbnailUnavailable = ref(false)
const displayUnavailable = ref(false)

const thumbnailUrl = computed(() => {
  if (thumbnailAttempt.value === 0 && props.sourceThumbnail) return props.sourceThumbnail
  const base = `/api/v1/bgg/catalog/covers/${props.bggId}/thumbnail`
  return thumbnailAttempt.value === 0 ? base : `${base}?retry=1`
})
const displayUrl = computed(() => `/api/v1/bgg/catalog/covers/${props.bggId}/image`)

function belongsToCurrentCover(event: Event) {
  const image = event.currentTarget as HTMLImageElement | null
  return image?.dataset.coverId === String(props.bggId)
}

function belongsToCurrentThumbnail(event: Event) {
  const image = event.currentTarget as HTMLImageElement | null
  return belongsToCurrentCover(event)
    && image?.dataset.coverAttempt === String(thumbnailAttempt.value)
}

function handleThumbnailLoad(event: Event) {
  if (!belongsToCurrentThumbnail(event)) return
  thumbnailLoaded.value = true
}

function handleThumbnailError(event: Event) {
  if (!belongsToCurrentThumbnail(event)) return
  if (thumbnailAttempt.value === 0) {
    thumbnailAttempt.value = 1
    return
  }
  thumbnailUnavailable.value = true
}

function handleDisplayError(event: Event) {
  if (belongsToCurrentCover(event)) displayUnavailable.value = true
}

watch(() => props.bggId, () => {
  thumbnailAttempt.value = 0
  thumbnailLoaded.value = false
  thumbnailUnavailable.value = false
  displayUnavailable.value = false
}, { flush: 'sync' })
</script>

<template>
  <div class="relative overflow-hidden">
    <img
      v-if="!thumbnailUnavailable"
      :key="`${bggId}-${thumbnailAttempt}`"
      :src="thumbnailUrl"
      :alt="alt"
      :data-cover-id="bggId"
      :data-cover-attempt="thumbnailAttempt"
      data-cover-kind="thumbnail"
      class="h-full w-full object-contain"
      width="720"
      height="960"
      :loading="loading"
      :fetchpriority="fetchPriority"
      decoding="async"
      @load="handleThumbnailLoad"
      @error="handleThumbnailError"
    >
    <div
      v-else
      data-cover-kind="placeholder"
      role="img"
      :aria-label="alt"
      class="grid h-full w-full place-items-center text-ink/25"
    >
      <TabletopGlyph name="cards" :size="48" />
    </div>
    <img
      v-if="upgrade && thumbnailLoaded && !displayUnavailable"
      :key="`display-${bggId}`"
      :src="displayUrl"
      alt=""
      aria-hidden="true"
      :data-cover-id="bggId"
      data-cover-kind="display"
      class="absolute inset-0 h-full w-full object-contain"
      width="960"
      height="1280"
      loading="eager"
      fetchpriority="low"
      decoding="async"
      @error="handleDisplayError"
    >
  </div>
</template>
