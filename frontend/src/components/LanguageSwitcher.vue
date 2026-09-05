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
  <div class="inline-flex shrink-0 whitespace-nowrap min-h-10 overflow-hidden rounded-lg border border-ink/15 bg-paper p-0.5 text-xs font-semibold" :aria-label="t('language.switch')">
    <button type="button" class="min-h-9 rounded-md px-2.5 transition" :class="locale === 'zh-CN' ? 'bg-felt text-white' : 'text-muted hover:bg-ink/5 hover:text-ink'" :aria-pressed="locale === 'zh-CN'" @click="choose('zh-CN')">中文</button>
    <button type="button" class="min-h-9 rounded-md px-2.5 transition" :class="locale === 'en' ? 'bg-felt text-white' : 'text-muted hover:bg-ink/5 hover:text-ink'" :aria-pressed="locale === 'en'" @click="choose('en')">EN</button>
  </div>
</template>
