<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import type { RecommendationGame, RecommendationProfile } from '@/components/gameRecommendationTypes'
import { notifyLoginRequired } from '@/lib/authSession'
import { useLocale } from '@/lib/locale'
import { rememberPendingRulebookLesson } from '@/lib/pendingRulebookLesson'

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
  acquisitionMode: 'DIRECT_PDF' | 'SOURCE_PAGE'
}

interface RulebookCandidateResponse {
  configured: boolean
  candidates: RulebookCandidate[]
}

interface OfficialImportResponse {
  duplicate: boolean
  version: { id: string; status: string }
}

interface CsrfResponse { headerName: string; token: string }

const props = defineProps<{ game: RecommendationGame; profile: RecommendationProfile }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const { locale } = useLocale()

const copy = computed(() => locale.value === 'zh-CN' ? {
  eyebrow: '从推荐到讲解', title: `已选《${props.game.name}》`, preparing: '正在保存桌游并让 Agent 寻找可审阅的规则书…',
  finding: '桌游已保存，Agent 正在检索出版社、BGG 和可信规则库（已等待 {seconds} 秒，通常几秒，偶尔约 30 秒）…', found: '选择一份规则书', detail: 'Agent 会优先出版社来源，也会保留 BGG 与可信规则库结果。请核对语言和版本；来源页会在新窗口打开，PDF 直链才能直接下载。',
  sources: { PUBLISHER: '出版社 / 权利方来源', TRUSTED_REPOSITORY: '可信规则库', COMMUNITY_PLATFORM: 'BGG 社区文件来源', PUBLIC_WEB: '公开 PDF（请重点核对）' },
  direct: '可直接核验并下载', page: '来源页，需要继续查找文件', publisher: '发布者', language: '语言', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择', open: '打开来源页',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  import: '下载规则书并生成讲解', importing: '正在安全下载并准备讲解…', manual: '改用公开链接或本地上传',
  unavailable: '当前没有找到可审阅的规则书来源。你仍可粘贴公开 PDF 链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', retry: '重试', close: '换一款',
} : {
  eyebrow: 'From recommendation to guide', title: `${props.game.name} selected`, preparing: 'Saving the game and asking the Agent to find reviewable rulebooks…',
  finding: 'Game saved. The Agent is searching publishers, BGG, and trusted repositories ({seconds}s elapsed; usually a few seconds, occasionally about 30s)…', found: 'Choose a rulebook', detail: 'The Agent prioritizes publisher sources while preserving useful BGG and trusted-repository results. Review language and edition; source pages open separately and only direct PDFs can be downloaded.',
  sources: { PUBLISHER: 'Publisher / rights-holder', TRUSTED_REPOSITORY: 'Trusted rules repository', COMMUNITY_PLATFORM: 'BGG community file source', PUBLIC_WEB: 'Public PDF (review carefully)' },
  direct: 'Direct PDF ready for verification', page: 'Source page; continue there', publisher: 'Provider', language: 'Language', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected', open: 'Open source page',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  import: 'Download and generate guide', importing: 'Downloading safely and preparing the guide…', manual: 'Use a public URL or local upload',
  unavailable: 'No reviewable rulebook source was found. You can still paste a public PDF URL or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', retry: 'Retry', close: 'Choose another game',
})

const imported = ref<ImportedGame | null>(null)
const candidates = ref<RulebookCandidate[]>([])
const selected = ref<RulebookCandidate | null>(null)
const consent = ref(false)
const state = ref<'preparing' | 'finding' | 'review' | 'unavailable' | 'login' | 'error' | 'importing'>('preparing')
const findingSeconds = ref(0)
let csrf: CsrfResponse | null = null
let sequence = 0
let findingClock: ReturnType<typeof setInterval> | null = null

const canImport = computed(() => selected.value?.acquisitionMode === 'DIRECT_PDF' && consent.value && state.value === 'review')
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
    window.open(candidate.url, '_blank', 'noopener,noreferrer')
    return
  }
  selected.value = candidate
  consent.value = false
}

async function importAndTeach() {
  if (!canImport.value || !imported.value || !selected.value) return
  state.value = 'importing'
  try {
    const [token, sessionResponse] = await Promise.all([
      csrfToken(),
      fetch('/api/auth/session', { credentials: 'include' }),
    ])
    if (sessionResponse.status === 401 || sessionResponse.status === 403) return requireLogin()
    if (!sessionResponse.ok) throw new Error('session unavailable')
    const session = await sessionResponse.json() as { username?: unknown }
    if (typeof session.username !== 'string' || !session.username.trim()) return requireLogin()
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
      }),
    })
    if (response.status === 401 || response.status === 403) return requireLogin()
    if (!response.ok) throw new Error('import failed')
    const result = await response.json() as OfficialImportResponse
    const players = props.profile.players ?? 4
    rememberPendingRulebookLesson(localStorage, session.username.trim(), {
      versionId: result.version.id,
      editionId: imported.value.edition.id,
      playerCount: players,
      beginnerCount: players,
      durationMinutes: 25,
    })
    await router.push(manualRoute.value)
  } catch {
    state.value = 'error'
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
                <p class="mt-1 text-xs text-ink/45">{{ candidate.acquisitionMode === 'DIRECT_PDF' ? copy.direct : copy.page }}</p>
              </div>
              <button type="button" class="min-h-11 shrink-0 rounded-lg border border-copper/35 px-4 text-sm font-semibold text-copper" :aria-pressed="candidate.acquisitionMode === 'DIRECT_PDF' ? selected?.url === candidate.url : undefined" @click="choose(candidate)">{{ candidate.acquisitionMode === 'SOURCE_PAGE' ? copy.open : selected?.url === candidate.url ? copy.selected : copy.choose }}</button>
            </div>
          </li>
        </ul>
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

      <div v-else class="text-sm text-danger" role="alert">
        <p>{{ copy.error }}</p>
        <button type="button" class="mt-2 min-h-11 font-semibold underline" @click="prepare">{{ copy.retry }}</button>
        <RouterLink v-if="imported" :to="manualRoute" class="ml-4 inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.manual }}</RouterLink>
      </div>
    </div>
  </aside>
</template>
