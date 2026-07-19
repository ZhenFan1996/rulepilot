<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'

withDefaults(defineProps<{ immersive?: boolean }>(), { immersive: false })

const route = useRoute()
const router = useRouter()
const isDark = ref(document.documentElement.classList.contains('dark'))
const username = ref('')

const navigation = [
  { name: 'home', path: '/', label: '首页' },
  { name: 'catalog', path: '/catalog', label: '游戏' },
  { name: 'teach', path: '/teach', label: '规则书' },
  { name: 'lessons', path: '/lessons', label: '讲解' },
  { name: 'account', path: '/account', label: '我的' },
] as const

const currentTitle = computed(() => navigation.find((item) => item.name === route.name)?.label ?? 'RulePilot')

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
}

async function loadSession() {
  try {
    const response = await fetch('/api/auth/session', { credentials: 'include' })
    if (response.ok) username.value = ((await response.json()) as { username: string }).username
  } catch {
    username.value = ''
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
          {{ item.label }}
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

    <nav v-if="!immersive" class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/95 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur lg:hidden" aria-label="主要导航">
      <RouterLink
        v-for="item in navigation"
        :key="item.name"
        :to="item.path"
        class="min-h-11 rounded-lg px-1 py-2 text-center text-xs font-medium"
        :class="route.name === item.name ? 'bg-ink text-canvas' : 'text-ink/55'"
      >
        {{ item.label }}
      </RouterLink>
    </nav>
  </div>
</template>
