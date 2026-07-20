<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface CsrfResponse { headerName: string; token: string }
interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
}
interface DocumentResponse {
  document: { id: string; gameEditionId: string | null; title: string }
  latestVersion: { id: string; originalFilename: string; size: number; status: string }
}
interface TeachingPlanResponse { id: string }
interface ModelConfigurationResponse {
  providers: Array<{ id: string; configured: boolean; visionCapable: boolean }>
  assignments: { teaching: string; visual: string }
}

const router = useRouter()
const route = useRoute()
const games = ref<GameResponse[]>([])
const editionId = ref('')
const documents = ref<DocumentResponse[]>([])
const file = ref<File | null>(null)
const title = ref('')
const sourceType = ref('BASE_RULEBOOK')
const playerCount = ref(4)
const beginnerCount = ref(4)
const durationMinutes = ref(25)
const loading = ref(true)
const uploading = ref(false)
const preparingVersionId = ref('')
const processingVersionId = ref('')
const assigningDocumentId = ref('')
const assignmentEditionIds = reactive<Record<string, string>>({})
const message = ref('')
const errorMessage = ref('')
const progress = ref<Record<string, { stage: string; percentage: number; processedPages: number }>>({})
const modelConfiguration = ref<ModelConfigurationResponse | null>(null)

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name}${edition.language ? ` · ${edition.language}` : ''}`,
}))))
const canUpload = computed(() => Boolean(file.value && !uploading.value && !preparingVersionId.value))
const visualProvider = computed(() => modelConfiguration.value?.providers.find(
  (provider) => provider.id === modelConfiguration.value?.assignments.visual,
))
const visualVisionCapable = computed(() => visualProvider.value?.visionCapable === true)
const visualProviderLabel = computed(() => ({
  gemini: 'Gemini', openai: 'OpenAI', deepseek: 'DeepSeek', qwen: 'Qwen', compatible: '兼容模型', fake: '内置演示',
}[modelConfiguration.value?.assignments.visual ?? 'fake'] ?? '当前模型'))

function editionLabel(id: string | null) {
  if (!id) return ''
  return editionOptions.value.find((item) => item.id === id)?.label ?? '已关联游戏'
}

async function checkedFetch(path: string, options?: Parameters<typeof fetch>[1]) {
  const response = await fetch(path, { credentials: 'include', ...options })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('请先登录。')
  }
  return response
}

async function csrfToken() {
  const response = await checkedFetch('/api/auth/csrf')
  if (!response.ok) throw new Error('无法建立安全会话。')
  return await response.json() as CsrfResponse
}

async function loadDocuments() {
  const response = await checkedFetch('/api/v1/documents')
  if (!response.ok) throw new Error('无法读取规则书。')
  documents.value = await response.json() as DocumentResponse[]
  for (const entry of documents.value) {
    if (!entry.document.gameEditionId && !assignmentEditionIds[entry.document.id]) {
      assignmentEditionIds[entry.document.id] = editionId.value
    }
  }
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [catalogResponse, modelResponse] = await Promise.all([
      checkedFetch('/api/v1/games'),
      checkedFetch('/api/v1/model-configuration'),
    ])
    if (!catalogResponse.ok) throw new Error('无法读取游戏目录。')
    games.value = await catalogResponse.json() as GameResponse[]
    if (modelResponse.ok) modelConfiguration.value = await modelResponse.json() as ModelConfigurationResponse
    const requestedEdition = typeof route.query.editionId === 'string' ? route.query.editionId : ''
    editionId.value = editionOptions.value.some((item) => item.id === requestedEdition) ? requestedEdition : ''
    await loadDocuments()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载失败。'
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
  return selected.name.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim() || '规则书'
}

async function startLesson(versionId: string) {
  if (beginnerCount.value > playerCount.value) throw new Error('新手人数不能超过玩家人数。')
  preparingVersionId.value = versionId
  message.value = '正在整理讲解顺序…'
  try {
    const csrf = await csrfToken()
    const planResponse = await checkedFetch(`/api/v1/document-versions/${versionId}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        playerCount: playerCount.value,
        beginnerCount: beginnerCount.value,
        durationMinutes: durationMinutes.value,
      }),
    })
    if (!planResponse.ok) throw new Error('暂时无法开始讲解，请确认规则书已经读取完成。')
    const plan = await planResponse.json() as TeachingPlanResponse
    message.value = '目录已经准备好，正在启动后台讲解…'
    const lessonResponse = await checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons`, {
      method: 'POST', headers: { [csrf.headerName]: csrf.token },
    })
    if (!lessonResponse.ok) throw new Error('讲解任务没有启动，请稍后重试。')
    localStorage.setItem('rulepilot:last-plan-id', plan.id)
    await router.push({ name: 'lessons', query: { started: plan.id } })
  } finally {
    preparingVersionId.value = ''
  }
}

function watchProgress(versionId: string) {
  processingVersionId.value = versionId
  const events = new EventSource(`/api/v1/document-versions/${versionId}/progress`, { withCredentials: true })
  events.addEventListener('progress', (event) => {
    const snapshot = JSON.parse((event as MessageEvent<string>).data) as {
      stage: string; percentage: number; processedPages: number; complete: boolean
    }
    progress.value = { ...progress.value, [versionId]: snapshot }
    message.value = `正在读取规则书：${snapshot.percentage}%`
    if (snapshot.complete) {
      events.close()
      processingVersionId.value = ''
      void loadDocuments()
      void startLesson(versionId).catch((error: unknown) => {
        errorMessage.value = error instanceof Error ? error.message : '无法生成讲解。'
      })
    }
  })
  events.onerror = () => events.close()
}

async function uploadRulebook() {
  if (!file.value) return
  uploading.value = true
  message.value = '正在上传规则书…'
  errorMessage.value = ''
  try {
    const selectedFile = file.value
    const csrf = await csrfToken()
    const form = new FormData()
    form.append('title', title.value.trim() || titleFromFile(selectedFile))
    form.append('sourceType', sourceType.value)
    form.append('file', selectedFile)
    const path = editionId.value
      ? `/api/v1/editions/${editionId.value}/documents`
      : '/api/v1/documents'
    const response = await checkedFetch(path, {
      method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form,
    })
    if (!response.ok) throw new Error('上传失败，请确认文件是 50 MiB 以内的 PDF。')
    const result = await response.json() as { duplicate: boolean; version: { id: string; status: string } }
    file.value = null
    title.value = ''
    await loadDocuments()
    if (result.version.status === 'READY') {
      await startLesson(result.version.id)
    } else {
      message.value = result.duplicate ? '已找到这本规则书，继续等待读取完成…' : '上传完成，正在读取页面和图片…'
      watchProgress(result.version.id)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败。'
  } finally {
    uploading.value = false
  }
}

async function assignDocument(entry: DocumentResponse) {
  const targetEditionId = assignmentEditionIds[entry.document.id]
  if (!targetEditionId) return
  assigningDocumentId.value = entry.document.id
  errorMessage.value = ''
  try {
    const csrf = await csrfToken()
    const response = await checkedFetch(`/api/v1/documents/${entry.document.id}/edition`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ editionId: targetEditionId }),
    })
    if (!response.ok) throw new Error('无法关联这个游戏版本。')
    await loadDocuments()
    message.value = `已关联到 ${editionLabel(targetEditionId)}，现在可以开始讲解。`
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '关联失败。'
  } finally {
    assigningDocumentId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <AppShell>
    <main class="mx-auto max-w-5xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <section class="mx-auto max-w-2xl text-center">
        <p class="text-sm font-medium text-copper">导入规则书</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight sm:text-5xl">先把 PDF 放进来</h1>
        <p class="mx-auto mt-4 max-w-xl leading-7 text-ink/55">不需要先创建游戏。上传后会直接生成讲解；如果暂不选择版本，RulePilot 会按规则书标题自动创建。</p>

        <form class="mt-8 rounded-xl border border-ink/10 bg-paper p-5 text-left sm:p-7" @submit.prevent="uploadRulebook">
          <label for="rulebook-file" class="flex min-h-40 cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-ink/25 bg-canvas px-6 py-8 text-center hover:border-copper/60">
            <span class="font-display text-xl font-semibold">{{ file?.name ?? '选择一本 PDF 规则书' }}</span>
            <span class="mt-2 text-sm text-ink/45">{{ file ? '点击可以换一本' : '最大 50 MiB' }}</span>
          </label>
          <input id="rulebook-file" accept="application/pdf,.pdf" type="file" class="sr-only" @change="selectFile">

          <label class="mt-4 block text-sm font-semibold">标题 <span class="font-normal text-ink/40">（可选）</span>
            <input v-model="title" maxlength="160" :placeholder="file ? titleFromFile(file) : '留空则使用 PDF 文件名'" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper">
          </label>

          <label v-if="editionOptions.length" class="mt-4 block text-sm font-semibold">关联游戏 <span class="font-normal text-ink/40">（可稍后）</span>
            <select v-model="editionId" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
              <option value="">让 RulePilot 自动创建</option>
              <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
            </select>
          </label>
          <div v-else class="mt-4 rounded-lg bg-ink/5 px-4 py-3 text-sm leading-6 text-ink/60">
            还没有游戏也没关系，RulePilot 会按规则书标题自动创建。
            <RouterLink :to="{ name: 'catalog' }" class="font-semibold text-indigo underline">也可以先去添加游戏</RouterLink>
          </div>

          <div v-if="modelConfiguration && !visualVisionCapable" class="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
            <p><span class="font-semibold">{{ visualProviderLabel }}目前不读取页面图片。</span>讲解仍可生成并附上原文页，但不会识别组件照片、版图位置或图标。</p>
            <RouterLink :to="{ name: 'model-settings' }" class="mt-1 inline-block font-semibold text-indigo underline underline-offset-2">连接支持图片的 Gemini、OpenAI 或 Qwen VL</RouterLink>
          </div>

          <details class="mt-4 border-t border-ink/10 pt-4">
            <summary class="cursor-pointer text-sm font-semibold text-ink/55">资料类型{{ editionId ? '、人数和讲解时长' : '' }}</summary>
            <div class="mt-4 grid gap-4 sm:grid-cols-3">
              <template v-if="editionId">
                <label class="text-sm font-semibold">玩家<input v-model.number="playerCount" type="number" min="1" max="20" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
                <label class="text-sm font-semibold">新手<input v-model.number="beginnerCount" type="number" min="0" :max="playerCount" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
                <label class="text-sm font-semibold">分钟<input v-model.number="durationMinutes" type="number" min="2" max="180" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
              </template>
              <label class="text-sm font-semibold sm:col-span-3">资料类型
                <select v-model="sourceType" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5">
                  <option value="BASE_RULEBOOK">基础规则书</option>
                  <option value="EXPANSION_RULEBOOK">扩展规则书</option>
                  <option value="OFFICIAL_FAQ">官方 FAQ</option>
                  <option value="OFFICIAL_ERRATA">官方勘误</option>
                </select>
              </label>
            </div>
          </details>

          <button :disabled="!canUpload" class="mt-5 w-full rounded-lg bg-copper px-5 py-3.5 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40">
            {{ preparingVersionId ? '正在启动讲解…' : uploading ? '正在上传…' : '上传并开始讲解' }}
          </button>
        </form>

        <p v-if="message" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <div v-if="processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
          <div class="h-full bg-copper transition-all" :style="{ width: `${progress[processingVersionId]?.percentage ?? 0}%` }" />
        </div>
      </section>

      <section class="mt-14 border-t border-ink/10 pt-8">
        <div class="flex items-center justify-between gap-4">
          <div>
            <h2 class="font-display text-2xl font-semibold">我的规则书</h2>
            <p class="mt-1 text-sm text-ink/45">没有归属的规则书也会留在这里，随时可以关联游戏。</p>
          </div>
          <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">管理游戏</RouterLink>
        </div>
        <p v-if="loading" class="mt-5 text-sm text-ink/45">正在读取…</p>
        <div v-else-if="documents.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center">
          <p class="font-semibold">还没有规则书</p>
          <p class="mt-2 text-sm text-ink/45">直接在上方选一个 PDF，不用先准备其他资料。</p>
        </div>
        <ul v-else class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in documents" :key="entry.document.id" class="py-5">
            <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <div class="min-w-0">
                <p class="truncate font-semibold">{{ entry.document.title }}</p>
                <p class="mt-1 text-sm text-ink/45">
                  {{ entry.latestVersion.status }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB
                  <span v-if="entry.document.gameEditionId"> · {{ editionLabel(entry.document.gameEditionId) }}</span>
                  <span v-else> · 讲解时自动创建游戏</span>
                </p>
              </div>
              <button v-if="entry.latestVersion.status === 'READY'" :disabled="Boolean(preparingVersionId)" class="shrink-0 rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="startLesson(entry.latestVersion.id).catch((error: unknown) => errorMessage = error instanceof Error ? error.message : '无法生成讲解。')">开始讲解</button>
            </div>
            <div v-if="!entry.document.gameEditionId" class="mt-4 flex flex-col gap-3 rounded-lg bg-ink/5 p-3 sm:flex-row">
              <template v-if="editionOptions.length">
                <select v-model="assignmentEditionIds[entry.document.id]" class="min-w-0 flex-1 rounded-lg border border-ink/15 bg-paper px-3 py-2.5 text-sm">
                  <option value="">选择游戏版本</option>
                  <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
                </select>
                <button :disabled="!assignmentEditionIds[entry.document.id] || Boolean(assigningDocumentId)" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40" @click="assignDocument(entry)">{{ assigningDocumentId === entry.document.id ? '正在关联…' : '关联游戏' }}</button>
              </template>
              <RouterLink v-else :to="{ name: 'catalog' }" class="py-2 text-center text-sm font-semibold text-indigo">添加一个游戏后再回来关联 →</RouterLink>
            </div>
          </li>
        </ul>
      </section>
    </main>
  </AppShell>
</template>
