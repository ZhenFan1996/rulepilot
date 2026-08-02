<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import { SESSION_CLEARED_EVENT } from '@/lib/authSession'

const sessionEpoch = ref(0)

function clearVisibleSessionState() {
  sessionEpoch.value += 1
}

onMounted(() => window.addEventListener(SESSION_CLEARED_EVENT, clearVisibleSessionState))
onBeforeUnmount(() => window.removeEventListener(SESSION_CLEARED_EVENT, clearVisibleSessionState))
</script>

<template>
  <RouterView :key="sessionEpoch" />
</template>
