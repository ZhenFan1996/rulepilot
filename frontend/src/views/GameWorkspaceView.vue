<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import {
  unrepresentedImportsForEdition,
  hasPendingShelfWork,
  type ShelfCatalogEntry,
  type ShelfDocument,
  type ShelfImportJob,
  type ShelfPlan,
  type ShelfPreparationRun,
  type ShelfUploadHandoff,
} from '@/lib/gameShelf'
import {
  parsePersonalShelfBase,
  parseRichBggDetails,
  parseShelfImports,
  parseShelfPlans,
  parseShelfPreparationRun,
  parseShelfUploadHandoffs,
  shelfPreparationSubjects,
  validatePersonalShelfRelationships,
  validateShelfUploadHandoffs,
  type RichBggDetails,
} from '@/lib/gameShelfSnapshot'
import { useLocale } from '@/lib/locale'

const route = useRoute()
const { locale } = useLocale()
const catalog = ref<ShelfCatalogEntry[]>([])
const documents = ref<ShelfDocument[]>([])
const imports = ref<ShelfImportJob[]>([])
const uploadHandoffs = ref<ShelfUploadHandoff[]>([])
const plans = ref<ShelfPlan[]>([])
const preparationRuns = ref<ShelfPreparationRun[]>([])
const details = ref<RichBggDetails | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const plansStatus = ref<'LOADING' | 'READY' | 'UNAVAILABLE'>('LOADING')
const importsStatus = ref<'LOADING' | 'READY' | 'UNAVAILABLE'>('LOADING')
const preparationsStatus = ref<'LOADING' | 'READY' | 'UNAVAILABLE'>('LOADING')
let username = ''
let shellIdentityResolved = false
let disposed = false
let latestWorkspaceLoad = 0
let activeBaseController: AbortController | null = null
let activeImportController: AbortController | null = null
let activePlanController: AbortController | null = null
let activeDetailsController: AbortController | null = null
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let refreshRevision = 0

const requestedGameId = computed(() => String(route.params.gameId ?? ''))
const candidateGame = computed(() => catalog.value.find(entry => entry.game.id === requestedGameId.value) ?? null)
const ownedGameIds = computed(() => {
  const editionOwners = new Map(catalog.value.flatMap(entry => entry.editions.map(edition => [edition.id, entry.game.id] as const)))
  const ids = new Set<string>()
  for (const document of documents.value) {
    if (!document.document.gameEditionId) continue
    const gameId = editionOwners.get(document.document.gameEditionId)
    if (gameId) ids.add(gameId)
  }
  for (const job of imports.value) {
    if (!job.editionId) continue
    const gameId = editionOwners.get(job.editionId)
    if (gameId) ids.add(gameId)
  }
  return ids
})
const game = computed(() => candidateGame.value && ownedGameIds.value.has(candidateGame.value.game.id)
  ? candidateGame.value
  : null)
const awaitingImportOwnership = computed(() => errorMessage.value === ''
  && !loading.value
  && candidateGame.value !== null
  && game.value === null
  && importsStatus.value === 'LOADING')
const membershipUnavailable = computed(() => errorMessage.value === ''
  && !loading.value
  && candidateGame.value !== null
  && game.value === null
  && importsStatus.value === 'UNAVAILABLE')
