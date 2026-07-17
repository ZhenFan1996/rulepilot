<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

interface CsrfResponse {
  headerName: string
  token: string
}

interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
}

interface EditionOption {
  id: string
  label: string
}

interface DocumentResponse {
  document: {
    id: string
    title: string
    sourceType: string
    createdBy: string
  }
  latestVersion: {
    id: string
    versionNumber: number
    originalFilename: string
    checksum: string
    size: number
    status: string
  }
}

const router = useRouter()
const games = ref<GameResponse[]>([])
const editionId = ref('')
const documents = ref<DocumentResponse[]>([])
const title = ref('')
const sourceType = ref('BASE_RULEBOOK')
const file = ref<File | null>(null)
const loading = ref(true)
const uploading = ref(false)
const message = ref('')
const errorMessage = ref('')
const previewVersionId = ref('')
const pages = ref<Array<{ pageNumber: number; text: string; characterCount: number }>>([])

const editionOptions = computed<EditionOption[]>(() =>
  games.value.flatMap((entry) =>
    entry.editions.map((edition) => ({
      id: edition.id,
      label: `${entry.game.name} · ${edition.name} · ${edition.language}`,
    })),
  ),
)

const sourceTypes = [
  ['BASE_RULEBOOK', '基础规则书'],
  ['EXPANSION_RULEBOOK', '扩展规则书'],
  ['OFFICIAL_FAQ', '官方 FAQ'],
  ['OFFICIAL_ERRATA', '官方勘误'],
  ['OFFICIAL_PLAYER_AID', '官方玩家辅助'],
] as const

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('请先登录。')
  }
  return response
}

async function loadDocuments() {
  documents.value = []
  if (!editionId.value) return
  const response = await checkedFetch(`/api/v1/editions/${editionId.value}/documents`)
  if (!response.ok) throw new Error('无法读取该版本的规则资料。')
  documents.value = (await response.json()) as DocumentResponse[]
}

async function previewPages(versionId: string) {
  errorMessage.value = ''
  try {
    const response = await checkedFetch(`/api/v1/document-versions/${versionId}/pages`)
    if (!response.ok) throw new Error('无法读取页级预览。')
    pages.value = (await response.json()) as typeof pages.value
    previewVersionId.value = versionId
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取页级预览。'
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await checkedFetch('/api/v1/games')
    if (!response.ok) throw new Error('无法读取游戏目录。')
    games.value = (await response.json()) as GameResponse[]
    editionId.value = editionOptions.value[0]?.id ?? ''
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载失败。'
  } finally {
    loading.value = false
  }
}

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0] ?? null
}

async function upload() {
  if (!editionId.value || !file.value) return
  uploading.value = true
  message.value = ''
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    const csrf = (await csrfResponse.json()) as CsrfResponse
    const form = new FormData()
    form.append('title', title.value)
    form.append('sourceType', sourceType.value)
    form.append('file', file.value)
    const response = await checkedFetch(`/api/v1/editions/${editionId.value}/documents`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
      body: form,
    })
    if (response.status === 403) throw new Error('需要 EDITOR 或 ADMIN 权限才能上传规则书。')
    if (!response.ok) throw new Error('上传失败，请确认文件是 50 MiB 以内的 PDF。')
    const result = (await response.json()) as { duplicate: boolean; version: { status: string } }
    message.value = result.duplicate ? '这份文件已上传，已保留现有版本。' : '规则书已安全保存，等待解析。'
    title.value = ''
    file.value = null
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败。'
  } finally {
    uploading.value = false
  }
}

watch(editionId, async () => {
  if (!loading.value) {
    try {
      await loadDocuments()
    } catch (error) {
      errorMessage.value = error instanceof Error ? error.message : '加载失败。'
    }
  }
})

