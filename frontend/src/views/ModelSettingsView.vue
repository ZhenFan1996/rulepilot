<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface CsrfResponse {
  headerName: string
  token: string
}

interface ProviderView {
  id: string
  configured: boolean
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  visionCapable: boolean
}

interface Assignments {
  teaching: string
  visual: string
  answer: string
  critic: string
}

interface ConfigurationSnapshot {
  providers: ProviderView[]
  assignments: Assignments
  revision: number
  volatileSecrets: boolean
}

const { t } = useLocale()
const snapshot = ref<ConfigurationSnapshot | null>(null)
const selectedProvider = ref('gemini')
const apiKey = ref('')
const baseUrl = ref('')
const modelName = ref('')
const visionCapable = ref(false)
const loading = ref(true)
const savingProvider = ref(false)
const savingAssignments = ref(false)
const message = ref('')
const errorMessage = ref('')

function providerLabel(id: string) {
  return id === 'compatible' ? t('models.provider.compatible') : ({ gemini: 'Gemini', openai: 'OpenAI', deepseek: 'DeepSeek', qwen: 'Qwen' }[id] ?? id)
}

const provider = computed(() => snapshot.value?.providers.find((entry) => entry.id === selectedProvider.value))
const configuredProviders = computed(() => snapshot.value?.providers.filter((entry) => entry.configured) ?? [])
const configuredVisualProviders = computed(() => configuredProviders.value.filter((entry) => entry.visionCapable))
const needsBaseUrl = computed(() => selectedProvider.value !== 'gemini')
const visualProvider = computed(() => snapshot.value?.providers.find(
  (entry) => entry.id === snapshot.value?.assignments.visual,
))
const qwenSelected = computed(() => selectedProvider.value === 'qwen')
const roleDefinitions = computed(() => [
  ['teaching', t('models.role.teaching')],
  ['visual', t('models.role.visual')],
  ['answer', t('models.role.answer')],
  ['critic', t('models.role.critic')],
] as const)

function selectProvider(id: string) {
  selectedProvider.value = id
  const selected = snapshot.value?.providers.find((entry) => entry.id === id)
  apiKey.value = ''
  baseUrl.value = selected?.baseUrl ?? ''
  modelName.value = selected?.model ?? ''
  visionCapable.value = selected?.visionCapable ?? false
  message.value = ''
  errorMessage.value = ''
}

async function checkedResponse(response: Response) {
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('models.expired'))
  }
  if (response.status === 403) throw new Error(t('models.forbidden'))
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null
    throw new Error(problem?.detail ?? t('models.error'))
  }
  return response
}

async function csrfToken() {
  const response = await checkedResponse(await fetch('/api/auth/csrf', { credentials: 'include' }))
  return (await response.json()) as CsrfResponse
}

async function loadConfiguration() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await checkedResponse(await fetch('/api/v1/model-configuration', { credentials: 'include' }))
    snapshot.value = (await response.json()) as ConfigurationSnapshot
    selectProvider(selectedProvider.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    loading.value = false
  }
}

async function mutate(path: string, method: 'PUT' | 'DELETE', body?: unknown) {
  const csrf = await csrfToken()
  const response = await checkedResponse(await fetch(path, {
    method,
    credentials: 'include',
    headers: {
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      [csrf.headerName]: csrf.token,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }))
  snapshot.value = (await response.json()) as ConfigurationSnapshot
}

async function saveProvider() {
  savingProvider.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await mutate(`/api/v1/model-configuration/providers/${selectedProvider.value}`, 'PUT', {
      apiKey: apiKey.value,
      baseUrl: needsBaseUrl.value ? baseUrl.value : '',
      model: modelName.value,
      visionCapable: visionCapable.value,
    })
    apiKey.value = ''
    message.value = t('models.connected', { provider: providerLabel(selectedProvider.value) })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingProvider.value = false
  }
}

async function disableProvider() {
  savingProvider.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await mutate(`/api/v1/model-configuration/providers/${selectedProvider.value}`, 'DELETE')
    message.value = t('models.disabled', { provider: providerLabel(selectedProvider.value) })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingProvider.value = false
  }
}

async function saveAssignments() {
  if (!snapshot.value) return
  savingAssignments.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await mutate('/api/v1/model-configuration/assignments', 'PUT', snapshot.value.assignments)
    message.value = t('models.assignmentsSaved')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingAssignments.value = false
  }
}