const pendingWork = computed(() => {
  const editionIds = new Set(game.value?.editions.map(edition => edition.id) ?? [])
  return editionIds.size > 0
    && hasPendingShelfWork(
      documents.value,
      imports.value,
      uploadHandoffs.value,
      plans.value,
      editionIds,
      preparationRuns.value,
    )
})
const copy = computed(() => locale.value === 'zh-CN' ? {
  back: '返回我的游戏', eyebrow: '桌游工作区', error: '暂时无法读取这款桌游。', notFound: '这款桌游不在你的“我的桌游”中。', retry: '重试',
  rating: 'BGG 评分', weight: '复杂度', designers: '设计师', publishers: '出版社', mechanics: '机制', categories: '类别',
  evidence: 'BGG 信息用于识别、推荐与展示；规则讲解和答疑只引用已处理的规则书。', editions: '版本、规则书与讲解',
  editionEmpty: '这个版本还没有规则书。', addRulebook: '找规则书', processing: '规则书处理中', failed: '规则书需要处理', ready: '规则书可用',
  openGuide: '打开讲解', ask: '规则答疑', generate: '开始讲解', source: '官方来源', bgg: '查看 BGG 原始资料',
  importQueued: '已加入“我的桌游”，规则书下载正在排队', importDownloading: '正在下载并绑定这本规则书', importSaving: '正在保存并绑定规则书', importReading: '规则书已保存，正在提取和建立检索', importFailed: '规则书导入需要处理',
  guidePreparing: '讲解任务已持久化，正在后台准备', guideFailed: '讲解准备需要处理', recoverGuide: '去我的讲解重试', guideChecking: '正在核对是否已有讲解；现有规则书仍可使用。',
  membershipUnavailable: '暂时无法确认这款桌游是否已加入“我的桌游”。',
} : {
  back: 'Back to my games', eyebrow: 'Game workspace', error: 'This game is unavailable right now.', notFound: 'This game is not in My Games.', retry: 'Try again',
  rating: 'BGG rating', weight: 'Complexity', designers: 'Designers', publishers: 'Publishers', mechanics: 'Mechanics', categories: 'Categories',
  evidence: 'BGG data supports identification, recommendations, and presentation. Teaching and Q&A cite only processed rulebooks.', editions: 'Editions, rulebooks, and guides',
  editionEmpty: 'This edition has no rulebook yet.', addRulebook: 'Find rulebook', processing: 'Processing rulebook', failed: 'Rulebook needs attention', ready: 'Rulebook ready',
  openGuide: 'Open guide', ask: 'Ask rules', generate: 'Start teaching', source: 'Official source', bgg: 'View original BGG data',
  importQueued: 'Added to My Games; the rulebook download is queued', importDownloading: 'Downloading and linking this rulebook', importSaving: 'Saving and linking this rulebook', importReading: 'Rulebook saved; extracting rules and building retrieval', importFailed: 'Rulebook import needs attention',
  guidePreparing: 'Guide work is persisted and continues in the background', guideFailed: 'Guide preparation needs attention', recoverGuide: 'Retry in My Guides', guideChecking: 'Checking for an existing guide. The rulebook remains usable.',
  membershipUnavailable: 'We cannot confirm whether this game is in My Games right now.',
})

function editionDocuments(editionId: string) {
  return documents.value.filter(document => document.document.gameEditionId === editionId)
}

function editionImports(editionId: string) {
  return unrepresentedImportsForEdition(editionId, imports.value, documents.value)
}

function documentPlans(document: ShelfDocument) {
  return plans.value
    .filter(plan => plan.documentVersionId === document.latestVersion.id)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
}

function documentHandoff(document: ShelfDocument) {
  return uploadHandoffs.value.find(handoff => handoff.documentVersionId === document.latestVersion.id)
}

