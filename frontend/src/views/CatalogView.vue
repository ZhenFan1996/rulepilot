<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

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
}

const router = useRouter()
const games = ref<GameResponse[]>([])
const selectedGameId = ref('')
const csrf = ref<CsrfResponse | null>(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')

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
  if (response.status === 403) throw new Error('当前账户只能查看目录，需要 EDITOR 或 ADMIN 权限才能修改。')
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

onMounted(loadCatalog)
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/70 backdrop-blur">
      <div class="mx-auto flex max-w-7xl items-center justify-between px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold">RulePilot</RouterLink>
        <p class="text-sm text-ink/50">游戏、版本与扩展</p>
      </div>
    </header>

    <div class="mx-auto grid max-w-7xl gap-8 px-5 py-10 sm:px-8 lg:grid-cols-[0.85fr_1.15fr]">
      <section>
        <p class="eyebrow">GAME CATALOG</p>
        <h1 class="mt-4 font-display text-4xl font-semibold tracking-tight sm:text-5xl">先确定规则适用范围</h1>
        <p class="mt-5 max-w-xl leading-7 text-ink/60">每份规则书都必须绑定游戏版本和语言；扩展只能关联明确兼容的版本。</p>

        <form class="mt-8 rounded-3xl border border-ink/10 bg-paper p-5" @submit.prevent="createGame">
          <h2 class="font-display text-xl font-semibold">创建游戏</h2>
          <div class="mt-4 flex gap-3">
            <input v-model="gameName" required maxlength="120" placeholder="例如：Wingspan" class="min-w-0 flex-1 rounded-2xl border border-ink/15 bg-canvas px-4 py-3 outline-none focus:border-indigo">
            <button :disabled="saving" class="rounded-2xl bg-indigo px-5 font-semibold text-white disabled:opacity-50">创建</button>
          </div>
        </form>

        <p v-if="message" class="mt-4 rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
      </section>

      <section class="space-y-6">
        <div v-if="loading" class="rounded-3xl border border-ink/10 bg-paper p-8 text-ink/50">正在读取目录…</div>
        <div v-else-if="games.length === 0" class="rounded-3xl border border-dashed border-ink/20 bg-paper/50 p-8 text-center text-ink/55">还没有游戏。使用左侧表单创建第一个目录条目。</div>
        <template v-else>
          <label class="block text-sm font-semibold">
            当前游戏
            <select v-model="selectedGameId" class="mt-2 w-full rounded-2xl border border-ink/15 bg-paper px-4 py-3">
              <option v-for="entry in games" :key="entry.game.id" :value="entry.game.id">{{ entry.game.name }}</option>
            </select>
          </label>

          <div v-if="selectedGame" class="grid gap-6 xl:grid-cols-2">
            <form class="rounded-3xl border border-ink/10 bg-paper p-5" @submit.prevent="createEdition">
              <h2 class="font-display text-xl font-semibold">添加版本</h2>
              <div class="mt-5 space-y-3">
                <input v-model="editionName" required maxlength="120" placeholder="版本名称" class="w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
                <div class="grid grid-cols-2 gap-3">
                  <input v-model="editionLanguage" required placeholder="语言，如 zh-CN" class="rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
                  <input v-model="editionYear" type="number" min="1900" max="2200" placeholder="发行年份" class="rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
                </div>
                <button :disabled="saving" class="w-full rounded-2xl bg-ink px-5 py-3 font-semibold text-canvas disabled:opacity-50">保存版本</button>
              </div>
              <ul class="mt-5 space-y-2 text-sm text-ink/60">
                <li v-for="edition in selectedGame.editions" :key="edition.id">{{ edition.name }} · {{ edition.language }}<span v-if="edition.publicationYear"> · {{ edition.publicationYear }}</span></li>
              </ul>
            </form>

            <form class="rounded-3xl border border-ink/10 bg-paper p-5" @submit.prevent="createExpansion">
              <h2 class="font-display text-xl font-semibold">添加扩展</h2>
              <input v-model="expansionName" required maxlength="120" placeholder="扩展名称" class="mt-5 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
              <fieldset class="mt-4 space-y-2">
                <legend class="text-sm font-semibold">兼容版本</legend>
                <label v-for="edition in selectedGame.editions" :key="edition.id" class="flex items-center gap-2 text-sm text-ink/65">
                  <input v-model="compatibleEditionIds" type="checkbox" :value="edition.id">
                  {{ edition.name }} · {{ edition.language }}
                </label>
                <p v-if="selectedGame.editions.length === 0" class="text-sm text-ink/45">请先添加至少一个版本。</p>
              </fieldset>
              <button :disabled="saving || compatibleEditionIds.length === 0" class="mt-5 w-full rounded-2xl bg-copper px-5 py-3 font-semibold text-white disabled:opacity-40">保存扩展</button>
              <ul class="mt-5 space-y-2 text-sm text-ink/60">
                <li v-for="expansion in selectedGame.expansions" :key="expansion.id">{{ expansion.name }} · {{ expansion.compatibleEditionIds.length }} 个兼容版本</li>
              </ul>
            </form>
          </div>
        </template>
      </section>
    </div>
  </main>
</template>
