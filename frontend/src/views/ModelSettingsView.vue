<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

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
}

interface Assignments {
  teaching: string
  answer: string
  critic: string
}

interface ConfigurationSnapshot {
  providers: ProviderView[]
  assignments: Assignments
  revision: number
  volatileSecrets: boolean
}

const router = useRouter()
const snapshot = ref<ConfigurationSnapshot | null>(null)
const selectedProvider = ref('gemini')
const apiKey = ref('')
const baseUrl = ref('')
const modelName = ref('')
const loading = ref(true)
const savingProvider = ref(false)
const savingAssignments = ref(false)
const message = ref('')
const errorMessage = ref('')

const providerLabels: Record<string, string> = {
  gemini: 'Gemini',
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  compatible: '其他兼容模型',
}

const provider = computed(() => snapshot.value?.providers.find((entry) => entry.id === selectedProvider.value))
const configuredProviders = computed(() => snapshot.value?.providers.filter((entry) => entry.configured) ?? [])
const needsBaseUrl = computed(() => selectedProvider.value !== 'gemini')

function selectProvider(id: string) {
  selectedProvider.value = id
  const selected = snapshot.value?.providers.find((entry) => entry.id === id)
  apiKey.value = ''
  baseUrl.value = selected?.baseUrl ?? ''
  modelName.value = selected?.model ?? ''
  message.value = ''
  errorMessage.value = ''
}

async function checkedResponse(response: Response) {
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('登录已失效。')
  }
  if (response.status === 403) throw new Error('只有管理员可以配置大模型。')
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null
    throw new Error(problem?.detail ?? '模型配置请求失败。')
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
    const response = await checkedResponse(await fetch('/api/admin/model-configuration', { credentials: 'include' }))
    snapshot.value = (await response.json()) as ConfigurationSnapshot
    selectProvider(selectedProvider.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取模型配置。'
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
    await mutate(`/api/admin/model-configuration/providers/${selectedProvider.value}`, 'PUT', {
      apiKey: apiKey.value,
      baseUrl: needsBaseUrl.value ? baseUrl.value : '',
      model: modelName.value,
    })
    apiKey.value = ''
    message.value = `${providerLabels[selectedProvider.value]} 已配置，可分配给 Agent。`
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存模型失败。'
  } finally {
    savingProvider.value = false
  }
}

async function disableProvider() {
  savingProvider.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await mutate(`/api/admin/model-configuration/providers/${selectedProvider.value}`, 'DELETE')
    message.value = `${providerLabels[selectedProvider.value]} 已停用，相关角色已切回 Fake。`
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '停用模型失败。'
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
    await mutate('/api/admin/model-configuration/assignments', 'PUT', snapshot.value.assignments)
    message.value = 'Agent 模型分工已立即生效。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存模型分工失败。'
  } finally {
    savingAssignments.value = false
  }
}