function documentGuidePreparation(document: ShelfDocument) {
  const uploadHandoff = documentHandoff(document)
  if (uploadHandoff) {
    if (uploadHandoff.state === 'FAILED') return 'FAILED'
    if (uploadHandoff.state !== 'LAUNCHED' || !uploadHandoff.preparationRunId) return 'PREPARING'
    return preparationStage(uploadHandoff.preparationRunId)
  }
  const importJob = imports.value.find(job => job.documentVersionId === document.latestVersion.id)
  if (importJob?.teachingHandoffState === 'FAILED') return 'FAILED'
  if (importJob && ['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(importJob.teachingHandoffState)) return 'PREPARING'
  if (importJob?.teachingHandoffState === 'LAUNCHED' && importJob.teachingPreparationRunId) {
    return preparationStage(importJob.teachingPreparationRunId)
  }
  return null
}

function preparationStage(runId: string) {
  const state = preparationRuns.value.find(run => run.id === runId)?.state
  if (state === 'FAILED') return 'FAILED'
  if (state === 'ACTIVE') return 'PREPARING'
  return 'CHECKING'
}

function statusLabel(status: string) {
  if (status === 'READY') return copy.value.ready
  if (status === 'FAILED') return copy.value.failed
  return copy.value.processing
}

function importStage(job: ShelfImportJob) {
  if (job.stage === 'FAILED') return copy.value.importFailed
  if (job.stage === 'QUEUED' || job.stage === 'CONNECTING') return copy.value.importQueued
  if (['DOWNLOADING', 'COMPRESSING', 'VERIFYING_FILE'].includes(job.stage)) return copy.value.importDownloading
  if (job.stage === 'SAVING') return copy.value.importSaving
  return copy.value.importReading
}

function importGuideStage(job: ShelfImportJob) {
  if (job.teachingHandoffState === 'FAILED') return copy.value.guideFailed
  if (['WAITING_FOR_DOCUMENT', 'LAUNCHING'].includes(job.teachingHandoffState)) return copy.value.guidePreparing
  if (job.teachingHandoffState === 'LAUNCHED' && job.teachingPreparationRunId) {
    const state = preparationStage(job.teachingPreparationRunId)
    if (state === 'FAILED') return copy.value.guideFailed
    if (state === 'PREPARING') return copy.value.guidePreparing
    return copy.value.guideChecking
  }
  return ''
}

function importGuideFailed(job: ShelfImportJob) {
  if (!job.documentVersionId) return false
  if (job.teachingHandoffState === 'FAILED') return true
  return job.teachingHandoffState === 'LAUNCHED'
    && Boolean(job.teachingPreparationRunId)
    && preparationStage(job.teachingPreparationRunId!) === 'FAILED'
}

async function checkedFetch(path: string, signal: AbortSignal) {
  const response = await fetch(path, { credentials: 'include', signal })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(copy.value.error)
  }
  if (!response.ok) throw new Error(copy.value.error)
  return response
}

async function loadWorkspace() {
  if (!username || disposed || !requestedGameId.value) return
  const request = ++latestWorkspaceLoad
  clearRefreshTimer()
  abortWorkspaceReads()
  const baseController = new AbortController()
  const importController = new AbortController()
  const planController = new AbortController()
  activeBaseController = baseController
  activeImportController = importController
  activePlanController = planController
  const targetUsername = username
  const targetGameId = requestedGameId.value
  const retainedGame = game.value?.game.id === targetGameId ? game.value : null
  loading.value = retainedGame === null
  errorMessage.value = ''
  plansStatus.value = 'LOADING'
  importsStatus.value = 'LOADING'
  preparationsStatus.value = 'LOADING'
  if (!retainedGame) {
    details.value = null
    catalog.value = []
    documents.value = []
    imports.value = []
    uploadHandoffs.value = []
    plans.value = []
    preparationRuns.value = []
  }

  void loadImports(request, targetUsername, targetGameId, importController)
  void loadPlans(request, targetUsername, targetGameId, planController)
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
    if (!isCurrentBase(request, targetUsername, targetGameId, baseController)) return
    try {
      validatePersonalShelfRelationships(snapshot.catalog, snapshot.documents, imports.value)
      validateShelfUploadHandoffs(uploadHandoffs.value, snapshot.documents)
    } catch {
      imports.value = []
      uploadHandoffs.value = []
      preparationRuns.value = []
      importsStatus.value = 'UNAVAILABLE'
      preparationsStatus.value = 'UNAVAILABLE'
    }
    catalog.value = snapshot.catalog
    documents.value = snapshot.documents
    loading.value = false
    const nextGame = snapshot.catalog.find(entry => entry.game.id === targetGameId)
    const gameEditionIds = new Set(nextGame?.editions.map(edition => edition.id) ?? [])
    const owned = snapshot.documents.some(document => document.document.gameEditionId !== null
        && gameEditionIds.has(document.document.gameEditionId))
      || imports.value.some(job => job.editionId !== null && gameEditionIds.has(job.editionId))
    if (owned && nextGame?.bggMetadata?.bggId) {
      void loadDetails(targetUsername, targetGameId, nextGame.bggMetadata.bggId)
    }
  } catch {
    if (!isCurrentBase(request, targetUsername, targetGameId, baseController) || baseController.signal.aborted) return
    errorMessage.value = copy.value.error
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
    if (!disposed && revision === refreshRevision && pendingWork.value) void refreshRelationships()
  }, 4_000)
}

