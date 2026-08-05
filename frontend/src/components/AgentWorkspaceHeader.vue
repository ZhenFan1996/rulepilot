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
      <div class="relative grid size-12 shrink-0 place-items-center rounded-2xl bg-ink text-canvas shadow-lg">
        <TabletopGlyph name="spark" :size="24" />
        <span class="absolute -bottom-1 -right-1 size-3 rounded-full border-2 border-paper bg-emerald-500" aria-hidden="true" />
      </div>
      <div class="min-w-0">
        <p class="text-xs font-bold uppercase tracking-[0.16em] text-indigo">{{ eyebrow }}</p>
        <h2 class="mt-1 font-display text-3xl font-semibold tracking-tight">{{ title }}</h2>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink/60">{{ description }}</p>
      </div>
    </div>
    <div class="flex shrink-0 flex-wrap items-center gap-2 sm:justify-end">
      <span v-if="status" class="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-800"><span class="size-1.5 rounded-full bg-emerald-500" />{{ status }}</span>
      <slot />
    </div>
  </header>
</template>
