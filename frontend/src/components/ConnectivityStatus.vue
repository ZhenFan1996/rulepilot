<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { useLocale } from '@/lib/locale'

const props = withDefaults(defineProps<{ reconnectedDurationMs?: number }>(), {
  reconnectedDurationMs: 5000,
})

type ConnectivityState = 'ONLINE' | 'OFFLINE' | 'RECONNECTED'

const ROOT_STATUS_CLASS = 'connectivity-status-visible'
const { t } = useLocale()
const state = ref<ConnectivityState>(navigator.onLine ? 'ONLINE' : 'OFFLINE')
let observedOffline = state.value === 'OFFLINE'
let hideReconnectedTimer: ReturnType<typeof setTimeout> | undefined

const visible = computed(() => state.value !== 'ONLINE')
const offline = computed(() => state.value === 'OFFLINE')
const title = computed(() => t(offline.value ? 'connectivity.offline.title' : 'connectivity.reconnected.title'))
const detail = computed(() => t(offline.value ? 'connectivity.offline.detail' : 'connectivity.reconnected.detail'))

function clearHideTimer() {
  if (hideReconnectedTimer) clearTimeout(hideReconnectedTimer)
  hideReconnectedTimer = undefined
}

function markOffline() {
  clearHideTimer()
  observedOffline = true
  state.value = 'OFFLINE'
}

function markOnline() {
  if (!observedOffline || state.value !== 'OFFLINE') return
  state.value = 'RECONNECTED'
  hideReconnectedTimer = setTimeout(() => {
    state.value = 'ONLINE'
    hideReconnectedTimer = undefined
  }, props.reconnectedDurationMs)
}

watch(visible, value => document.documentElement.classList.toggle(ROOT_STATUS_CLASS, value), { immediate: true })

onMounted(() => {
  window.addEventListener('offline', markOffline)
  window.addEventListener('online', markOnline)
})

onBeforeUnmount(() => {
  clearHideTimer()
  window.removeEventListener('offline', markOffline)
  window.removeEventListener('online', markOnline)
  document.documentElement.classList.remove(ROOT_STATUS_CLASS)
})
</script>

<template>
  <div
    v-if="visible"
    data-testid="connectivity-status"
    class="connectivity-status-bar fixed inset-x-0 top-0 z-[120] flex items-center border-b px-4 text-ink shadow-sm sm:justify-center sm:px-6"
    :class="offline ? 'border-amber-300 bg-amber-100' : 'border-emerald-300 bg-emerald-100'"
    role="status"
    aria-live="polite"
    aria-atomic="true"
  >
    <span class="mr-2.5 size-2.5 shrink-0 rounded-full border-2 border-current" :class="offline ? 'text-amber-800' : 'text-emerald-800'" aria-hidden="true" />
    <p class="min-w-0 text-xs leading-5 sm:flex sm:items-baseline sm:gap-2 sm:text-sm">
      <strong class="block font-bold">{{ title }}</strong>
      <span class="block text-ink/65">{{ detail }}</span>
    </p>
  </div>
</template>