onMounted(loadConfiguration)
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-6xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <div class="max-w-3xl">
        <p class="text-sm font-medium text-copper">{{ t('models.eyebrow') }}</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">{{ t('models.title') }}</h1>
        <p class="mt-4 leading-7 text-ink/55">{{ t('models.description') }}</p>
      </div>

      <div v-if="loading" class="mt-10 rounded-3xl border border-ink/10 bg-paper p-8 text-ink/55">{{ t('models.loading') }}</div>
      <div v-else-if="!snapshot" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage || t('models.unavailable') }}</p>
        <button class="mt-4 rounded-xl border border-red-300 px-4 py-2 font-semibold" @click="loadConfiguration">{{ t('models.retry') }}</button>
      </div>

      <template v-else>
        <div class="mt-8 border-l-2 border-copper/60 pl-4 text-sm leading-6 text-ink/60">
          {{ t('models.temporary') }}
        </div>

        <p v-if="message" class="mt-5 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <div class="mt-8 grid gap-8 lg:grid-cols-[1.15fr_0.85fr]">
          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <div class="flex flex-wrap gap-2" role="tablist" :aria-label="t('models.providers')">
              <button
                v-for="item in snapshot.providers"
                :key="item.id"
                type="button"
                role="tab"
                :aria-selected="selectedProvider === item.id"
                class="rounded-lg border px-4 py-2 text-sm font-semibold transition-colors"
                :class="selectedProvider === item.id ? 'border-ink bg-ink text-canvas' : 'border-ink/10 bg-canvas text-ink/60 hover:border-ink/30'"
                @click="selectProvider(item.id)"
              >
                {{ providerLabel(item.id) }}
                <span class="ml-1" :class="item.configured ? 'text-emerald-500' : 'text-ink/30'" aria-hidden="true">●</span>
              </button>
            </div>

            <form class="mt-7 space-y-5" @submit.prevent="saveProvider">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <h2 class="font-display text-2xl font-semibold">{{ providerLabel(selectedProvider) }}</h2>
                  <p class="mt-1 text-sm text-ink/50">{{ provider?.configured ? t('models.connectedHint') : t('models.disconnectedHint') }}</p>
                </div>
                <span class="rounded-md px-2.5 py-1 text-xs font-semibold" :class="provider?.configured ? 'bg-emerald-100 text-emerald-800' : 'bg-ink/5 text-ink/45'">
                  {{ provider?.configured ? t('models.configured') : t('models.notConfigured') }}
                </span>
              </div>

              <p v-if="qwenSelected" class="rounded-lg bg-indigo/5 px-4 py-3 text-sm leading-6 text-ink/65">
                {{ t('models.qwenHint') }}
              </p>

              <label class="block text-sm font-semibold">
                API Key
                <input v-model="apiKey" required type="password" autocomplete="new-password" maxlength="4096" :placeholder="t('models.keyHint')" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label v-if="needsBaseUrl" class="block text-sm font-semibold">
                API Base URL
                <input v-model="baseUrl" required type="url" maxlength="500" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label class="block text-sm font-semibold">
                {{ t('models.name') }}
                <input v-model="modelName" required maxlength="200" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label class="flex min-h-11 items-start gap-3 rounded-lg border border-ink/10 bg-canvas px-4 py-3 text-sm">
                <input v-model="visionCapable" type="checkbox" class="mt-1 size-4 accent-indigo">
                <span><strong class="block">{{ t('models.vision.title') }}</strong><span class="mt-1 block font-normal leading-5 text-ink/45">{{ t('models.vision.description') }}</span></span>
              </label>

              <div class="flex flex-col gap-3 sm:flex-row">
                <button :disabled="savingProvider" class="min-h-11 flex-1 rounded-lg bg-indigo px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingProvider ? t('models.saving') : t('models.saveConnection') }}</button>
                <button v-if="provider?.configured" type="button" :disabled="savingProvider" class="min-h-11 rounded-lg border border-red-200 px-5 py-3 font-semibold text-red-700 disabled:opacity-50" @click="disableProvider">{{ t('models.disable') }}</button>
              </div>
            </form>
          </section>

          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <p class="text-xs font-medium text-ink/40">{{ t('models.uses') }}</p>
            <h2 class="mt-2 font-display text-2xl font-semibold">{{ t('models.assignmentTitle') }}</h2>
            <p class="mt-3 text-sm leading-6 text-ink/55">{{ t('models.assignmentDescription') }}</p>
            <p v-if="!visualProvider" class="mt-4 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
              {{ t('models.noVisual') }}
            </p>

            <form class="mt-7 space-y-5" @submit.prevent="saveAssignments">
              <label v-for="role in roleDefinitions" :key="role[0]" class="block text-sm font-semibold">
                {{ role[1] }}
                <select v-model="snapshot.assignments[role[0]]" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 text-ink outline-none focus:border-copper">
                  <option value="fake">{{ role[0] === 'visual' ? t('models.noVisualOption') : t('models.fakeOption') }}</option>
                  <option v-for="item in role[0] === 'visual' ? configuredVisualProviders : configuredProviders" :key="item.id" :value="item.id">{{ providerLabel(item.id) }}</option>
                </select>
                <span v-if="role[0] === 'visual'" class="mt-1.5 block font-normal leading-5 text-ink/40">{{ t('models.visualRoleHint') }}</span>
              </label>

              <button :disabled="savingAssignments" class="min-h-11 w-full rounded-lg bg-copper px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingAssignments ? t('models.applying') : t('models.saveUses') }}</button>
            </form>

            <p class="mt-5 text-xs text-ink/35">{{ t('models.revision', { revision: snapshot.revision }) }}</p>
          </section>
        </div>
      </template>
    </div>
  </AppShell>
</template>
