<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'

const route = useRoute()
const isDark = ref(document.documentElement.classList.contains('dark'))

const navigation = [
  { name: 'home', path: '/', label: '首页' },
  { name: 'catalog', path: '/catalog', label: '游戏' },
  { name: 'teach', path: '/teach', label: '规则书' },
  { name: 'lessons', path: '/lessons', label: '讲解' },
  { name: 'model-settings', path: '/settings/models', label: '模型' },
] as const

const currentTitle = computed(() => navigation.find((item) => item.name === route.name)?.label ?? 'RulePilot')

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
}
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
        <button class="flex min-h-10 w-full items-center justify-between rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink" :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'" @click="toggleTheme">
          <span>{{ isDark ? '浅色外观' : '深色外观' }}</span>
          <span aria-hidden="true">{{ isDark ? '☀' : '◐' }}</span>
        </button>
        <RouterLink :to="{ name: 'login' }" class="mt-1 flex min-h-10 items-center rounded-lg px-3 text-sm text-ink/55 hover:bg-ink/5 hover:text-ink">切换账户</RouterLink>
      </div>
    </aside>

    <header class="sticky top-0 z-30 flex h-14 items-center justify-between border-b border-ink/10 bg-canvas/95 px-4 backdrop-blur lg:hidden">
      <RouterLink :to="{ name: 'home' }" aria-label="RulePilot 首页"><ProductMark /></RouterLink>
      <span class="text-sm font-medium text-ink/50">{{ currentTitle }}</span>
    </header>

    <main class="min-h-screen pb-20 lg:pb-0">
      <slot />
    </main>

    <nav class="fixed inset-x-0 bottom-0 z-40 grid grid-cols-5 border-t border-ink/10 bg-paper/95 px-2 pb-[max(0.5rem,env(safe-area-inset-bottom))] pt-2 backdrop-blur lg:hidden" aria-label="主要导航">
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
