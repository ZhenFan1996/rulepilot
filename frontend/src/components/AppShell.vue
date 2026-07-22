<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import {
  parseBackgroundTeachingItems,
  reconcileBackgroundTeaching,
  type BackgroundTeachingItem,
} from '@/lib/backgroundTeachingStatus'

const props = withDefaults(defineProps<{ immersive?: boolean }>(), { immersive: false })

interface TeachingPlanSummary { id: string; gameTitle: string }
interface ActiveTeachingRun { id: string; subjectId: string }

const route = useRoute()
const router = useRouter()
const isDark = ref(document.documentElement.classList.contains('dark'))
const username = ref('')
const activeTeaching = ref<BackgroundTeachingItem[]>([])
const completedTeaching = ref<BackgroundTeachingItem[]>([])
const teachingStatusUnavailable = ref(false)
const ACTIVE_TEACHING_KEY = 'rulepilot:active-teaching-runs'
const COMPLETED_TEACHING_KEY = 'rulepilot:completed-teaching-runs'
let teachingTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const teachingTitles = new Map<string, string>()

const navigation = [
  { name: 'home', path: '/', label: '首页' },
  { name: 'public-library', path: '/library', label: '公开讲解' },
  { name: 'teach', path: '/teach', label: '添加规则书' },
  { name: 'lessons', path: '/lessons', label: '我的讲解' },
  { name: 'catalog', path: '/catalog', label: '我的游戏' },
  { name: 'account', path: '/account', label: '我的' },
] as const
const mobileNavigation = navigation.filter((item) => item.name !== 'catalog')

const currentTitle = computed(() => navigation.find((item) => item.name === route.name)?.label ?? 'RulePilot')
const detailedTeachingRoute = computed(() => route.name === 'lessons' || route.name === 'lesson')
const backgroundStatusVisible = computed(() => !props.immersive && !detailedTeachingRoute.value)
const activeTeachingText = computed(() => {
  if (activeTeaching.value.length === 1) return `《${activeTeaching.value[0]!.gameTitle}》仍在后台准备`
  return `${activeTeaching.value.length} 份讲解仍在后台准备`
})
const completedTeachingText = computed(() => {
  if (completedTeaching.value.length === 1) return `《${completedTeaching.value[0]!.gameTitle}》的后台处理已经结束`
  return `${completedTeaching.value.length} 份讲解的后台处理已经结束`
})

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
}

async function loadSession() {
  try {
    const response = await fetch('/api/auth/session', { credentials: 'include' })
    if (response.ok) {
      username.value = ((await response.json()) as { username: string }).username
      completedTeaching.value = parseBackgroundTeachingItems(sessionStorage.getItem(COMPLETED_TEACHING_KEY))
      await refreshTeachingStatus()
    }
  } catch {
    username.value = ''
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
      for (const plan of plans) teachingTitles.set(plan.id, plan.gameTitle)
    }
    const active = runs.map((run) => ({
      runId: run.id,
      planId: run.subjectId,
      gameTitle: teachingTitles.get(run.subjectId) ?? '一份讲解',
    }))
    const previous = parseBackgroundTeachingItems(sessionStorage.getItem(ACTIVE_TEACHING_KEY))
    const transition = reconcileBackgroundTeaching(previous, active)
    activeTeaching.value = transition.active
    sessionStorage.setItem(ACTIVE_TEACHING_KEY, JSON.stringify(transition.active))
    if (transition.finished.length) {
      const notices = new Map(completedTeaching.value.map((item) => [item.planId, item]))
      for (const item of transition.finished) notices.set(item.planId, item)
      completedTeaching.value = [...notices.values()]
      sessionStorage.setItem(COMPLETED_TEACHING_KEY, JSON.stringify(completedTeaching.value))
    }
    teachingStatusUnavailable.value = false
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

async function logout() {
  const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!csrfResponse.ok) return
  const csrf = (await csrfResponse.json()) as { headerName: string; token: string }
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'include', headers: { [csrf.headerName]: csrf.token } })
  username.value = ''
  await router.push({ name: 'login' })
}

