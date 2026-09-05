<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import AppShell from '@/components/AppShell.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface ProviderView {
  id: string
  configured: boolean
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  visionCapable: boolean
  credentialSource: string
}
interface Assignments { teaching: string; visual: string; answer: string; critic: string; recommendation: string }
interface Snapshot { providers: ProviderView[]; assignments: Assignments; revision: number }
interface Usage {
  username: string
  platformAccessEnabled: boolean
  monthlyTokenLimit: number
  platformTokensCharged: number
  platformTokensReserved: number
  personalTokensUsed: number
  platformTokensRemaining: number
}
interface Account { username: string; email: string | null; enabled: boolean; authorities: string[]; usage: Usage }
interface ProviderDraft { apiKey: string; baseUrl: string; model: string; visionCapable: boolean }

const { locale } = useLocale()
const snapshot = ref<Snapshot | null>(null)
const accounts = ref<Account[]>([])
const selectedProvider = ref('qwen')
const providerDrafts = ref<Record<string, ProviderDraft>>({})
const assignments = ref<Assignments>({ teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake', recommendation: 'fake' })
const quotaDrafts = ref<Record<string, { enabled: boolean; limit: number }>>({})
const loading = ref(true)
const saving = ref('')
const message = ref('')
const error = ref('')

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '平台运营', title: '模型与账户额度', description: '在这里维护平台提供的模型连接，并查看、调整每个账户的月度额度。API Key 只会加密保存，页面不会把旧密钥读回来。',
  providers: '平台模型连接', accounts: '账户用量', roles: '各功能使用的模型', key: '新的 API Key', keyHint: '留空表示本次不保存连接；更新连接时需要重新输入。', baseUrl: 'API 地址', model: '模型名', vision: '可读取规则页图片', saveProvider: '加密保存连接', saveRoles: '保存模型分配', configured: '已连接', missing: '未连接',
  account: '账户', access: '平台额度', limit: '月度 token 额度', used: '平台已用', personal: '自有 Key 用量', remaining: '剩余', save: '保存', enabled: '可用', paused: '暂停', loading: '正在读取平台配置…', retry: '重新加载', saved: '已保存', forbidden: '只有管理员可以打开这个页面。',
} : {
  eyebrow: 'Platform operations', title: 'Models and account quotas', description: 'Manage platform model connections and each account’s monthly allowance. API keys are encrypted at rest and existing secrets are never returned to this page.',
  providers: 'Platform model connections', accounts: 'Account usage', roles: 'Model role assignments', key: 'New API key', keyHint: 'Leave blank unless saving this connection; updates require entering the key again.', baseUrl: 'API base URL', model: 'Model', vision: 'Can read rulebook images', saveProvider: 'Encrypt and save connection', saveRoles: 'Save role assignments', configured: 'Connected', missing: 'Not connected',
  account: 'Account', access: 'Platform allowance', limit: 'Monthly token limit', used: 'Platform used', personal: 'BYOK usage', remaining: 'Remaining', save: 'Save', enabled: 'Enabled', paused: 'Paused', loading: 'Loading platform configuration…', retry: 'Reload', saved: 'Saved', forbidden: 'Only administrators can open this page.',
})

const currentProvider = computed(() => snapshot.value?.providers.find(provider => provider.id === selectedProvider.value) ?? null)
const currentDraft = computed(() => providerDrafts.value[selectedProvider.value])
const selectableProviders = computed(() => ['fake', ...(snapshot.value?.providers.filter(provider => provider.configured).map(provider => provider.id) ?? [])])
const roleDefinitions = computed(() => [
  ['recommendation', locale.value === 'zh-CN' ? '推荐' : 'Recommendation'],
  ['teaching', locale.value === 'zh-CN' ? '讲解' : 'Teaching'],
  ['visual', locale.value === 'zh-CN' ? '规则页图片' : 'Visual evidence'],
  ['answer', locale.value === 'zh-CN' ? '答疑' : 'Q&A'],
  ['critic', locale.value === 'zh-CN' ? '离线评测' : 'Offline evaluation'],
] as const)

function initialize(next: Snapshot, accountList: Account[]) {
  snapshot.value = next
  assignments.value = { ...next.assignments }
  providerDrafts.value = Object.fromEntries(next.providers.map(provider => [provider.id, {
    apiKey: '', baseUrl: provider.baseUrl, model: provider.model, visionCapable: provider.visionCapable,
  }]))
  accounts.value = accountList
  quotaDrafts.value = Object.fromEntries(accountList.map(account => [account.username, {
    enabled: account.usage.platformAccessEnabled,
    limit: account.usage.monthlyTokenLimit,
  }]))
}

