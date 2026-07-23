<script setup lang="ts">
import TabletopGlyph from '@/components/TabletopGlyph.vue'
</script>

<template>
  <span class="inline-flex items-center gap-3">
    <span class="grid size-9 place-items-center rounded-xl border border-ink/15 bg-paper text-copper shadow-sm" aria-hidden="true">
      <TabletopGlyph name="meeple" :size="22" />
    </span>
    <span class="font-display text-lg font-semibold tracking-tight">RulePilot</span>
  </span>
</template>
