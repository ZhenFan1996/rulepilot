<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import type { RecommendationGame, RecommendationProfile } from '@/components/gameRecommendationTypes'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'

interface ImportedGame {
  game: { id: string; name: string }
  edition: { id: string; name: string }
  alreadyImported: boolean
}

interface RulebookCandidate {
  title: string
  url: string
  publisher: string
  language: string
  edition: string
  sourceDomain: string
  officialDomainVerified: boolean
  sourceType: 'PUBLISHER' | 'TRUSTED_REPOSITORY' | 'COMMUNITY_PLATFORM' | 'PUBLIC_WEB'
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
}

interface RulebookCandidateResponse {
  configured: boolean
  candidates: RulebookCandidate[]
}

interface OfficialImportJob {
  id: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode?: string | null
}

interface CsrfResponse { headerName: string; token: string }

const props = defineProps<{ game: RecommendationGame; profile: RecommendationProfile }>()
const emit = defineEmits<{ close: [] }>()
const { locale } = useLocale()

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '从推荐到讲解', title: `已选《${props.game.name}》`, preparing: '正在保存桌游并寻找可审阅的规则书…',
  finding: '桌游已保存，正在检索出版社、BGG、集石和可信规则库（已等待 {seconds} 秒，通常几秒，偶尔约 30 秒）…', found: '选择一份规则书', detail: '优先展示出版社来源，也会保留社区与可信规则库结果。请核对语言和版本；PDF 直链与已识别的连续规则页图片都可直接导入。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: '社区规则书来源（如 BGG / 集石）', PUBLIC_WEB: '公开来源（请重点核对）' },
  direct: 'PDF 可直接核验并下载', gallery: '连续规则页图片，可合成为 PDF', page: '来源页，需要继续查找文件', publisher: '发布者', language: '语言', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择', open: '打开来源页',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  import: '下载规则书并生成讲解', importing: '正在安全下载并准备讲解…', manual: '改用公开链接或本地上传',
  success: '已加入“我的桌游”，讲解已经在后台开始。你可以继续浏览，准备进度会持续保留。', catalog: '打开我的桌游', lessons: '查看讲解进度',
  browserRequired: '已经找到这份文件，但来源网站要求在浏览器里完成隐私选择、刷新临时链接或登录。打开原始下载页取得 PDF 后，回到 RulePilot 上传即可继续；桌游、版本和讲解偏好都已保留。',
  sourcePageHandoff: '这是经过核对的来源页面，但搜索结果没有提供可验证的 PDF 直链。请在来源网站核对语言和版本并下载 PDF，再回到 RulePilot 上传；桌游和讲解偏好都已保留。',
  browserAction: '在来源网站继续下载',
  unavailable: '当前没有找到可审阅的规则书来源。你仍可粘贴公开 PDF 链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', retry: '重试', close: '换一款',
} : {
  eyebrow: 'From recommendation to guide', title: `${props.game.name} selected`, preparing: 'Saving the game and finding reviewable rulebooks…',
  finding: 'Game saved. Searching publishers, BGG, Gstone, and trusted repositories ({seconds}s elapsed; usually a few seconds, occasionally about 30s)…', found: 'Choose a rulebook', detail: 'Publisher sources come first, with useful community and trusted-repository results preserved. Review language and edition; direct PDFs and recognized ordered page-image documents can both be imported.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'Community rulebook source (such as BGG / Gstone)', PUBLIC_WEB: 'Public source (review carefully)' },
  direct: 'Direct PDF ready for verification', gallery: 'Ordered rulebook pages; RulePilot can build the PDF', page: 'Source page; continue there', publisher: 'Provider', language: 'Language', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected', open: 'Open source page',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  import: 'Download and generate guide', importing: 'Downloading safely and preparing the guide…', manual: 'Use a public URL or local upload',
  success: 'Added to My Games, and guide preparation has started in the background. You can keep browsing while RulePilot preserves its progress.', catalog: 'Open My Games', lessons: 'View guide progress',
  browserRequired: 'The file was found, but its source requires an in-browser privacy choice, refreshed temporary link, or sign-in. Download it there, then return to upload it; the game, edition, and guide preferences are preserved.',
  sourcePageHandoff: 'This source page was verified, but search did not expose a verifiable PDF URL. Review the language and edition there, download the PDF, then return to upload it; the game and guide preferences are preserved.',
  browserAction: 'Continue on the source site',
  unavailable: 'No reviewable rulebook source was found. You can still paste a public PDF URL or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', retry: 'Retry', close: 'Choose another game',
})

const imported = ref<ImportedGame | null>(null)
const candidates = ref<RulebookCandidate[]>([])
const selected = ref<RulebookCandidate | null>(null)
const openedSource = ref<RulebookCandidate | null>(null)
const consent = ref(false)
const state = ref<'preparing' | 'finding' | 'review' | 'unavailable' | 'login' | 'error' | 'importing' | 'browser-required' | 'success'>('preparing')
const findingSeconds = ref(0)
let csrf: CsrfResponse | null = null
let sequence = 0
let findingClock: ReturnType<typeof setInterval> | null = null

