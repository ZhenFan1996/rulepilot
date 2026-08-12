<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { useLocale } from '@/lib/locale'
import { safeAuthReturnPath } from '@/lib/authSession'

interface CsrfResponse {
  headerName: string
  token: string
}

type LoginFailure = 'invalid-credentials' | 'connection' | 'unavailable'

class LoginRequestError extends Error {
  constructor(readonly failure: LoginFailure) {
    super(failure)
  }
}

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const username = ref('')
const password = ref('')
const isSubmitting = ref(false)
const failure = ref<LoginFailure | null>(null)
const usernameInput = ref<HTMLInputElement | null>(null)
const errorSummary = ref<HTMLElement | null>(null)
const returnPath = computed(() => safeAuthReturnPath(route.query.redirect))
const registerTarget = computed(() => returnPath.value
  ? { name: 'register', query: { redirect: returnPath.value } }
  : { name: 'register' })
const invalidCredentials = computed(() => failure.value === 'invalid-credentials')
const errorMessage = computed(() => failure.value ? ({
  'invalid-credentials': t('auth.login.invalid'),
  connection: t('auth.connection'),
  unavailable: t('auth.unavailable'),
} as const)[failure.value] : '')

function clearFailure() {
  failure.value = null
}

async function exposeFailure(nextFailure: LoginFailure) {
  failure.value = nextFailure
  await nextTick()
  if (nextFailure === 'invalid-credentials') usernameInput.value?.focus()
  else errorSummary.value?.focus()
}

async function login() {
  if (isSubmitting.value) return
  isSubmitting.value = true
  failure.value = null
  let nextFailure: LoginFailure | null = null

  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new LoginRequestError('connection')
    const csrf = (await csrfResponse.json()) as CsrfResponse

    const body = new URLSearchParams({ username: username.value.trim().toLowerCase(), password: password.value })
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        [csrf.headerName]: csrf.token,
      },
      body,
    })

    if (response.status === 401) throw new LoginRequestError('invalid-credentials')
    if (!response.ok) throw new LoginRequestError('unavailable')
  } catch (error) {
    nextFailure = error instanceof LoginRequestError ? error.failure : 'connection'
  } finally {
    isSubmitting.value = false
  }

  if (nextFailure) {
    await exposeFailure(nextFailure)
    return
  }
  await router.replace(returnPath.value ?? { name: 'account' })
}
</script>

<template>
  <main id="main-content" tabindex="-1" class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md border border-ink/10 bg-paper p-7 sm:p-9">
      <div class="flex items-center justify-between gap-4"><RouterLink :to="{ name: 'home' }" aria-label="RulePilot"><ProductMark /></RouterLink><LanguageSwitcher /></div>
      <h1 class="mt-10 font-display text-4xl font-semibold tracking-tight">{{ t('auth.login.title') }}</h1>
      <p class="mt-3 leading-7 text-ink/55">{{ t('auth.login.description') }}</p>
      <p v-if="returnPath" data-testid="auth-return-context" class="mt-3 rounded-lg bg-indigo/7 px-4 py-3 text-sm leading-6 text-indigo">
        {{ t('auth.login.return') }}
      </p>

      <form class="mt-8 stack-y-xl" :aria-busy="isSubmitting" @submit.prevent="login">
        <label class="block text-sm font-semibold">
          {{ t('auth.username') }}
          <input ref="usernameInput" v-model="username" name="username" autocomplete="username" required :disabled="isSubmitting" :aria-invalid="invalidCredentials ? 'true' : undefined" :aria-describedby="invalidCredentials ? 'auth-login-error' : undefined" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
        </label>
        <label class="block text-sm font-semibold">
          {{ t('auth.password') }}
          <input v-model="password" name="password" type="password" autocomplete="current-password" required :disabled="isSubmitting" :aria-invalid="invalidCredentials ? 'true' : undefined" :aria-describedby="invalidCredentials ? 'auth-login-error' : undefined" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
        </label>

        <p v-if="errorMessage" id="auth-login-error" ref="errorSummary" tabindex="-1" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700 focus-visible:outline-offset-4" role="alert">{{ errorMessage }}</p>

        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white transition-colors hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? t('auth.login.loading') : t('auth.login.submit') }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        {{ t('auth.login.first') }}
        <RouterLink replace :to="registerTarget" class="font-semibold text-indigo">{{ t('auth.login.create') }}</RouterLink>
      </p>
    </section>
  </main>
</template>
