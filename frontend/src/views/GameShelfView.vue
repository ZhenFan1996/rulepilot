<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import GameShelfCard from '@/components/GameShelfCard.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import {
  buildPersonalShelf,
  hasPendingShelfWork,
  type ShelfCatalogEntry,
  type ShelfDocument,
  type ShelfImportJob,
  type ShelfPlan,
  type ShelfPlansAvailability,
  type ShelfPreparationRun,
  type ShelfUploadHandoff,
} from '@/lib/gameShelf'
import {
  parsePersonalShelfBase,
  parseShelfPreparationRun,
  parseShelfImports,
  parseShelfPlans,
  parseShelfUploadHandoffs,
  shelfPreparationSubjects,
  validatePersonalShelfRelationships,
  validateShelfUploadHandoffs,
} from '@/lib/gameShelfSnapshot'
import { useLocale } from '@/lib/locale'

const { locale, t } = useLocale()
const catalog = ref<ShelfCatalogEntry[]>([])
const documents = ref<ShelfDocument[]>([])
const imports = ref<ShelfImportJob[]>([])
const uploadHandoffs = ref<ShelfUploadHandoff[]>([])
const plans = ref<ShelfPlan[]>([])
const preparationRuns = ref<ShelfPreparationRun[]>([])
const plansAvailability = ref<ShelfPlansAvailability>('LOADING')
const handoffsAvailability = ref<ShelfPlansAvailability>('LOADING')
const preparationsAvailability = ref<ShelfPlansAvailability>('LOADING')
const loading = ref(true)
const errorMessage = ref('')
const refreshWarning = ref('')
const search = ref('')
let username = ''
let shellIdentityResolved = false
let disposed = false
let latestLoad = 0
let activeBaseController: AbortController | null = null
let activeImportController: AbortController | null = null
let activePlanController: AbortController | null = null
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let refreshRevision = 0

const guideAvailability = computed<ShelfPlansAvailability>(() => {
  if (plansAvailability.value === 'UNAVAILABLE' || handoffsAvailability.value === 'UNAVAILABLE') return 'UNAVAILABLE'
  if (plansAvailability.value === 'LOADING' || handoffsAvailability.value === 'LOADING') return 'LOADING'
  return 'READY'
})
const shelf = computed(() => buildPersonalShelf(catalog.value, documents.value, plans.value, {
  imports: imports.value,
  uploadHandoffs: uploadHandoffs.value,
  preparationRuns: preparationRuns.value,
  plansAvailability: guideAvailability.value,
  preparationsAvailability: preparationsAvailability.value,
  locale: locale.value,
}))
const filteredShelf = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  if (!keyword) return shelf.value
  return shelf.value.filter(item => item.title.toLocaleLowerCase().includes(keyword))
})
const readyLessons = computed(() => shelf.value.filter(item => item.latestPlanId).length)
const pendingWork = computed(() => hasPendingShelfWork(
  documents.value, imports.value, uploadHandoffs.value, plans.value, undefined, preparationRuns.value,
))

async function checkedFetch(path: string, signal: AbortSignal) {
  const response = await fetch(path, { credentials: 'include', signal })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(t('shelf.error.login'))
  }
  if (!response.ok) throw new Error(t('shelf.error.load'))
  return response
}