function clearRefreshTimer() {
  refreshRevision++
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = undefined
}

function handleVisibility() {
  if (document.visibilityState === 'hidden') clearRefreshTimer()
  else if (pendingWork.value) void refreshRelationships()
}

async function refreshRelationships() {
  if (!username || disposed || !requestedGameId.value) return
  const request = ++latestWorkspaceLoad
  clearRefreshTimer()
  abortRelationshipReads()
  const baseController = new AbortController()
  const importController = new AbortController()
  const planController = new AbortController()
  activeBaseController = baseController
  activeImportController = importController
  activePlanController = planController
  const targetUsername = username
  const targetGameId = requestedGameId.value
  plansStatus.value = 'LOADING'
  importsStatus.value = 'LOADING'
  preparationsStatus.value = 'LOADING'
  void loadImports(request, targetUsername, targetGameId, importController)
  void loadPlans(request, targetUsername, targetGameId, planController)
  try {
    const [catalogResponse, documentResponse] = await Promise.all([
      checkedFetch('/api/v1/games', baseController.signal),
      checkedFetch('/api/v1/documents', baseController.signal),
    ])
    const snapshot = parsePersonalShelfBase(
      await catalogResponse.json() as unknown,
      await documentResponse.json() as unknown,
      [],
      targetUsername,
    )
    if (!isCurrentBase(request, targetUsername, targetGameId, baseController)) return
    validateShelfUploadHandoffs(uploadHandoffs.value, snapshot.documents)
    catalog.value = snapshot.catalog
    documents.value = snapshot.documents
  } catch {
    if (isCurrentBase(request, targetUsername, targetGameId, baseController) && !baseController.signal.aborted) {
      importsStatus.value = 'UNAVAILABLE'
    }
  } finally {
    if (activeBaseController === baseController) {
      activeBaseController = null
      scheduleRefresh()
    }
    baseController.abort()
  }
}

