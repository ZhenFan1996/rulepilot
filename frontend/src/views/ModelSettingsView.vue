<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import DestructiveActionDialog from '@/components/DestructiveActionDialog.vue'
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
  recommendation: string
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
  managedStartupAccess: boolean
}

interface ProviderDraft {
  apiKey: string
  baseUrl: string
  model: string
  visionCapable: boolean
}

const { locale, t } = useLocale()
const router = useRouter()
const snapshot = ref<ConfigurationSnapshot | null>(null)
const selectedProvider = ref('gemini')
const providerDrafts = ref<Record<string, ProviderDraft>>({})
const assignmentsDraft = ref<Assignments>({
  recommendation: 'fake', teaching: 'fake', visual: 'fake', answer: 'fake', critic: 'fake',
})
const loading = ref(true)
const savingProvider = ref(false)
const savingAssignments = ref(false)
const message = ref('')
const errorMessage = ref('')
const providerToDisable = ref('')
const disableError = ref('')
const pageHeading = ref<HTMLElement | null>(null)
const restoreAfterDisable = ref(false)
const navigationDialogOpen = ref(false)
let resolvePendingNavigation: ((allow: boolean) => void) | null = null

const assignmentRoles = ['recommendation', 'teaching', 'visual', 'answer', 'critic'] as const

const disableCopy = computed(() => locale.value === 'zh-CN' ? {
  title: (providerName: string) => `停用 ${providerName}？`,
  description: (hasDraft: boolean) => `保存的 API Key 会从当前后端进程中移除，使用此连接的功能会切换到内置演示。密钥不会再次显示；如需恢复，必须重新输入。${hasDraft ? '此连接尚未保存的页面草稿也会被清除。' : ''}`,
  cancel: '保留连接', confirm: '停用连接', pending: '正在停用…', retry: '重新尝试停用',
} : {
  title: (providerName: string) => `Disable ${providerName}?`,
  description: (hasDraft: boolean) => `The saved API key will be removed from this backend process, and features using this connection will switch to the built-in demo. The key cannot be shown again; reconnecting requires entering it again.${hasDraft ? ' The unsaved page draft for this connection will also be cleared.' : ''}`,
  cancel: 'Keep connection', confirm: 'Disable connection', pending: 'Disabling…', retry: 'Try disabling again',
})

const draftCopy = computed(() => locale.value === 'zh-CN' ? {
  unsaved: '未保存',
  status: (areas: string) => `尚未保存：${areas}`,
  memoryOnly: '草稿只保留在当前页面，不会把 API Key 写入浏览器存储。',
  connectionArea: (providerName: string) => `${providerName} 连接`,
  assignmentsArea: '模型用途',
  keyRequired: '保存已连接服务的新设置时，请重新输入 API Key；后端不会把原密钥返回页面。',
  leaveTitle: '放弃未保存的设置并离开？',
  leaveDescription: (areas: string) => `${areas}仍未保存。离开后，页面内存中的 API Key 和其他草稿会被清除，无法恢复。`,
  savingTitle: '正在完成保存',
  savingDescription: '保存请求完成前暂时不能离开。成功且没有其他草稿时会继续前往刚才选择的页面。',
  stay: '继续编辑',
  leave: '放弃更改并离开',
  saving: '正在保存…',
} : {
  unsaved: 'Unsaved',
  status: (areas: string) => `Not saved yet: ${areas}`,
  memoryOnly: 'Drafts stay only on this page; API keys are never written to browser storage.',
  connectionArea: (providerName: string) => `${providerName} connection`,
  assignmentsArea: 'model roles',
  keyRequired: 'To save new settings for a connected service, enter its API key again; the backend never returns the saved key to this page.',
  leaveTitle: 'Discard unsaved settings and leave?',
  leaveDescription: (areas: string) => `${areas} are not saved. Leaving clears the API key and other drafts from page memory, and they cannot be recovered.`,
  savingTitle: 'Finishing your save',
  savingDescription: 'You cannot leave until the save request finishes. If it succeeds and no other drafts remain, the page you chose will open automatically.',
  stay: 'Keep editing',
  leave: 'Discard changes and leave',
  saving: 'Saving…',
})

