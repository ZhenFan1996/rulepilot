<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import {
  forgetPendingRulebookLesson,
  readPendingRulebookLessons,
  rememberPendingRulebookLesson,
  type PendingRulebookLesson,
} from '@/lib/pendingRulebookLesson'
import { useLocale } from '@/lib/locale'

interface CsrfResponse { headerName: string; token: string }
interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
}
interface DocumentResponse {
  document: { id: string; gameEditionId: string | null; title: string; officialSourceUrl: string | null; officialCoverUrl: string | null }
  latestVersion: { id: string; originalFilename: string; size: number; status: string }
}
interface TeachingPlanResponse { id: string }
interface TeachingPreparationLaunch { assistantRunId: string; state: string; reused: boolean }
interface TeachingPreparationRun {
  run: { id: string; state: string; lastErrorCode: string | null }
  activities?: Array<{
    sequence: number
    operation: string
    outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  }>
}
interface ProcessingSnapshot { stage: string; percentage: number; processedPages: number; complete: boolean }
interface ModelConfigurationResponse {
  providers: Array<{ id: string; configured: boolean; visionCapable: boolean }>
  assignments: { teaching: string; visual: string }
}

class PreparationFailedError extends Error {}

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const username = ref('')
const games = ref<GameResponse[]>([])
const editionId = ref('')
const documents = ref<DocumentResponse[]>([])
const file = ref<File | null>(null)
const title = ref('')
const officialSourceUrl = ref('')
const sourceType = ref('BASE_RULEBOOK')
const playerCount = ref(4)
const beginnerCount = ref(4)
const durationMinutes = ref(25)
const loading = ref(true)
const uploading = ref(false)
const deletingDocumentId = ref('')
const preparingVersionId = ref('')
const preparationElapsedSeconds = ref(0)
const processingVersionId = ref('')
const message = ref('')
const errorMessage = ref('')
const progress = ref<Record<string, { stage: string; percentage: number; processedPages: number }>>({})
const modelConfiguration = ref<ModelConfigurationResponse | null>(null)
const progressConnections = new Map<string, EventSource>()
const progressRetryTimers = new Map<string, ReturnType<typeof setTimeout>>()
const progressRetryAttempts = new Map<string, number>()
let disposed = false
let preparationClock: ReturnType<typeof setInterval> | null = null

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name}${edition.language ? ` · ${edition.language}` : ''}`,
}))))
const canUpload = computed(() => Boolean(file.value && !uploading.value && !preparingVersionId.value))
const visualProvider = computed(() => modelConfiguration.value?.providers.find(
  (provider) => provider.id === modelConfiguration.value?.assignments.visual,
))
const visualVisionCapable = computed(() => visualProvider.value?.visionCapable === true)

function documentStatusLabel(status: string) {
  return {
    UPLOADED: t('documents.status.uploaded'),
    EXTRACTING: t('documents.status.extracting'),
    READY: t('documents.status.ready'),
    FAILED: t('documents.status.failed'),
  }[status] ?? t('documents.status.processing')
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error(t('documents.login'))
  }
  return response
}

async function csrfToken() {
  const response = await checkedFetch('/api/auth/csrf')
  if (!response.ok) throw new Error(t('documents.error'))
  return await response.json() as CsrfResponse
}

async function loadDocuments() {
  const response = await checkedFetch('/api/v1/documents')
  if (!response.ok) throw new Error(t('documents.error'))
  documents.value = await response.json() as DocumentResponse[]
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [sessionResponse, catalogResponse, modelResponse] = await Promise.all([
      checkedFetch('/api/auth/session'),
      checkedFetch('/api/v1/games'),
      checkedFetch('/api/v1/model-configuration'),
    ])
    if (!sessionResponse.ok) throw new Error(t('documents.error'))
    if (!catalogResponse.ok) throw new Error(t('documents.error'))
    username.value = ((await sessionResponse.json()) as { username: string }).username
    games.value = await catalogResponse.json() as GameResponse[]
    if (modelResponse.ok) modelConfiguration.value = await modelResponse.json() as ModelConfigurationResponse
    const requestedEdition = typeof route.query.editionId === 'string' ? route.query.editionId : ''
    editionId.value = editionOptions.value.some((item) => item.id === requestedEdition) ? requestedEdition : ''
    await loadDocuments()
    await recoverPendingHandoff()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    loading.value = false
  }
}

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  message.value = ''
  errorMessage.value = ''
}

function titleFromFile(selected: File) {
  return selected.name.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim() || t('documents.titleFallback')
}

function currentPreferences(versionId: string): PendingRulebookLesson {
  return {
    versionId,
    playerCount: playerCount.value,
    beginnerCount: beginnerCount.value,
    durationMinutes: durationMinutes.value,
  }
}

async function startLesson(versionId: string, preferences = currentPreferences(versionId)) {
  if (preferences.beginnerCount > preferences.playerCount) throw new Error(t('documents.error'))
  beginPreparation(versionId, 'RECEIVED')
  try {
    const csrf = await csrfToken()
    const planResponse = await checkedFetch(`/api/v1/document-versions/${versionId}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        playerCount: preferences.playerCount,
        beginnerCount: preferences.beginnerCount,
        durationMinutes: preferences.durationMinutes,
      }),
    })
    if (!planResponse.ok) throw new Error(t('documents.error'))
    const launch = await planResponse.json() as TeachingPreparationLaunch
    await waitForTeachingPreparation(launch.assistantRunId, preferences, csrf)
  } finally {
    if (preparingVersionId.value === versionId) endPreparation()
  }
}

