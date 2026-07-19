<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import ProductMark from '@/components/ProductMark.vue'

interface CsrfResponse { headerName: string; token: string }

const router = useRouter()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const isSubmitting = ref(false)
const errorMessage = ref('')

async function register() {
  errorMessage.value = ''
  if (password.value !== confirmation.value) {
    errorMessage.value = '两次输入的密码不一致。'
    return
  }
  isSubmitting.value = true
  try {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    if (!csrfResponse.ok) throw new Error('无法连接服务器，请确认后端和 Redis 已启动。')
    const csrf = await csrfResponse.json() as CsrfResponse
    const response = await fetch('/api/auth/register', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ username: username.value, password: password.value }),
    })
    if (response.status === 409) throw new Error('这个用户名已经有人使用。')
    if (response.status === 400) throw new Error('用户名需为 3–40 个字符；密码至少 8 个字符。')
    if (!response.ok) throw new Error('暂时无法创建账号，请稍后重试。')

    const body = new URLSearchParams({ username: username.value.trim().toLowerCase(), password: password.value })
    const loginResponse = await fetch('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
      body,
    })
    if (!loginResponse.ok) throw new Error('账号已经创建，请返回登录页登录。')
    await router.push({ name: 'account' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '暂时无法创建账号。'
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-canvas px-5 py-12 text-ink">
    <section class="w-full max-w-md border border-ink/10 bg-paper p-7 sm:p-9">
      <RouterLink :to="{ name: 'home' }" aria-label="返回 RulePilot 首页"><ProductMark /></RouterLink>
      <h1 class="mt-10 font-display text-4xl font-semibold tracking-tight">创建账号</h1>
      <p class="mt-3 leading-7 text-ink/55">保存你的规则书、讲解进度和模型设置。</p>

      <form class="mt-8 space-y-5" @submit.prevent="register">
        <label class="block text-sm font-semibold">用户名
          <input v-model="username" name="username" autocomplete="username" required minlength="3" maxlength="40" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
          <span class="mt-1.5 block font-normal text-ink/40">3–40 个字符，可使用中文、字母、数字、点、横线和下划线。</span>
        </label>
        <label class="block text-sm font-semibold">密码
          <input v-model="password" name="password" type="password" autocomplete="new-password" required minlength="8" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
          <span class="mt-1.5 block font-normal text-ink/40">至少 8 个字符。</span>
        </label>
        <label class="block text-sm font-semibold">再次输入密码
          <input v-model="confirmation" name="confirmation" type="password" autocomplete="new-password" required minlength="8" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo focus:ring-4 focus:ring-indigo/10">
        </label>

        <p v-if="errorMessage" class="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <button type="submit" :disabled="isSubmitting" class="w-full rounded-lg bg-indigo px-5 py-3.5 font-semibold text-white hover:bg-indigo/90 disabled:cursor-not-allowed disabled:opacity-50">
          {{ isSubmitting ? '正在创建…' : '创建并登录' }}
        </button>
      </form>

      <p class="mt-7 border-t border-ink/10 pt-6 text-center text-sm text-ink/55">
        已有账号？
        <RouterLink :to="{ name: 'login' }" class="font-semibold text-indigo">直接登录</RouterLink>
      </p>
    </section>
  </main>
</template>
