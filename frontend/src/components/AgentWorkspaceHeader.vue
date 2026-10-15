<script setup lang="ts">

withDefaults(defineProps<{
  eyebrow: string
  title: string
  description: string
  status?: string
}>(), { status: '' })
</script>

<template>
  <header class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
    <div class="min-w-0 flex-1">
      <p class="text-sm text-muted">{{ eyebrow }}</p>
      <h2 class="mt-1 text-2xl font-semibold tracking-tight">{{ title }}</h2>
      <p class="mt-2 max-w-2xl text-sm leading-6 text-muted">{{ description }}</p>
    </div>
    <div class="flex shrink-0 flex-wrap items-center gap-2 lg:max-w-[45%] lg:justify-end">
      <span v-if="status" class="tabletop-chip min-h-9 px-3">{{ status }}</span>
      <slot />
    </div>
  </header>
</template>
