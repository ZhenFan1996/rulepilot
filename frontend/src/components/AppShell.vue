<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { LOGIN_REQUIRED_EVENT, notifySessionCleared } from '@/lib/authSession'
import {
  parseBackgroundTeachingItems,
  reconcileBackgroundTeaching,
  type BackgroundTeachingItem,
} from '@/lib/backgroundTeachingStatus'
import { playerFacingTitle } from '@/lib/lessonPresentation'
import { useLocale } from '@/lib/locale'
import {
  TEACHING_LAUNCHED_EVENT,
  teachingLaunchDetail,
} from '@/lib/teachingLaunch'

const props = withDefaults(defineProps<{ immersive?: boolean }>(), { immersive: false })

interface TeachingPlanSummary { id: string; gameTitle: string }
interface ActiveTeachingRun { id: string; subjectId: string }
interface TeachingRunDetails { run: { id: string; state: string } }

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
const activeTeaching = ref<BackgroundTeachingItem[]>([])
const completedTeaching = ref<BackgroundTeachingItem[]>([])
const teachingStatusUnavailable = ref(false)
const ACTIVE_TEACHING_KEY = 'rulepilot:active-teaching-runs'
const COMPLETED_TEACHING_KEY = 'rulepilot:completed-teaching-runs'
let teachingTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const teachingTitles = new Map<string, string>()
const terminalTeachingStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])

const navigation = [
  { name: 'home', path: '/', labelKey: 'nav.home', icon: 'compass' },
  { name: 'public-library', path: '/library', labelKey: 'nav.library', icon: 'library' },
  { name: 'teach', path: '/teach', labelKey: 'nav.rulebook', icon: 'rulebook' },
  { name: 'lessons', path: '/lessons', labelKey: 'nav.lessons', icon: 'cards' },
  { name: 'catalog', path: '/catalog', labelKey: 'nav.games', icon: 'meeple' },
  { name: 'account', path: '/account', labelKey: 'nav.account', icon: 'players' },
] as const
const mobileNavigation = navigation.filter((item) => item.name !== 'account')

const currentNavigationName = computed(() => {
  if (route.name === 'catalog-manage') return 'catalog'
  if (route.name === 'public-lesson') return 'public-library'
  return route.name
})
const isAdmin = computed(() => roles.value.includes('ADMIN') || roles.value.includes('ROLE_ADMIN'))
const loginTarget = computed(() => ({
  name: 'login',
  query: route.name === 'login' ? undefined : { redirect: route.fullPath },
}))
const detailedTeachingRoute = computed(() => route.name === 'lessons' || route.name === 'lesson')
const backgroundStatusVisible = computed(() => !props.immersive && !detailedTeachingRoute.value)
const activeTeachingText = computed(() => {
  if (activeTeaching.value.length === 1) return t('shell.lesson.oneActive', { title: activeTeaching.value[0]!.gameTitle })
  return t('shell.lesson.manyActive', { count: activeTeaching.value.length })
})
const completedTeachingText = computed(() => {
  if (completedTeaching.value.length === 1) return t('shell.lesson.oneFinished', { title: completedTeaching.value[0]!.gameTitle })
  return t('shell.lesson.manyFinished', { count: completedTeaching.value.length })
})

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
      completedTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(COMPLETED_TEACHING_KEY))
      await refreshTeachingStatus()
    }
  } catch {
    username.value = ''
    roles.value = []
  }
}

function clearTeachingTimer() {
  if (teachingTimer) clearTimeout(teachingTimer)
  teachingTimer = undefined
}

function scheduleTeachingRefresh(delay = 5000) {
  clearTeachingTimer()
  if (disposed || document.visibilityState === 'hidden') return
  if (activeTeaching.value.length === 0 && !teachingStatusUnavailable.value) return
  teachingTimer = setTimeout(() => {
    teachingTimer = undefined
    void refreshTeachingStatus()
  }, delay)
}

