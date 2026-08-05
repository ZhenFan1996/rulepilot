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
  <header class="tabletop-hero relative overflow-hidden rounded-[2rem] border border-ink/10 px-5 shadow-[0_24px_70px_-48px_rgba(16,31,38,0.75)] sm:px-8 sm:py-8" :class="compact ? 'py-5' : 'py-6'">
    <div class="pointer-events-none absolute -right-10 -top-12 size-44 rotate-12 rounded-[2rem] border border-paper/15" aria-hidden="true" />
    <div class="pointer-events-none absolute bottom-5 right-8 hidden gap-2 text-paper/20 sm:flex" aria-hidden="true">
      <TabletopGlyph name="dice" :size="28" />
      <TabletopGlyph name="meeple" :size="30" />
      <TabletopGlyph name="cards" :size="28" />
    </div>

    <div class="relative flex items-start gap-5 sm:gap-7">
      <component
        :is="coverHref ? 'a' : 'div'"
        :href="coverHref || undefined"
        :target="coverHref ? '_blank' : undefined"
        :rel="coverHref ? 'noopener noreferrer' : undefined"
        class="aspect-[3/4] shrink-0 place-items-center overflow-hidden rounded-2xl border border-paper/20 bg-paper/10 text-paper shadow-xl sm:grid sm:w-32"
        :class="compact ? 'hidden' : 'grid w-24'"
      >
        <img v-if="coverUrl && !coverUnavailable" :src="coverUrl" :alt="coverAlt" class="size-full bg-paper object-contain p-1" decoding="async" @error="emit('coverError')">
        <div v-else class="relative grid size-full place-items-center">
          <TabletopGlyph name="cards" :size="44" class="opacity-75" />
          <span class="absolute bottom-3 left-3 font-display text-xl font-semibold">{{ initials }}</span>
        </div>
      </component>

      <div class="min-w-0 flex-1 py-1">
        <p class="text-xs font-bold uppercase tracking-[0.16em] text-[#f0c878]">{{ eyebrow }}</p>
        <h1 class="mt-2 break-words font-display font-semibold leading-[1.05] tracking-tight text-paper sm:text-5xl" :class="compact ? 'text-3xl' : 'text-4xl'">{{ title }}</h1>
        <p v-if="rulebookTitle && rulebookTitle !== title" class="mt-2 text-sm font-medium text-paper/55">{{ rulebookTitle }}</p>
        <p class="max-w-2xl text-sm text-paper/72 sm:text-base" :class="compact ? 'mt-3 leading-6' : 'mt-4 leading-7'">{{ description }}</p>
        <div v-if="$slots.actions" class="mt-5 flex flex-wrap gap-3"><slot name="actions" /></div>
      </div>
    </div>
    <div v-if="$slots.status" class="relative mt-5"><slot name="status" /></div>
  </header>
</template>
