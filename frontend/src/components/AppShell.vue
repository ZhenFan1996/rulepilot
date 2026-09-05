<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import BackgroundWorkCenter from '@/components/BackgroundWorkCenter.vue'
import { LOGIN_REQUIRED_EVENT, notifySessionCleared } from '@/lib/authSession'
import { clearBackgroundWorkStorage } from '@/lib/backgroundTeachingStatus'
import { useLocale } from '@/lib/locale'

withDefaults(defineProps<{ immersive?: boolean; loginActionOwned?: boolean }>(), {
  immersive: false,
  loginActionOwned: false,
})
defineSlots<{ default(props: { username: string }): unknown }>()
const emit = defineEmits<{
  sessionIdentity: [username: string]
}>()

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
const backgroundWorkCenter = ref<{ openCenter: (trigger?: HTMLElement | null) => void } | null>(null)
const backgroundActiveCount = ref(0)
const backgroundFinishedCount = ref(0)
const backgroundActiveTitle = ref('')
const backgroundFinishedTitle = ref('')
let disposed = false
const sessionController = new AbortController()

const navigation = [
  { name: 'home', path: '/', labelKey: 'nav.home', icon: 'compass' },
  { name: 'game-recommendations', path: '/discover', labelKey: 'nav.discover', icon: 'meeple' },
  { name: 'public-library', path: '/library', labelKey: 'nav.library', icon: 'library' },
  { name: 'work-status', path: '/work', labelKey: 'nav.lessons', icon: 'cards' },
  { name: 'catalog', path: '/catalog', labelKey: 'nav.games', icon: 'meeple' },
  { name: 'account', path: '/account', labelKey: 'nav.account', icon: 'players' },
] as const
const mobileNavigation = navigation.filter((item) => item.name !== 'account')

const currentNavigationName = computed(() => {
  if (route.name === 'catalog-manage') return 'catalog'
  if (route.name === 'teach' || route.name === 'rulebook-reader' || route.name === 'game-workspace') return 'catalog'
  if (route.name === 'public-lesson' || route.name === 'public-lesson-questions') return 'public-library'
  if (route.name === 'lessons' || route.name === 'lesson' || route.name === 'lesson-questions') return 'work-status'
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
    const response = await fetch('/api/auth/session', { credentials: 'include', signal: sessionController.signal })
    if (disposed) return
    if (!response.ok) return clearSessionIdentity()
    const session = await response.json() as { username?: unknown; roles?: unknown }
    if (disposed) return
    username.value = typeof session.username === 'string' ? session.username.trim() : ''
    roles.value = Array.isArray(session.roles)
      ? session.roles.filter((role): role is string => typeof role === 'string')
      : []
    emit('sessionIdentity', username.value)
  } catch {
    if (!disposed) clearSessionIdentity()
  }
}

function clearSessionIdentity() {
  username.value = ''
  roles.value = []
  emit('sessionIdentity', '')
}

function showLoginReminder(event: Event) {
  sessionController.abort()
  clearSessionIdentity()
  loginReminderVisible.value = !(event instanceof CustomEvent
    && (event.detail as { showReminder?: unknown } | null)?.showReminder === false)
}

function updateBackgroundWorkStatus(
  activeCount: number,
  finishedCount: number,
  activeTitle: string,
  finishedTitle: string,
) {
  backgroundActiveCount.value = activeCount
  backgroundFinishedCount.value = finishedCount
  backgroundActiveTitle.value = activeTitle
  backgroundFinishedTitle.value = finishedTitle
}

const onWorkStatusPage = computed(() => route.name === 'work-status' || route.name === 'lessons')
const backgroundShortcutTitle = computed(() => {
  if (backgroundActiveCount.value) {
    return backgroundActiveCount.value === 1 && backgroundActiveTitle.value
      ? t('shell.lesson.oneActive', { title: backgroundActiveTitle.value })
      : t('shell.lesson.manyActive', { count: backgroundActiveCount.value })
  }
  return backgroundFinishedCount.value === 1 && backgroundFinishedTitle.value
    ? t('shell.lesson.oneFinished', { title: backgroundFinishedTitle.value })
    : t('shell.lesson.manyFinished', { count: backgroundFinishedCount.value })
})

function openBackgroundWork(event: MouseEvent) {
  backgroundWorkCenter.value?.openCenter(event.currentTarget as HTMLElement)
}

async function logout() {
  const signedOutUsername = username.value
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!csrfResponse.ok) return
  const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
  const response = await fetch('/api/auth/logout', { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token } })
  if (!response.ok) return
  username.value = ''
  roles.value = []
  emit('sessionIdentity', '')
  clearBackgroundWorkStorage(sessionStorage, signedOutUsername)
  notifySessionCleared()
}