onMounted(loadSession)
onMounted(() => document.addEventListener('visibilitychange', handleVisibilityChange))
onBeforeUnmount(() => {
  disposed = true
  clearTeachingTimer()
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <div class="min-h-screen bg-canvas text-ink lg:pl-60">
    <aside class="fixed inset-y-0 left-0 z-30 hidden w-60 flex-col border-r border-ink/10 bg-paper px-5 py-6 lg:flex">
      <RouterLink :to="{ name: 'home' }" aria-label="RulePilot 首页">
        <ProductMark />
      </RouterLink>

      <nav class="mt-10 space-y-1" aria-label="主要导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.name"
          :to="item.path"
          class="flex min-h-11 items-center rounded-lg px-3 text-sm font-medium transition-colors"
          :class="route.name === item.name ? 'bg-ink text-canvas' : 'text-ink/60 hover:bg-ink/5 hover:text-ink'"
        >
          <span>{{ item.label }}</span>
          <span v-if="item.name === 'lessons' && activeTeaching.length" class="ml-auto rounded-full bg-copper px-2 py-0.5 text-[0.65rem] font-bold text-white" :aria-label="`${activeTeaching.length} 份讲解正在生成`">{{ activeTeaching.length }}</span>
        </RouterLink>
      </nav>

      <div class="mt-auto border-t border-ink/10 pt-5">
        <RouterLink v-if="username" :to="{ name: 'account' }" class="mb-2 flex min-h-11 items-center gap-3 rounded-lg bg-ink/5 px-3 text-sm font-semibold">
          <span class="grid h-7 w-7 place-items-center rounded-full bg-ink text-xs text-canvas">{{ username.slice(0, 1).toUpperCase() }}</span>
          <span class="truncate">{{ username }}</span>
        </RouterLink>
        <button class="flex min-h-10 w-full items-center justify-between rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink" :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
          <span>{{ isDark ? '浅色外观' : '深色外观' }}</span>
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
        <button v-if="username" class="mt-1 flex min-h-10 w-full items-center rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink" @click="logout">退出登录</button>
        <RouterLink v-else :to="{ name: 'login' }" class="mt-1 flex min-h-10 items-center rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink">登录</RouterLink>
      </div>
    </aside>

    <header class="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-ink/10 bg-canvas/95 px-4 backdrop-blur lg:hidden">
      <RouterLink :to="{ name: 'home' }" aria-label="RulePilot 首页"><ProductMark /></RouterLink>
      <RouterLink v-if="username" :to="{ name: 'account' }" class="text-sm font-semibold text-ink/60">{{ username }}</RouterLink>
      <span v-else class="text-sm font-medium text-ink/50">{{ currentTitle }}</span>
    </header>

    <main class="min-h-screen pb-20 lg:pb-0">
      <slot />
    </main>

    <aside v-if="backgroundStatusVisible && (completedTeaching.length || activeTeaching.length)" class="fixed bottom-20 left-4 right-4 z-30 rounded-xl border border-ink/10 bg-paper p-4 shadow-lg shadow-ink/10 sm:left-auto sm:max-w-md lg:bottom-6 lg:right-6" :aria-live="completedTeaching.length ? 'polite' : 'off'">
      <div v-if="completedTeaching.length" class="flex items-start gap-3">
        <div class="min-w-0 flex-1">
          <p class="font-semibold">{{ completedTeachingText }}</p>
          <p class="mt-1 text-sm leading-6 text-ink/55">可能已经完整，也可能保留了可读章节；打开讲解中心查看实际结果。</p>
          <RouterLink :to="{ name: 'lessons' }" class="mt-2 inline-flex min-h-11 items-center text-sm font-semibold text-indigo">查看结果 →</RouterLink>
        </div>
        <button type="button" class="grid min-h-11 min-w-11 place-items-center rounded-lg text-xl text-ink/45 hover:bg-ink/5 hover:text-ink" aria-label="关闭讲解完成提醒" @click="dismissCompletedTeaching">×</button>
      </div>
      <div v-else class="flex items-center justify-between gap-4" role="status">
        <div class="min-w-0">
          <p class="truncate font-semibold">{{ activeTeachingText }}</p>
          <p class="mt-1 text-sm text-ink/50">可以继续浏览，任务不会因为离开页面而停止。</p>
        </div>
        <RouterLink :to="{ name: 'lessons' }" class="shrink-0 rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">看进度</RouterLink>
      </div>
    </aside>

    <nav v-if="!immersive" class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/95 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur lg:hidden" aria-label="主要导航">
      <RouterLink
        v-for="item in mobileNavigation"
        :key="item.name"
        :to="item.path"
        class="min-h-11 rounded-lg px-1 py-2 text-center text-xs font-medium"
        :class="route.name === item.name ? 'bg-ink text-canvas' : 'text-ink/55'"
      >
        <span>{{ item.label }}</span>
        <span v-if="item.name === 'lessons' && activeTeaching.length" class="ml-1 inline-grid min-w-5 place-items-center rounded-full bg-copper px-1 text-[0.65rem] font-bold text-white" :aria-label="`${activeTeaching.length} 份讲解正在生成`">{{ activeTeaching.length }}</span>
      </RouterLink>
    </nav>
  </div>
</template>
