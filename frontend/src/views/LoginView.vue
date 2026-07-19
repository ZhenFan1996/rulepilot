<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'

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

    await router.push({ name: 'account' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录暂时失败。'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md border border-ink/10 bg-paper p-7 sm:p-9">
      <RouterLink :to="{ name: 'home' }" aria-label="返回 RulePilot 首页"><ProductMark /></RouterLink>
      <h1 class="mt-10 font-display text-4xl font-semibold tracking-tight">欢迎回来</h1>
      <p class="mt-3 leading-7 text-ink/55">登录后进入你的规则书、讲解和模型设置。</p>

      <form class="mt-8 space-y-5" @submit.prevent="login">
        <label class="block text-sm font-semibold">
          用户名
          <input v-model="username" name="username" autocomplete="username" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>
        <label class="block text-sm font-semibold">
          密码
          <input v-model="password" name="password" type="password" autocomplete="current-password" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none transition focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>

        <p v-if="errorMessage" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white transition-colors hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        第一次使用？
        <RouterLink :to="{ name: 'register' }" class="font-semibold text-indigo">创建账号</RouterLink>
      </p>
    </section>
  </main>
</template>
