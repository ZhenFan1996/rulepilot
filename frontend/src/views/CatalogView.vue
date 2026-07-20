<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface CsrfResponse {
  headerName: string
  token: string
}

interface GameDetails {
  id: string
  name: string
}

interface EditionDetails {
  id: string
  gameId: string
  name: string
  language: string
  publicationYear: number | null
}

interface ExpansionDetails {
  id: string
  gameId: string
  name: string
  compatibleEditionIds: string[]
}

interface GameResponse {
  game: GameDetails
  editions: EditionDetails[]
  expansions: ExpansionDetails[]
  bggMetadata: BggMetadata | null
}

interface BggMetadata {
  bggId: number
  description: string
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  bggUrl: string
}

interface BggSearchResult {
  bggId: number
  name: string
  publicationYear: number | null
  bggUrl: string
}

interface BggImportResponse {
  game: GameDetails
  edition: EditionDetails
  bggId: number
  description: string
  thumbnailUrl: string
  minPlayers: number | null
  maxPlayers: number | null
  playingTimeMinutes: number | null
  minimumAge: number | null
  bggUrl: string
  alreadyImported: boolean
}

const router = useRouter()
const games = ref<GameResponse[]>([])
const selectedGameId = ref('')
const csrf = ref<CsrfResponse | null>(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')
const bggConfigured = ref<boolean | null>(null)
const bggQuery = ref('')
const bggResults = ref<BggSearchResult[]>([])
const bggSearching = ref(false)
const bggImportingId = ref<number | null>(null)
const bggError = ref('')
const importedBgg = ref<BggImportResponse | null>(null)

const gameName = ref('')
const editionName = ref('')
const editionLanguage = ref('zh-CN')
const editionYear = ref<number | null>(null)
const expansionName = ref('')
const compatibleEditionIds = ref<string[]>([])

const selectedGame = computed(() => games.value.find((entry) => entry.game.id === selectedGameId.value))

async function loadCatalog() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/v1/games', { credentials: 'include' })
    if (response.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (!response.ok) throw new Error('无法读取游戏目录。')
    games.value = (await response.json()) as GameResponse[]
    if (!selectedGameId.value && games.value.length > 0) selectedGameId.value = games.value[0]!.game.id
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取游戏目录。'
  } finally {
    loading.value = false
  }
}

async function loadBggStatus() {
  try {
    const response = await fetch('/api/v1/bgg/status', { credentials: 'include' })
    if (response.status === 401) return
    if (!response.ok) throw new Error()
    bggConfigured.value = ((await response.json()) as { configured: boolean }).configured
  } catch {
    bggConfigured.value = false
  }
}

async function searchBgg() {
  if (bggQuery.value.trim().length < 2) return
  bggSearching.value = true
  bggError.value = ''
  importedBgg.value = null
  try {
    const response = await fetch(`/api/v1/bgg/search?q=${encodeURIComponent(bggQuery.value.trim())}`, { credentials: 'include' })
    if (response.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (response.status === 503) throw new Error('后端尚未配置 BGG Application Token。')
    if (!response.ok) throw new Error('BGG 暂时无法完成搜索，请稍后重试。')
    bggResults.value = (await response.json()) as BggSearchResult[]
  } catch (error) {
    bggError.value = error instanceof Error ? error.message : 'BGG 搜索失败。'
  } finally {
    bggSearching.value = false
  }
}

async function importBggGame(result: BggSearchResult) {
  bggImportingId.value = result.bggId
  bggError.value = ''
  try {
    const imported = await post<BggImportResponse>(`/api/v1/bgg/games/${result.bggId}/import`, {})
    importedBgg.value = imported
    selectedGameId.value = imported.game.id
    message.value = imported.alreadyImported ? `${imported.game.name} 已在目录中` : `已从 BGG 导入 ${imported.game.name}`
    await loadCatalog()
  } catch (error) {
    bggError.value = error instanceof Error ? error.message : 'BGG 导入失败。'
  } finally {
    bggImportingId.value = null
  }
}

async function csrfToken() {
  if (csrf.value) return csrf.value
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('无法建立安全会话。')
  csrf.value = (await response.json()) as CsrfResponse
  return csrf.value
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const token = await csrfToken()
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [token.headerName]: token.token },
    body: JSON.stringify(body),
  })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('登录已失效。')
  }
  if (response.status === 403) throw new Error('当前账户不能修改游戏库。')
  if (!response.ok) throw new Error('提交内容无效或名称已经存在。')
  return (await response.json()) as T
}

