<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import BackgroundWorkCenter from '@/components/BackgroundWorkCenter.vue'
import { LOGIN_REQUIRED_EVENT, notifySessionCleared } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

withDefaults(defineProps<{ immersive?: boolean }>(), { immersive: false })

const route = useRoute()
const { t } = useLocale()
type Appearance = 'light' | 'dark'

const APPEARANCE_PREFERENCE_KEY = 'rulepilot:appearance-preference'

function preferredAppearance(): Appearance {
  const storedPreference = localStorage.getItem(APPEARANCE_PREFERENCE_KEY)
  if (storedPreference === 'light' || storedPreference === 'dark') return storedPreference
  if (document.documentElement.classList.contains('light')) return 'light'
  if (document.documentElement.classList.contains('dark')) return 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const appearance = ref<Appearance>(preferredAppearance())
const isDark = computed(() => appearance.value === 'dark')
const username = ref('')
const roles = ref<string[]>([])
const loginReminderVisible = ref(false)
const backgroundWorkCenter = ref<{ openCenter: () => void } | null>(null)
const backgroundActiveCount = ref(0)
const backgroundFinishedCount = ref(0)

const navigation = [
  { name: 'home', path: '/', labelKey: 'nav.home', icon: 'compass' },
  { name: 'game-recommendations', path: '/discover', labelKey: 'nav.discover', icon: 'meeple' },
  { name: 'public-library', path: '/library', labelKey: 'nav.library', icon: 'library' },
  { name: 'teach', path: '/teach', labelKey: 'nav.rulebook', icon: 'rulebook' },
  { name: 'lessons', path: '/lessons', labelKey: 'nav.lessons', icon: 'cards' },
  { name: 'catalog', path: '/catalog', labelKey: 'nav.games', icon: 'meeple' },
  { name: 'account', path: '/account', labelKey: 'nav.account', icon: 'players' },
] as const
const mobileNavigation = navigation.filter((item) => item.name !== 'account' && item.name !== 'public-library')

const currentNavigationName = computed(() => {
  if (route.name === 'catalog-manage') return 'catalog'
  if (route.name === 'public-lesson' || route.name === 'public-lesson-questions') return 'public-library'
  if (route.name === 'lesson' || route.name === 'lesson-questions') return 'lessons'
  if (route.name === 'game-discovery' || route.name === 'game-catalog-browse') return 'game-recommendations'
  return route.name
})
const isAdmin = computed(() => roles.value.includes('ADMIN') || roles.value.includes('ROLE_ADMIN'))
const loginTarget = computed(() => ({
  name: 'login',
  query: route.name === 'login' ? undefined : { redirect: route.fullPath },
}))

function applyAppearance(nextAppearance: Appearance, persist = true) {
  appearance.value = nextAppearance
  document.documentElement.classList.toggle('dark', nextAppearance === 'dark')
  document.documentElement.classList.toggle('light', nextAppearance === 'light')
  if (persist) localStorage.setItem(APPEARANCE_PREFERENCE_KEY, nextAppearance)
}

function toggleTheme() {
  applyAppearance(isDark.value ? 'light' : 'dark')
}

async function loadSession() {
  try {
    const response = await fetch('/api/auth/session', { credentials: 'include' })
    if (response.ok) {
      const session = await response.json() as { username: string; roles?: string[] }
      username.value = session.username
      roles.value = Array.isArray(session.roles) ? session.roles : []
    }
  } catch {
    username.value = ''
    roles.value = []
  }
}

function showLoginReminder() {
  username.value = ''
  roles.value = []
  loginReminderVisible.value = true
}

function updateBackgroundWorkStatus(activeCount: number, finishedCount: number) {
  backgroundActiveCount.value = activeCount
  backgroundFinishedCount.value = finishedCount
}

function openBackgroundWork() {
  backgroundWorkCenter.value?.openCenter()
}

async function logout() {
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!csrfResponse.ok) return
  const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
  const response = await fetch('/api/auth/logout', { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token } })
  if (!response.ok) return
  username.value = ''
  roles.value = []
  sessionStorage.removeItem('rulepilot:active-teaching-runs')
  sessionStorage.removeItem('rulepilot:completed-teaching-runs')
  notifySessionCleared()
}

onMounted(() => applyAppearance(appearance.value, false))
onMounted(loadSession)
onMounted(() => window.addEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder))
onBeforeUnmount(() => {
  window.removeEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder)
})
</script>

