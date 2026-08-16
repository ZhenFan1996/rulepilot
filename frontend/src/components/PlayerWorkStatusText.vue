<script setup lang="ts">
import type { PlayerWorkStatus } from '@/lib/playerWorkStatus'

withDefaults(defineProps<{
  status: PlayerWorkStatus
  as?: 'p' | 'span' | 'strong'
}>(), {
  as: 'p',
})
</script>

<template>
  <component
    :is="as"
    data-testid="player-work-status"
    :data-player-work-stage="status.stage"
    :data-player-work-capability="status.capability"
    :data-player-work-readiness="status.readiness"
    :data-player-work-terminality="status.terminality"
    :data-player-work-outcome="status.outcome"
  >
    {{ status.label }}
  </component>
</template>