async function runSave(action: () => Promise<void>) {
  saving.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    await action()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '保存失败。'
  } finally {
    saving.value = false
  }
}

async function createGame() {
  await runSave(async () => {
    const created = await post<GameDetails>('/api/v1/games', { name: gameName.value })
    gameName.value = ''
    selectedGameId.value = created.id
    message.value = `已创建 ${created.name}`
    await loadCatalog()
  })
}

async function createEdition() {
  if (!selectedGameId.value) return
  await runSave(async () => {
    await post<EditionDetails>(`/api/v1/games/${selectedGameId.value}/editions`, {
      name: editionName.value,
      language: editionLanguage.value,
      publicationYear: editionYear.value,
    })
    editionName.value = ''
    editionYear.value = null
    message.value = '版本已添加'
    await loadCatalog()
  })
}

async function createExpansion() {
  if (!selectedGameId.value) return
  await runSave(async () => {
    await post<ExpansionDetails>(`/api/v1/games/${selectedGameId.value}/expansions`, {
      name: expansionName.value,
      compatibleEditionIds: compatibleEditionIds.value,
    })
    expansionName.value = ''
    compatibleEditionIds.value = []
    message.value = '扩展已添加'
    await loadCatalog()
  })
}

function hideBrokenImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  image.hidden = true
}

onMounted(() => Promise.all([loadCatalog(), loadBggStatus()]))
</script>