async function loadShelf() {
  if (!username || disposed) return
  const request = ++latestLoad
  clearRefreshTimer()
  activeBaseController?.abort()
  activeImportController?.abort()
  activePlanController?.abort()
  const baseController = new AbortController()
  const importController = new AbortController()
  const planController = new AbortController()
  activeBaseController = baseController
  activeImportController = importController
  activePlanController = planController
  const targetUsername = username
  const hasPublishedShelf = catalog.value.length > 0 || documents.value.length > 0 || imports.value.length > 0
  if (!hasPublishedShelf) loading.value = true
  errorMessage.value = ''
  refreshWarning.value = ''
  plansAvailability.value = 'LOADING'
  handoffsAvailability.value = 'LOADING'
  preparationsAvailability.value = 'LOADING'

  void loadImports(request, targetUsername, importController)
  void loadPlans(request, targetUsername, planController)
  try {
    const [catalogResponse, documentResponse] = await Promise.all([
      checkedFetch('/api/v1/games', baseController.signal),
      checkedFetch('/api/v1/documents', baseController.signal),
    ])
    const [catalogPayload, documentPayload] = await Promise.all([
      catalogResponse.json() as Promise<unknown>,
      documentResponse.json() as Promise<unknown>,
    ])
    const snapshot = parsePersonalShelfBase(catalogPayload, documentPayload, [], targetUsername)
    if (!isCurrentBase(request, targetUsername, baseController)) return
    try {
      validatePersonalShelfRelationships(snapshot.catalog, snapshot.documents, imports.value)
      validateShelfUploadHandoffs(uploadHandoffs.value, snapshot.documents)
    } catch {
      imports.value = []
      uploadHandoffs.value = []
      preparationRuns.value = []
      preparationsAvailability.value = 'UNAVAILABLE'
      refreshWarning.value = t('shelf.refresh.warning')
    }
    catalog.value = snapshot.catalog
    documents.value = snapshot.documents
    loading.value = false
  } catch (error) {
    if (!isCurrentBase(request, targetUsername, baseController) || baseController.signal.aborted) return
    const message = error instanceof Error && error.message === t('shelf.error.login')
      ? error.message
      : t('shelf.error.load')
    if (hasPublishedShelf) refreshWarning.value = t('shelf.refresh.warning')
    else errorMessage.value = message
    loading.value = false
  } finally {
    if (activeBaseController === baseController) {
      activeBaseController = null
      scheduleRefresh()
    }
    baseController.abort()
  }
}

function scheduleRefresh() {
  clearRefreshTimer()
  if (disposed
    || !username
    || activeBaseController
    || activeImportController
    || activePlanController
    || !pendingWork.value
    || document.visibilityState === 'hidden') return
  const revision = refreshRevision
  refreshTimer = setTimeout(() => {
    refreshTimer = undefined
    if (!disposed && revision === refreshRevision && pendingWork.value) void loadShelf()
  }, 4_000)
}

function clearRefreshTimer() {
  refreshRevision++
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = undefined
}

function handleVisibility() {
  if (document.visibilityState === 'hidden') clearRefreshTimer()
  else if (pendingWork.value) void loadShelf()
}

async function loadImports(request: number, targetUsername: string, controller: AbortController) {
  try {
    const [importResponse, handoffResponse] = await Promise.all([
      checkedFetch('/api/v1/documents/official-imports', controller.signal),
      checkedFetch('/api/v1/documents/upload-teaching-handoffs', controller.signal),
    ])
    const [nextImports, nextHandoffs] = await Promise.all([
      importResponse.json().then(value => parseShelfImports(value as unknown)),
      handoffResponse.json().then(value => parseShelfUploadHandoffs(value as unknown)),
    ])
    if (!isCurrentImports(request, targetUsername, controller)) return
    if (catalog.value.length || documents.value.length) {
      validatePersonalShelfRelationships(catalog.value, documents.value, nextImports)
      validateShelfUploadHandoffs(nextHandoffs, documents.value)
    }
    imports.value = nextImports
    uploadHandoffs.value = nextHandoffs
    handoffsAvailability.value = 'READY'
    const subjects = shelfPreparationSubjects(nextImports, nextHandoffs)
    if (subjects.size === 0) {
      preparationRuns.value = []
      preparationsAvailability.value = 'READY'
      return
    }
    const previousById = new Map(preparationRuns.value.map(run => [run.id, run]))
    const subjectEntries = [...subjects]
    const settled = await Promise.allSettled(subjectEntries.map(async ([runId, documentVersionId]) => {
      const response = await checkedFetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, controller.signal)
      return parseShelfPreparationRun(
        await response.json() as unknown,
        runId,
        documentVersionId,
        targetUsername,
      )
    }))
    if (!isCurrentImports(request, targetUsername, controller)) return
    preparationRuns.value = settled.flatMap((result, index) => {
      if (result.status === 'fulfilled') return [result.value]
      const runId = subjectEntries[index]?.[0]
      const retained = runId ? previousById.get(runId) : undefined
      return retained ? [retained] : []
    })
    preparationsAvailability.value = settled.every(result => result.status === 'fulfilled') ? 'READY' : 'UNAVAILABLE'
    if (preparationsAvailability.value === 'UNAVAILABLE') refreshWarning.value = t('shelf.refresh.warning')
  } catch {
    if (!isCurrentImports(request, targetUsername, controller) || controller.signal.aborted) return
    handoffsAvailability.value = 'UNAVAILABLE'
    preparationsAvailability.value = 'UNAVAILABLE'
    refreshWarning.value = t('shelf.refresh.warning')
  } finally {
    if (activeImportController === controller) {
      activeImportController = null
      scheduleRefresh()
    }
    controller.abort()
  }
}