onMounted(() => applyAppearance(appearance.value, false))
onMounted(loadSession)
onMounted(() => window.addEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder))
onBeforeUnmount(() => {
  disposed = true
  sessionController.abort()
  window.removeEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder)
})
</script>

<template>
  <div class="tabletop-app min-h-screen bg-canvas text-ink lg:pt-20">
    <aside class="drawer-shelf desktop-masthead app-fixed-top fixed inset-x-0 z-30 hidden h-20 items-center gap-6 border-b border-ink/10 bg-paper px-6 text-ink lg:flex">
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')" class="shrink-0 rounded-lg focus-visible:outline-offset-4">
        <ProductMark />
      </RouterLink>
      <nav class="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto" :aria-label="t('shell.primaryNav')">
        <RouterLink v-for="item in navigation" :key="item.name" :to="item.path"
          :data-testid="item.name === 'work-status' ? 'work-status-navigation-link' : undefined"
          class="drawer-link relative flex min-h-11 shrink-0 items-center gap-2 whitespace-nowrap px-3 text-sm font-semibold transition-colors"
          :class="currentNavigationName === item.name ? 'text-indigo drawer-link-active' : 'text-muted hover:bg-ink/5 hover:text-ink'">
          <span>{{ t(item.labelKey) }}</span>
          <span v-if="item.name === 'work-status' && backgroundActiveCount" data-testid="work-status-navigation-active" class="grid min-w-5 place-items-center rounded-full bg-copper px-1 text-xs text-on-accent">{{ backgroundActiveCount }}</span>
          <span v-else-if="item.name === 'work-status' && backgroundFinishedCount" data-testid="work-status-navigation-finished" class="size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        </RouterLink>
        <RouterLink v-if="isAdmin" :to="{ name: 'admin-models' }" class="drawer-link flex min-h-11 shrink-0 items-center px-3 text-sm text-muted">{{ t('nav.adminModels') }}</RouterLink>
        <RouterLink v-if="isAdmin" :to="{ name: 'agent-audit' }" class="drawer-link flex min-h-11 shrink-0 items-center px-3 text-sm text-muted">{{ t('nav.agentAudit') }}</RouterLink>
      </nav>
      <div class="flex shrink-0 items-center gap-2">
        <div v-if="username && !immersive" id="background-work-desktop-trigger">
          <button type="button" data-testid="background-work-trigger-desktop" class="relative grid size-11 place-items-center rounded-lg text-indigo hover:bg-indigo/8"
            :aria-label="t('shell.backgroundWork')" aria-haspopup="dialog" @click="openBackgroundWork">
            <TabletopGlyph name="cards" :size="20" />
            <span v-if="backgroundActiveCount" class="absolute -right-0.5 top-0 grid min-w-4 place-items-center rounded-full bg-copper px-1 text-[0.65rem] text-on-accent">{{ backgroundActiveCount }}</span>
            <span v-else-if="backgroundFinishedCount" class="absolute right-1.5 top-1.5 size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
          </button>
        </div>
        <LanguageSwitcher />
        <button type="button" class="grid size-11 place-items-center rounded-lg text-lg text-muted hover:bg-ink/5" :aria-label="isDark ? t('shell.theme.toLight') : t('shell.theme.toDark')" :aria-pressed="isDark" @click="toggleTheme">
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
        <RouterLink v-if="username" :to="{ name: 'account' }" class="grid size-9 place-items-center rounded-full bg-felt text-sm font-semibold text-white" :aria-label="username">{{ username.slice(0, 1).toUpperCase() }}</RouterLink>
        <button v-if="username" class="min-h-11 whitespace-nowrap rounded-lg px-2 text-xs text-muted hover:text-ink" @click="logout">{{ t('shell.signOut') }}</button>
        <RouterLink v-else-if="!loginActionOwned" :to="loginTarget" class="inline-flex min-h-11 items-center whitespace-nowrap rounded-lg bg-felt px-4 text-sm font-semibold text-white">{{ t('shell.signIn') }}</RouterLink>
      </div>
    </aside>

    <header class="mobile-app-header app-sticky-top sticky z-30 flex h-16 items-center justify-between border-b border-ink/10 bg-paper/95 px-3 backdrop-blur lg:hidden">
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')"><ProductMark /></RouterLink>
      <div class="flex min-w-0 items-center gap-1.5">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="max-w-16 truncate text-sm font-semibold text-muted max-[480px]:hidden">{{ username }}</RouterLink>
        <RouterLink v-else-if="!loginActionOwned" :to="loginTarget" class="inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-semibold text-indigo">{{ t('shell.signIn') }}</RouterLink>
        <button
          v-if="username && !immersive"
          type="button"
          data-testid="background-work-trigger-mobile"
          class="relative grid min-h-11 min-w-11 place-items-center rounded-xl border border-ink/10 bg-canvas/65 text-muted transition hover:border-copper/35 hover:text-ink"
          :aria-label="t('shell.backgroundWork')"
          aria-haspopup="dialog"
          @click="openBackgroundWork"
        >
          <TabletopGlyph name="cards" :size="18" />
          <span v-if="backgroundActiveCount" class="absolute -right-1 -top-1 grid min-w-5 place-items-center rounded-full bg-copper px-1 text-[0.65rem] text-on-accent">{{ backgroundActiveCount }}</span>
          <span v-else-if="backgroundFinishedCount" class="absolute right-1 top-1 size-2 rounded-full bg-emerald-500" aria-hidden="true" />
        </button>
        <LanguageSwitcher />
        <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-lg text-muted hover:bg-ink/5 hover:text-ink" :aria-label="isDark ? t('shell.theme.toLight') : t('shell.theme.toDark')" :aria-pressed="isDark" @click="toggleTheme">
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
      </div>
    </header>

    <main id="main-content" tabindex="-1" class="app-main min-h-screen pb-20 lg:pb-0" :aria-label="t('shell.mainContent')">
      <aside v-if="loginReminderVisible && !loginActionOwned" class="border-b border-copper/25 bg-copper/10 px-4 py-3 sm:px-8" role="status">
        <div class="mx-auto flex max-w-7xl items-start gap-3">
          <div class="min-w-0 flex-1">
            <p class="font-semibold">{{ t('shell.loginReminder.title') }}</p>
            <p class="mt-1 text-sm leading-6 text-muted">{{ t('shell.loginReminder.description') }}</p>
            <RouterLink :to="loginTarget" class="mt-2 inline-flex min-h-11 items-center font-semibold text-indigo">{{ t('shell.loginReminder.action') }} →</RouterLink>
          </div>
          <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-xl text-muted hover:bg-ink/5 hover:text-ink" :aria-label="t('shell.loginReminder.dismiss')" @click="loginReminderVisible = false">×</button>
        </div>
      </aside>
      <slot :username="username" />
    </main>

    <BackgroundWorkCenter
      v-if="username && !immersive && !onWorkStatusPage"
      ref="backgroundWorkCenter"
      :username="username"
      @status="updateBackgroundWorkStatus"
    />

    <button
      v-if="username && !immersive && !onWorkStatusPage && (backgroundActiveCount || backgroundFinishedCount)"
      type="button"
      data-testid="background-work-persistent-shortcut"
      class="background-work-shortcut"
      aria-haspopup="dialog"
      @click="openBackgroundWork"
    >
      <span class="work-status-icon" aria-hidden="true">
        <TabletopGlyph name="cards" :size="18" />
      </span>
      <span class="work-status-copy">
        <span class="work-status-label">{{ t('shell.guideStatus') }}</span>
        <span class="work-status-title">{{ backgroundShortcutTitle }}</span>
      </span>
      <span class="work-status-action">
        {{ t(backgroundActiveCount ? 'shell.lesson.viewProgress' : 'shell.lesson.viewResult') }}
      </span>
    </button>

    <nav v-if="!immersive" class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/97 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 mobile-navigation backdrop-blur lg:hidden" :aria-label="t('shell.primaryNav')">
      <RouterLink
        v-for="item in mobileNavigation"
        :key="item.name"
        :to="item.path"
        :data-testid="item.name === 'work-status' ? 'work-status-mobile-navigation-link' : undefined"
        class="flex min-h-12 flex-col items-center justify-center gap-0.5 rounded-xl px-1 py-1.5 text-center text-[0.65rem] font-semibold"
        :class="currentNavigationName === item.name ? 'bg-felt text-white' : 'text-muted'"
      >
        <span class="relative">
          <TabletopGlyph :name="item.icon" :size="18" />
          <span v-if="item.name === 'work-status' && backgroundActiveCount" data-testid="work-status-mobile-navigation-active" class="absolute -right-3 -top-2 grid min-w-4 place-items-center rounded-full bg-copper px-1 text-[0.58rem] leading-4 text-on-accent">{{ backgroundActiveCount }}</span>
          <span v-else-if="item.name === 'work-status' && backgroundFinishedCount" class="absolute -right-1.5 -top-1 size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        </span>
        <span>{{ t(item.labelKey) }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
@media (max-width: 359px) {
  .mobile-app-header :deep(.product-mark__name) { display: none; }
}
</style>
