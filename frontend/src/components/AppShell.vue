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
  if (route.name === 'public-lesson') return 'public-library'
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
  <div class="tabletop-app min-h-screen bg-canvas text-ink lg:pl-[17.5rem]">
    <aside class="drawer-shelf fixed inset-y-0 left-0 z-30 hidden w-[17.5rem] flex-col overflow-hidden border-r border-white/8 bg-ink-panel px-5 py-6 text-[#f5f0e8]  lg:flex">
      <div class="pointer-events-none absolute -right-12 -top-14 size-36 rotate-12 border border-white/7" aria-hidden="true" />
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')" class="relative mx-1 rounded-xl focus-visible:outline-offset-4">
        <ProductMark />
      </RouterLink>

      <nav class="relative mt-9 stack-y-sm" :aria-label="t('shell.primaryNav')">
        <RouterLink
          v-for="item in navigation"
          :key="item.name"
          :to="item.path"
          class="drawer-link group flex min-h-12 items-center gap-3 px-3 text-sm font-semibold transition-[color,background-color,translate]"
          :class="currentNavigationName === item.name ? 'border-[#f5f0e8]/40 bg-[#f5f0e8] text-[#1a232a] drawer-link-active -translate-x-0.5' : 'border-white/5 bg-white/[0.035] text-[#f5f0e8]/62 hover:translate-x-0.5 hover:bg-white/8 hover:text-[#f5f0e8]'"
        >
          <TabletopGlyph :name="item.icon" :size="19" class="shrink-0" :class="currentNavigationName === item.name ? 'text-copper' : 'text-[#f5f0e8]/48 group-hover:text-[#f5f0e8]'" />
          <span>{{ t(item.labelKey) }}</span>
        </RouterLink>
        <RouterLink
          v-if="isAdmin"
          :to="{ name: 'agent-audit' }"
          class="drawer-link flex min-h-12 items-center gap-3 px-3 text-sm font-semibold transition-colors"
          :class="currentNavigationName === 'agent-audit' ? 'bg-[#f5f0e8] text-[#1a232a]' : 'text-[#f5f0e8]/58 hover:bg-white/7 hover:text-[#f5f0e8]'"
        >
          <TabletopGlyph name="rulebook" :size="19" class="shrink-0" />
          <span>{{ t('nav.agentAudit') }}</span>
        </RouterLink>
      </nav>

      <div class="relative mt-auto border-t border-white/10 pt-5">
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

    <header class="sticky top-0 z-30 flex h-16 items-center justify-between border-b border-ink/10 bg-paper/95 px-4 elevation-sm backdrop-blur lg:hidden">
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')"><ProductMark /></RouterLink>
      <div class="flex min-w-0 items-center gap-1.5">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="max-w-16 truncate text-sm font-semibold text-ink/60 max-[360px]:hidden">{{ username }}</RouterLink>
        <RouterLink v-else :to="loginTarget" class="inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-semibold text-indigo">{{ t('shell.signIn') }}</RouterLink>
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

    <BackgroundWorkCenter v-if="username && !immersive" :username="username" />

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