function beginPreparation(versionId: string, state: string) {
  preparingVersionId.value = versionId
  preparationElapsedSeconds.value = 0
  updatePreparationMessage(state)
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = setInterval(() => preparationElapsedSeconds.value += 1, 1000)
}

function endPreparation() {
  preparingVersionId.value = ''
  preparationElapsedSeconds.value = 0
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = null
}

function updatePreparationMessage(state: string, activities: TeachingPreparationRun['activities'] = []) {
  const active = [...activities].reverse().find((activity) => activity.outcome === 'RUNNING')
    ?? activities.at(-1)
  if (active?.operation.startsWith('inspectRulebookVisualBatch')) {
    const batch = active.operation.split('|')[1]
    message.value = batch
      ? t('documents.prepare.visualBatch', { batch })
      : t('documents.prepare.visual')
    return
  }
  if (active?.operation.startsWith('organizeTeachingOutline')) {
    message.value = t('documents.prepare.outline')
    return
  }
  message.value = {
    RECEIVED: t('documents.prepare.received'),
    DOCUMENT_READINESS: t('documents.prepare.readiness'),
    LESSON_PLANNING: t('documents.prepare.planning'),
    COMPLETED: t('documents.prepare.completed'),
  }[state] ?? t('documents.prepare.default')
}

function preparationElapsedLabel() {
  const seconds = preparationElapsedSeconds.value
  if (seconds < 60) return t('documents.elapsed.seconds', { seconds })
  const minutes = Math.floor(seconds / 60)
  return t('documents.elapsed.minutes', { minutes, seconds: String(seconds % 60).padStart(2, '0') })
}

async function waitForTeachingPreparation(
  runId: string,
  preferences: PendingRulebookLesson,
  csrf: CsrfResponse,
  initial?: TeachingPreparationRun,
) {
  let snapshot = initial
  while (!disposed && preparingVersionId.value === preferences.versionId) {
    try {
      if (!snapshot) {
        const response = await checkedFetch(`/api/v1/assistant-runs/${runId}`)
        if (!response.ok) throw new Error(t('documents.error'))
        snapshot = await response.json() as TeachingPreparationRun
      }
      updatePreparationMessage(snapshot.run.state, snapshot.activities)
      if (snapshot.run.state === 'COMPLETED') {
        await openPreparedLesson(preferences, csrf)
        return
      }
      if (snapshot.run.state === 'FAILED' || snapshot.run.state === 'DEGRADED') {
        throw new PreparationFailedError(t('documents.error'))
      }
    } catch (error) {
      if (error instanceof PreparationFailedError) throw error
      message.value = t('documents.prepare.reconnect')
    }
    snapshot = undefined
    await new Promise((resolve) => setTimeout(resolve, 1200))
  }
}