async function loadImports(
  request: number,
  targetUsername: string,
  targetGameId: string,
  controller: AbortController,
) {
  try {
    const [importResponse, handoffResponse] = await Promise.all([
      checkedFetch('/api/v1/documents/official-imports', controller.signal),
      checkedFetch('/api/v1/documents/upload-teaching-handoffs', controller.signal),
    ])
    const [nextImports, nextHandoffs] = await Promise.all([
      importResponse.json().then(value => parseShelfImports(value as unknown)),
      handoffResponse.json().then(value => parseShelfUploadHandoffs(value as unknown)),
    ])
    if (!isCurrentImports(request, targetUsername, targetGameId, controller)) return
    if (catalog.value.length || documents.value.length) {
      validatePersonalShelfRelationships(catalog.value, documents.value, nextImports)
      validateShelfUploadHandoffs(nextHandoffs, documents.value)
    }
    imports.value = nextImports
    uploadHandoffs.value = nextHandoffs
    importsStatus.value = 'READY'
    const subjectEntries = [...shelfPreparationSubjects(nextImports, nextHandoffs)]
    if (subjectEntries.length === 0) {
      preparationRuns.value = []
      preparationsStatus.value = 'READY'
    } else {
      const previousById = new Map(preparationRuns.value.map(run => [run.id, run]))
      const settled = await Promise.allSettled(subjectEntries.map(async ([runId, documentVersionId]) => {
        const response = await checkedFetch(`/api/v1/assistant-runs/${encodeURIComponent(runId)}`, controller.signal)
        return parseShelfPreparationRun(
          await response.json() as unknown,
          runId,
          documentVersionId,
          targetUsername,
        )
      }))
      if (!isCurrentImports(request, targetUsername, targetGameId, controller)) return
      preparationRuns.value = settled.flatMap((result, index) => {
        if (result.status === 'fulfilled') return [result.value]
        const retained = previousById.get(subjectEntries[index]![0])
        return retained ? [retained] : []
      })
      const complete = settled.every(result => result.status === 'fulfilled')
      preparationsStatus.value = complete ? 'READY' : 'UNAVAILABLE'
    }
    if (!details.value && !activeDetailsController) {
      const nextGame = catalog.value.find(entry => entry.game.id === targetGameId)
      const gameEditionIds = new Set(nextGame?.editions.map(edition => edition.id) ?? [])
      if (nextGame?.bggMetadata?.bggId
        && nextImports.some(job => job.editionId !== null && gameEditionIds.has(job.editionId))) {
        void loadDetails(targetUsername, targetGameId, nextGame.bggMetadata.bggId)
      }
    }
  } catch {
    // Import status is progressive enrichment; owner documents remain usable without it.
    if (isCurrentImports(request, targetUsername, targetGameId, controller) && !controller.signal.aborted) {
      importsStatus.value = 'UNAVAILABLE'
      preparationsStatus.value = 'UNAVAILABLE'
    }
  } finally {
    if (activeImportController === controller) {
      activeImportController = null
      scheduleRefresh()
    }
    controller.abort()
  }
}

async function loadPlans(
  request: number,
  targetUsername: string,
  targetGameId: string,
  controller: AbortController,
) {
  try {
    const response = await checkedFetch('/api/v1/teaching-plans', controller.signal)
    const nextPlans = parseShelfPlans(await response.json() as unknown, targetUsername)
    if (!isCurrentPlans(request, targetUsername, targetGameId, controller)) return
    plans.value = nextPlans
    plansStatus.value = 'READY'
  } catch {
    if (!isCurrentPlans(request, targetUsername, targetGameId, controller) || controller.signal.aborted) return
    plansStatus.value = 'UNAVAILABLE'
  } finally {
    if (activePlanController === controller) {
      activePlanController = null
      scheduleRefresh()
    }
    controller.abort()
  }
}

async function loadDetails(targetUsername: string, targetGameId: string, bggId: number) {
  activeDetailsController?.abort()
  const controller = new AbortController()
  activeDetailsController = controller
  const targetLocale = locale.value
  try {
    const parameters = new URLSearchParams({ locale: targetLocale, translate: 'true' })
    const response = await fetch(`/api/v1/bgg/games/${bggId}?${parameters}`, {
      credentials: 'include', signal: controller.signal,
    })
    if (!response.ok) return
    const nextDetails = parseRichBggDetails(await response.json() as unknown, bggId)
    if (!isCurrentDetails(targetUsername, targetGameId, bggId, targetLocale, controller)) return
    details.value = nextDetails
  } catch {
    // BGG metadata is presentation-only; durable game, document, and guide bindings stay visible.
  } finally {
    if (activeDetailsController === controller) activeDetailsController = null
    controller.abort()
  }
}

function isCurrentBase(
  request: number,
  targetUsername: string,
  targetGameId: string,
  controller: AbortController,
) {
  return !disposed
    && latestWorkspaceLoad === request
    && username === targetUsername
    && requestedGameId.value === targetGameId
    && activeBaseController === controller
}