function providerLabel(id: string) {
  return id === 'compatible' ? t('models.provider.compatible') : ({ gemini: 'Gemini', openai: 'OpenAI', deepseek: 'DeepSeek', qwen: 'Qwen' }[id] ?? id)
}

const provider = computed(() => snapshot.value?.providers.find((entry) => entry.id === selectedProvider.value))
const currentDraft = computed<ProviderDraft>(() => providerDrafts.value[selectedProvider.value] ?? {
  apiKey: '', baseUrl: '', model: '', visionCapable: false,
})
const configuredProviders = computed(() => snapshot.value?.providers.filter((entry) => entry.configured) ?? [])
const configuredVisualProviders = computed(() => configuredProviders.value.filter((entry) => entry.visionCapable))
const needsBaseUrl = computed(() => selectedProvider.value !== 'gemini')
const visualProvider = computed(() => snapshot.value?.providers.find(
  (entry) => entry.id === assignmentsDraft.value.visual,
))
const qwenSelected = computed(() => selectedProvider.value === 'qwen')
const hasPendingMutation = computed(() => savingProvider.value || savingAssignments.value)
const roleDefinitions = computed(() => [
  ['recommendation', t('models.role.recommendation')],
  ['teaching', t('models.role.teaching')],
  ['visual', t('models.role.visual')],
  ['answer', t('models.role.answer')],
  ['critic', t('models.role.critic')],
] as const)

function providerDraftChanged(draft: ProviderDraft | undefined, baseline: ProviderView | undefined) {
  if (!draft || !baseline) return false
  return (
    draft.apiKey.length > 0
    || draft.baseUrl !== baseline.baseUrl
    || draft.model !== baseline.model
    || draft.visionCapable !== baseline.visionCapable
  )
}

function providerDraftDirty(id: string) {
  return providerDraftChanged(
    providerDrafts.value[id],
    snapshot.value?.providers.find(entry => entry.id === id),
  )
}

const dirtyProviderIds = computed(() => snapshot.value?.providers
  .filter(entry => providerDraftDirty(entry.id))
  .map(entry => entry.id) ?? [])
const assignmentsDirty = computed(() => Boolean(snapshot.value) && assignmentRoles.some(
  role => assignmentsDraft.value[role] !== snapshot.value!.assignments[role],
))
const hasUnsavedChanges = computed(() => dirtyProviderIds.value.length > 0 || assignmentsDirty.value)
const protectsNavigation = computed(() => hasUnsavedChanges.value || hasPendingMutation.value)
const unsavedAreaLabels = computed(() => [
  ...dirtyProviderIds.value.map(id => draftCopy.value.connectionArea(providerLabel(id))),
  ...(assignmentsDirty.value ? [draftCopy.value.assignmentsArea] : []),
])
const unsavedAreaSummary = computed(() => unsavedAreaLabels.value.join(locale.value === 'zh-CN' ? '、' : ', '))
const navigationCopy = computed(() => hasPendingMutation.value ? {
  title: draftCopy.value.savingTitle,
  description: draftCopy.value.savingDescription,
} : {
  title: draftCopy.value.leaveTitle,
  description: draftCopy.value.leaveDescription(unsavedAreaSummary.value),
})

function freshProviderDraft(providerView: ProviderView): ProviderDraft {
  return {
    apiKey: '',
    baseUrl: providerView.baseUrl,
    model: providerView.model,
    visionCapable: providerView.visionCapable,
  }
}

function cloneAssignments(assignments: Assignments): Assignments {
  return { ...assignments }
}

function initializeDrafts(next: ConfigurationSnapshot) {
  snapshot.value = next
  providerDrafts.value = Object.fromEntries(next.providers.map(entry => [entry.id, freshProviderDraft(entry)]))
  assignmentsDraft.value = cloneAssignments(next.assignments)
  if (!next.providers.some(entry => entry.id === selectedProvider.value)) {
    selectedProvider.value = next.providers[0]?.id ?? 'gemini'
  }
}