const canImport = computed(() => Boolean(
  selected.value
  && selected.value.acquisitionMode !== 'SOURCE_PAGE'
  && consent.value
  && state.value === 'review',
))
const findingText = computed(() => copy.value.finding.replace('{seconds}', String(findingSeconds.value)))
const manualRoute = computed(() => ({
  name: 'teach' as const,
  query: imported.value ? { editionId: imported.value.edition.id, onboarding: 'recommendation-agent' } : {},
}))

async function csrfToken() {
  if (csrf) return csrf
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('csrf unavailable')
  csrf = await response.json() as CsrfResponse
  return csrf
}

function requireLogin() {
  state.value = 'login'
  notifyLoginRequired()
}

async function prepare() {
  const request = ++sequence
  state.value = 'preparing'
  candidates.value = []
  selected.value = null
  openedSource.value = null
  consent.value = false
  try {
    const token = await csrfToken()
    const response = await fetch(`/api/v1/bgg/games/${props.game.bggId}/import`, {
      method: 'POST', credentials: 'include', headers: { [token.headerName]: token.token },
    })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('selection failed')
    imported.value = await response.json() as ImportedGame
    await discover(request)
  } catch {
    if (request === sequence) state.value = 'error'
  }
}

async function discover(request = sequence) {
  if (!imported.value || request !== sequence) return
  state.value = 'finding'
  findingSeconds.value = 0
  if (findingClock) clearInterval(findingClock)
  findingClock = setInterval(() => { findingSeconds.value += 1 }, 1000)
  try {
    const parameters = new URLSearchParams({ editionId: imported.value.edition.id, language: locale.value })
    const response = await fetch(`/api/v1/documents/rulebook-candidates?${parameters.toString()}`, { credentials: 'include' })
    if (request !== sequence) return
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('discovery failed')
    const result = await response.json() as RulebookCandidateResponse
    candidates.value = result.candidates
    state.value = result.configured && result.candidates.length ? 'review' : 'unavailable'
  } catch {
    if (request === sequence) state.value = 'error'
  } finally {
    if (request === sequence && findingClock) {
      clearInterval(findingClock)
      findingClock = null
    }
  }
}

function choose(candidate: RulebookCandidate) {
  if (candidate.acquisitionMode === 'SOURCE_PAGE') {
    openedSource.value = candidate
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  openedSource.value = null
  selected.value = candidate
  consent.value = false
}

async function importAndTeach() {
  if (!canImport.value || !imported.value || !selected.value) return
  const request = sequence
  state.value = 'importing'
  try {
    const token = await csrfToken()
    const candidate = selected.value
    const response = await fetch('/api/v1/documents/official-imports', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
      body: JSON.stringify({
        editionId: imported.value.edition.id,
        title: candidate.title,
        sourceType: 'BASE_RULEBOOK',
        officialSourceUrl: candidate.url,
        rightsConfirmed: true,
        startTeaching: true,
        learningGoal: null,
      }),
    })
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('import failed')
    const job = await response.json() as OfficialImportJob
    await waitForOfficialImport(job, request)
  } catch {
    if (request === sequence && state.value === 'importing') state.value = 'error'
  }
}

async function waitForOfficialImport(initial: OfficialImportJob, request: number) {
  let job = initial
  let failures = 0
  while (request === sequence) {
    if (job.stage === 'FAILED') {
      state.value = job.errorCode === 'SOURCE_BROWSER_REQUIRED' ? 'browser-required' : 'error'
      return
    }
    if (job.stage === 'COMPLETED') {
      if (!job.documentVersionId || !imported.value) throw new Error('completed import has no document version')
      if (job.teachingHandoffState === 'LAUNCHED' && job.teachingPreparationRunId) {
        state.value = 'success'
        return
      }
      if (job.teachingHandoffState === 'FAILED' || job.teachingHandoffState === 'NOT_REQUESTED') {
        throw new Error('teaching handoff failed')
      }
    }
    try {
      const response = await fetch(`/api/v1/documents/official-imports/${encodeURIComponent(job.id)}`, { credentials: 'include' })
      if (request !== sequence) return
      if (response.status === 401 || response.status === 403) return requireLogin()
      if (!response.ok) throw new Error('import status unavailable')
      job = await response.json() as OfficialImportJob
      failures = 0
    } catch {
      failures += 1
      if (failures >= 3) throw new Error('import status unavailable')
    }
    const handoffSettled = job.stage === 'COMPLETED'
      && ['LAUNCHED', 'FAILED', 'NOT_REQUESTED'].includes(job.teachingHandoffState)
    if (job.stage !== 'FAILED' && !handoffSettled) {
      await new Promise(resolve => setTimeout(resolve, failures ? 1500 : 750))
    }
  }
}

watch(() => props.game.bggId, prepare)
onMounted(prepare)
onBeforeUnmount(() => {
  sequence += 1
  if (findingClock) clearInterval(findingClock)
})
</script>

