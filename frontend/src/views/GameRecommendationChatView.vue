<script setup lang="ts">
import { ref } from 'vue'

import AppShell from '@/components/AppShell.vue'
import GameRecommendationAgent from '@/components/GameRecommendationAgent.vue'
import { useLocale } from '@/lib/locale'

const { locale } = useLocale()
const sessionIdentity = ref<string | null>(null)
const copy = {
  'zh-CN': {
    title: '找桌游',
    catalogAction: '浏览目录',
  },
  en: {
    title: 'Find a game',
    catalogAction: 'Browse catalog',
  },
} as const
const t = (key: keyof typeof copy['zh-CN']) => copy[locale.value][key]
</script>

<template>
  <AppShell @session-identity="sessionIdentity = $event">
    <div class="tabletop-page">
      <header class="flex flex-wrap items-center justify-between gap-4 pb-5">
        <h1 class="tabletop-title">{{ t('title') }}</h1>
        <RouterLink :to="{ name: 'game-catalog-browse' }" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline underline-offset-4">{{ t('catalogAction') }}</RouterLink>
      </header>

      <GameRecommendationAgent :session-identity="sessionIdentity" />
    </div>
  </AppShell>
</template>
