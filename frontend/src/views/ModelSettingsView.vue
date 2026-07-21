<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

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

const router = useRouter()
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

const providerLabels: Record<string, string> = {
  gemini: 'Gemini',
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  qwen: 'Qwen',
  compatible: '其他兼容模型',
}

const provider = computed(() => snapshot.value?.providers.find((entry) => entry.id === selectedProvider.value))
const configuredProviders = computed(() => snapshot.value?.providers.filter((entry) => entry.configured) ?? [])
const configuredVisualProviders = computed(() => configuredProviders.value.filter((entry) => entry.visionCapable))
const needsBaseUrl = computed(() => selectedProvider.value !== 'gemini')
const visualProvider = computed(() => snapshot.value?.providers.find(
  (entry) => entry.id === snapshot.value?.assignments.visual,
))
const qwenSelected = computed(() => selectedProvider.value === 'qwen')
const roleDefinitions = [
  ['teaching', '讲解文字与结构'],
  ['visual', '规则书页面视觉'],
  ['answer', '规则答疑'],
  ['critic', '事实审校'],
] as const

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
    await router.push({ name: 'login' })
    throw new Error('登录已失效。')
  }
  if (response.status === 403) throw new Error('当前账户不能修改模型配置。')
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
    const response = await checkedResponse(await fetch('/api/v1/model-configuration', { credentials: 'include' }))
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
    await mutate(`/api/v1/model-configuration/providers/${selectedProvider.value}`, 'PUT', {
      apiKey: apiKey.value,
      baseUrl: needsBaseUrl.value ? baseUrl.value : '',
      model: modelName.value,
      visionCapable: visionCapable.value,
    })
    apiKey.value = ''
    message.value = `${providerLabels[selectedProvider.value]} 已连接。`
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
    await mutate(`/api/v1/model-configuration/providers/${selectedProvider.value}`, 'DELETE')
    message.value = `${providerLabels[selectedProvider.value]} 已停用，相关功能将使用内置演示模式。`
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
    await mutate('/api/v1/model-configuration/assignments', 'PUT', snapshot.value.assignments)
    message.value = '用途设置已生效。'
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存模型分工失败。'
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
        <p class="text-sm font-medium text-copper">模型设置</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">连接你使用的模型服务</h1>
        <p class="mt-4 leading-7 text-ink/55">这些连接只属于当前账户。密钥直接交给本机后端，页面不会保存或再次显示。</p>
      </div>

      <div v-if="loading" class="mt-10 rounded-3xl border border-ink/10 bg-paper p-8 text-ink/55">正在读取模型配置…</div>
      <div v-else-if="!snapshot" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage || '模型配置不可用。' }}</p>
        <button class="mt-4 rounded-xl border border-red-300 px-4 py-2 font-semibold" @click="loadConfiguration">重试</button>
      </div>

      <template v-else>
        <div class="mt-8 border-l-2 border-copper/60 pl-4 text-sm leading-6 text-ink/60">
          这里的连接在后端重启后会清除。长期使用时请改为在本机 `.env` 中配置。
        </div>

        <p v-if="message" class="mt-5 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>

        <div class="mt-8 grid gap-8 lg:grid-cols-[1.15fr_0.85fr]">
          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <div class="flex flex-wrap gap-2" role="tablist" aria-label="模型供应商">
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
                <span class="rounded-md px-2.5 py-1 text-xs font-semibold" :class="provider?.configured ? 'bg-emerald-100 text-emerald-800' : 'bg-ink/5 text-ink/45'">
                  {{ provider?.configured ? '已配置' : '未配置' }}
                </span>
              </div>

              <p v-if="qwenSelected" class="rounded-lg bg-indigo/5 px-4 py-3 text-sm leading-6 text-ink/65">
                Qwen VL 可以读取规则书页面图片。默认使用 qwen3-vl-plus；如果你的百炼账号位于其他地域或使用工作空间，请按控制台信息替换 Base URL。
              </p>

              <label class="block text-sm font-semibold">
                API Key
                <input v-model="apiKey" required type="password" autocomplete="new-password" maxlength="4096" placeholder="保存后不会再次显示" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label v-if="needsBaseUrl" class="block text-sm font-semibold">
                API Base URL
                <input v-model="baseUrl" required type="url" maxlength="500" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label class="block text-sm font-semibold">
                模型名称
                <input v-model="modelName" required maxlength="200" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo">
              </label>

              <label class="flex min-h-11 items-start gap-3 rounded-lg border border-ink/10 bg-canvas px-4 py-3 text-sm">
                <input v-model="visionCapable" type="checkbox" class="mt-1 size-4 accent-indigo">
                <span><strong class="block">当前模型支持图片输入</strong><span class="mt-1 block font-normal leading-5 text-ink/45">只有模型文档明确支持图片时才启用；关闭后会自动取消它的“规则书页面视觉”用途。</span></span>
              </label>

              <div class="flex flex-col gap-3 sm:flex-row">
                <button :disabled="savingProvider" class="min-h-11 flex-1 rounded-lg bg-indigo px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingProvider ? '正在保存…' : '保存连接' }}</button>
                <button v-if="provider?.configured" type="button" :disabled="savingProvider" class="min-h-11 rounded-lg border border-red-200 px-5 py-3 font-semibold text-red-700 disabled:opacity-50" @click="disableProvider">停用</button>
              </div>
            </form>
          </section>

          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <p class="text-xs font-medium text-ink/40">用途</p>
            <h2 class="mt-2 font-display text-2xl font-semibold">各项功能使用哪个模型</h2>
            <p class="mt-3 text-sm leading-6 text-ink/55">未连接外部服务时，可以继续使用不联网的内置演示。</p>
            <p v-if="!visualProvider" class="mt-4 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
              当前未启用页面视觉。讲解仍会引用并展示原文页，但 Agent 不会识别棋盘布局、组件照片和图标；连接 Gemini、OpenAI 或 Qwen VL 后，可只把页面视觉交给它，讲解文字仍由你选择的模型完成。
            </p>

            <form class="mt-7 space-y-5" @submit.prevent="saveAssignments">
              <label v-for="role in roleDefinitions" :key="role[0]" class="block text-sm font-semibold">
                {{ role[1] }}
                <select v-model="snapshot.assignments[role[0]]" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 text-ink outline-none focus:border-copper">
                  <option value="fake">{{ role[0] === 'visual' ? '不读取页面图片' : '内置演示（不联网）' }}</option>
                  <option v-for="item in role[0] === 'visual' ? configuredVisualProviders : configuredProviders" :key="item.id" :value="item.id">{{ providerLabels[item.id] }}</option>
                </select>
                <span v-if="role[0] === 'visual'" class="mt-1.5 block font-normal leading-5 text-ink/40">基础讲解发布后，它会为少量最需要看图的章节单独定位规则书区域。这会产生额外视觉调用，但不会阻塞讲解阅读；慢或无效结果会被跳过。</span>
              </label>

              <button :disabled="savingAssignments" class="min-h-11 w-full rounded-lg bg-copper px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingAssignments ? '正在应用…' : '保存用途设置' }}</button>
            </form>

            <p class="mt-5 text-xs text-ink/35">设置编号 {{ snapshot.revision }} · 后端重启后清除</p>
          </section>
        </div>
      </template>
    </div>
  </AppShell>
</template>