<template>
  <div class="tabletop-app min-h-screen bg-canvas text-ink lg:pl-64">
    <aside class="drawer-shelf fixed inset-y-0 left-0 z-30 hidden w-64 flex-col overflow-y-auto border-r border-white/8 bg-ink-panel px-4 py-5 text-[#f5f0e8] lg:flex">
      <div class="drawer-shelf__ornament" aria-hidden="true">❦</div>
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')" class="relative mx-2 rounded-xl focus-visible:outline-offset-4">
        <ProductMark />
      </RouterLink>

      <nav class="relative mt-7 stack-y-s" :aria-label="t('shell.primaryNav')">
        <RouterLink
          v-for="item in navigation"
          :key="item.name"
          :to="item.path"
          class="drawer-link group flex min-h-11 items-center gap-3 px-3 text-sm font-semibold transition-[color,background-color,translate]"
          :class="currentNavigationName === item.name ? 'border-[#d5b46a]/55 bg-[#f8efdf] text-[#27312e] drawer-link-active' : 'border-transparent text-[#f5f0e8]/66 hover:translate-x-0.5 hover:bg-white/7 hover:text-[#f5f0e8]'"
        >
          <TabletopGlyph :name="item.icon" :size="19" class="shrink-0" :class="currentNavigationName === item.name ? 'text-copper' : 'text-[#f5f0e8]/48 group-hover:text-[#f5f0e8]'" />
          <span>{{ t(item.labelKey) }}</span>
        </RouterLink>
        <RouterLink
          v-if="isAdmin"
          :to="{ name: 'agent-audit' }"
          class="drawer-link flex min-h-11 items-center gap-3 px-3 text-sm font-semibold transition-colors"
          :class="currentNavigationName === 'agent-audit' ? 'border-[#d5b46a]/55 bg-[#f8efdf] text-[#27312e]' : 'border-transparent text-[#f5f0e8]/58 hover:bg-white/7 hover:text-[#f5f0e8]'"
        >
          <TabletopGlyph name="rulebook" :size="19" class="shrink-0" />
          <span>{{ t('nav.agentAudit') }}</span>
        </RouterLink>
      </nav>

      <div v-if="username && !immersive" id="background-work-desktop-trigger" class="relative mt-auto border-t border-white/10 pt-4">
        <button
          type="button"
          data-testid="background-work-trigger-desktop"
          class="flex min-h-11 w-full items-center gap-3 rounded-xl border border-white/12 bg-white/6 px-3 text-sm font-semibold text-[#f5f0e8]/78 transition hover:border-[#d5b46a]/45 hover:bg-white/10 hover:text-[#f5f0e8]"
          :aria-label="t('shell.backgroundWork')"
          aria-haspopup="dialog"
          @click="openBackgroundWork"
        >
          <span class="grid size-8 shrink-0 place-items-center rounded-full bg-[#d5b46a]/14 text-[#e0be74]" aria-hidden="true"><TabletopGlyph name="cards" :size="17" /></span>
          <span class="min-w-0 flex-1 text-left">{{ t('shell.backgroundWork') }}</span>
          <span v-if="backgroundActiveCount" class="grid min-w-5 place-items-center rounded-full bg-copper px-1.5 text-xs text-white">{{ backgroundActiveCount }}</span>
          <span v-else-if="backgroundFinishedCount" class="size-2 rounded-full bg-emerald-500" aria-hidden="true" />
        </button>
      </div>

      <div class="relative border-t border-white/10 pt-4" :class="username && !immersive ? 'mt-3' : 'mt-auto'">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="mb-2 flex min-h-11 items-center gap-3 rounded-xl bg-white/6 px-3 text-sm font-semibold hover:bg-white/10">
          <span class="grid h-7 w-7 place-items-center rounded-full bg-copper text-xs text-white">{{ username.slice(0, 1).toUpperCase() }}</span>
          <span class="truncate">{{ username }}</span>
        </RouterLink>
        <div class="mb-2 px-1"><LanguageSwitcher /></div>
        <button class="flex min-h-10 w-full items-center justify-between rounded-lg px-3 text-sm text-[#f5f0e8]/55 hover:bg-white/7 hover:text-[#f5f0e8]" :aria-label="isDark ? t('shell.theme.toLight') : t('shell.theme.toDark')" @click="toggleTheme">
          <span>{{ isDark ? t('shell.theme.light') : t('shell.theme.dark') }}</span>
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
        <button v-if="username" class="mt-1 flex min-h-10 w-full items-center rounded-lg px-3 text-sm text-[#f5f0e8]/55 hover:bg-white/7 hover:text-[#f5f0e8]" @click="logout">{{ t('shell.signOut') }}</button>
        <RouterLink v-else :to="loginTarget" class="mt-1 flex min-h-10 items-center rounded-lg px-3 text-sm text-[#f5f0e8]/55 hover:bg-white/7 hover:text-[#f5f0e8]">{{ t('shell.signIn') }}</RouterLink>
      </div>
    </aside>

    <header class="mobile-app-header sticky top-0 z-30 flex h-16 items-center justify-between border-b border-ink/10 bg-paper/95 px-3 backdrop-blur lg:hidden">
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')"><ProductMark /></RouterLink>
      <div class="flex min-w-0 items-center gap-1.5">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="max-w-16 truncate text-sm font-semibold text-ink/60 max-[480px]:hidden">{{ username }}</RouterLink>
        <RouterLink v-else :to="loginTarget" class="inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-semibold text-indigo">{{ t('shell.signIn') }}</RouterLink>
        <button
          v-if="username && !immersive"
          type="button"
          data-testid="background-work-trigger-mobile"
          class="relative grid min-h-11 min-w-11 place-items-center rounded-xl border border-ink/10 bg-canvas/65 text-ink/62 transition hover:border-copper/35 hover:text-ink"
          :aria-label="t('shell.backgroundWork')"
          aria-haspopup="dialog"
          @click="openBackgroundWork"
        >
          <TabletopGlyph name="cards" :size="18" />
          <span v-if="backgroundActiveCount" class="absolute -right-1 -top-1 grid min-w-5 place-items-center rounded-full bg-copper px-1 text-[0.65rem] text-white">{{ backgroundActiveCount }}</span>
          <span v-else-if="backgroundFinishedCount" class="absolute right-1 top-1 size-2 rounded-full bg-emerald-500" aria-hidden="true" />
        </button>
        <LanguageSwitcher />
        <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-lg text-ink/60 hover:bg-ink/5 hover:text-ink" :aria-label="isDark ? t('shell.theme.toLight') : t('shell.theme.toDark')" :aria-pressed="isDark" @click="toggleTheme">
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
      </div>
    </header>

    <main class="min-h-screen pb-20 lg:pb-0">
      <aside v-if="loginReminderVisible" class="border-b border-copper/25 bg-copper/10 px-4 py-3 sm:px-8" role="status">
        <div class="mx-auto flex max-w-7xl items-start gap-3">
          <div class="min-w-0 flex-1">
            <p class="font-semibold">{{ t('shell.loginReminder.title') }}</p>
            <p class="mt-1 text-sm leading-6 text-ink/60">{{ t('shell.loginReminder.description') }}</p>
            <RouterLink :to="loginTarget" class="mt-2 inline-flex min-h-11 items-center font-semibold text-indigo">{{ t('shell.loginReminder.action') }} →</RouterLink>
          </div>
          <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-xl text-ink/45 hover:bg-ink/5 hover:text-ink" :aria-label="t('shell.loginReminder.dismiss')" @click="loginReminderVisible = false">×</button>
        </div>
      </aside>
      <slot />
    </main>

    <BackgroundWorkCenter
      v-if="username && !immersive"
      ref="backgroundWorkCenter"
      :username="username"
      @status="updateBackgroundWorkStatus"
    />

    <nav v-if="!immersive" class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/97 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 mobile-navigation backdrop-blur lg:hidden" :aria-label="t('shell.primaryNav')">
      <RouterLink
        v-for="item in mobileNavigation"
        :key="item.name"
        :to="item.path"
        class="flex min-h-12 flex-col items-center justify-center gap-0.5 rounded-xl px-1 py-1.5 text-center text-[0.65rem] font-semibold"
        :class="currentNavigationName === item.name ? 'bg-felt text-white' : 'text-ink/55'"
      >
        <TabletopGlyph :name="item.icon" :size="18" />
        <span>{{ t(item.labelKey) }}</span>
      </RouterLink>
    </nav>
  </div>
</template>