<template>
  <aside class="mt-6 overflow-hidden rounded-2xl border border-copper/25 bg-copper/5" aria-live="polite">
    <div class="flex items-start gap-4 border-b border-copper/15 p-4 sm:p-5">
      <img v-if="game.thumbnailUrl" :src="game.thumbnailUrl" :alt="game.name" class="h-20 w-16 shrink-0 rounded-lg bg-paper object-contain" referrerpolicy="no-referrer">
      <div class="min-w-0 flex-1">
        <p class="text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ copy.eyebrow }}</p>
        <h3 class="mt-1 font-display text-xl font-semibold">{{ copy.title }}</h3>
        <p v-if="game.nameLocalized" class="mt-1 text-xs text-ink/45">{{ game.originalName }}</p>
      </div>
      <button type="button" class="min-h-11 shrink-0 text-sm font-semibold text-ink/50 underline" @click="emit('close')">{{ copy.close }}</button>
    </div>

    <div class="p-4 sm:p-5">
      <p v-if="state === 'preparing' || state === 'finding' || state === 'importing'" class="flex items-center gap-3 text-sm text-ink/65" role="status">
        <span class="size-2 animate-pulse rounded-full bg-copper" aria-hidden="true" />
        {{ state === 'preparing' ? copy.preparing : state === 'finding' ? findingText : copy.importing }}
      </p>

      <template v-else-if="state === 'review'">
        <h4 class="font-display text-lg font-semibold">{{ copy.found }}</h4>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.detail }}</p>
        <ul class="mt-4 stack-y-md">
          <li v-for="candidate in candidates" :key="candidate.url" class="rounded-xl border bg-paper p-4" :class="selected?.url === candidate.url ? 'border-copper/60 ring-2 ring-copper/10' : 'border-ink/10'">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="font-semibold">{{ candidate.title }}</p>
                <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all text-xs font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
                <p class="mt-2 text-xs leading-5 text-ink/55">{{ copy.publisher }}：{{ candidate.publisher || copy.unknown }} · {{ copy.language }}：{{ candidate.language || copy.unknown }} · {{ copy.edition }}：{{ candidate.edition || copy.unknown }}</p>
                <p class="mt-1 text-xs font-semibold" :class="candidate.sourceType === 'PUBLIC_WEB' ? 'text-amber-700' : 'text-emerald-700'">{{ copy.sources[candidate.sourceType] }}</p>
                <p class="mt-1 text-xs text-ink/45">{{ candidate.acquisitionMode === 'DIRECT_PDF' ? copy.direct : candidate.acquisitionMode === 'IMAGE_GALLERY' ? copy.gallery : copy.page }}</p>
              </div>
              <button type="button" class="min-h-11 shrink-0 rounded-lg border border-copper/35 px-4 text-sm font-semibold text-copper" :aria-pressed="candidate.acquisitionMode !== 'SOURCE_PAGE' ? selected?.url === candidate.url : undefined" @click="choose(candidate)">{{ candidate.acquisitionMode === 'SOURCE_PAGE' ? copy.open : selected?.url === candidate.url ? copy.selected : copy.choose }}</button>
            </div>
          </li>
        </ul>
        <div v-if="openedSource" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4 text-sm leading-6 text-ink/65" role="status">
          <p>{{ copy.sourcePageHandoff }}</p>
          <a :href="openedSource.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
          <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
        </div>
        <div v-if="selected" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/5 p-4">
          <label class="flex items-start gap-3 text-sm leading-6 text-ink/65">
            <input v-model="consent" type="checkbox" class="mt-1 size-5 shrink-0 accent-indigo">
            <span>{{ copy.consent }}</span>
          </label>
          <button type="button" :disabled="!canImport" class="mt-3 min-h-11 rounded-lg bg-indigo px-5 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40" @click="importAndTeach">{{ copy.import }}</button>
        </div>
      </template>

      <div v-else-if="state === 'login'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.login }}</p>
        <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.loginAction }} →</RouterLink>
      </div>

      <div v-else-if="state === 'unavailable'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.unavailable }}</p>
        <RouterLink :to="manualRoute" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
      </div>

      <div v-else-if="state === 'browser-required'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.browserRequired }}</p>
        <a v-if="selected" :href="selected.url" target="_blank" rel="noopener noreferrer" class="mt-3 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.browserAction }} ↗</a>
        <RouterLink :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }} →</RouterLink>
      </div>

      <div v-else-if="state === 'success'" class="text-sm leading-6 text-ink/65" role="status">
        <p>{{ copy.success }}</p>
        <div class="mt-3 flex flex-wrap gap-x-5 gap-y-2">
          <RouterLink to="/catalog" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.catalog }} →</RouterLink>
          <RouterLink to="/lessons" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.lessons }} →</RouterLink>
        </div>
      </div>

      <div v-else class="text-sm text-danger" role="alert">
        <p>{{ copy.error }}</p>
        <button type="button" class="mt-2 min-h-11 font-semibold underline" @click="prepare">{{ copy.retry }}</button>
        <RouterLink v-if="imported" :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }}</RouterLink>
      </div>
    </div>
  </aside>
</template>