async function refreshTeachingStatus() {
  if (!username.value || disposed || document.visibilityState === 'hidden') return
  try {
    const runsResponse = await fetch('/api/v1/assistant-runs/active?mode=TEACHING', { credentials: 'include' })
    if (!runsResponse.ok) throw new Error('background teaching status is unavailable')
    const runs = await runsResponse.json() as ActiveTeachingRun[]
    if (runs.some((run) => !teachingTitles.has(run.subjectId))) {
      const plansResponse = await fetch('/api/v1/teaching-plans', { credentials: 'include' })
      if (!plansResponse.ok) throw new Error('background teaching titles are unavailable')
      const plans = await plansResponse.json() as TeachingPlanSummary[]
      for (const plan of plans) teachingTitles.set(plan.id, playerFacingTitle(plan.gameTitle))
    }
    const active = runs.map((run) => ({
      runId: run.id,
      planId: run.subjectId,
      gameTitle: teachingTitles.get(run.subjectId) ?? t('shell.lesson.unavailable'),
    }))
    const previous = parseBackgroundTeachingItems(sessionStorage.getItem(ACTIVE_TEACHING_KEY))
    const activePlanIds = new Set(active.map((item) => item.planId))
    const missing = previous.filter((item) => !activePlanIds.has(item.planId))
    let uncertainStatus = false
    const confirmations = await Promise.all(missing.map(async (item) => {
      try {
        const response = await fetch(`/api/v1/assistant-runs/${encodeURIComponent(item.runId)}`, { credentials: 'include' })
        if (!response.ok) {
          uncertainStatus = true
          return item
        }
        const details = await response.json() as TeachingRunDetails
        return terminalTeachingStates.has(details.run.state) ? null : item
      } catch {
        uncertainStatus = true
        return item
      }
    }))
    const retained = confirmations.filter((item): item is BackgroundTeachingItem => item !== null)
    const transition = reconcileBackgroundTeaching(previous, [...active, ...retained])
    activeTeaching.value = transition.active
    sessionStorage.setItem(ACTIVE_TEACHING_KEY, JSON.stringify(transition.active))
    if (transition.finished.length) {
      const notices = new Map(completedTeaching.value.map((item) => [item.planId, item]))
      for (const item of transition.finished) notices.set(item.planId, item)
      completedTeaching.value = [...notices.values()]
      sessionStorage.setItem(COMPLETED_TEACHING_KEY, JSON.stringify(completedTeaching.value))
    }
    teachingStatusUnavailable.value = uncertainStatus
  } catch {
    teachingStatusUnavailable.value = true
  } finally {
    scheduleTeachingRefresh()
  }
}

function dismissCompletedTeaching() {
  completedTeaching.value = []
  sessionStorage.removeItem(COMPLETED_TEACHING_KEY)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    clearTeachingTimer()
  } else if (username.value) {
    void refreshTeachingStatus()
  }
}

function showLoginReminder() {
  username.value = ''
  roles.value = []
  loginReminderVisible.value = true
}

function handleTeachingLaunched(event: Event) {
  const detail = teachingLaunchDetail(event)
  if (!detail || !username.value) return
  const gameTitle = detail.gameTitle ?? teachingTitles.get(detail.planId) ?? t('shell.lesson.unavailable')
  if (detail.gameTitle) teachingTitles.set(detail.planId, detail.gameTitle)
  const items = new Map(activeTeaching.value.map((item) => [item.planId, item]))
  items.set(detail.planId, { runId: detail.runId, planId: detail.planId, gameTitle })
  activeTeaching.value = [...items.values()]
  sessionStorage.setItem(ACTIVE_TEACHING_KEY, JSON.stringify(activeTeaching.value))
  completedTeaching.value = completedTeaching.value.filter((item) => item.planId !== detail.planId)
  sessionStorage.setItem(COMPLETED_TEACHING_KEY, JSON.stringify(completedTeaching.value))
  teachingStatusUnavailable.value = false
  void refreshTeachingStatus()
}

async function logout() {
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!csrfResponse.ok) return
  const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
  const response = await fetch('/api/auth/logout', { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token } })
  if (!response.ok) return
  username.value = ''
  roles.value = []
  activeTeaching.value = []
  completedTeaching.value = []
  sessionStorage.removeItem(ACTIVE_TEACHING_KEY)
  sessionStorage.removeItem(COMPLETED_TEACHING_KEY)
  notifySessionCleared()
}

onMounted(() => applyAppearance(appearance.value, false))
onMounted(loadSession)
onMounted(() => document.addEventListener('visibilitychange', handleVisibilityChange))
onMounted(() => window.addEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder))
onMounted(() => window.addEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched))
onBeforeUnmount(() => {
  disposed = true
  clearTeachingTimer()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener(LOGIN_REQUIRED_EVENT, showLoginReminder)
  window.removeEventListener(TEACHING_LAUNCHED_EVENT, handleTeachingLaunched)
})
</script>

