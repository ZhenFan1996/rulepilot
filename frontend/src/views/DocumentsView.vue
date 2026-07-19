<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

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

interface RuleStructureResponse {
  presentSections: number
  requiredSections: number
  sections: Array<{
    type: string
    label: string
    present: boolean
    content: string
    pageNumbers: number[]
  }>
}

interface TeachingPlanResponse {
  id: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
  gameTitle: string
  premise: string
  sections: Array<{
    position: number
    topicKey: string
    title: string
    objective: string
    required: boolean
    retrievalQueries: string[]
    coverageTags: string[]
  }>
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
const structureVersionId = ref('')
const ruleStructure = ref<RuleStructureResponse | null>(null)
const planPlayerCount = ref(4)
const planBeginnerCount = ref(2)
const planDurationMinutes = ref(30)
const teachingPlan = ref<TeachingPlanResponse | null>(null)
const creatingPlan = ref(false)
const creatingLesson = ref(false)
const processingProgress = ref<Record<string, { stage: string; percentage: number; processedPages: number; complete: boolean }>>({})

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

async function previewStructure(versionId: string) {
  errorMessage.value = ''
  try {
    const response = await checkedFetch(`/api/v1/document-versions/${versionId}/rule-structure`)
    if (!response.ok) throw new Error('无法读取规则结构。')
    ruleStructure.value = (await response.json()) as RuleStructureResponse
    structureVersionId.value = versionId
    teachingPlan.value = null
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取规则结构。'
  }
}

async function createTeachingPlan(versionId: string) {
  if (planBeginnerCount.value > planPlayerCount.value) {
    errorMessage.value = '新手人数不能超过总玩家人数。'
    return
  }
  creatingPlan.value = true
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    const csrf = (await csrfResponse.json()) as CsrfResponse
    const response = await checkedFetch(`/api/v1/document-versions/${versionId}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({
        playerCount: planPlayerCount.value,
        beginnerCount: planBeginnerCount.value,
        durationMinutes: planDurationMinutes.value,
      }),
    })
    if (!response.ok) throw new Error('无法创建教学计划，请检查人数和讲解时长。')
    teachingPlan.value = (await response.json()) as TeachingPlanResponse
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法创建教学计划。'
  } finally {
    creatingPlan.value = false
  }
}

async function createIllustratedLesson(planId: string) {
  creatingLesson.value = true
  errorMessage.value = ''
  try {
    const csrfResponse = await checkedFetch('/api/auth/csrf')
    const csrf = (await csrfResponse.json()) as CsrfResponse
    const response = await checkedFetch(`/api/v1/teaching-plans/${planId}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    if (!response.ok) throw new Error('无法生成图文讲解。')
    localStorage.setItem('rulepilot:last-plan-id', planId)
    await router.push({ name: 'lesson', params: { planId } })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法生成图文讲解。'
  } finally {
    creatingLesson.value = false
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
    const result = (await response.json()) as { duplicate: boolean; version: { id: string; status: string } }
    message.value = result.duplicate ? '这份文件已上传，已保留现有版本。' : '规则书已安全保存，等待解析。'
    title.value = ''
    file.value = null
    await loadDocuments()
    if (!result.duplicate) watchProgress(result.version.id)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '上传失败。'
  } finally {
    uploading.value = false
  }
}

function watchProgress(versionId: string) {
  const events = new EventSource(`/api/v1/document-versions/${versionId}/progress`, { withCredentials: true })
  events.addEventListener('progress', (event) => {
    const snapshot = JSON.parse((event as MessageEvent<string>).data) as {
      stage: string
      percentage: number
      processedPages: number
      complete: boolean
    }
    processingProgress.value = { ...processingProgress.value, [versionId]: snapshot }
    const document = documents.value.find((entry) => entry.latestVersion.id === versionId)
    if (document) document.latestVersion.status = snapshot.stage
    if (snapshot.complete) {
      events.close()
      void loadDocuments()
    }
  })
  events.onerror = () => events.close()
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
  <AppShell>
    <div class="mx-auto grid max-w-6xl gap-10 px-5 py-10 sm:px-8 lg:grid-cols-[0.82fr_1.18fr] lg:px-12 lg:py-14">
      <section>
        <p class="text-sm font-medium text-copper">规则书</p>
        <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight">添加一本规则书</h1>
        <p class="mt-4 max-w-xl leading-7 text-ink/55">选择它对应的游戏版本，再上传 PDF。读取完成后，你可以检查内容并准备一场讲解。</p>

        <form class="mt-8 space-y-4 rounded-xl border border-ink/10 bg-paper p-6" @submit.prevent="upload">
          <label class="block text-sm font-semibold">
            游戏版本
            <select v-model="editionId" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
              <option value="" disabled>请先在目录中创建游戏版本</option>
              <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
            </select>
          </label>
          <label class="block text-sm font-semibold">
            资料标题
            <input v-model="title" required maxlength="160" placeholder="例如：基础规则书 2026 中文版" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
          </label>
          <label class="block text-sm font-semibold">
            资料类型
            <select v-model="sourceType" class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
              <option v-for="entry in sourceTypes" :key="entry[0]" :value="entry[0]">{{ entry[1] }}</option>
            </select>
          </label>
          <div class="text-sm font-semibold">
            <span>PDF 文件</span>
            <label for="rulebook-file" class="mt-2 flex min-h-16 cursor-pointer items-center justify-between gap-3 rounded-lg border border-dashed border-ink/20 bg-canvas px-4 py-3 font-normal hover:border-copper/50">
              <span class="truncate text-ink/55">{{ file?.name ?? '还没有选择文件' }}</span>
              <span class="shrink-0 rounded-md border border-ink/15 bg-paper px-3 py-2 text-xs font-semibold text-ink">选择 PDF</span>
            </label>
            <input id="rulebook-file" required accept="application/pdf,.pdf" type="file" class="sr-only" @change="selectFile">
          </div>
          <button :disabled="uploading || !editionId" class="w-full rounded-lg bg-copper px-5 py-3 font-semibold text-white disabled:opacity-40">
            {{ uploading ? '正在上传…' : '保存规则书' }}
          </button>
        </form>

        <p v-if="message" class="mt-4 rounded-lg bg-emerald-50 px-4 py-3 text-sm text-emerald-800" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-4 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
      </section>

      <section>
        <div class="flex items-end justify-between gap-4">
          <h2 class="font-display text-2xl font-semibold">已添加的资料</h2>
          <RouterLink :to="{ name: 'catalog' }" class="text-sm font-medium text-indigo">管理游戏版本</RouterLink>
        </div>
        <div v-if="loading" class="mt-5 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50">正在加载…</div>
        <div v-else-if="!editionId" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center text-ink/55">请先添加游戏和版本。</div>
        <div v-else-if="documents.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center text-ink/55">这个版本还没有规则书。</div>
        <ul v-else class="mt-5 space-y-4">
          <li v-for="entry in documents" :key="entry.document.id" class="rounded-xl border border-ink/10 bg-paper p-5">
            <div class="flex items-start justify-between gap-4">
              <div>
                <p class="font-display text-xl font-semibold">{{ entry.document.title }}</p>
                <p class="mt-2 text-sm text-ink/55">{{ entry.latestVersion.originalFilename }} · v{{ entry.latestVersion.versionNumber }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB</p>
              </div>
              <span class="rounded-md bg-indigo/10 px-2.5 py-1 text-xs font-semibold text-indigo">{{ entry.latestVersion.status }}</span>
            </div>
            <div v-if="processingProgress[entry.latestVersion.id]" class="mt-4">
              <div class="h-2 overflow-hidden rounded-full bg-ink/8">
                <div class="h-full rounded-full bg-indigo transition-all" :style="{ width: `${processingProgress[entry.latestVersion.id]!.percentage}%` }" />
              </div>
              <p class="mt-2 text-xs text-ink/45">{{ processingProgress[entry.latestVersion.id]!.percentage }}% · 已读取 {{ processingProgress[entry.latestVersion.id]!.processedPages }} 页</p>
            </div>
            <details class="mt-4 text-xs text-ink/40">
              <summary class="cursor-pointer">文件信息</summary>
              <p class="mt-2 break-all font-mono" :title="entry.latestVersion.checksum">SHA-256 {{ entry.latestVersion.checksum }}</p>
            </details>
            <div class="mt-4 flex flex-wrap gap-3">
              <button class="rounded-lg border border-ink/15 px-3 py-2 text-sm font-semibold hover:border-indigo/40" @click="previewStructure(entry.latestVersion.id)">检查规则内容</button>
              <button class="rounded-lg px-3 py-2 text-sm font-medium text-indigo hover:bg-indigo/5" @click="previewPages(entry.latestVersion.id)">查看原文</button>
            </div>
            <div v-if="structureVersionId === entry.latestVersion.id && ruleStructure" class="mt-5 border-t border-ink/10 pt-5">
              <div class="flex items-center justify-between gap-4">
                <p class="font-semibold">讲解需要的内容：{{ ruleStructure.presentSections }} / {{ ruleStructure.requiredSections }} 项已找到</p>
                <span :class="ruleStructure.presentSections === ruleStructure.requiredSections ? 'bg-emerald-50 text-emerald-800' : 'bg-amber-50 text-amber-800'" class="rounded-full px-3 py-1 text-xs font-semibold">
                  {{ ruleStructure.presentSections === ruleStructure.requiredSections ? '可以开始' : '需要补充' }}
                </span>
              </div>
              <div class="mt-4 grid gap-3 sm:grid-cols-2">
                <article v-for="section in ruleStructure.sections" :key="section.type" class="rounded-lg bg-canvas p-4">
                  <div class="flex items-center justify-between gap-3">
                    <h3 class="font-semibold">{{ section.label }}</h3>
                    <span :class="section.present ? 'text-emerald-700' : 'text-amber-700'" class="text-xs font-semibold">{{ section.present ? '已找到' : '缺失' }}</span>
                  </div>
                  <p v-if="section.present" class="mt-2 text-xs text-ink/45">来源页：{{ section.pageNumbers.join('、') }}</p>
                  <p v-else class="mt-2 text-sm leading-6 text-ink/50">这份规则书里暂时没有找到相关内容。</p>
                  <details v-if="section.present" class="mt-3">
                    <summary class="cursor-pointer text-sm font-semibold text-indigo">查看找到的原文</summary>
                    <pre class="mt-3 max-h-44 overflow-auto whitespace-pre-wrap font-sans text-sm leading-6 text-ink/65">{{ section.content }}</pre>
                  </details>
                </article>
              </div>
              <form class="mt-5 rounded-lg border border-ink/10 p-4" @submit.prevent="createTeachingPlan(entry.latestVersion.id)">
                <h3 class="font-semibold">这次要给谁讲？</h3>
                <div class="mt-3 grid gap-3 sm:grid-cols-3">
                  <label class="text-sm font-semibold">玩家人数<input v-model.number="planPlayerCount" type="number" min="1" max="20" required class="mt-2 w-full rounded-xl border border-ink/15 bg-canvas px-3 py-2"></label>
                  <label class="text-sm font-semibold">其中新手<input v-model.number="planBeginnerCount" type="number" min="0" :max="planPlayerCount" required class="mt-2 w-full rounded-xl border border-ink/15 bg-canvas px-3 py-2"></label>
                  <label class="text-sm font-semibold">讲解分钟<input v-model.number="planDurationMinutes" type="number" min="2" max="180" required class="mt-2 w-full rounded-xl border border-ink/15 bg-canvas px-3 py-2"></label>
                </div>
                <button :disabled="creatingPlan" class="mt-4 rounded-lg bg-copper px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40">{{ creatingPlan ? '正在准备…' : '准备讲解' }}</button>
              </form>
              <div v-if="teachingPlan" class="mt-5 rounded-lg bg-indigo/5 p-4">
                <p class="font-display text-xl font-semibold">{{ teachingPlan.gameTitle }}</p>
                <p class="mt-2 text-sm leading-6 text-ink/60">{{ teachingPlan.premise }}</p>
                <p class="mt-3 text-xs font-semibold text-ink/45">{{ teachingPlan.playerCount }} 人 · {{ teachingPlan.beginnerCount }} 位新手 · {{ teachingPlan.durationMinutes }} 分钟</p>
                <ol class="mt-4 space-y-2">
                  <li v-for="section in teachingPlan.sections" :key="section.topicKey" class="flex items-start gap-3 rounded-xl bg-paper/70 p-3 text-sm">
                    <span class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-indigo text-xs font-semibold text-white">{{ section.position }}</span>
                    <span>
                      <strong>{{ section.title }}</strong>
                      <span v-if="!section.required" class="ml-2 text-ink/45">可选</span>
                      <span class="mt-1 block leading-5 text-ink/55">{{ section.objective }}</span>
                      <span class="mt-1 block text-xs text-ink/40">检索：{{ section.retrievalQueries.join('；') }}</span>
                    </span>
                  </li>
                </ol>
                <button :disabled="creatingLesson" class="mt-5 rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white disabled:opacity-40" @click="createIllustratedLesson(teachingPlan.id)">{{ creatingLesson ? '正在整理…' : '开始整理讲解' }}</button>
              </div>
            </div>
            <div v-if="previewVersionId === entry.latestVersion.id" class="mt-5 space-y-3 border-t border-ink/10 pt-5">
              <p v-if="pages.length === 0" class="text-sm text-ink/45">尚未提取到页面文字。</p>
              <article v-for="page in pages" :key="page.pageNumber" class="rounded-lg bg-canvas p-4">
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
  </AppShell>
</template>
