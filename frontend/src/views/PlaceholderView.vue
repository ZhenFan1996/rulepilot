<script setup lang="ts">
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { useLocale } from '@/lib/locale'
import { buttonVariants } from '@/lib/variants'

const { t } = useLocale()
</script>

<template>
  <AppShell>
    <section class="mx-auto w-full max-w-xl px-5 py-20 text-center sm:p-12">
      <h1 class="font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ t('placeholder.questions.title') }}</h1>
      <p class="mx-auto mt-5 max-w-md text-base leading-7 text-ink/65">{{ t('placeholder.questions.description') }}</p>
      <RouterLink :to="{ name: 'home' }" :class="buttonVariants({ variant: 'primary', size: 'md' })" class="mt-8">
        {{ t('placeholder.home') }}
      </RouterLink>
    </section>
  </AppShell>
</template>
