<script setup lang="ts">
import TabletopGlyph from '@/components/TabletopGlyph.vue'
</script>

<template>
  <span class="product-mark">
    <span class="product-mark__seal" aria-hidden="true">
      <TabletopGlyph name="meeple" :size="20" />
    </span>
    <span class="product-mark__name">RulePilot</span>
  </span>
</template>

<style scoped>
.product-mark {
  display: inline-flex;
  align-items: center;
  gap: 0.7rem;
}

.product-mark__seal {
  display: grid;
  width: 2rem;
  height: 2rem;
  place-items: center;
  border-radius: 0.5rem;
  color: var(--color-indigo);
  background: color-mix(in srgb, var(--color-indigo) 9%, var(--color-paper));
}

.product-mark__name {
  font-family: var(--font-display);
  font-size: 1.08rem;
  font-weight: 650;
  letter-spacing: -0.018em;
}
</style>