<template>
  <AppShell>
    <div class="mx-auto grid max-w-6xl gap-10 px-5 py-10 sm:px-8 lg:grid-cols-[0.82fr_1.18fr] lg:px-12 lg:py-14">
      <section>
        <p class="text-sm font-medium text-copper">游戏库</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">管理游戏和版本</h1>
        <p class="mt-4 max-w-xl leading-7 text-ink/55">从 BGG 导入或手动记下游戏版本。你可以从这里开始，也可以先上传规则书，之后再把两者关联起来。</p>

        <form class="mt-8 rounded-xl border border-ink/10 bg-paper p-5" @submit.prevent="createGame">
          <h2 class="font-display text-xl font-semibold">添加游戏</h2>
          <div class="mt-4 flex gap-3">
            <input v-model="gameName" required maxlength="120" placeholder="例如：Wingspan" class="min-w-0 flex-1 rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo">
            <button :disabled="saving" class="rounded-lg bg-indigo px-5 font-semibold text-white disabled:opacity-50">创建</button>
          </div>
        </form>

        <section class="mt-5 rounded-xl border border-ink/10 bg-paper p-5">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="text-xs font-semibold text-copper">BoardGameGeek</p>
              <h2 class="mt-1 font-display text-xl font-semibold">从 BGG 查找</h2>
            </div>
            <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer" class="flex items-center gap-2 text-xs font-medium text-ink/45" aria-label="Powered by BoardGameGeek">
              <img src="https://cf.geekdo-images.com/HZy35cmzmmyV9BarSuk6ug__small/img/gbE7sulIurZE_Tx8EQJXnZSKI6w%3D/fit-in/200x150/filters%3Astrip_icc%28%29/pic7779581.png" alt="" class="h-8 w-auto" referrerpolicy="no-referrer" @error="hideBrokenImage">
              <span>Powered by BGG</span>
            </a>
          </div>

          <div v-if="bggConfigured === false" class="mt-4 rounded-lg bg-copper/8 p-4 text-sm leading-6 text-ink/65">
            需要先在 BGG 注册应用，并在 `.env` 中填写 `BGG_API_TOKEN`。
            <a href="https://boardgamegeek.com/applications" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo underline">前往 BGG Applications</a>
          </div>

          <form v-else class="mt-4 flex gap-3" @submit.prevent="searchBgg">
            <input v-model="bggQuery" required minlength="2" maxlength="120" placeholder="输入英文或原版游戏名" class="min-w-0 flex-1 rounded-lg border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-copper">
            <button :disabled="bggSearching" class="rounded-lg bg-copper px-5 font-semibold text-white disabled:opacity-50">{{ bggSearching ? '搜索中…' : '搜索' }}</button>
          </form>

          <p v-if="bggError" class="mt-4 text-sm text-red-700" role="alert">{{ bggError }}</p>
          <p v-if="!bggSearching && bggQuery && bggResults.length === 0 && !bggError" class="mt-4 text-sm text-ink/45">没有匹配结果，请尝试原版名称或减少关键词。</p>

          <ul v-if="bggResults.length" class="mt-4 max-h-72 space-y-2 overflow-y-auto pr-1">
            <li v-for="result in bggResults" :key="result.bggId" class="flex items-center justify-between gap-3 rounded-lg border border-ink/8 bg-canvas p-3">
              <div class="min-w-0">
                <a :href="result.bggUrl" target="_blank" rel="noopener noreferrer" class="block truncate font-semibold hover:text-indigo">{{ result.name }}</a>
                <p class="mt-1 text-xs text-ink/45">{{ result.publicationYear ?? '年份未知' }} · BGG #{{ result.bggId }}</p>
              </div>
              <button type="button" :disabled="bggImportingId !== null" class="shrink-0 rounded-xl border border-indigo/20 px-3 py-2 text-sm font-semibold text-indigo disabled:opacity-40" @click="importBggGame(result)">
                {{ bggImportingId === result.bggId ? '读取中…' : '读取并导入' }}
              </button>
            </li>
          </ul>

          <article v-if="importedBgg" class="mt-5 rounded-xl border border-ink/10 bg-canvas p-4 text-ink">
            <div class="flex gap-4">
              <img v-if="importedBgg.thumbnailUrl" :src="importedBgg.thumbnailUrl" :alt="`${importedBgg.game.name} 的 BGG 缩略图`" class="h-24 w-24 rounded-xl object-cover" referrerpolicy="no-referrer">
              <div>
                <h3 class="font-display text-lg font-semibold">{{ importedBgg.game.name }}</h3>
                <p class="mt-2 text-xs text-ink/50">
                  {{ importedBgg.minPlayers ?? '?' }}–{{ importedBgg.maxPlayers ?? '?' }} 人 ·
                  {{ importedBgg.playingTimeMinutes ?? '?' }} 分钟 ·
                  {{ importedBgg.minimumAge ?? '?' }} 岁以上
                </p>
                <a :href="importedBgg.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-2 inline-block text-xs font-semibold text-indigo">查看 BGG 页面 ↗</a>
              </div>
            </div>
            <p v-if="importedBgg.description" class="mt-4 max-h-28 overflow-y-auto text-sm leading-6 text-ink/60">{{ importedBgg.description }}</p>
            <p class="mt-3 text-xs text-ink/40">资料来自 BGG，仅用于识别游戏。</p>
            <RouterLink :to="{ name: 'teach', query: { editionId: importedBgg.edition.id } }" class="mt-4 inline-flex rounded-lg bg-copper px-4 py-2.5 text-sm font-semibold text-white">上传或关联规则书</RouterLink>
          </article>
        </section>

        <p v-if="message" class="mt-4 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
      </section>

      <section class="space-y-6">
        <div v-if="loading" class="rounded-xl border border-ink/10 bg-paper p-8 text-ink/50">正在读取游戏库…</div>
        <div v-else-if="games.length === 0" class="rounded-xl border border-dashed border-ink/20 p-8 text-center text-ink/55">还没有游戏。可以从 BGG 查找，也可以自己添加。</div>
        <template v-else>
          <label class="block text-sm font-semibold">
            当前游戏
            <select v-model="selectedGameId" class="mt-2 w-full rounded-lg border border-ink/15 bg-paper px-4 py-3">
              <option v-for="entry in games" :key="entry.game.id" :value="entry.game.id">{{ entry.game.name }}</option>
            </select>
          </label>

          <div v-if="selectedGame" class="grid gap-6 xl:grid-cols-2">
            <article v-if="selectedGame.bggMetadata" class="rounded-xl border border-ink/10 bg-paper p-5 xl:col-span-2">
              <div class="flex gap-4">
                <img v-if="selectedGame.bggMetadata.thumbnailUrl" :src="selectedGame.bggMetadata.thumbnailUrl" :alt="`${selectedGame.game.name} 的 BGG 缩略图`" class="h-28 w-28 rounded-lg object-cover" referrerpolicy="no-referrer">
                <div>
                  <p class="text-xs font-semibold text-copper">BoardGameGeek</p>
                  <h2 class="mt-2 font-display text-2xl font-semibold">{{ selectedGame.game.name }}</h2>
                  <p class="mt-2 text-sm text-ink/55">
                    {{ selectedGame.bggMetadata.minPlayers ?? '?' }}–{{ selectedGame.bggMetadata.maxPlayers ?? '?' }} 人 ·
                    {{ selectedGame.bggMetadata.playingTimeMinutes ?? '?' }} 分钟 ·
                    {{ selectedGame.bggMetadata.minimumAge ?? '?' }} 岁以上
                  </p>
                  <a :href="selectedGame.bggMetadata.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-3 inline-block text-sm font-semibold text-copper">查看 BGG 原始页面 ↗</a>
                </div>
              </div>
              <p v-if="selectedGame.bggMetadata.description" class="mt-4 max-h-36 overflow-y-auto text-sm leading-6 text-ink/60">{{ selectedGame.bggMetadata.description }}</p>
              <p class="mt-3 text-xs text-ink/40">资料来自 BGG，仅用于识别游戏。</p>
            </article>

            <form class="rounded-xl border border-ink/10 bg-paper p-5" @submit.prevent="createEdition">
              <h2 class="font-display text-xl font-semibold">添加版本</h2>
              <div class="mt-5 space-y-3">
                <input v-model="editionName" required maxlength="120" placeholder="版本名称" class="w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
                <div class="grid grid-cols-2 gap-3">
                  <input v-model="editionLanguage" required placeholder="语言，如 zh-CN" class="rounded-lg border border-ink/15 bg-canvas px-4 py-3">
                  <input v-model="editionYear" type="number" min="1900" max="2200" placeholder="发行年份" class="rounded-lg border border-ink/15 bg-canvas px-4 py-3">
                </div>
                <button :disabled="saving" class="w-full rounded-lg bg-ink px-5 py-3 font-semibold text-canvas disabled:opacity-50">保存版本</button>
              </div>
              <ul class="mt-5 divide-y divide-ink/10 text-sm text-ink/60">
                <li v-for="edition in selectedGame.editions" :key="edition.id" class="flex items-center justify-between gap-3 py-2">
                  <span>{{ edition.name }} · {{ edition.language }}<span v-if="edition.publicationYear"> · {{ edition.publicationYear }}</span></span>
                  <RouterLink :to="{ name: 'teach', query: { editionId: edition.id } }" class="shrink-0 font-semibold text-indigo">上传或关联规则书</RouterLink>
                </li>
              </ul>
            </form>

            <form class="rounded-xl border border-ink/10 bg-paper p-5" @submit.prevent="createExpansion">
              <h2 class="font-display text-xl font-semibold">添加扩展</h2>
              <input v-model="expansionName" required maxlength="120" placeholder="扩展名称" class="mt-5 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
              <fieldset class="mt-4 space-y-2">
                <legend class="text-sm font-semibold">兼容版本</legend>
                <label v-for="edition in selectedGame.editions" :key="edition.id" class="flex items-center gap-2 text-sm text-ink/65">
                  <input v-model="compatibleEditionIds" type="checkbox" :value="edition.id">
                  {{ edition.name }} · {{ edition.language }}
                </label>
                <p v-if="selectedGame.editions.length === 0" class="text-sm text-ink/45">请先添加至少一个版本。</p>
              </fieldset>
              <button :disabled="saving || compatibleEditionIds.length === 0" class="mt-5 w-full rounded-lg bg-copper px-5 py-3 font-semibold text-white disabled:opacity-40">保存扩展</button>
              <ul class="mt-5 space-y-2 text-sm text-ink/60">
                <li v-for="expansion in selectedGame.expansions" :key="expansion.id">{{ expansion.name }} · {{ expansion.compatibleEditionIds.length }} 个兼容版本</li>
              </ul>
            </form>
          </div>
        </template>
      </section>
    </div>
  </AppShell>
</template>
