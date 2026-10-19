<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'
import {
  playerFailureCategoryCopy,
  type PlayerFailureCategory,
} from '@/lib/playerFailureSemantics'

const props = defineProps<{
  category: PlayerFailureCategory
  owner: string
  code?: string | null
}>()

const { locale } = useLocale()
const copy = computed(() => playerFailureCategoryCopy(props.category, locale.value))
</script>

<template>
  <div
    data-testid="player-failure-details"
    :data-failure-classification="category"
    class="rounded-xl border border-current/15 bg-canvas/70 px-3 py-2 text-xs leading-5"
  >
    <p class="font-semibold">{{ copy.title }}</p>
    <p class="mt-1 opacity-75">{{ copy.detail }}</p>
  </div>
</template>