function assignmentIsSelectable(role: keyof Assignments, providerId: string, next: ConfigurationSnapshot) {
  if (providerId === 'fake') return true
  const selected = next.providers.find(entry => entry.id === providerId)
  return Boolean(selected?.configured && (role !== 'visual' || selected.visionCapable))
}

function reconcileSnapshot(next: ConfigurationSnapshot, resetProviderId?: string, resetAssignments = false) {
  const previousSnapshot = snapshot.value
  const previousProviderDrafts = providerDrafts.value
  const previousAssignments = assignmentsDraft.value
  const reconciledProviderDrafts: Record<string, ProviderDraft> = {}

  for (const nextProvider of next.providers) {
    const previousBaseline = previousSnapshot?.providers.find(entry => entry.id === nextProvider.id)
    const previousDraft = previousProviderDrafts[nextProvider.id]
    const preserveDraft = nextProvider.id !== resetProviderId
      && providerDraftChanged(previousDraft, previousBaseline)
    reconciledProviderDrafts[nextProvider.id] = preserveDraft
      ? { ...previousDraft! }
      : freshProviderDraft(nextProvider)
  }

  const reconciledAssignments = cloneAssignments(next.assignments)
  if (!resetAssignments && previousSnapshot) {
    for (const role of assignmentRoles) {
      const wasEdited = previousAssignments[role] !== previousSnapshot.assignments[role]
      if (wasEdited && assignmentIsSelectable(role, previousAssignments[role], next)) {
        reconciledAssignments[role] = previousAssignments[role]
      }
    }
  }

  snapshot.value = next
  providerDrafts.value = reconciledProviderDrafts
  assignmentsDraft.value = reconciledAssignments
}

function selectProvider(id: string) {
  if (hasPendingMutation.value) return
  selectedProvider.value = id
  message.value = ''
  errorMessage.value = ''
}

function updateProviderDraft(id: string, key: keyof ProviderDraft, value: string | boolean) {
  const current = providerDrafts.value[id]
  if (!current) return
  providerDrafts.value = {
    ...providerDrafts.value,
    [id]: { ...current, [key]: value },
  }
}

function setCurrentDraft<Key extends keyof ProviderDraft>(key: Key, value: ProviderDraft[Key]) {
  updateProviderDraft(selectedProvider.value, key, value)
}

async function loadConfiguration() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await checkedResponse(await fetch('/api/v1/model-configuration', { credentials: 'include' }))
    initializeDrafts((await response.json()) as ConfigurationSnapshot)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    loading.value = false
  }
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
  return (await response.json()) as ConfigurationSnapshot
}

async function saveProvider() {
  if (hasPendingMutation.value || !providerDraftDirty(selectedProvider.value)) return
  const target = selectedProvider.value
  const submitted = { ...currentDraft.value }
  savingProvider.value = true
  let saved = false
  message.value = ''
  errorMessage.value = ''
  try {
    const next = await mutate(`/api/v1/model-configuration/providers/${target}`, 'PUT', {
      apiKey: submitted.apiKey,
      baseUrl: target === 'gemini' ? '' : submitted.baseUrl,
      model: submitted.model,
      visionCapable: submitted.visionCapable,
    })
    reconcileSnapshot(next, target)
    message.value = t('models.connected', { provider: providerLabel(target) })
    saved = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingProvider.value = false
    settlePendingNavigation(saved)
  }
}

function requestDisableProvider() {
  if (hasPendingMutation.value || !provider.value?.configured) return
  providerToDisable.value = selectedProvider.value
  disableError.value = ''
  restoreAfterDisable.value = false
}

function cancelDisableProvider() {
  if (savingProvider.value) return
  providerToDisable.value = ''
  disableError.value = ''
  restoreAfterDisable.value = false
}

