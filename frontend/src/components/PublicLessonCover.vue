<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'

const props = withDefaults(defineProps<{
  title: string
  imageUrl?: string | null
  alt: string
  index?: number
}>(), { imageUrl: null, index: 0 })

const coverRoot = ref<HTMLElement | null>(null)
const imageUnavailable = ref(false)
const imageLoaded = ref(false)
const shouldRequestImage = ref(false)
const initials = computed(() => props.title.trim().slice(0, 2).toUpperCase())
const tone = computed(() => [
  ['#324864', '#f5f0e8'],
  ['#754638', '#fff5e9'],
  ['#4d6e59', '#edf5eb'],
  ['#704b65', '#fff1fa'],
][props.index % 4]!)

let imageObserver: IntersectionObserver | undefined

function stopObservingImage() {
  imageObserver?.disconnect()
  imageObserver = undefined
}

function requestImage() {
  shouldRequestImage.value = true
  stopObservingImage()
}

function observeImage() {
  stopObservingImage()
  shouldRequestImage.value = false

  if (!props.imageUrl) return

  if (!coverRoot.value || typeof IntersectionObserver === 'undefined') {
    requestImage()
    return
  }

  imageObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) requestImage()
  }, { rootMargin: '80px 0px', threshold: 0.01 })
  imageObserver.observe(coverRoot.value)
}

onMounted(observeImage)
onBeforeUnmount(stopObservingImage)

watch(() => props.imageUrl, () => {
  imageUnavailable.value = false
  imageLoaded.value = false
  observeImage()
})
</script>

<template>
  <div ref="coverRoot" class="lesson-cover size-full" :style="{ '--lesson-cover': tone[0], '--lesson-cover-ink': tone[1] }">
    <div class="absolute inset-3 rounded-xl border border-current/35" aria-hidden="true" />
    <TabletopGlyph name="meeple" :size="52" class="absolute right-3 top-4 opacity-80" />
    <span class="absolute bottom-3 left-4 font-display text-3xl font-semibold tracking-tight" aria-hidden="true">{{ initials }}</span>
    <img
      v-if="imageUrl && shouldRequestImage && !imageUnavailable"
      :src="imageUrl"
      :alt="alt"
      :fetchpriority="index < 2 ? 'high' : 'auto'"
      :class="imageLoaded ? 'opacity-100' : 'opacity-0'"
      class="absolute inset-0 size-full bg-paper p-1.5 object-contain transition-opacity duration-200"
      decoding="async"
      loading="lazy"
      referrerpolicy="no-referrer"
      @error="imageUnavailable = true"
      @load="imageLoaded = true"
    >
  </div>
</template>
