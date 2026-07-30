<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { useLocale } from '@/lib/locale'
import { safeLoginReturnPath } from '@/lib/authSession'

interface CsrfResponse {
  headerName: string
  token: string
}

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const username = ref('player')
const password = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')

async function login() {
  isSubmitting.value = true
  errorMessage.value = ''

  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new Error(t('auth.connection'))
    const csrf = (await csrfResponse.json()) as CsrfResponse

    const body = new URLSearchParams({ username: username.value, password: password.value })
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        [csrf.headerName]: csrf.token,
      },
      body,
    })

    if (response.status === 401) throw new Error(t('auth.login.invalid'))
    if (!response.ok) throw new Error(t('auth.unavailable'))

    const returnPath = safeLoginReturnPath(route.query.redirect)
    await router.push(returnPath ?? { name: 'account' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('auth.login.failed')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md border border-ink/10 bg-paper p-7 sm:p-9">
      <div class="flex items-center justify-between gap-4"><RouterLink :to="{ name: 'home' }" aria-label="RulePilot"><ProductMark /></RouterLink><LanguageSwitcher /></div>
      <h1 class="mt-10 font-display text-4xl font-semibold tracking-tight">{{ t('auth.login.title') }}</h1>
      <p class="mt-3 leading-7 text-ink/55">{{ t('auth.login.description') }}</p>

      <form class="mt-8 space-y-5" @submit.prevent="login">
        <label class="block text-sm font-semibold">
          {{ t('auth.username') }}
          <input v-model="username" name="username" autocomplete="username" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>
        <label class="block text-sm font-semibold">
          {{ t('auth.password') }}
          <input v-model="password" name="password" type="password" autocomplete="current-password" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>

        <p v-if="errorMessage" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white transition-colors hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? t('auth.login.loading') : t('auth.login.submit') }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        {{ t('auth.login.first') }}
        <RouterLink :to="{ name: 'register' }" class="font-semibold text-indigo">{{ t('auth.login.create') }}</RouterLink>
      </p>
    </section>
  </main>
</template>