onMounted(loadConfiguration)
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/75 backdrop-blur">
      <div class="mx-auto flex max-w-6xl items-center justify-between px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold">RulePilot</RouterLink>
        <span class="rounded-full border border-ink/10 px-3 py-1.5 text-xs font-semibold text-ink/55">管理员 · 运行时配置</span>
      </div>
    </header>

    <div class="mx-auto max-w-6xl px-5 py-10 sm:px-8 lg:py-14">
      <div class="max-w-3xl">
        <p class="eyebrow">MODEL CONTROL</p>
        <h1 class="mt-4 font-display text-4xl font-semibold tracking-tight sm:text-5xl">在这里连接和分配大模型</h1>
        <p class="mt-5 leading-7 text-ink/60">API Key 只提交给后端并保存在当前后端进程内存中；页面、响应和浏览器存储都不会保存或回显密钥。</p>
      </div>

      <div v-if="loading" class="mt-10 rounded-3xl border border-ink/10 bg-paper p-8 text-ink/55">正在读取模型配置…</div>
      <div v-else-if="!snapshot" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage || '模型配置不可用。' }}</p>
        <button class="mt-4 rounded-xl border border-red-300 px-4 py-2 font-semibold" @click="loadConfiguration">重试</button>
      </div>

      <template v-else>
        <div class="mt-8 rounded-2xl border border-copper/25 bg-copper/8 px-4 py-3 text-sm leading-6 text-ink/70">
          后端重启后会恢复 `.env` 中的默认配置。生产环境请使用 HTTPS；不要在共享或不可信设备上输入密钥。
        </div>

        <p v-if="message" class="mt-5 rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <div class="mt-8 grid gap-8 lg:grid-cols-[1.15fr_0.85fr]">
          <section class="rounded-3xl border border-ink/10 bg-paper p-5 sm:p-7">
            <div class="flex flex-wrap gap-2" role="tablist" aria-label="模型供应商">
              <button
                v-for="item in snapshot.providers"
                :key="item.id"
                type="button"
                role="tab"
                :aria-selected="selectedProvider === item.id"
                class="rounded-full border px-4 py-2 text-sm font-semibold transition-colors"
                :class="selectedProvider === item.id ? 'border-ink bg-ink text-canvas' : 'border-ink/10 bg-canvas text-ink/60 hover:border-ink/30'"
                @click="selectProvider(item.id)"
              >
                {{ providerLabels[item.id] }}
                <span class="ml-1" :class="item.configured ? 'text-emerald-500' : 'text-ink/30'" aria-hidden="true">●</span>
              </button>
            </div>

            <form class="mt-7 space-y-5" @submit.prevent="saveProvider">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <h2 class="font-display text-2xl font-semibold">{{ providerLabels[selectedProvider] }}</h2>
                  <p class="mt-1 text-sm text-ink/50">{{ provider?.configured ? '已连接；输入新的 Key 可替换当前连接。' : '尚未连接。' }}</p>
                </div>
                <span class="rounded-full px-3 py-1 text-xs font-semibold" :class="provider?.configured ? 'bg-emerald-100 text-emerald-800' : 'bg-ink/5 text-ink/45'">
                  {{ provider?.configured ? '已配置' : '未配置' }}
                </span>
              </div>

              <label class="block text-sm font-semibold">
                API Key
                <input v-model="apiKey" required type="password" autocomplete="new-password" maxlength="4096" placeholder="保存后不会再次显示" class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label v-if="needsBaseUrl" class="block text-sm font-semibold">
                API Base URL
                <input v-model="baseUrl" required type="url" maxlength="500" class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label class="block text-sm font-semibold">
                模型名称
                <input v-model="modelName" required maxlength="200" class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <div class="flex flex-col gap-3 sm:flex-row">
                <button :disabled="savingProvider" class="min-h-11 flex-1 rounded-2xl bg-indigo px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingProvider ? '正在保存…' : '保存连接' }}</button>
                <button v-if="provider?.configured" type="button" :disabled="savingProvider" class="min-h-11 rounded-2xl border border-red-200 px-5 py-3 font-semibold text-red-700 disabled:opacity-50" @click="disableProvider">停用</button>
              </div>
            </form>
          </section>

          <section class="rounded-3xl border border-ink/10 bg-ink-panel p-5 text-panel-text sm:p-7">
            <p class="text-xs font-semibold uppercase tracking-[0.2em] text-panel-text/45">AGENT ASSIGNMENTS</p>
            <h2 class="mt-3 font-display text-2xl font-semibold">为每项工作选择模型</h2>
            <p class="mt-3 text-sm leading-6 text-panel-text/55">只有已经配置的供应商可以选择。Fake 不发起外部请求。</p>

            <form class="mt-7 space-y-5" @submit.prevent="saveAssignments">
              <label v-for="role in ([['teaching', '规则讲解'], ['answer', '规则答疑'], ['critic', '事实审校']] as const)" :key="role[0]" class="block text-sm font-semibold">
                {{ role[1] }}
                <select v-model="snapshot.assignments[role[0]]" class="mt-2 w-full rounded-2xl border border-white/15 bg-white/8 px-4 py-3 text-panel-text outline-none focus:border-copper">
                  <option value="fake" class="text-ink">Fake（本地无费用）</option>
                  <option v-for="item in configuredProviders" :key="item.id" :value="item.id" class="text-ink">{{ providerLabels[item.id] }}</option>
                </select>
              </label>

              <button :disabled="savingAssignments" class="min-h-11 w-full rounded-2xl bg-copper px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingAssignments ? '正在应用…' : '应用模型分工' }}</button>
            </form>

            <p class="mt-5 text-xs text-panel-text/40">配置版本 {{ snapshot.revision }} · 仅当前后端进程</p>
          </section>
        </div>
      </template>
    </div>
  </main>
</template>