<template>
  <div class="min-h-screen bg-canvas text-ink lg:pl-60">
    <aside class="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col border-r border-ink/10 bg-paper px-5 py-6 lg:flex">
      <RouterLink :to="{ name: 'home' }" :aria-label="t('shell.homeAria')">
        <ProductMark />
      </RouterLink>

      <nav class="mt-10 space-y-1" :aria-label="t('shell.primaryNav')">
        <RouterLink
          v-for="item in navigation"
          :key="item.name"
          :to="item.path"
          class="flex min-h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors"
          :class="currentNavigationName === item.name ? 'bg-ink text-canvas' : 'text-ink/60 hover:bg-ink/5 hover:text-ink'"
        >
          <TabletopGlyph :name="item.icon" :size="19" class="shrink-0" />
          <span>{{ t(item.labelKey) }}</span>
          <span v-if="item.name === 'lessons' && activeTeaching.length" class="ml-auto rounded-full bg-copper px-2 py-0.5 text-[0.65rem] font-bold text-white" :aria-label="t('shell.lesson.badge', { count: activeTeaching.length })">{{ activeTeaching.length }}</span>
        </RouterLink>
        <RouterLink
          v-if="isAdmin"
          :to="{ name: 'agent-audit' }"
          class="flex min-h-11 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors"
          :class="currentNavigationName === 'agent-audit' ? 'bg-ink text-canvas' : 'text-ink/60 hover:bg-ink/5 hover:text-ink'"
        >
          <TabletopGlyph name="rulebook" :size="19" class="shrink-0" />
          <span>{{ t('nav.agentAudit') }}</span>
        </RouterLink>
      </nav>

      <div class="mt-auto border-t border-ink/10 pt-5">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="mb-2 flex min-h-11 items-center gap-3 rounded-lg bg-ink/5 px-3 text-sm font-semibold">
          <span class="grid h-7 w-7 place-items-center rounded-full bg-ink text-xs text-canvas">{{ username.slice(0, 1).toUpperCase() }}</span>
          <span class="truncate">{{ username }}</span>
        </RouterLink>
        <div class="mb-2 px-1"><LanguageSwitcher /></div>
        <button class="flex min-h-10 w-full items-center justify-between rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink" :aria-label="isDark ? t('shell.theme.toLight') : t('shell.theme.toDark')" @click="toggleTheme">
          <span>{{ isDark ? t('shell.theme.light') : t('shell.theme.dark') }}</span>
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
        <button v-if="username" class="mt-1 flex min-h-10 w-full items-center rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink" @click="logout">{{ t('shell.signOut') }}</button>
        <RouterLink v-else :to="loginTarget" class="mt-1 flex min-h-10 items-center rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink">{{ t('shell.signIn') }}</RouterLink>
      </div>
    </aside>

    <header class="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-ink/10 bg-canvas/95 px-4 backdrop-blur lg:hidden">
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

    <aside v-if="backgroundStatusVisible && (completedTeaching.length || activeTeaching.length)" class="fixed bottom-20 left-4 right-4 z-30 rounded-xl border border-ink/10 bg-paper p-4 shadow-lg shadow-ink/10 sm:left-auto sm:max-w-md lg:bottom-6 lg:right-6" :aria-live="completedTeaching.length ? 'polite' : 'off'">
      <div v-if="completedTeaching.length" class="flex items-start gap-3">
        <div class="min-w-0 flex-1">
          <p class="font-semibold">{{ completedTeachingText }}</p>
          <p class="mt-1 text-sm leading-6 text-ink/55">{{ t('shell.lesson.finishedDetail') }}</p>
          <RouterLink :to="{ name: 'lessons' }" class="mt-2 inline-flex min-h-11 items-center text-sm font-semibold text-indigo">{{ t('shell.lesson.viewResult') }}</RouterLink>
        </div>
        <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-xl text-ink/45 hover:bg-ink/5 hover:text-ink" :aria-label="t('shell.lesson.closeNotice')" @click="dismissCompletedTeaching">×</button>
      </div>
      <div v-else class="flex items-center justify-between gap-4" role="status">
        <div class="min-w-0">
          <p class="truncate font-semibold">{{ activeTeachingText }}</p>
          <p class="mt-1 text-sm text-ink/50">{{ t('shell.lesson.activeDetail') }}</p>
        </div>
        <RouterLink :to="{ name: 'lessons' }" class="shrink-0 rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ t('shell.lesson.viewProgress') }}</RouterLink>
      </div>
    </aside>

    <nav v-if="!immersive" class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/95 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur lg:hidden" :aria-label="t('shell.primaryNav')">
      <RouterLink
        v-for="item in mobileNavigation"
        :key="item.name"
        :to="item.path"
        class="flex min-h-11 flex-col items-center justify-center gap-0.5 rounded-lg px-1 py-1.5 text-center text-[0.65rem] font-medium"
        :class="currentNavigationName === item.name ? 'bg-ink text-canvas' : 'text-ink/55'"
      >
        <TabletopGlyph :name="item.icon" :size="18" />
        <span>{{ t(item.labelKey) }}</span>
        <span v-if="item.name === 'lessons' && activeTeaching.length" class="ml-1 inline-grid min-w-5 place-items-center rounded-full bg-copper px-1 text-[0.65rem] font-bold text-white" :aria-label="t('shell.lesson.badge', { count: activeTeaching.length })">{{ activeTeaching.length }}</span>
      </RouterLink>
    </nav>
  </div>
</template>
