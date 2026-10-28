<script setup lang="ts">
import { computed } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

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

const { locale } = useLocale()
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
        class="aspect-[3/4] shrink-0 place-items-center overflow-hidden rounded-md bg-paper text-muted"
        :class="compact ? 'hidden' : 'grid w-16 sm:w-24'"
      >
        <img v-if="coverUrl && !coverUnavailable" :src="coverUrl" :alt="coverAlt" class="size-full bg-paper object-contain p-1" decoding="async" @error="emit('coverError')">
        <div v-else class="relative grid size-full place-items-center">
          <TabletopGlyph name="cards" :size="44" class="opacity-75" />
          <span class="absolute bottom-3 left-3 font-display text-xl font-semibold">{{ initials }}</span>
        </div>
      </component>

      <div class="min-w-0 flex-1 py-1">
        <p v-if="!compact" class="text-xs font-medium text-muted">{{ eyebrow }}</p>
        <h1 class="mt-1 break-words font-semibold leading-tight tracking-tight text-ink" :class="compact ? 'text-2xl' : 'text-2xl sm:text-3xl'">{{ title }}</h1>
        <p v-if="!compact && rulebookTitle && rulebookTitle !== title" class="mt-2 text-sm font-medium text-muted">{{ rulebookTitle }}</p>
        <p v-if="!compact" class="mt-3 max-w-2xl text-sm leading-6 text-muted sm:text-base">{{ description }}</p>
        <details v-if="compact && ($slots.actions || rulebookTitle)" class="mt-2 text-xs text-muted">
          <summary class="min-h-9 cursor-pointer py-2">{{ locale === 'en' ? 'Rulebook and game details' : '规则书与桌游资料' }}</summary>
          <p v-if="rulebookTitle" class="py-2">{{ rulebookTitle }}</p>
          <div class="flex flex-wrap gap-3"><slot name="actions" /></div>
        </details>
        <div v-else-if="$slots.actions" class="mt-5 flex flex-wrap gap-3"><slot name="actions" /></div>
      </div>
    </div>
    <div v-if="$slots.status" class="relative mt-5"><slot name="status" /></div>
  </header>
</template>
