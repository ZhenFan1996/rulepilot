<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

interface CsrfResponse {
  headerName: string
  token: string
}

const router = useRouter()
const username = ref('player')
const password = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')

async function login() {
  isSubmitting.value = true
  errorMessage.value = ''

  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new Error('无法建立安全会话，请确认后端和 Redis 已启动。')
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

    if (response.status === 401) throw new Error('用户名或密码不正确。')
    if (!response.ok) throw new Error('登录暂时失败，请稍后重试。')

    await router.push({ name: 'home' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录暂时失败。'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md rounded-[2rem] border border-ink/10 bg-paper p-7 shadow-xl shadow-ink/5 sm:p-9">
      <RouterLink :to="{ name: 'home' }" class="text-sm font-semibold text-indigo">← 返回 RulePilot</RouterLink>
      <p class="mt-10 text-xs font-semibold uppercase tracking-[0.22em] text-indigo">LOCAL SESSION</p>
      <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">登录并继续学习</h1>
      <p class="mt-4 leading-7 text-ink/60">使用本地账户进入规则书讲解空间。会话由 Redis 保存，退出后立即失效。</p>

      <form class="mt-8 space-y-5" @submit.prevent="login">
        <label class="block text-sm font-semibold">
          用户名
          <input v-model="username" name="username" autocomplete="username" required class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>
        <label class="block text-sm font-semibold">
          密码
          <input v-model="password" name="password" type="password" autocomplete="current-password" required class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>

        <p v-if="errorMessage" class="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <button type="submit" :disabled="isSubmitting" class="w-full rounded-2xl bg-indigo px-5 py-3.5 font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>