onMounted(load)
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/70 backdrop-blur">
      <div class="mx-auto flex max-w-7xl items-center justify-between px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold">RulePilot</RouterLink>
        <RouterLink :to="{ name: 'catalog' }" class="text-sm font-semibold text-indigo">管理游戏目录</RouterLink>
      </div>
    </header>

    <div class="mx-auto grid max-w-7xl gap-8 px-5 py-10 sm:px-8 lg:grid-cols-[0.9fr_1.1fr]">
      <section>
        <p class="eyebrow">IMPORT RULEBOOK</p>
        <h1 class="mt-4 font-display text-4xl font-semibold tracking-tight sm:text-5xl">导入第一份规则资料</h1>
        <p class="mt-5 max-w-xl leading-7 text-ink/60">先绑定准确的游戏版本和资料类型。文件保存后会进入页级解析，再组织成从 setup 到计分的完整讲解。</p>

        <form class="mt-8 space-y-4 rounded-3xl border border-ink/10 bg-paper p-6" @submit.prevent="upload">
          <label class="block text-sm font-semibold">
            游戏版本
            <select v-model="editionId" required class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
              <option value="" disabled>请先在目录中创建游戏版本</option>
              <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
            </select>
          </label>
          <label class="block text-sm font-semibold">
            资料标题
            <input v-model="title" required maxlength="160" placeholder="例如：基础规则书 2026 中文版" class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
          </label>
          <label class="block text-sm font-semibold">
            资料类型
            <select v-model="sourceType" class="mt-2 w-full rounded-2xl border border-ink/15 bg-canvas px-4 py-3">
              <option v-for="entry in sourceTypes" :key="entry[0]" :value="entry[0]">{{ entry[1] }}</option>
            </select>
          </label>
          <label class="block text-sm font-semibold">
            PDF 文件
            <input required accept="application/pdf,.pdf" type="file" class="mt-2 block w-full rounded-2xl border border-dashed border-ink/20 bg-canvas px-4 py-6 text-sm" @change="selectFile">
          </label>
          <button :disabled="uploading || !editionId" class="w-full rounded-2xl bg-copper px-5 py-3 font-semibold text-white disabled:opacity-40">
            {{ uploading ? '正在上传…' : '保存规则书' }}
          </button>
        </form>

        <p v-if="message" class="mt-4 rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
      </section>

      <section>
        <h2 class="font-display text-2xl font-semibold">当前版本的规则资料</h2>
        <div v-if="loading" class="mt-5 rounded-3xl border border-ink/10 bg-paper p-8 text-ink/50">正在加载…</div>
        <div v-else-if="!editionId" class="mt-5 rounded-3xl border border-dashed border-ink/20 p-8 text-center text-ink/55">请先创建游戏版本，再回来导入规则书。</div>
        <div v-else-if="documents.length === 0" class="mt-5 rounded-3xl border border-dashed border-ink/20 p-8 text-center text-ink/55">这个版本还没有规则资料。</div>
        <ul v-else class="mt-5 space-y-4">
          <li v-for="entry in documents" :key="entry.document.id" class="rounded-3xl border border-ink/10 bg-paper p-5">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="font-display text-xl font-semibold">{{ entry.document.title }}</p>
                <p class="mt-2 text-sm text-ink/55">{{ entry.latestVersion.originalFilename }} · v{{ entry.latestVersion.versionNumber }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB</p>
              </div>
              <span class="rounded-full bg-indigo/10 px-3 py-1.5 text-xs font-semibold text-indigo">{{ entry.latestVersion.status }}</span>
            </div>
            <p class="mt-4 truncate font-mono text-xs text-ink/35" :title="entry.latestVersion.checksum">SHA-256 {{ entry.latestVersion.checksum }}</p>
            <button class="mt-4 text-sm font-semibold text-indigo underline decoration-indigo/30 underline-offset-4" @click="previewPages(entry.latestVersion.id)">查看页级文字</button>
            <div v-if="previewVersionId === entry.latestVersion.id" class="mt-5 space-y-3 border-t border-ink/10 pt-5">
              <p v-if="pages.length === 0" class="text-sm text-ink/45">尚未提取到页面文字。</p>
              <article v-for="page in pages" :key="page.pageNumber" class="rounded-2xl bg-canvas p-4">
                <div class="flex items-center justify-between text-xs font-semibold text-ink/45">
                  <span>第 {{ page.pageNumber }} 页</span>
                  <span>{{ page.characterCount }} 字符</span>
                </div>
                <pre class="mt-3 max-h-56 overflow-auto whitespace-pre-wrap font-sans text-sm leading-6 text-ink/70">{{ page.text || '此页没有可提取文字，后续可进入 OCR。' }}</pre>
              </article>
            </div>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>
