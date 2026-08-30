<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { useLocale } from '@/lib/locale'

interface RunAudit {
  run: { id: string; mode: string; subjectId: string; ownerUsername: string; state: string; createdAt: string; updatedAt: string; lastErrorCode: string | null }
  steps: Array<{ sequence: number; fromState: string; toState: string; summary: string; occurredAt: string }>
  budget: { maxTokens: number; usedToolCalls: number; usedModelCalls: number; usedTokens: number; tokenLimitEnforced: boolean; deadlineAt: string }
  activities: Array<{ sequence: number; type: string; operation: string; outcome: string; estimatedInputTokens: number; estimatedOutputTokens: number; latencyMs: number; summary: string; occurredAt: string }>
}

const route = useRoute()
const { t } = useLocale()
const runId = ref(typeof route.query.runId === 'string' ? route.query.runId : '')
const audit = ref<RunAudit | null>(null)
const loading = ref(false)
const error = ref('')
const canLoad = computed(() => /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(runId.value.trim()))

async function loadAudit() {
  if (!canLoad.value || loading.value) return
  loading.value = true
  error.value = ''
  audit.value = null
  try {
    const response = await fetch(`/api/admin/assistant-runs/${encodeURIComponent(runId.value.trim())}/audit`, { credentials: 'include' })
    if (response.status === 401) throw new Error(t('agentAudit.loginRequired'))
    if (response.status === 403) throw new Error(t('agentAudit.forbidden'))
    if (response.status === 404) throw new Error(t('agentAudit.notFound'))
    if (!response.ok) throw new Error(t('agentAudit.unavailable'))
    audit.value = await response.json() as RunAudit
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : t('agentAudit.unavailable')
  } finally {
    loading.value = false
  }
}

watch(() => route.query.runId, (value) => {
  if (typeof value !== 'string') return
  runId.value = value
  if (canLoad.value) void loadAudit()
}, { immediate: true })
</script>

<template>
  <AppShell>
    <div class="tabletop-page min-h-screen max-w-5xl text-ink">
      <div class="mx-auto max-w-6xl">
        <header class="max-w-3xl">
          <p class="text-xs font-semibold uppercase tracking-[0.14em] text-copper">{{ t('agentAudit.eyebrow') }}</p>
          <h1 class="mt-2 font-display text-4xl font-semibold">{{ t('agentAudit.title') }}</h1>
          <p class="mt-4 leading-7 text-ink/60">{{ t('agentAudit.description') }}</p>
          <p class="mt-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{{ t('agentAudit.boundary') }}</p>
        </header>

        <form class="mt-8 flex flex-col gap-3 rounded-3xl border border-ink/10 bg-paper p-5 sm:flex-row" @submit.prevent="loadAudit">
          <div class="min-w-0 flex-1"><label for="audit-run-id" class="text-sm font-semibold">{{ t('agentAudit.runId') }}</label><input id="audit-run-id" v-model="runId" class="mt-2 min-h-11 w-full rounded-xl border border-ink/15 bg-canvas px-4 font-mono text-sm outline-none focus:border-indigo" placeholder="00000000-0000-0000-0000-000000000000"></div>
          <button :disabled="!canLoad || loading" class="min-h-11 self-end rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-40">{{ loading ? t('agentAudit.loading') : t('agentAudit.load') }}</button>
        </form>

        <p v-if="error" class="mt-5 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ error }}</p>
        <p v-else-if="!audit && !loading" class="mt-8 rounded-3xl border border-dashed border-ink/15 p-8 text-center text-ink/50">{{ t('agentAudit.empty') }}</p>

        <template v-if="audit">
          <section class="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-label="Run summary">
            <div class="rounded-2xl bg-paper p-4"><p class="text-xs text-ink/45">{{ t('agentAudit.state') }}</p><p class="mt-1 font-semibold">{{ audit.run.state }}</p></div>
            <div class="rounded-2xl bg-paper p-4"><p class="text-xs text-ink/45">{{ t('agentAudit.owner') }}</p><p class="mt-1 font-semibold">{{ audit.run.ownerUsername }}</p></div>
            <div class="rounded-2xl bg-paper p-4"><p class="text-xs text-ink/45">{{ t('agentAudit.tools') }}</p><p class="mt-1 font-semibold">{{ audit.budget.usedToolCalls }}</p></div>
            <div class="rounded-2xl bg-paper p-4"><p class="text-xs text-ink/45">{{ t('agentAudit.models') }}</p><p class="mt-1 font-semibold">{{ audit.budget.usedModelCalls }}</p></div>
            <div class="rounded-2xl bg-paper p-4">
              <p class="text-xs text-ink/45">{{ t(audit.budget.tokenLimitEnforced ? 'agentAudit.tokensHard' : 'agentAudit.tokensObserved') }}</p>
              <p v-if="audit.budget.tokenLimitEnforced" class="mt-1 font-semibold">{{ audit.budget.usedTokens }} / {{ audit.budget.maxTokens }}</p>
              <template v-else>
                <p class="mt-1 font-semibold">{{ t('agentAudit.tokensObservedValue', { used: audit.budget.usedTokens, threshold: audit.budget.maxTokens }) }}</p>
                <p class="mt-1 text-xs leading-5 text-ink/50">{{ t('agentAudit.tokensObservedHint') }}</p>
              </template>
            </div>
            <div class="rounded-2xl bg-paper p-4"><p class="text-xs text-ink/45">{{ t('agentAudit.deadline') }}</p><p class="mt-1 font-semibold">{{ audit.budget.deadlineAt }}</p></div>
          </section>

          <section class="mt-8" aria-labelledby="audit-activities-title">
            <h2 id="audit-activities-title" class="font-display text-2xl font-semibold">{{ t('agentAudit.activities') }}</h2>
            <ol class="mt-4 stack-y-md"><li v-for="activity in audit.activities" :key="activity.sequence" class="rounded-2xl border border-ink/10 bg-paper p-4"><div class="flex flex-wrap items-center gap-2 text-xs font-semibold"><span class="rounded-full bg-indigo/8 px-2 py-1 text-indigo">{{ activity.type }}</span><span class="rounded-full bg-ink/6 px-2 py-1">{{ activity.outcome }}</span><span class="text-ink/45">#{{ activity.sequence }} · {{ activity.latencyMs }} ms</span></div><p class="mt-3 break-all font-mono text-sm">{{ activity.operation }}</p><p class="mt-2 text-sm leading-6 text-ink/65">{{ activity.summary }}</p><p class="mt-2 text-xs text-ink/40">tokens {{ activity.estimatedInputTokens }} → {{ activity.estimatedOutputTokens }}</p></li></ol>
          </section>

          <section class="mt-8" aria-labelledby="audit-steps-title">
            <h2 id="audit-steps-title" class="font-display text-2xl font-semibold">{{ t('agentAudit.steps') }}</h2>
            <ol class="mt-4 stack-y-sm text-sm"><li v-for="step in audit.steps" :key="step.sequence" class="rounded-xl bg-paper px-4 py-3"><span class="font-mono text-xs text-ink/45">#{{ step.sequence }}</span> <span class="font-semibold">{{ step.fromState }} → {{ step.toState }}</span><p class="mt-1 text-ink/60">{{ step.summary }}</p></li></ol>
          </section>
        </template>
      </div>
    </div>
  </AppShell>
</template>
