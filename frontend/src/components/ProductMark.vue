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
  width: 2.3rem;
  height: 2.3rem;
  place-items: center;
  border-radius: 0.6rem 0.6rem 0.9rem 0.6rem;
  color: #ffffff;
  background: var(--color-felt);
}

.product-mark__name {
  font-family: var(--font-display);
  font-size: 1.08rem;
  font-weight: 750;
  letter-spacing: -0.018em;
}
</style>
