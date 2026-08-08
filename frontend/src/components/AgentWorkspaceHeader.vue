<script setup lang="ts">
import TabletopGlyph from '@/components/TabletopGlyph.vue'

withDefaults(defineProps<{
  eyebrow: string
  title: string
  description: string
  status?: string
}>(), { status: '' })
</script>

<template>
  <header class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
    <div class="flex min-w-0 items-start gap-4">
      <div class="grid size-12 shrink-0 place-items-center rounded-xl bg-felt text-white elevation-sm">
        <TabletopGlyph name="rulebook" :size="24" />
      </div>
      <div class="min-w-0">
        <p class="text-xs font-bold uppercase tracking-[0.16em] text-indigo">{{ eyebrow }}</p>
        <h2 class="mt-1 font-display text-3xl font-semibold tracking-tight">{{ title }}</h2>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink/60">{{ description }}</p>
      </div>
    </div>
    <div class="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
      <span v-if="status" class="tabletop-chip min-h-9 px-3">{{ status }}</span>
      <slot />
    </div>
  </header>
</template>
