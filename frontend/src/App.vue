<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { SESSION_CLEARED_EVENT } from '@/lib/authSession'
import ConnectivityStatus from '@/components/ConnectivityStatus.vue'
import { preloadLocale, useLocale } from '@/lib/locale'
import { focusMainContent, routeDocumentTitle, routeNeedsContentFocus } from '@/lib/routeExperience'

const sessionEpoch = ref(0)
const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
let removeRouteFocusHook: (() => void) | undefined

function clearVisibleSessionState() {
  sessionEpoch.value += 1
}

onMounted(async () => {
  window.addEventListener(SESSION_CLEARED_EVENT, clearVisibleSessionState)
  await router.isReady()
  removeRouteFocusHook = router.afterEach(async (to, from, failure) => {
    if (failure) return
    if (!routeNeedsContentFocus(to, from)) return
    await nextTick()
    focusMainContent()
  })
})
onBeforeUnmount(() => {
  window.removeEventListener(SESSION_CLEARED_EVENT, clearVisibleSessionState)
  removeRouteFocusHook?.()
})

watch([() => route.meta.titleKey, locale], async ([, requestedLocale]) => {
  await preloadLocale(requestedLocale)
  if (locale.value !== requestedLocale) return
  document.title = routeDocumentTitle(route, t)
}, { immediate: true })

</script>

<template>
  <div class="app-root">
    <ConnectivityStatus />
    <a href="#main-content" class="skip-to-content">{{ t('shell.skipToContent') }}</a>
    <RouterView :key="sessionEpoch" />
  </div>
</template>
