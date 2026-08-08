<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface Session { username: string; roles: string[] }
interface TeachingPlan { id: string; gameTitle: string; createdAt: string }
interface ModelSnapshot {
  providers: Array<{ configured: boolean }>
  assignments: { teaching: string; visual: string; answer: string; critic: string }
}

const { t } = useLocale()
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
      notifyLoginRequired()
      errorMessage.value = t('account.loginRequired')
      return
    }
    if (responses.some((response) => !response.ok)) throw new Error(t('account.error'))
    session.value = await responses[0]!.json() as Session
    plans.value = await responses[1]!.json() as TeachingPlan[]
    models.value = await responses[2]!.json() as ModelSnapshot
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('account.error')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell>
    <section class="tabletop-page max-w-5xl">
      <p class="text-sm font-medium text-copper">{{ t('account.title') }}</p>
      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">{{ t('account.loading') }}</div>
      <div v-else-if="errorMessage" class="mt-8 rounded-xl bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 font-semibold underline" @click="load">{{ t('account.retry') }}</button>
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
            <p class="text-sm text-ink/45">{{ t('account.guides') }}</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ t('account.guideCount', { count: plans.length }) }}</p>
            <p class="mt-4 text-sm text-indigo">{{ t('account.guideAction') }}</p>
          </RouterLink>
          <RouterLink :to="{ name: 'model-settings' }" class="rounded-xl border border-ink/10 bg-paper p-6 hover:border-copper/40">
            <p class="text-sm text-ink/45">{{ t('account.models') }}</p>
            <p class="mt-2 font-display text-3xl font-semibold">{{ t('account.modelCount', { count: connectedModels }) }}</p>
            <p class="mt-4 text-sm text-indigo">{{ t('account.modelAction') }}</p>
          </RouterLink>
        </div>

        <section class="mt-8 rounded-xl border border-ink/10 bg-paper p-6">
          <h2 class="font-display text-2xl font-semibold">{{ t('account.assignments') }}</h2>
          <dl class="mt-5 grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div><dt class="text-ink/45">{{ t('account.teaching') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.teaching }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.visual') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.visual }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.answer') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.answer }}</dd></div>
            <div><dt class="text-ink/45">{{ t('account.critic') }}</dt><dd class="mt-1 font-semibold">{{ models?.assignments.critic }}</dd></div>
          </dl>
        </section>
      </template>
    </section>
  </AppShell>
</template>
