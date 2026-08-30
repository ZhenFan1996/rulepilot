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
const labels = computed(() => locale.value === 'en'
  ? { category: 'Category', owner: 'Owner', code: 'Backend code / record' }
  : { category: '分类', owner: '处理方', code: '后端代码 / 记录' })
</script>

<template>
  <div
    data-testid="player-failure-details"
    :data-failure-classification="category"
    class="rounded-xl border border-current/15 bg-canvas/70 px-3 py-2 text-xs leading-5"
  >
    <dl class="flex flex-wrap gap-x-4 gap-y-1">
      <div><dt class="inline opacity-60">{{ labels.category }}：</dt><dd class="inline font-semibold">{{ copy.title }}</dd></div>
      <div><dt class="inline opacity-60">{{ labels.owner }}：</dt><dd class="inline font-semibold">{{ owner }}</dd></div>
      <div v-if="code" class="min-w-0"><dt class="inline opacity-60">{{ labels.code }}：</dt><dd class="inline break-all font-mono">{{ code }}</dd></div>
    </dl>
    <p class="mt-1 opacity-75">{{ copy.detail }}</p>
  </div>
</template>