function disableRestoreTarget() {
  if (!restoreAfterDisable.value) return null
  restoreAfterDisable.value = false
  return pageHeading.value
}

async function confirmDisableProvider() {
  const target = providerToDisable.value
  if (hasPendingMutation.value || !target) return
  savingProvider.value = true
  let disabled = false
  message.value = ''
  errorMessage.value = ''
  disableError.value = ''
  try {
    const next = await mutate(`/api/v1/model-configuration/providers/${target}`, 'DELETE')
    reconcileSnapshot(next, target)
    message.value = t('models.disabled', { provider: providerLabel(target) })
    restoreAfterDisable.value = true
    providerToDisable.value = ''
    disabled = true
  } catch (error) {
    disableError.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingProvider.value = false
    settlePendingNavigation(disabled)
  }
}

async function saveAssignments() {
  if (!snapshot.value || hasPendingMutation.value || !assignmentsDirty.value) return
  const submitted = cloneAssignments(assignmentsDraft.value)
  savingAssignments.value = true
  let saved = false
  message.value = ''
  errorMessage.value = ''
  try {
    const next = await mutate('/api/v1/model-configuration/assignments', 'PUT', submitted)
    reconcileSnapshot(next, undefined, true)
    message.value = t('models.assignmentsSaved')
    saved = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('models.error')
  } finally {
    savingAssignments.value = false
    settlePendingNavigation(saved)
  }
}

function clearSensitiveDrafts() {
  for (const [id, draft] of Object.entries(providerDrafts.value)) {
    if (draft.apiKey) updateProviderDraft(id, 'apiKey', '')
  }
}

function discardAllDrafts() {
  if (snapshot.value) initializeDrafts(snapshot.value)
  else clearSensitiveDrafts()
}

function takePendingNavigationResolution() {
  const resolution = resolvePendingNavigation
  resolvePendingNavigation = null
  return resolution
}

function cancelPendingNavigation() {
  if (hasPendingMutation.value) return
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(false)
}

function discardDraftsAndLeave() {
  if (hasPendingMutation.value) return
  discardAllDrafts()
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(true)
}

function settlePendingNavigation(mutationSucceeded: boolean) {
  if (!navigationDialogOpen.value || hasPendingMutation.value) return
  if (!mutationSucceeded) {
    cancelPendingNavigation()
    return
  }
  if (hasUnsavedChanges.value) return
  navigationDialogOpen.value = false
  takePendingNavigationResolution()?.(true)
}

function protectBrowserUnload(event: BeforeUnloadEvent) {
  if (!protectsNavigation.value) return
  event.preventDefault()
  event.returnValue = ''
}

const removeNavigationGuard = router.beforeEach((to, from) => {
  if (to.path === from.path || !protectsNavigation.value) return true
  if (navigationDialogOpen.value) takePendingNavigationResolution()?.(false)
  navigationDialogOpen.value = true
  return new Promise<boolean>((resolve) => {
    resolvePendingNavigation = resolve
  })
})

onMounted(() => {
  window.addEventListener('beforeunload', protectBrowserUnload)
  void loadConfiguration()
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', protectBrowserUnload)
  removeNavigationGuard()
  clearSensitiveDrafts()
  takePendingNavigationResolution()?.(false)
})
</script>

