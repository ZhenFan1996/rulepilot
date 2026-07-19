<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface CsrfResponse { headerName: string; token: string }
interface GameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string; language: string }>
}
interface DocumentResponse {
  document: { id: string; title: string }
  latestVersion: { id: string; originalFilename: string; size: number; status: string }
}
interface TeachingPlanResponse { id: string }

const router = useRouter()
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
const message = ref('')
const errorMessage = ref('')
const progress = ref<Record<string, { stage: string; percentage: number; processedPages: number }>>({})

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name}${edition.language ? ` · ${edition.language}` : ''}`,
}))))
const selectedEdition = computed(() => editionOptions.value.find((item) => item.id === editionId.value))
const canUpload = computed(() => Boolean(file.value && editionId.value && !uploading.value && !preparingVersionId.value))

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
  documents.value = []
  if (!editionId.value) return
  const response = await checkedFetch(`/api/v1/editions/${editionId.value}/documents`)
  if (!response.ok) throw new Error('无法读取规则书。')
  documents.value = await response.json() as DocumentResponse[]
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await checkedFetch('/api/v1/games')
    if (!response.ok) throw new Error('无法读取游戏目录。')
    games.value = await response.json() as GameResponse[]
    editionId.value = editionOptions.value[0]?.id ?? ''
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
  if (beginnerCount.value > playerCount.value) {
    throw new Error('新手人数不能超过玩家人数。')
  }
  preparingVersionId.value = versionId
  message.value = '规则书已经读完，正在整理讲解顺序…'
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
    if (!planResponse.ok) throw new Error('规则书已读取，但讲解目录生成失败。')
    const plan = await planResponse.json() as TeachingPlanResponse
    message.value = '目录已经准备好，正在逐节核对并生成讲解…'
    const lessonResponse = await checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!lessonResponse.ok) throw new Error('讲解生成没有完成，可以稍后从“我的讲解”继续。')
    localStorage.setItem('rulepilot:last-plan-id', plan.id)
    await router.push({ name: 'lesson', params: { planId: plan.id } })
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

async function uploadAndTeach() {
  if (!file.value || !editionId.value) return
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
    const response = await checkedFetch(`/api/v1/editions/${editionId.value}/documents`, {
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

watch(editionId, () => {
  if (!loading.value) void loadDocuments().catch((error: unknown) => {
    errorMessage.value = error instanceof Error ? error.message : '无法读取规则书。'
  })
})
onMounted(load)
</script>

<template>
  <AppShell>
    <main class="mx-auto max-w-5xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <section class="mx-auto max-w-2xl text-center">
        <p class="text-sm font-medium text-copper">开始一份新讲解</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight sm:text-5xl">把规则书放进来</h1>
        <p class="mx-auto mt-4 max-w-xl leading-7 text-ink/55">选一个 PDF，RulePilot 会读取文字和页面图片，然后直接打开完整讲解。</p>

        <form class="mt-8 rounded-xl border border-ink/10 bg-paper p-5 text-left sm:p-7" @submit.prevent="uploadAndTeach">
          <label for="rulebook-file" class="flex min-h-40 cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-ink/25 bg-canvas px-6 py-8 text-center hover:border-copper/60">
            <span class="font-display text-xl font-semibold">{{ file?.name ?? '选择一本 PDF 规则书' }}</span>
            <span class="mt-2 text-sm text-ink/45">{{ file ? '点击可以换一本' : '最大 50 MiB' }}</span>
          </label>
          <input id="rulebook-file" accept="application/pdf,.pdf" type="file" class="sr-only" @change="selectFile">

          <label class="mt-4 block text-sm font-semibold">讲解标题 <span class="font-normal text-ink/40">（可选）</span>
            <input v-model="title" maxlength="160" :placeholder="file ? titleFromFile(file) : '留空则使用 PDF 文件名'" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3 font-normal outline-none focus:border-copper">
          </label>

          <div v-if="selectedEdition" class="mt-4 flex items-center justify-between gap-4 rounded-lg bg-ink/5 px-4 py-3 text-sm">
            <span class="text-ink/50">将加入</span>
            <span class="truncate font-semibold">{{ selectedEdition.label }}</span>
          </div>

          <details class="mt-4 border-t border-ink/10 pt-4">
            <summary class="cursor-pointer text-sm font-semibold text-ink/55">人数、时长和版本</summary>
            <div class="mt-4 grid gap-4 sm:grid-cols-3">
              <label class="text-sm font-semibold sm:col-span-3">游戏版本
                <select v-model="editionId" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5">
                  <option value="" disabled>还没有游戏版本</option>
                  <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
                </select>
              </label>
              <label class="text-sm font-semibold">玩家<input v-model.number="playerCount" type="number" min="1" max="20" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
              <label class="text-sm font-semibold">新手<input v-model.number="beginnerCount" type="number" min="0" :max="playerCount" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
              <label class="text-sm font-semibold">分钟<input v-model.number="durationMinutes" type="number" min="2" max="180" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-3 py-2.5"></label>
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
            {{ preparingVersionId ? '正在生成讲解…' : uploading ? '正在上传…' : '上传并开始讲解' }}
          </button>
        </form>

        <p v-if="message" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <div v-if="preparingVersionId || processingVersionId" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
          <div class="h-full bg-copper transition-all" :class="preparingVersionId ? 'animate-pulse' : ''" :style="{ width: preparingVersionId ? '100%' : `${progress[processingVersionId]?.percentage ?? 0}%` }" />
        </div>
      </section>

      <section class="mt-14 border-t border-ink/10 pt-8">
        <div class="flex items-center justify-between gap-4">
          <h2 class="font-display text-2xl font-semibold">这个版本的规则书</h2>
          <RouterLink :to="{ name: 'catalog' }" class="text-sm font-semibold text-indigo">管理游戏版本</RouterLink>
        </div>
        <p v-if="loading" class="mt-5 text-sm text-ink/45">正在读取…</p>
        <div v-else-if="editionOptions.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center">
          <p class="font-semibold">还没有游戏版本</p>
          <RouterLink :to="{ name: 'catalog' }" class="mt-3 inline-block text-sm font-semibold text-indigo">先从 BGG 导入或添加一个 →</RouterLink>
        </div>
        <p v-else-if="documents.length === 0" class="mt-5 text-sm text-ink/45">还没有规则书。</p>
        <ul v-else class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="entry in documents" :key="entry.document.id" class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <div class="min-w-0">
              <p class="truncate font-semibold">{{ entry.document.title }}</p>
              <p class="mt-1 text-sm text-ink/45">{{ entry.latestVersion.status }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB</p>
            </div>
            <button v-if="entry.latestVersion.status === 'READY'" :disabled="Boolean(preparingVersionId)" class="shrink-0 rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="startLesson(entry.latestVersion.id).catch((error: unknown) => errorMessage = error instanceof Error ? error.message : '无法生成讲解。')">直接讲解</button>
          </li>
        </ul>
      </section>
    </main>
  </AppShell>
</template>