async function checked(response: Response) {
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(copy.value.forbidden)
  }
  if (response.status === 403) throw new Error(copy.value.forbidden)
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { detail?: string } | null
    throw new Error(problem?.detail ?? 'Request failed')
  }
  return response
}

async function csrf() {
  const response = await checked(await fetch('/api/auth/csrf', { credentials: 'include' }))
  return await response.json() as { headerName: string; token: string }
}

async function mutate(path: string, body: unknown) {
  const token = await csrf()
  return checked(await fetch(path, {
    method: 'PUT', credentials: 'include',
    headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
    body: JSON.stringify(body),
  }))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [configurationResponse, accountsResponse] = await Promise.all([
      checked(await fetch('/api/admin/model-configuration', { credentials: 'include' })),
      checked(await fetch('/api/admin/model-configuration/accounts', { credentials: 'include' })),
    ])
    initialize(await configurationResponse.json() as Snapshot, await accountsResponse.json() as Account[])
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    loading.value = false
  }
}

async function saveProvider() {
  const draft = currentDraft.value
  if (!draft || !draft.apiKey.trim() || saving.value) return
  saving.value = `provider:${selectedProvider.value}`
  error.value = ''
  message.value = ''
  try {
    const response = await mutate(`/api/admin/model-configuration/providers/${selectedProvider.value}`, draft)
    const next = await response.json() as Snapshot
    initialize(next, accounts.value)
    selectedProvider.value = next.providers.some(provider => provider.id === selectedProvider.value) ? selectedProvider.value : next.providers[0]?.id ?? 'qwen'
    message.value = copy.value.saved
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    saving.value = ''
  }
}

async function saveAssignments() {
  if (saving.value) return
  saving.value = 'assignments'
  error.value = ''
  try {
    const response = await mutate('/api/admin/model-configuration/assignments', assignments.value)
    snapshot.value = await response.json() as Snapshot
    message.value = copy.value.saved
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    saving.value = ''
  }
}

async function saveQuota(account: Account) {
  const draft = quotaDrafts.value[account.username]
  if (!draft || saving.value) return
  saving.value = `quota:${account.username}`
  error.value = ''
  try {
    const response = await mutate(`/api/admin/model-configuration/accounts/${encodeURIComponent(account.username)}/quota`, {
      platformAccessEnabled: draft.enabled,
      monthlyTokenLimit: Math.max(0, Math.trunc(draft.limit)),
    })
    const usage = await response.json() as Usage
    accounts.value = accounts.value.map(item => item.username === account.username ? { ...item, usage } : item)
    message.value = copy.value.saved
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    saving.value = ''
  }
}

onMounted(load)
</script>