async function loadPlans(request: number, targetUsername: string, controller: AbortController) {
  try {
    const response = await checkedFetch('/api/v1/teaching-plans', controller.signal)
    const nextPlans = parseShelfPlans(await response.json() as unknown, targetUsername)
    if (!isCurrentPlans(request, targetUsername, controller)) return
    plans.value = nextPlans
    plansAvailability.value = 'READY'
  } catch {
    if (!isCurrentPlans(request, targetUsername, controller) || controller.signal.aborted) return
    plansAvailability.value = 'UNAVAILABLE'
    refreshWarning.value = t('shelf.refresh.warning')
  } finally {
    if (activePlanController === controller) {
      activePlanController = null
      scheduleRefresh()
    }
    controller.abort()
  }
}

function isCurrentImports(request: number, targetUsername: string, controller: AbortController) {
  return !disposed && latestLoad === request && username === targetUsername && activeImportController === controller
}

function isCurrentBase(request: number, targetUsername: string, controller: AbortController) {
  return !disposed && latestLoad === request && username === targetUsername && activeBaseController === controller
}

function isCurrentPlans(request: number, targetUsername: string, controller: AbortController) {
  return !disposed && latestLoad === request && username === targetUsername && activePlanController === controller
}

function updateSessionIdentity(nextUsername: string) {
  if (disposed) return
  const normalizedUsername = nextUsername.trim()
  if (shellIdentityResolved && normalizedUsername === username) return
  shellIdentityResolved = true
  latestLoad++
  clearRefreshTimer()
  activeBaseController?.abort()
  activeImportController?.abort()
  activePlanController?.abort()
  activeBaseController = null
  activeImportController = null
  activePlanController = null
  const identityChanged = normalizedUsername !== username
  username = normalizedUsername
  if (identityChanged) clearShelf()
  if (username) void loadShelf()
  else {
    loading.value = false
    errorMessage.value = t('shelf.error.login')
    notifyLoginRequired()
  }
}

function clearShelf() {
  catalog.value = []
  documents.value = []
  imports.value = []
  uploadHandoffs.value = []
  plans.value = []
  preparationRuns.value = []
  plansAvailability.value = 'LOADING'
  handoffsAvailability.value = 'LOADING'
  preparationsAvailability.value = 'LOADING'
  errorMessage.value = ''
  refreshWarning.value = ''
  loading.value = true
}

watch(pendingWork, scheduleRefresh)
onMounted(() => document.addEventListener('visibilitychange', handleVisibility))

