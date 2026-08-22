<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'

const props = withDefaults(defineProps<{
  title: string
  imageUrl?: string | null
  alt: string
  index?: number
}>(), { imageUrl: null, index: 0 })

const imageUnavailable = ref(false)
const imageLoaded = ref(false)
const initials = computed(() => props.title.trim().slice(0, 2).toUpperCase())
const tone = computed(() => [
  ['#315264', '#f2ead9'],
  ['#754638', '#fff5e9'],
  ['#4d6e59', '#edf5eb'],
  ['#704b65', '#fff1fa'],
][props.index % 4]!)

watch(() => props.imageUrl, () => {
  imageUnavailable.value = false
  imageLoaded.value = false
})
</script>

<template>
  <div class="lesson-cover size-full" :style="{ '--lesson-cover': tone[0], '--lesson-cover-ink': tone[1] }">
    <div class="absolute inset-3 rounded-xl border border-current/35" aria-hidden="true" />
    <TabletopGlyph name="meeple" :size="52" class="absolute right-3 top-4 opacity-80" />
    <span class="absolute bottom-3 left-4 font-display text-3xl font-semibold tracking-tight" aria-hidden="true">{{ initials }}</span>
    <img
      v-if="imageUrl && !imageUnavailable"
      :src="imageUrl"
      :alt="alt"
      :fetchpriority="index < 4 ? 'high' : 'auto'"
      :class="imageLoaded ? 'opacity-100' : 'opacity-0'"
      class="absolute inset-0 size-full bg-paper p-1.5 object-contain transition-opacity duration-200"
      decoding="async"
      :loading="index < 4 ? 'eager' : 'lazy'"
      referrerpolicy="no-referrer"
      @error="imageUnavailable = true"
      @load="imageLoaded = true"
    >
  </div>
</template>
