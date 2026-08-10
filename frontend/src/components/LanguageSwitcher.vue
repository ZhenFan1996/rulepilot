<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

import { setLocale, useLocale, type AppLocale } from '@/lib/locale'

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()

async function choose(value: AppLocale) {
  if (locale.value === value) return
  setLocale(value)
  await router.replace({ query: { ...route.query, lang: value === 'en' ? 'en' : undefined } })
}
</script>

<template>
  <div class="inline-flex min-h-10 overflow-hidden rounded-full border border-gold/30 bg-paper/95 p-0.5 text-xs font-bold shadow-sm" :aria-label="t('language.switch')">
    <button type="button" class="min-h-9 rounded-full px-2.5 transition" :class="locale === 'zh-CN' ? 'bg-felt text-white' : 'text-ink/55 hover:bg-gold/10 hover:text-ink'" :aria-pressed="locale === 'zh-CN'" @click="choose('zh-CN')">中文</button>
    <button type="button" class="min-h-9 rounded-full px-2.5 transition" :class="locale === 'en' ? 'bg-felt text-white' : 'text-ink/55 hover:bg-gold/10 hover:text-ink'" :aria-pressed="locale === 'en'" @click="choose('en')">EN</button>
  </div>
</template>