function isCurrentImports(
  request: number,
  targetUsername: string,
  targetGameId: string,
  controller: AbortController,
) {
  return !disposed
    && latestWorkspaceLoad === request
    && username === targetUsername
    && requestedGameId.value === targetGameId
    && activeImportController === controller
}

function isCurrentPlans(
  request: number,
  targetUsername: string,
  targetGameId: string,
  controller: AbortController,
) {
  return !disposed
    && latestWorkspaceLoad === request
    && username === targetUsername
    && requestedGameId.value === targetGameId
    && activePlanController === controller
}

function isCurrentDetails(
  targetUsername: string,
  targetGameId: string,
  bggId: number,
  targetLocale: string,
  controller: AbortController,
) {
  return !disposed
    && username === targetUsername
    && requestedGameId.value === targetGameId
    && game.value?.bggMetadata?.bggId === bggId
    && locale.value === targetLocale
    && activeDetailsController === controller
}

function abortRelationshipReads() {
  activeBaseController?.abort()
  activeImportController?.abort()
  activePlanController?.abort()
  activeBaseController = null
  activeImportController = null
  activePlanController = null
}

function abortWorkspaceReads() {
  abortRelationshipReads()
  activeDetailsController?.abort()
  activeDetailsController = null
}

function updateSessionIdentity(nextUsername: string) {
  if (disposed) return
  const normalizedUsername = nextUsername.trim()
  if (shellIdentityResolved && normalizedUsername === username) return
  shellIdentityResolved = true
  username = normalizedUsername
  latestWorkspaceLoad++
  clearRefreshTimer()
  abortWorkspaceReads()
  catalog.value = []
  documents.value = []
  imports.value = []
  uploadHandoffs.value = []
  plans.value = []
  preparationRuns.value = []
  details.value = null
  errorMessage.value = ''
  plansStatus.value = 'LOADING'
  importsStatus.value = 'LOADING'
  preparationsStatus.value = 'LOADING'
  if (username) void loadWorkspace()
  else {
    loading.value = false
    errorMessage.value = copy.value.error
    notifyLoginRequired()
  }
}

watch(requestedGameId, (nextGameId, previousGameId) => {
  if (!shellIdentityResolved || !username || nextGameId === previousGameId) return
  void loadWorkspace()
})

watch(locale, () => {
  const bggId = game.value?.bggMetadata?.bggId
  if (!bggId || loading.value || !username) return
  details.value = null
  void loadDetails(username, requestedGameId.value, bggId)
})

watch(pendingWork, scheduleRefresh)
onMounted(() => document.addEventListener('visibilitychange', handleVisibility))