async function openPreparedLesson(preferences: PendingRulebookLesson, csrf: CsrfResponse) {
  const latestResponse = await checkedFetch(
    `/api/v1/document-versions/${preferences.versionId}/teaching-plans/latest`,
  )
  if (!latestResponse.ok) throw new Error(t('documents.prepare.openLater'))
  const plan = await latestResponse.json() as TeachingPlanResponse
  message.value = t('documents.prepare.started')
  const lessonResponse = await checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons`, {
    method: 'POST', headers: { [csrf.headerName]: csrf.token },
  })
  if (!lessonResponse.ok) throw new Error(t('documents.error'))
  if (username.value) forgetPendingRulebookLesson(localStorage, username.value, preferences.versionId)
  localStorage.setItem('rulepilot:last-plan-id', plan.id)
  await router.push({ name: 'lessons', query: { started: plan.id } })
}

async function resumeOrStartLesson(pending: PendingRulebookLesson) {
  let snapshot: TeachingPreparationRun | null = null
  try {
    const response = await checkedFetch(
      `/api/v1/assistant-runs/latest?mode=TEACHING_PREPARATION&subjectId=${pending.versionId}`,
    )
    if (response.ok) snapshot = await response.json() as TeachingPreparationRun
  } catch {
    // A missing run is safe to recover through the idempotent launch endpoint.
  }
  if (snapshot && (snapshot.run.state === 'FAILED' || snapshot.run.state === 'DEGRADED')) {
    if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
    throw new Error(t('documents.error'))
  }
  if (snapshot) {
    beginPreparation(pending.versionId, snapshot.run.state)
    const csrf = await csrfToken()
    try {
      await waitForTeachingPreparation(snapshot.run.id, pending, csrf, snapshot)
    } finally {
      if (preparingVersionId.value === pending.versionId) endPreparation()
    }
    return
  }
  await startLesson(pending.versionId, pending)
}

function closeProgressConnection(versionId: string) {
  progressConnections.get(versionId)?.close()
  progressConnections.delete(versionId)
  const timer = progressRetryTimers.get(versionId)
  if (timer) clearTimeout(timer)
  progressRetryTimers.delete(versionId)
}

function watchProgress(pending: PendingRulebookLesson) {
  const versionId = pending.versionId
  closeProgressConnection(versionId)
  if (disposed) return
  processingVersionId.value = versionId
  const events = new EventSource(`/api/v1/document-versions/${versionId}/progress`, { withCredentials: true })
  progressConnections.set(versionId, events)
  events.addEventListener('progress', (event) => {
    const snapshot = parseProgressSnapshot((event as MessageEvent<string>).data)
    if (!snapshot) {
      events.close()
      progressConnections.delete(versionId)
      void reconcileProgressAfterDisconnect(pending)
      return
    }
    progressRetryAttempts.set(versionId, 0)
    progress.value = { ...progress.value, [versionId]: snapshot }
    message.value = t('documents.progress.reading', { percentage: snapshot.percentage })
    if (snapshot.complete) {
      void handleTerminalProgress(pending, snapshot.stage)
    }
  })
  events.onerror = () => {
    events.close()
    progressConnections.delete(versionId)
    if (!disposed) void reconcileProgressAfterDisconnect(pending)
  }
}

async function handleTerminalProgress(pending: PendingRulebookLesson, stage: string) {
  closeProgressConnection(pending.versionId)
  progressRetryAttempts.delete(pending.versionId)
  processingVersionId.value = ''
  await loadDocuments().catch(() => undefined)
  if (stage === 'READY') {
    await resumeOrStartLesson(pending).catch((error: unknown) => {
      errorMessage.value = error instanceof Error ? error.message : t('documents.error')
    })
    return
  }
  if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
  errorMessage.value = t('documents.progress.failed')
}

async function reconcileProgressAfterDisconnect(pending: PendingRulebookLesson) {
  try {
    await loadDocuments()
    const status = documents.value.find((entry) => entry.latestVersion.id === pending.versionId)?.latestVersion.status
    if (status === 'READY' || status === 'FAILED') {
      await handleTerminalProgress(pending, status)
      return
    }
    if (!status) {
      if (username.value) forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
      processingVersionId.value = ''
      errorMessage.value = t('documents.progress.missing')
      return
    }
    message.value = t('documents.progress.reconnect')
  } catch {
    message.value = t('documents.progress.reconnect')
  }
  scheduleProgressReconnect(pending)
}

function scheduleProgressReconnect(pending: PendingRulebookLesson) {
  if (disposed || progressRetryTimers.has(pending.versionId)) return
  const attempt = Math.min((progressRetryAttempts.get(pending.versionId) ?? 0) + 1, 4)
  progressRetryAttempts.set(pending.versionId, attempt)
  const delay = [1000, 2000, 5000, 10000][attempt - 1]!
  progressRetryTimers.set(pending.versionId, setTimeout(() => {
    progressRetryTimers.delete(pending.versionId)
    watchProgress(pending)
  }, delay))
}

function parseProgressSnapshot(value: string): ProcessingSnapshot | null {
  try {
    const snapshot = JSON.parse(value) as Partial<ProcessingSnapshot>
    return typeof snapshot.stage === 'string'
      && snapshot.stage.length > 0
      && typeof snapshot.percentage === 'number'
      && snapshot.percentage >= 0
      && snapshot.percentage <= 100
      && typeof snapshot.processedPages === 'number'
      && snapshot.processedPages >= 0
      && typeof snapshot.complete === 'boolean'
      ? snapshot as ProcessingSnapshot
      : null
  } catch {
    return null
  }
}

async function recoverPendingHandoff() {
  if (!username.value || preparingVersionId.value) return
  for (const pending of readPendingRulebookLessons(localStorage, username.value)) {
    const entry = documents.value.find((candidate) => candidate.latestVersion.id === pending.versionId)
    if (!entry) {
      forgetPendingRulebookLesson(localStorage, username.value, pending.versionId)
      continue
    }
    if (entry.latestVersion.status === 'READY' || entry.latestVersion.status === 'FAILED') {
      await handleTerminalProgress(pending, entry.latestVersion.status)
      return
    }
    watchProgress(pending)
    return
  }
}

async function uploadRulebook() {
  if (!file.value) return
  uploading.value = true
  message.value = t('documents.uploading')
  errorMessage.value = ''
  try {
    const selectedFile = file.value
    const csrf = await csrfToken()
    const form = new FormData()
    form.append('title', title.value.trim() || titleFromFile(selectedFile))
    form.append('sourceType', sourceType.value)
    if (officialSourceUrl.value.trim()) form.append('officialSourceUrl', officialSourceUrl.value.trim())
    form.append('file', selectedFile)
    const path = editionId.value
      ? `/api/v1/editions/${editionId.value}/documents`
      : '/api/v1/documents'
    const response = await checkedFetch(path, {
      method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form,
    })
    if (!response.ok) throw new Error(t('documents.error'))
    const result = await response.json() as { duplicate: boolean; version: { id: string; status: string } }
    const pending = currentPreferences(result.version.id)
    if (username.value) rememberPendingRulebookLesson(localStorage, username.value, pending)
    file.value = null
    title.value = ''
    officialSourceUrl.value = ''
    await loadDocuments()
    if (result.version.status === 'READY') {
      await startLesson(result.version.id, pending)
    } else if (result.version.status === 'FAILED') {
      await handleTerminalProgress(pending, 'FAILED')
    } else {
      message.value = result.duplicate ? t('documents.uploadedExisting') : t('documents.uploadedReading')
      watchProgress(pending)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    uploading.value = false
  }
}

async function deleteRulebook(entry: DocumentResponse) {
  if (deletingDocumentId.value || preparingVersionId.value) return
  const confirmed = window.confirm(t('documents.delete.confirm', { title: entry.document.title }))
  if (!confirmed) return
  deletingDocumentId.value = entry.document.id
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await checkedFetch(`/api/v1/documents/${encodeURIComponent(entry.document.id)}`, {
      method: 'DELETE', headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error(t('documents.error'))
    if (username.value) forgetPendingRulebookLesson(localStorage, username.value, entry.latestVersion.id)
    if (processingVersionId.value === entry.latestVersion.id) {
      closeProgressConnection(entry.latestVersion.id)
      processingVersionId.value = ''
    }
    await loadDocuments()
    message.value = t('documents.deleted')
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : t('documents.error')
  } finally {
    deletingDocumentId.value = ''
  }
}

onMounted(() => {
  disposed = false
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  if (preparationClock) clearInterval(preparationClock)
  preparationClock = null
  for (const versionId of new Set([...progressConnections.keys(), ...progressRetryTimers.keys()])) {
    closeProgressConnection(versionId)
  }
})
</script>

<template>
  <AppShell>
    <main class="mx-auto max-w-5xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <section class="mx-auto max-w-2xl text-center">
        <p class="text-sm font-medium text-copper">{{ t('documents.heading.eyebrow') }}</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ t('documents.heading.title') }}</h1>
        <p class="mx-auto mt-4 max-w-xl leading-7 text-ink/55">{{ t('documents.heading.description') }}</p>

        <form class="mt-8 rounded-xl border border-ink/10 bg-paper p-5 text-left sm:p-7" @submit.prevent="uploadRulebook">
          <label for="rulebook-file" class="flex min-h-40 cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-ink/25 bg-canvas px-6 py-8 text-center hover:border-copper/60">
            <span class="font-display text-xl font-semibold">{{ file?.name ?? t('documents.file.choose') }}</span>
            <span class="mt-2 text-sm text-ink/45">{{ file ? t('documents.file.change') : t('documents.file.limit') }}</span>
          </label>
          <input id="rulebook-file" accept="application/pdf,.pdf" type="file" class="sr-only" @change="selectFile">

          <label class="mt-4 block text-sm font-semibold">{{ t('documents.title.label') }} <span class="font-normal text-ink/40">{{ t('documents.optional') }}</span>
            <input v-model="title" maxlength="160" :placeholder="t('documents.title.placeholder')" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper">
          </label>

          <details class="mt-4 border-t border-ink/10 pt-4">
            <summary class="cursor-pointer text-sm font-semibold text-ink/55">{{ t('documents.advanced') }}</summary>
            <div class="mt-4 space-y-4">
              <label class="block text-sm font-semibold">{{ t('documents.source.label') }}
                <input v-model="officialSourceUrl" type="url" inputmode="url" maxlength="2000" placeholder="https://publisher.example.com/rulebook.pdf" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper">
                <span class="mt-1 block text-xs font-normal leading-5 text-ink/45">{{ t('documents.source.hint') }}</span>
              </label>

              <label v-if="editionOptions.length" class="block text-sm font-semibold">{{ t('documents.game.label') }}
                <select v-model="editionId" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal">
                  <option value="">{{ t('documents.game.none') }}</option>
                  <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
                </select>
              </label>
              <p v-else class="text-sm leading-6 text-ink/55">{{ t('documents.game.missing') }} <RouterLink :to="{ name: 'catalog' }" class="font-semibold text-indigo underline">{{ t('documents.game.organize') }}</RouterLink>{{ t('documents.game.missingTail') }}</p>

              <div v-if="modelConfiguration && !visualVisionCapable" class="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
                <p><span class="font-semibold">{{ t('documents.visual.warningLead') }}</span>{{ t('documents.visual.warningBody') }}</p>
                <RouterLink :to="{ name: 'model-settings' }" class="mt-1 inline-block font-semibold text-indigo underline underline-offset-2">{{ t('documents.visual.settings') }}</RouterLink>
              </div>

              <div class="grid gap-4 sm:grid-cols-3">
                <template v-if="editionId">
                  <label class="text-sm font-semibold">{{ t('documents.players') }}<input v-model.number="playerCount" type="number" min="1" max="20" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
                  <label class="text-sm font-semibold">{{ t('documents.beginners') }}<input v-model.number="beginnerCount" type="number" min="0" :max="playerCount" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
                  <label class="text-sm font-semibold">{{ t('documents.minutes') }}<input v-model.number="durationMinutes" type="number" min="2" max="180" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
                </template>
                <label class="text-sm font-semibold" :class="editionId ? 'sm:col-span-3' : ''">{{ t('documents.sourceType') }}
                  <select v-model="sourceType" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5">
                    <option value="BASE_RULEBOOK">{{ t('documents.type.base') }}</option>
                    <option value="EXPANSION_RULEBOOK">{{ t('documents.type.expansion') }}</option>
                    <option value="OFFICIAL_FAQ">{{ t('documents.type.faq') }}</option>
                    <option value="OFFICIAL_ERRATA">{{ t('documents.type.errata') }}</option>
                  </select>
                </label>
              </div>
            </div>
          </details>

          <button :disabled="!canUpload" class="mt-5 w-full rounded-lg bg-copper px-5 py-3.5 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40">
            {{ preparingVersionId ? t('documents.submitPreparing') : uploading ? t('documents.submitUploading') : t('documents.submit') }}
          </button>
        </form>

        <p v-if="message && !preparingVersionId" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
        <div v-if="preparingVersionId" class="mt-5 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-left" role="status" aria-live="polite">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="font-semibold text-ink">{{ t('documents.organizing') }}</p>
              <p class="mt-1 text-sm leading-6 text-ink/60">{{ message }}</p>
            </div>
            <span class="shrink-0 text-xs font-medium text-indigo">{{ preparationElapsedLabel() }}</span>
          </div>
          <p class="mt-3 border-t border-indigo/10 pt-3 text-xs leading-5 text-ink/45">{{ t('documents.background') }}</p>
        </div>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <div v-if="processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
          <div class="h-full bg-copper transition-all" :style="{ width: `${progress[processingVersionId]?.percentage ?? 0}%` }" />
        </div>
      </section>

      <section class="mt-14 border-t border-ink/10 pt-8">
        <div class="flex items-center justify-between gap-4">
          <div>
            <h2 class="font-display text-2xl font-semibold">{{ t('documents.list.title') }}</h2>
            <p class="mt-1 text-sm text-ink/45">{{ t('documents.list.description') }}</p>
          </div>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">{{ t('documents.list.manage') }}</RouterLink>
        </div>
        <p v-if="loading" class="mt-5 text-sm text-ink/45">{{ t('documents.list.loading') }}</p>
        <div v-else-if="documents.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center">
          <p class="font-semibold">{{ t('documents.empty.title') }}</p>
          <p class="mt-2 text-sm text-ink/45">{{ t('documents.empty.description') }}</p>
        </div>
        <ul v-else class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in documents" :key="entry.document.id" class="py-5">
            <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div class="min-w-0">
                <p class="truncate font-semibold">{{ entry.document.title }}</p>
                <p class="mt-1 text-sm text-ink/45">
                  {{ documentStatusLabel(entry.latestVersion.status) }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB
                </p>
              </div>
              <div class="flex shrink-0 flex-wrap gap-2">
                <button v-if="entry.latestVersion.status === 'READY'" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="startLesson(entry.latestVersion.id).catch((error: unknown) => errorMessage = error instanceof Error ? error.message : t('documents.error'))">{{ t('documents.start') }}</button>
                <button type="button" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg px-3 py-2.5 text-sm font-semibold text-ink/45 hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="deleteRulebook(entry)">{{ deletingDocumentId === entry.document.id ? t('documents.deleting') : t('documents.delete') }}</button>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </main>
  </AppShell>
</template>