<template>
  <AppShell>
    <section class="tabletop-page max-w-7xl">
      <p class="tabletop-kicker">{{ copy.eyebrow }}</p>
      <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">{{ copy.title }}</h1>
      <p class="mt-3 max-w-3xl leading-7 text-ink/55">{{ copy.description }}</p>
      <p v-if="message" class="mt-5 rounded-xl bg-emerald-50 px-4 py-3 text-sm text-emerald-800" role="status">{{ message }}</p>
      <div v-if="loading" class="mt-8 rounded-2xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">{{ copy.loading }}</div>
      <div v-else-if="error && !snapshot" class="mt-8 rounded-2xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert"><p>{{ error }}</p><button class="mt-4 min-h-11 font-semibold underline" @click="load">{{ copy.retry }}</button></div>
      <template v-else-if="snapshot">
        <p v-if="error" class="mt-5 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-800" role="alert">{{ error }}</p>
        <section class="mt-8 rounded-3xl border border-copper/20 bg-paper p-5 sm:p-7">
          <h2 class="font-display text-2xl font-semibold">{{ copy.providers }}</h2>
          <div class="mt-5 flex flex-wrap gap-2">
            <button v-for="provider in snapshot.providers" :key="provider.id" type="button" class="min-h-11 rounded-full border px-4 text-sm font-semibold" :class="provider.id === selectedProvider ? 'border-ink bg-ink text-paper' : 'border-ink/15'" @click="selectedProvider = provider.id">{{ provider.id }} · {{ provider.configured ? copy.configured : copy.missing }}</button>
          </div>
          <form v-if="currentProvider && currentDraft" class="mt-6 grid gap-4 md:grid-cols-2" @submit.prevent="saveProvider">
            <label class="md:col-span-2"><span class="text-sm font-semibold">{{ copy.key }}</span><input v-model="currentDraft.apiKey" type="password" autocomplete="new-password" class="mt-2 min-h-12 w-full rounded-xl border border-ink/15 bg-canvas px-4"><span class="mt-1 block text-xs text-ink/45">{{ copy.keyHint }}</span></label>
            <label><span class="text-sm font-semibold">{{ copy.baseUrl }}</span><input v-model="currentDraft.baseUrl" :disabled="selectedProvider === 'gemini'" class="mt-2 min-h-12 w-full rounded-xl border border-ink/15 bg-canvas px-4 disabled:opacity-45"></label>
            <label><span class="text-sm font-semibold">{{ copy.model }}</span><input v-model="currentDraft.model" required class="mt-2 min-h-12 w-full rounded-xl border border-ink/15 bg-canvas px-4"></label>
            <label class="flex min-h-12 items-center gap-3"><input v-model="currentDraft.visionCapable" type="checkbox" class="size-5"><span class="text-sm font-semibold">{{ copy.vision }}</span></label>
            <div class="md:text-right"><button type="submit" :disabled="!currentDraft.apiKey.trim() || Boolean(saving)" class="min-h-12 rounded-xl bg-copper px-5 font-semibold text-on-accent disabled:opacity-40">{{ copy.saveProvider }}</button></div>
          </form>
        </section>

        <section class="mt-6 rounded-3xl border border-ink/10 bg-paper p-5 sm:p-7">
          <h2 class="font-display text-2xl font-semibold">{{ copy.roles }}</h2>
          <div class="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <label v-for="[role, label] in roleDefinitions" :key="role"><span class="text-sm font-semibold">{{ label }}</span><select v-model="assignments[role]" class="mt-2 min-h-12 w-full rounded-xl border border-ink/15 bg-canvas px-3"><option v-for="provider in selectableProviders" :key="provider" :value="provider">{{ provider }}</option></select></label>
          </div>
          <button type="button" :disabled="Boolean(saving)" class="mt-5 min-h-11 rounded-xl bg-indigo px-5 font-semibold text-white disabled:opacity-40" @click="saveAssignments">{{ copy.saveRoles }}</button>
        </section>

        <section class="mt-6 overflow-hidden rounded-3xl border border-ink/10 bg-paper">
          <div class="p-5 sm:p-7"><h2 class="font-display text-2xl font-semibold">{{ copy.accounts }}</h2></div>
          <div class="overflow-x-auto">
            <table class="w-full min-w-[58rem] border-collapse text-left text-sm">
              <thead class="bg-canvas text-ink/50"><tr><th class="px-5 py-3">{{ copy.account }}</th><th class="px-5 py-3">{{ copy.access }}</th><th class="px-5 py-3">{{ copy.limit }}</th><th class="px-5 py-3">{{ copy.used }}</th><th class="px-5 py-3">{{ copy.personal }}</th><th class="px-5 py-3">{{ copy.remaining }}</th><th class="px-5 py-3" /></tr></thead><tbody>
                <tr v-for="account in accounts" :key="account.username" class="border-t border-ink/8">
                  <td class="px-5 py-4"><p class="font-semibold">{{ account.username }}</p><p v-if="account.email" class="mt-1 text-xs text-ink/55">{{ account.email }}</p><p class="mt-1 text-xs text-ink/40">{{ account.authorities.join(' · ') }}</p></td>
                  <td class="px-5 py-4"><label class="inline-flex items-center gap-2"><input v-model="quotaDrafts[account.username]!.enabled" type="checkbox" class="size-5"><span>{{ quotaDrafts[account.username]!.enabled ? copy.enabled : copy.paused }}</span></label></td>
                  <td class="px-5 py-4"><input v-model.number="quotaDrafts[account.username]!.limit" type="number" min="0" step="1000" class="min-h-11 w-40 rounded-lg border border-ink/15 bg-canvas px-3 font-mono"></td>
                  <td class="px-5 py-4 font-mono">{{ account.usage.platformTokensCharged.toLocaleString() }}</td><td class="px-5 py-4 font-mono">{{ account.usage.personalTokensUsed.toLocaleString() }}</td><td class="px-5 py-4 font-mono">{{ account.usage.platformTokensRemaining.toLocaleString() }}</td>
                  <td class="px-5 py-4"><button type="button" :disabled="Boolean(saving)" class="min-h-11 rounded-lg border border-ink/15 px-4 font-semibold disabled:opacity-40" @click="saveQuota(account)">{{ copy.save }}</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </section>
  </AppShell>
</template>
