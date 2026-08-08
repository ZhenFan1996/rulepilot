<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
  eyebrow: '从推荐到讲解', title: `已选《${props.game.name}》`, preparing: '正在保存桌游并寻找可审阅的官方规则书…',
  finding: '桌游已保存，正在检索出版社来源…', found: '选择一份规则书', detail: '候选来自联网搜索，下载前请核对来源、语言和版本。语言不必与讲解语言一致，后续讲解会本地化。',
  verified: '域名匹配出版社', review: '需要人工核对域名', publisher: '出版社', language: '语言', edition: '版本', unknown: '未标明', choose: '选择这份', selected: '已选择',
  consent: '我确认该链接来自有权提供这份规则书的来源，并授权 RulePilot 下载用于我的个人讲解。',
  import: '下载规则书并生成讲解', importing: '正在安全下载并准备讲解…', manual: '改用官方链接或本地上传',
  unavailable: '当前没有找到可直接确认的官方 PDF。你仍可粘贴出版社链接或上传自己的规则书。',
  login: '登录后即可保留这次选择并继续找规则书。', loginAction: '打开桌游详情并继续',
  error: '这一步暂时没有完成；推荐对话和已选桌游不会受影响。', retry: '重试', close: '换一款',
} : {
  eyebrow: 'From recommendation to guide', title: `${props.game.name} selected`, preparing: 'Saving the game and finding reviewable official rulebooks…',
  finding: 'Game saved. Searching publisher sources…', found: 'Choose a rulebook', detail: 'Candidates come from web search. Review the source, language, and edition before download. The guide can be localized later.',
  verified: 'Domain matches publisher', review: 'Review domain manually', publisher: 'Publisher', language: 'Language', edition: 'Edition', unknown: 'Not stated', choose: 'Choose this one', selected: 'Selected',
  consent: 'I confirm that this source may provide the rulebook and authorize RulePilot to download it for my personal guide.',
  import: 'Download and generate guide', importing: 'Downloading safely and preparing the guide…', manual: 'Use an official URL or local upload',
  unavailable: 'No directly reviewable official PDF was found. You can still paste a publisher URL or upload your own rulebook.',
  login: 'Sign in to keep this selection and continue to its rulebook.', loginAction: 'Open game details and continue',
  error: 'This step did not complete. The conversation and selected game are unaffected.', retry: 'Retry', close: 'Choose another game',
})

const imported = ref<ImportedGame | null>(null)
const candidates = ref<RulebookCandidate[]>([])
const selected = ref<RulebookCandidate | null>(null)
const consent = ref(false)
const state = ref<'preparing' | 'finding' | 'review' | 'unavailable' | 'login' | 'error' | 'importing'>('preparing')
let csrf: CsrfResponse | null = null
let sequence = 0

const canImport = computed(() => selected.value !== null && consent.value && state.value === 'review')
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
  }
}

function choose(candidate: RulebookCandidate) {
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
        {{ state === 'preparing' ? copy.preparing : state === 'finding' ? copy.finding : copy.importing }}
      </p>

      <template v-else-if="state === 'review'">
        <h4 class="font-display text-lg font-semibold">{{ copy.found }}</h4>
        <p class="mt-1 text-xs leading-5 text-ink/50">{{ copy.detail }}</p>
        <ul class="mt-4 space-y-3">
          <li v-for="candidate in candidates" :key="candidate.url" class="rounded-xl border bg-paper p-4" :class="selected?.url === candidate.url ? 'border-copper/60 ring-2 ring-copper/10' : 'border-ink/10'">
            <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="font-semibold">{{ candidate.title }}</p>
                <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all text-xs font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
                <p class="mt-2 text-xs leading-5 text-ink/55">{{ copy.publisher }}：{{ candidate.publisher || copy.unknown }} · {{ copy.language }}：{{ candidate.language || copy.unknown }} · {{ copy.edition }}：{{ candidate.edition || copy.unknown }}</p>
                <p class="mt-1 text-xs font-semibold" :class="candidate.officialDomainVerified ? 'text-emerald-700' : 'text-amber-700'">{{ candidate.officialDomainVerified ? copy.verified : copy.review }}</p>
              </div>
              <button type="button" class="min-h-11 shrink-0 rounded-lg border border-copper/35 px-4 text-sm font-semibold text-copper" :aria-pressed="selected?.url === candidate.url" @click="choose(candidate)">{{ selected?.url === candidate.url ? copy.selected : copy.choose }}</button>
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