<template>
  <AppShell>
    <div class="tabletop-page max-w-6xl">
      <div class="max-w-3xl">
        <p class="text-sm font-medium text-copper">{{ t('models.eyebrow') }}</p>
        <h1 ref="pageHeading" tabindex="-1" class="mt-3 font-display text-4xl font-semibold tracking-tight outline-none">{{ t('models.title') }}</h1>
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
        <div v-if="snapshot.managedStartupAccess" class="mt-4 rounded-lg bg-indigo/5 px-4 py-3 text-sm leading-6 text-ink/65" role="status">
          {{ t('models.managedStartupAccess') }}
        </div>

        <p v-if="message" class="mt-5 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <div v-if="hasUnsavedChanges" data-testid="model-settings-unsaved" class="mt-5 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
          <strong>{{ draftCopy.status(unsavedAreaSummary) }}</strong>
          <span class="mt-0.5 block">{{ draftCopy.memoryOnly }}</span>
        </div>

        <div class="mt-8 grid gap-8 lg:grid-cols-[1.15fr_0.85fr]">
          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <div class="flex flex-wrap gap-2" role="tablist" :aria-label="t('models.providers')">
              <button
                v-for="item in snapshot.providers"
                :key="item.id"
                type="button"
                role="tab"
                :aria-selected="selectedProvider === item.id"
                :disabled="hasPendingMutation"
                class="rounded-lg border px-4 py-2 text-sm font-semibold transition-colors"
                :class="selectedProvider === item.id ? 'border-ink bg-ink text-canvas' : 'border-ink/10 bg-canvas text-ink/60 hover:border-ink/30'"
                @click="selectProvider(item.id)"
              >
                {{ providerLabel(item.id) }}
                <span class="ml-1" :class="item.configured ? 'text-emerald-500' : 'text-ink/30'" aria-hidden="true">●</span>
                <span v-if="providerDraftDirty(item.id)" class="ml-1 text-[0.6875rem]" data-testid="provider-unsaved">{{ draftCopy.unsaved }}</span>
              </button>
            </div>

            <form class="mt-7 stack-y-xl" @submit.prevent="saveProvider">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <h2 class="font-display text-2xl font-semibold">{{ providerLabel(selectedProvider) }}</h2>
                  <p class="mt-1 text-sm text-ink/50">{{ provider?.configured ? t('models.connectedHint') : t('models.disconnectedHint') }}</p>
                </div>
                <div class="flex flex-wrap justify-end gap-2">
                  <span v-if="providerDraftDirty(selectedProvider)" class="rounded-md bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-900">{{ draftCopy.unsaved }}</span>
                  <span class="rounded-md px-2.5 py-1 text-xs font-semibold" :class="provider?.configured ? 'bg-emerald-100 text-emerald-800' : 'bg-ink/5 text-ink/45'">
                    {{ provider?.configured ? t('models.configured') : t('models.notConfigured') }}
                  </span>
                </div>
              </div>

              <p v-if="qwenSelected" class="rounded-lg bg-indigo/5 px-4 py-3 text-sm leading-6 text-ink/65">
                {{ t('models.qwenHint') }}
              </p>

              <label class="block text-sm font-semibold">
                API Key
                <input :value="currentDraft.apiKey" :disabled="hasPendingMutation" required type="password" autocomplete="new-password" maxlength="4096" :placeholder="t('models.keyHint')" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo disabled:opacity-50" @input="setCurrentDraft('apiKey', ($event.target as HTMLInputElement).value)">
                <span v-if="provider?.configured && providerDraftDirty(selectedProvider) && !currentDraft.apiKey" class="mt-1.5 block font-normal leading-5 text-amber-800">{{ draftCopy.keyRequired }}</span>
              </label>

              <label v-if="needsBaseUrl" class="block text-sm font-semibold">
                API Base URL
                <input :value="currentDraft.baseUrl" :disabled="hasPendingMutation" required type="url" maxlength="500" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo disabled:opacity-50" @input="setCurrentDraft('baseUrl', ($event.target as HTMLInputElement).value)">
              </label>

              <label class="block text-sm font-semibold">
                {{ t('models.name') }}
                <input :value="currentDraft.model" :disabled="hasPendingMutation" required maxlength="200" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-mono text-sm outline-none focus:border-indigo disabled:opacity-50" @input="setCurrentDraft('model', ($event.target as HTMLInputElement).value)">
              </label>

              <label class="flex min-h-11 items-start gap-3 rounded-lg border border-ink/10 bg-canvas px-4 py-3 text-sm">
                <input :checked="currentDraft.visionCapable" :disabled="hasPendingMutation" type="checkbox" class="mt-1 size-4 accent-indigo disabled:opacity-50" @change="setCurrentDraft('visionCapable', ($event.target as HTMLInputElement).checked)">
                <span><strong class="block">{{ t('models.vision.title') }}</strong><span class="mt-1 block font-normal leading-5 text-ink/45">{{ t('models.vision.description') }}</span></span>
              </label>

              <div class="flex flex-col gap-3 sm:flex-row">
                <button :disabled="hasPendingMutation || !providerDraftDirty(selectedProvider)" class="min-h-11 flex-1 rounded-lg bg-indigo px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingProvider ? t('models.saving') : t('models.saveConnection') }}</button>
                <button v-if="provider?.configured" type="button" :disabled="hasPendingMutation" class="min-h-11 rounded-lg border border-red-200 px-5 py-3 font-semibold text-red-700 disabled:opacity-50" @click="requestDisableProvider">{{ t('models.disable') }}</button>
              </div>
            </form>
          </section>

          <section class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-7">
            <p class="text-xs font-medium text-ink/40">{{ t('models.uses') }}</p>
            <div class="mt-2 flex items-start justify-between gap-3">
              <h2 class="font-display text-2xl font-semibold">{{ t('models.assignmentTitle') }}</h2>
              <span v-if="assignmentsDirty" class="rounded-md bg-amber-100 px-2.5 py-1 text-xs font-semibold text-amber-900">{{ draftCopy.unsaved }}</span>
            </div>
            <p class="mt-3 text-sm leading-6 text-ink/55">{{ t('models.assignmentDescription') }}</p>
            <p v-if="!visualProvider" class="mt-4 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900" role="status">
              {{ t('models.noVisual') }}
            </p>

            <form class="mt-7 stack-y-xl" @submit.prevent="saveAssignments">
              <label v-for="role in roleDefinitions" :key="role[0]" class="block text-sm font-semibold">
                {{ role[1] }}
                <select v-model="assignmentsDraft[role[0]]" :disabled="hasPendingMutation" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 text-ink outline-none focus:border-copper disabled:opacity-50">
                  <option value="fake">{{ role[0] === 'visual' ? t('models.noVisualOption') : t('models.fakeOption') }}</option>
                  <option v-for="item in role[0] === 'visual' ? configuredVisualProviders : configuredProviders" :key="item.id" :value="item.id">{{ providerLabel(item.id) }}</option>
                </select>
                <span v-if="role[0] === 'visual'" class="mt-1.5 block font-normal leading-5 text-ink/40">{{ t('models.visualRoleHint') }}</span>
              </label>

              <button :disabled="hasPendingMutation || !assignmentsDirty" class="min-h-11 w-full rounded-lg bg-copper px-5 py-3 font-semibold text-white disabled:opacity-50">{{ savingAssignments ? t('models.applying') : t('models.saveUses') }}</button>
            </form>

            <p class="mt-5 text-xs text-ink/35">{{ t('models.revision', { revision: snapshot.revision }) }}</p>
          </section>
        </div>
      </template>
    </div>

    <DestructiveActionDialog
      :open="Boolean(providerToDisable)"
      :pending="savingProvider"
      :error="disableError"
      :title="disableCopy.title(providerLabel(providerToDisable))"
      :description="disableCopy.description(providerDraftDirty(providerToDisable))"
      :cancel-label="disableCopy.cancel"
      :confirm-label="disableCopy.confirm"
      :pending-label="disableCopy.pending"
      :retry-label="disableCopy.retry"
      :restore-focus="disableRestoreTarget"
      @cancel="cancelDisableProvider"
      @confirm="confirmDisableProvider"
    />
    <DestructiveActionDialog
      :open="navigationDialogOpen"
      :pending="hasPendingMutation"
      :title="navigationCopy.title"
      :description="navigationCopy.description"
      :cancel-label="draftCopy.stay"
      :confirm-label="draftCopy.leave"
      :pending-label="draftCopy.saving"
      @cancel="cancelPendingNavigation"
      @confirm="discardDraftsAndLeave"
    />
  </AppShell>
</template>
