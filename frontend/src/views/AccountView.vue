<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface Session { username: string; roles: string[] }
interface TeachingPlan { id: string; gameTitle: string; createdAt: string }
interface ModelSnapshot {
  providers: Array<{ configured: boolean }>
  assignments: { teaching: string; visual: string; answer: string; critic: string }
}

const router = useRouter()
const session = ref<Session | null>(null)
const plans = ref<TeachingPlan[]>([])
const models = ref<ModelSnapshot | null>(null)
const loading = ref(true)
const errorMessage = ref('')

const connectedModels = computed(() => models.value?.providers.filter((item) => item.configured).length ?? 0)
const initial = computed(() => session.value?.username.slice(0, 1).toUpperCase() ?? '?')

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const responses = await Promise.all([
      fetch('/api/auth/session', { credentials: 'include' }),
      fetch('/api/v1/teaching-plans', { credentials: 'include' }),
      fetch('/api/v1/model-configuration', { credentials: 'include' }),
    ])
    if (responses.some((response) => response.status === 401)) {
      await router.push({ name: 'login' })
      return
    }
    if (responses.some((response) => !response.ok)) throw new Error('暂时无法读取账户信息。')
    session.value = await responses[0]!.json() as Session
    plans.value = await responses[1]!.json() as TeachingPlan[]
    models.value = await responses[2]!.json() as ModelSnapshot
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '暂时无法读取账户信息。'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell>
    <section class="mx-auto max-w-5xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <p class="text-sm font-medium text-copper">我的账户</p>
      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">正在读取你的空间…</div>
      <div v-else-if="errorMessage" class="mt-8 rounded-xl bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 font-semibold underline" @click="load">重试</button>
      </div>
      <template v-else-if="session">
        <header class="mt-5 flex items-center gap-5 border-b border-ink/10 pb-8">
          <span class="grid h-16 w-16 place-items-center rounded-full bg-ink font-display text-2xl font-semibold text-canvas" aria-hidden="true">{{ initial }}</span>
          <div>
            <h1 class="font-display text-4xl font-semibold tracking-tight">{{ session.username }}</h1>
            <p class="mt-2 text-sm text-ink/45">{{ session.roles.join(' · ') }}</p>
          </div>
        </header>

        <div class="mt-8 grid gap-5 sm:grid-cols-2">
          <RouterLink :to="{ name: 'lessons' }" class="rounded-xl border border-ink/10 bg-paper p-6 hover:border-copper/40">
            <p class="text-sm text-ink/45">我的讲解</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ plans.length }} 份</p>
            <p class="mt-4 text-sm text-indigo">查看和继续 →</p>
          </RouterLink>
          <RouterLink :to="{ name: 'model-settings' }" class="rounded-xl border border-ink/10 bg-paper p-6 hover:border-copper/40">
            <p class="text-sm text-ink/45">大模型连接</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ connectedModels }} 个</p>
            <p class="mt-4 text-sm text-indigo">配置 API Key 和用途 →</p>
          </RouterLink>
        </div>

        <section class="mt-8 rounded-xl border border-ink/10 bg-paper p-6">
          <h2 class="font-display text-2xl font-semibold">当前模型分工</h2>
          <dl class="mt-5 grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div><dt class="text-ink/45">讲解文字</dt><dd class="mt-1 font-semibold">{{ models?.assignments.teaching }}</dd></div>
            <div><dt class="text-ink/45">页面视觉</dt><dd class="mt-1 font-semibold">{{ models?.assignments.visual }}</dd></div>
            <div><dt class="text-ink/45">规则答疑</dt><dd class="mt-1 font-semibold">{{ models?.assignments.answer }}</dd></div>
            <div><dt class="text-ink/45">事实审校</dt><dd class="mt-1 font-semibold">{{ models?.assignments.critic }}</dd></div>
          </dl>
        </section>
      </template>
    </section>
  </AppShell>
</template>
