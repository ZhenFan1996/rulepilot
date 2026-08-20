<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { safeKnownAuthReturnPath } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface CsrfResponse { headerName: string; token: string }

type RegistrationFailure = 'mismatch' | 'taken' | 'email-taken' | 'requirements' | 'connection' | 'unavailable' | 'created-login'

class RegistrationRequestError extends Error {
  constructor(readonly failure: RegistrationFailure) {
    super(failure)
  }
}

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const username = ref('')
const email = ref('')
const password = ref('')
const confirmation = ref('')
const isSubmitting = ref(false)
const accountCreated = ref(false)
const failure = ref<RegistrationFailure | null>(null)
const usernameInput = ref<HTMLInputElement | null>(null)
const emailInput = ref<HTMLInputElement | null>(null)
const confirmationInput = ref<HTMLInputElement | null>(null)
const errorSummary = ref<HTMLElement | null>(null)
const returnPath = computed(() => safeKnownAuthReturnPath(router, route.query.redirect))
const loginTarget = computed(() => returnPath.value
  ? { name: 'login', query: { redirect: returnPath.value } }
  : { name: 'login' })
const inputsLocked = computed(() => isSubmitting.value || accountCreated.value)
const usernameInvalid = computed(() => failure.value === 'taken' || failure.value === 'requirements')
const emailInvalid = computed(() => failure.value === 'email-taken' || failure.value === 'requirements')
const passwordInvalid = computed(() => failure.value === 'requirements')
const confirmationInvalid = computed(() => failure.value === 'mismatch')
const errorMessage = computed(() => failure.value ? ({
  mismatch: t('auth.register.mismatch'),
  taken: t('auth.register.taken'),
  'email-taken': t('auth.register.emailTaken'),
  requirements: t('auth.register.requirements'),
  connection: t('auth.connection'),
  unavailable: t('auth.unavailable'),
  'created-login': t('auth.register.created'),
} as const)[failure.value] : '')
const submitLabel = computed(() => {
  if (isSubmitting.value) return accountCreated.value ? t('auth.register.signingIn') : t('auth.register.loading')
  return accountCreated.value ? t('auth.register.retryLogin') : t('auth.register.submit')
})

function clearFailure() {
  if (!accountCreated.value) failure.value = null
}

async function exposeFailure(nextFailure: RegistrationFailure) {
  failure.value = nextFailure
  await nextTick()
  if (nextFailure === 'mismatch') confirmationInput.value?.focus()
  else if (nextFailure === 'email-taken') emailInput.value?.focus()
  else if (nextFailure === 'taken' || nextFailure === 'requirements') usernameInput.value?.focus()
  else errorSummary.value?.focus()
}

async function register() {
  if (isSubmitting.value) return
  failure.value = null
  if (!accountCreated.value && password.value !== confirmation.value) {
    await exposeFailure('mismatch')
    return
  }
  isSubmitting.value = true
  let nextFailure: RegistrationFailure | null = null
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new RegistrationRequestError('connection')
    const csrf = await csrfResponse.json() as CsrfResponse
    if (!accountCreated.value) {
      const response = await fetch('/api/auth/register', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ username: username.value, email: email.value, password: password.value }),
      })
      if (response.status === 409) {
        const conflict = await response.json().catch(() => null) as { code?: string } | null
        throw new RegistrationRequestError(conflict?.code === 'EMAIL_ALREADY_REGISTERED' ? 'email-taken' : 'taken')
      }
      if (response.status === 400) throw new RegistrationRequestError('requirements')
      if (!response.ok) throw new RegistrationRequestError('unavailable')
      accountCreated.value = true
      username.value = username.value.trim().toLowerCase()
      email.value = email.value.trim().toLowerCase()
    }

    const body = new URLSearchParams({ username: username.value.trim().toLowerCase(), password: password.value })
    const loginResponse = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
      body,
    })
    if (!loginResponse.ok) throw new RegistrationRequestError('created-login')
  } catch (error) {
    nextFailure = accountCreated.value
      ? 'created-login'
      : error instanceof RegistrationRequestError ? error.failure : 'connection'
  } finally {
    isSubmitting.value = false
  }

  if (nextFailure) {
    await exposeFailure(nextFailure)
    return
  }
  await router.replace(returnPath.value ?? { name: 'home' })
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

      <form class="mt-8 stack-y-xl" :aria-busy="isSubmitting" @submit.prevent="register">
        <label class="block text-sm font-semibold">{{ t('auth.username') }}
          <input ref="usernameInput" v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="40" :disabled="inputsLocked" :aria-invalid="usernameInvalid ? 'true' : undefined" :aria-describedby="usernameInvalid ? 'auth-register-username-hint auth-register-error' : 'auth-register-username-hint'" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
          <span id="auth-register-username-hint" class="mt-1.5 block font-normal text-ink/40">{{ t('auth.register.usernameHint') }}</span>
        </label>
        <label class="block text-sm font-semibold">{{ t('auth.email') }}
          <input ref="emailInput" v-model="email" name="email" type="email" autocomplete="email" required maxlength="254" :disabled="inputsLocked" :aria-invalid="emailInvalid ? 'true' : undefined" :aria-describedby="emailInvalid ? 'auth-register-email-hint auth-register-error' : 'auth-register-email-hint'" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
          <span id="auth-register-email-hint" class="mt-1.5 block font-normal text-ink/40">{{ t('auth.register.emailHint') }}</span>
        </label>
        <label class="block text-sm font-semibold">{{ t('auth.password') }}
          <input v-model="password" name="password" type="password" autocomplete="new-password" required minlength="8" :disabled="inputsLocked" :aria-invalid="passwordInvalid ? 'true' : undefined" :aria-describedby="passwordInvalid ? 'auth-register-password-hint auth-register-error' : 'auth-register-password-hint'" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
          <span id="auth-register-password-hint" class="mt-1.5 block font-normal text-ink/40">{{ t('auth.register.passwordHint') }}</span>
        </label>
        <label class="block text-sm font-semibold">{{ t('auth.register.confirm') }}
          <input ref="confirmationInput" v-model="confirmation" name="confirmation" type="password" autocomplete="new-password" required minlength="8" :disabled="inputsLocked" :aria-invalid="confirmationInvalid ? 'true' : undefined" :aria-describedby="confirmationInvalid ? 'auth-register-error' : undefined" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-50" @input="clearFailure">
        </label>

        <p v-if="errorMessage" id="auth-register-error" ref="errorSummary" tabindex="-1" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700 focus-visible:outline-offset-4" role="alert">{{ errorMessage }}</p>
        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ submitLabel }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        {{ t('auth.register.existing') }}
        <RouterLink replace :to="loginTarget" class="font-semibold text-indigo">{{ t('auth.register.signIn') }}</RouterLink>
      </p>
    </section>
  </main>
</template>