onBeforeUnmount(() => {
  disposed = true
  latestLoad++
  clearRefreshTimer()
  activeBaseController?.abort()
  activeImportController?.abort()
  activePlanController?.abort()
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<template>
  <AppShell @session-identity="updateSessionIdentity">
    <div class="shelf-page tabletop-page">
      <section class="border-b border-ink/10 pb-7">
        <div class="tabletop-heading">
          <h1 class="tabletop-title">{{ t('shelf.title') }}</h1>
          <p class="tabletop-lede">{{ t('shelf.description') }}</p>
          <div class="mt-7 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl bg-copper px-5 text-sm font-bold text-on-accent transition hover:bg-copper-dark">
              <TabletopGlyph name="plus" :size="18" /> {{ t('shelf.addRulebook') }}
            </RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-ink/15 px-5 text-sm font-semibold text-indigo transition hover:bg-ink/5">
              <TabletopGlyph name="library" :size="18" /> {{ t('shelf.publicGuides') }}
            </RouterLink>
          </div>
        </div>
      </section>

      <section class="mt-8 flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p class="text-sm font-semibold text-copper">{{ t('shelf.collection') }}</p>
          <h2 class="mt-1 font-display text-3xl font-semibold tracking-tight">{{ t('shelf.playing') }}</h2>
          <p class="mt-2 text-sm leading-6 text-muted">{{ t('shelf.summary', { games: shelf.length, rulebooks: documents.length, guides: readyLessons }) }}</p>
        </div>
        <label class="relative block w-full lg:w-72">
          <span class="sr-only">{{ t('shelf.searchLabel') }}</span>
          <TabletopGlyph name="compass" :size="18" class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-muted" />
          <input v-model="search" type="search" :placeholder="t('shelf.searchPlaceholder')" class="min-h-11 w-full rounded-xl border border-ink/12 bg-paper py-3 pl-11 pr-4 text-sm outline-none transition placeholder:text-ink/40 focus:border-indigo focus:ring-2 focus:ring-indigo/15">
        </label>
      </section>

      <div v-if="refreshWarning" class="mt-6 flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950 sm:flex-row sm:items-center sm:justify-between" role="status">
        <p>{{ refreshWarning }}</p>
        <button type="button" class="min-h-10 shrink-0 font-semibold underline" @click="loadShelf">{{ t('shelf.tryAgain') }}</button>
      </div>

      <section v-if="loading" class="mt-7 grid gap-5 sm:grid-cols-2 xl:grid-cols-3" aria-live="polite" :aria-label="t('shelf.loading')">
        <div v-for="index in 3" :key="index" class="h-[25rem] animate-pulse rounded-[1.65rem] border border-ink/8 bg-paper" />
      </section>

      <section v-else-if="errorMessage" class="mt-7 rounded-[1.5rem] border border-red-200 bg-red-50 p-6 text-red-900" role="alert">
        <h2 class="font-display text-xl font-semibold">{{ t('shelf.error.title') }}</h2>
        <p class="mt-2 text-sm leading-6">{{ errorMessage }}</p>
        <button type="button" class="mt-5 min-h-11 rounded-xl bg-ink px-5 text-sm font-bold text-canvas" @click="loadShelf">{{ t('shelf.tryAgain') }}</button>
      </section>

      <section v-else-if="shelf.length === 0" class="mt-7 overflow-hidden rounded-[1.75rem] border border-dashed border-ink/25 bg-paper px-6 py-12 text-center sm:px-10">
        <div class="mx-auto grid size-16 place-items-center rounded-2xl bg-copper/10 text-copper"><TabletopGlyph name="rulebook" :size="32" /></div>
        <h2 class="mt-5 font-display text-3xl font-semibold">{{ t('shelf.empty.title') }}</h2>
        <p class="mx-auto mt-3 max-w-md leading-7 text-muted">{{ t('shelf.empty.description') }}</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-6 inline-flex min-h-11 items-center gap-2 rounded-xl bg-copper px-5 text-sm font-bold text-on-accent transition hover:bg-copper-dark">
          <TabletopGlyph name="plus" :size="18" /> {{ t('shelf.empty.action') }}
        </RouterLink>
      </section>

      <section v-else-if="filteredShelf.length" class="mt-7 grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
        <GameShelfCard v-for="(item, index) in filteredShelf" :key="item.id" :item="item" :index="index" />
      </section>

      <section v-else class="mt-7 rounded-[1.5rem] border border-ink/10 bg-paper p-8 text-center">
        <TabletopGlyph name="compass" :size="30" class="mx-auto text-indigo" />
        <h2 class="mt-4 font-display text-2xl font-semibold">{{ t('shelf.noResults.title') }}</h2>
        <p class="mt-2 text-sm text-muted">{{ t('shelf.noResults.description') }}</p>
        <button type="button" class="mt-4 min-h-11 text-sm font-bold text-indigo" @click="search = ''">{{ t('shelf.noResults.action') }}</button>
      </section>

      <aside class="mt-10 flex flex-col gap-4 rounded-[1.5rem] border border-ink/10 bg-paper p-5 sm:flex-row sm:items-center sm:justify-between">
        <div class="flex items-center gap-3">
          <span class="grid size-11 place-items-center rounded-xl bg-indigo/10 text-indigo"><TabletopGlyph name="cards" :size="23" /></span>
          <div><h2 class="font-semibold">{{ t('shelf.manage.title') }}</h2><p class="mt-1 text-sm text-muted">{{ t('shelf.manage.description') }}</p></div>
        </div>
        <RouterLink :to="{ name: 'catalog-manage' }" class="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-ink/12 px-4 text-sm font-bold text-ink/75 transition hover:border-indigo hover:text-indigo">
          {{ t('shelf.manage.action') }} <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
      </aside>
    </div>
  </AppShell>
</template>

<style scoped>
.shelf-page {
  background-image: radial-gradient(rgba(26, 35, 42, 0.055) 0.7px, transparent 0.7px);
  background-size: 12px 12px;
}
</style>
