<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { safeAuthReturnPath } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface CsrfResponse { headerName: string; token: string }

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')
const returnPath = computed(() => safeAuthReturnPath(route.query.redirect))
const loginTarget = computed(() => returnPath.value
  ? { name: 'login', query: { redirect: returnPath.value } }
  : { name: 'login' })

async function register() {
  errorMessage.value = ''
  if (password.value !== confirmation.value) {
    errorMessage.value = t('auth.register.mismatch')
    return
  }
  isSubmitting.value = true
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new Error(t('auth.connection'))
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ username: username.value, password: password.value }),
    })
    if (response.status === 409) throw new Error(t('auth.register.taken'))
    if (response.status === 400) throw new Error(t('auth.register.requirements'))
    if (!response.ok) throw new Error(t('auth.unavailable'))

    const body = new URLSearchParams({ username: username.value.trim().toLowerCase(), password: password.value })
    const loginResponse = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
      body,
    })
    if (!loginResponse.ok) throw new Error(t('auth.register.created'))
    await router.replace(returnPath.value ?? { name: 'account' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('auth.register.failed')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main id="main-content" tabindex="-1" class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md border border-ink/10 bg-paper p-7 sm:p-9">
      <div class="flex items-center justify-between gap-4"><RouterLink :to="{ name: 'home' }" aria-label="RulePilot"><ProductMark /></RouterLink><LanguageSwitcher /></div>
      <h1 class="mt-10 font-display text-4xl font-semibold tracking-tight">{{ t('auth.register.title') }}</h1>
      <p class="mt-3 leading-7 text-ink/55">{{ t('auth.register.description') }}</p>
      <p v-if="returnPath" data-testid="auth-return-context" class="mt-3 rounded-lg bg-indigo/7 px-4 py-3 text-sm leading-6 text-indigo">
        {{ t('auth.register.return') }}
      </p>

      <form class="mt-8 stack-y-xl" @submit.prevent="register">
        <label class="block text-sm font-semibold">{{ t('auth.username') }}
          <input v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="40" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
          <span class="mt-1.5 block font-normal text-ink/40">{{ t('auth.register.usernameHint') }}</span>
        </label>
        <label class="block text-sm font-semibold">{{ t('auth.password') }}
          <input v-model="password" name="password" type="password" autocomplete="new-password" required minlength="8" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
          <span class="mt-1.5 block font-normal text-ink/40">{{ t('auth.register.passwordHint') }}</span>
        </label>
        <label class="block text-sm font-semibold">{{ t('auth.register.confirm') }}
          <input v-model="confirmation" name="confirmation" type="password" autocomplete="new-password" required minlength="8" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>

        <p v-if="errorMessage" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? t('auth.register.loading') : t('auth.register.submit') }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        {{ t('auth.register.existing') }}
        <RouterLink replace :to="loginTarget" class="font-semibold text-indigo">{{ t('auth.register.signIn') }}</RouterLink>
      </p>
    </section>
  </main>
</template>
