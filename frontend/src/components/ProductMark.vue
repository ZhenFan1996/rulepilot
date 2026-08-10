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
  position: relative;
  display: grid;
  width: 2.35rem;
  height: 2.5rem;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--color-gold) 58%, var(--color-border));
  border-radius: 1.2rem 1.2rem 0.68rem 0.68rem;
  color: var(--color-copper);
  background: var(--color-paper);
  box-shadow: 0 10px 24px -19px color-mix(in srgb, var(--color-ink-panel) 85%, transparent);
}

.product-mark__seal::after {
  position: absolute;
  inset: 0.22rem;
  border: 1px solid color-mix(in srgb, var(--color-gold) 24%, transparent);
  border-radius: 0.9rem 0.9rem 0.45rem 0.45rem;
  content: '';
  pointer-events: none;
}

.product-mark__name {
  font-family: var(--font-display);
  font-size: 1.08rem;
  font-weight: 650;
  letter-spacing: -0.018em;
}
</style>
