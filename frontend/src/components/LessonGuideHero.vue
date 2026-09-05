<script setup lang="ts">
import { computed } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'

const props = withDefaults(defineProps<{
  title: string
  eyebrow: string
  description: string
  rulebookTitle?: string
  coverUrl?: string
  coverAlt?: string
  coverHref?: string
  coverUnavailable?: boolean
  compact?: boolean
}>(), {
  rulebookTitle: '',
  coverUrl: '',
  coverAlt: '',
  coverHref: '',
  coverUnavailable: false,
  compact: false,
})

const emit = defineEmits<{ coverError: [] }>()
const initials = computed(() => props.title.trim().slice(0, 2).toLocaleUpperCase())
</script>

<template>
  <header class="relative border-b border-ink/10" :class="compact ? 'pb-4' : 'pb-6'">
    <div class="relative flex items-start gap-5 sm:gap-7">
      <component
        :is="coverHref ? 'a' : 'div'"
        :href="coverHref || undefined"
        :target="coverHref ? '_blank' : undefined"
        :rel="coverHref ? 'noopener noreferrer' : undefined"
        class="aspect-[3/4] shrink-0 place-items-center overflow-hidden rounded-md bg-paper text-ink/60"
        :class="compact ? 'hidden' : 'grid w-16 sm:w-24'"
      >
        <img v-if="coverUrl && !coverUnavailable" :src="coverUrl" :alt="coverAlt" class="size-full bg-paper object-contain p-1" decoding="async" @error="emit('coverError')">
        <div v-else class="relative grid size-full place-items-center">
          <TabletopGlyph name="cards" :size="44" class="opacity-75" />
          <span class="absolute bottom-3 left-3 font-display text-xl font-semibold">{{ initials }}</span>
        </div>
      </component>

      <div class="min-w-0 flex-1 py-1">
        <p class="text-xs font-medium text-ink/60">{{ eyebrow }}</p>
        <h1 class="mt-1 break-words font-semibold leading-tight tracking-tight text-ink" :class="compact ? 'text-2xl' : 'text-2xl sm:text-3xl'">{{ title }}</h1>
        <p v-if="rulebookTitle && rulebookTitle !== title" class="mt-2 text-sm font-medium text-ink/60">{{ rulebookTitle }}</p>
        <p class="max-w-2xl text-sm text-ink/60 sm:text-base" :class="compact ? 'mt-3 leading-6' : 'mt-3 leading-6'">{{ description }}</p>
        <div v-if="$slots.actions" class="mt-5 flex flex-wrap gap-3"><slot name="actions" /></div>
      </div>
    </div>
    <div v-if="$slots.status" class="relative mt-5"><slot name="status" /></div>
  </header>
</template>