onBeforeUnmount(() => {
  disposed = true
  latestWorkspaceLoad++
  clearRefreshTimer()
  abortWorkspaceReads()
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<template>
  <AppShell @session-identity="updateSessionIdentity">
    <div class="tabletop-page max-w-6xl">
      <RouterLink :to="{ name: 'catalog' }" class="text-sm font-semibold text-indigo">← {{ copy.back }}</RouterLink>

      <div v-if="loading || awaitingImportOwnership" class="mt-8 h-96 animate-pulse rounded-2xl bg-ink/8" aria-live="polite" />
      <section v-else-if="errorMessage" class="mt-8 rounded-2xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p><button type="button" class="mt-3 font-semibold underline" @click="loadWorkspace">{{ copy.retry }}</button>
      </section>
      <section v-else-if="membershipUnavailable" class="mt-8 rounded-2xl border border-amber-200 bg-amber-50 p-6 text-amber-950" role="alert">
        <h1 class="font-display text-2xl font-semibold">{{ copy.membershipUnavailable }}</h1>
        <button type="button" class="mt-3 font-semibold underline" @click="loadWorkspace">{{ copy.retry }}</button>
      </section>
      <section v-else-if="!game" class="mt-8 rounded-2xl border border-dashed border-ink/20 bg-paper p-8 text-center">
        <h1 class="font-display text-3xl font-semibold">{{ copy.notFound }}</h1>
      </section>

      <template v-else>
        <section class="tabletop-panel player-board mt-8 grid gap-7 overflow-hidden p-5 sm:p-8 lg:grid-cols-[16rem_1fr]">
          <div class="rounded-2xl bg-canvas p-4">
            <img v-if="details?.imageUrl || game.bggMetadata?.thumbnailUrl" :src="details?.imageUrl || game.bggMetadata?.thumbnailUrl" :alt="game.game.name" class="mx-auto aspect-[4/5] size-full object-contain" referrerpolicy="no-referrer">
            <TabletopGlyph v-else name="meeple" :size="96" class="mx-auto my-16 text-copper" />
          </div>
          <div class="self-center">
            <p class="text-sm font-semibold text-copper">{{ copy.eyebrow }}</p>
            <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ game.game.name }}</h1>
            <div v-if="details?.averageRating || details?.averageWeight" class="mt-4 flex flex-wrap gap-2 text-sm">
              <span v-if="details.averageRating" class="rounded-full bg-canvas px-3 py-1.5">{{ copy.rating }} {{ details.averageRating.toFixed(1) }}</span>
              <span v-if="details.averageWeight" class="rounded-full bg-canvas px-3 py-1.5">{{ copy.weight }} {{ details.averageWeight.toFixed(1) }} / 5</span>
            </div>
            <p v-if="details?.description" class="mt-5 whitespace-pre-line leading-7 text-ink/65">{{ details.description }}</p>
            <dl v-if="details" class="mt-5 grid gap-3 text-sm sm:grid-cols-2">
              <div v-if="details.designers.length"><dt class="font-semibold text-ink/45">{{ copy.designers }}</dt><dd>{{ details.designers.join('、') }}</dd></div>
              <div v-if="details.publishers.length"><dt class="font-semibold text-ink/45">{{ copy.publishers }}</dt><dd>{{ details.publishers.join('、') }}</dd></div>
              <div v-if="details.mechanics.length"><dt class="font-semibold text-ink/45">{{ copy.mechanics }}</dt><dd>{{ details.mechanics.join('、') }}</dd></div>
              <div v-if="details.categories.length"><dt class="font-semibold text-ink/45">{{ copy.categories }}</dt><dd>{{ details.categories.join('、') }}</dd></div>
            </dl>
            <p class="mt-5 text-xs leading-5 text-ink/45">{{ copy.evidence }}</p>
            <a v-if="details?.bggUrl || game.bggMetadata?.bggUrl" :href="details?.bggUrl || game.bggMetadata?.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-3 inline-block text-sm font-semibold text-indigo">{{ copy.bgg }} ↗</a>
          </div>
        </section>

        <p v-if="plansStatus !== 'READY' || importsStatus !== 'READY' || preparationsStatus !== 'READY'" class="mt-6 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950" role="status">{{ copy.guideChecking }}</p>

        <section class="mt-10">
          <h2 class="font-display text-3xl font-semibold">{{ copy.editions }}</h2>
          <div class="mt-5 stack-y-xl">
            <article v-for="edition in game.editions" :key="edition.id" class="tabletop-panel player-board p-5 sm:p-6">
              <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div><h3 class="font-display text-2xl font-semibold">{{ edition.name }}</h3><p class="mt-1 text-sm text-ink/45">{{ edition.language }}<span v-if="edition.publicationYear"> · {{ edition.publicationYear }}</span></p></div>
                <RouterLink :to="{ name: 'teach', query: { editionId: edition.id, onboarding: 'selected-game' } }" class="inline-flex min-h-11 items-center justify-center rounded-xl bg-copper px-5 text-sm font-semibold text-white">{{ copy.addRulebook }}</RouterLink>
              </div>

              <ul v-if="editionImports(edition.id).length" class="mt-5 stack-y-md" aria-live="polite">
                <li v-for="job in editionImports(edition.id)" :key="job.id" class="rounded-xl border border-copper/20 bg-copper/5 p-4">
                  <p class="font-semibold">{{ job.rulebookTitle }}</p>
                  <p class="mt-1 text-sm text-copper">{{ importStage(job) }}</p>
                  <p v-if="importGuideStage(job)" class="mt-1 text-xs leading-5 text-ink/55">
                    {{ importGuideStage(job) }}
                    <RouterLink v-if="importGuideFailed(job)" :to="{ name: 'lessons' }" class="ml-2 font-semibold text-red-800 underline decoration-red-300 underline-offset-2">{{ copy.recoverGuide }}</RouterLink>
                  </p>
                </li>
              </ul>

              <p v-if="editionDocuments(edition.id).length === 0 && editionImports(edition.id).length === 0" class="mt-5 rounded-xl bg-canvas px-4 py-5 text-sm text-ink/55">{{ copy.editionEmpty }}</p>
              <ul v-else-if="editionDocuments(edition.id).length" class="mt-5 stack-y-md">
                <li v-for="document in editionDocuments(edition.id)" :key="document.document.id" class="rounded-xl border border-ink/10 bg-canvas p-4">
                  <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div><p class="font-semibold">{{ document.document.title }}</p><p class="mt-1 text-xs text-ink/45">{{ statusLabel(document.latestVersion.status) }}</p></div>
                    <div class="flex flex-wrap gap-2">
                      <template v-if="documentPlans(document)[0]">
                        <RouterLink :to="{ name: 'lesson', params: { planId: documentPlans(document)[0]!.id } }" class="min-h-10 rounded-lg bg-ink px-4 py-2.5 text-sm font-semibold text-canvas">{{ copy.openGuide }}</RouterLink>
                        <RouterLink :to="{ name: 'lesson-questions', params: { planId: documentPlans(document)[0]!.id } }" class="min-h-10 rounded-lg border border-indigo/25 px-4 py-2.5 text-sm font-semibold text-indigo">{{ copy.ask }}</RouterLink>
                      </template>
                      <span v-else-if="documentGuidePreparation(document) === 'PREPARING'" class="inline-flex min-h-10 items-center rounded-lg border border-copper/20 bg-copper/5 px-4 py-2.5 text-sm font-semibold text-copper">{{ copy.guidePreparing }}</span>
                      <RouterLink v-else-if="documentGuidePreparation(document) === 'FAILED'" :to="{ name: 'lessons' }" class="inline-flex min-h-10 items-center rounded-lg border border-red-200 bg-red-50 px-4 py-2.5 text-sm font-semibold text-red-800 transition hover:border-red-400">{{ copy.guideFailed }} · {{ copy.recoverGuide }}</RouterLink>
                      <span v-else-if="documentGuidePreparation(document) === 'CHECKING'" class="inline-flex min-h-10 items-center rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm font-semibold text-amber-950">{{ copy.guideChecking }}</span>
                      <RouterLink v-else-if="document.latestVersion.status === 'READY' && plansStatus === 'READY' && importsStatus === 'READY' && preparationsStatus === 'READY'" :to="{ name: 'teach', query: { editionId: edition.id } }" class="min-h-10 rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold">{{ copy.generate }}</RouterLink>
                      <a v-if="document.document.officialSourceUrl" :href="document.document.officialSourceUrl" target="_blank" rel="noopener noreferrer" class="min-h-10 px-2 py-2.5 text-sm font-semibold text-indigo">{{ copy.source }} ↗</a>
                    </div>
                  </div>
                </li>
              </ul>
            </article>
          </div>
        </section>
      </template>
    </div>
  </AppShell>
</template>
