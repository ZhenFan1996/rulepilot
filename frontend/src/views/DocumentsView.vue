<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

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
interface TeachingPlanResponse {
  id: string
  sections: Array<{ position: number; topicKey: string; title: string }>
}
interface ModelConfigurationResponse {
  providers: Array<{ id: string; configured: boolean; visionCapable: boolean }>
  assignments: { teaching: string; visual: string }
}
interface AssistantRunDetails {
  run: {
    id: string
    state: string
    createdAt: string
    updatedAt: string
    completedAt: string | null
    lastErrorCode: string | null
  }
  activities: Array<{
    sequence: number
    type: 'TOOL' | 'MODEL' | 'CRITIC'
    operation: string
    outcome: 'SUCCEEDED' | 'FAILED' | 'REJECTED'
    summary: string
    occurredAt: string
  }>
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
const message = ref('')
const errorMessage = ref('')
const progress = ref<Record<string, { stage: string; percentage: number; processedPages: number }>>({})
const generationPlan = ref<TeachingPlanResponse | null>(null)
const generationRun = ref<AssistantRunDetails | null>(null)
const modelConfiguration = ref<ModelConfigurationResponse | null>(null)
const generationElapsedSeconds = ref(0)
let generationPollTimer: ReturnType<typeof setTimeout> | undefined
let generationClockTimer: ReturnType<typeof setInterval> | undefined
let generationStartedAt = 0

const editionOptions = computed(() => games.value.flatMap((entry) => entry.editions.map((edition) => ({
  id: edition.id,
  label: `${entry.game.name} · ${edition.name}${edition.language ? ` · ${edition.language}` : ''}`,
}))))
const selectedEdition = computed(() => editionOptions.value.find((item) => item.id === editionId.value))
const canUpload = computed(() => Boolean(file.value && editionId.value && !uploading.value && !preparingVersionId.value))
const visualProvider = computed(() => modelConfiguration.value?.providers.find(
  (provider) => provider.id === modelConfiguration.value?.assignments.visual,
))
const visualVisionCapable = computed(() => visualProvider.value?.visionCapable === true)
const visualProviderLabel = computed(() => ({
  gemini: 'Gemini',
  openai: 'OpenAI',
  deepseek: 'DeepSeek',
  compatible: '兼容模型',
  fake: '内置演示',
}[modelConfiguration.value?.assignments.visual ?? 'fake'] ?? '当前模型'))
const terminalRunStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])
const generationSectionIdentifier = computed(() => {
  const activities = generationRun.value?.activities ?? []
  for (let index = activities.length - 1; index >= 0; index -= 1) {
    const identifier = activities[index]!.operation.split('|')[1]
    if (identifier) return identifier
  }
  return ''
})
const generationSectionIndex = computed(() => {
  const sections = generationPlan.value?.sections ?? []
  const index = sections.findIndex((section) =>
    section.topicKey === generationSectionIdentifier.value
    || String(section.position) === generationSectionIdentifier.value)
  return index >= 0 ? index : 0
})
const generationSection = computed(() => generationPlan.value?.sections[generationSectionIndex.value] ?? null)
const generationStatus = computed(() => {
  const run = generationRun.value
  const section = generationSection.value
  if (!run) return '正在启动讲解流程…'
  if (run.run.state === 'FAILED') return '生成遇到问题，正在结束本次任务。'
  if (terminalRunStates.has(run.run.state)) return '章节已经处理完毕，正在打开讲解。'
  const latest = run.activities.at(-1)
  if (!latest) return '正在确认规则书和讲解范围…'
  const operation = latest.operation.split('|')[0]
  const title = section ? `“${section.title}”` : '当前章节'
  if (operation === 'searchRuleEvidence') return `已找到${title}的规则依据，正在继续整理…`
  if (operation === 'composeTeachingSection') return `${title}的初稿已完成，正在核对规则…`
  if (operation === 'reviseTeachingSection') return `${title}已按核对结果修正，正在复核…`
  if (operation === 'reviewGeneratedContent') return `刚完成${title}的一次规则核对，正在处理结果…`
  return '讲解仍在继续生成…'
})
const generationActivityItems = computed(() => {
  const activities = generationRun.value?.activities ?? []
  return activities.slice(-4).reverse().map((activity, reverseIndex) => {
    const originalIndex = activities.length - 1 - reverseIndex
    const operation = activity.operation.split('|')[0] ?? ''
    let sectionIdentifier = activity.operation.split('|')[1] ?? ''
    if (!sectionIdentifier) {
      for (let index = originalIndex - 1; index >= 0; index -= 1) {
        sectionIdentifier = activities[index]!.operation.split('|')[1] ?? ''
        if (sectionIdentifier) break
      }
    }
    const title = generationPlan.value?.sections.find((section) =>
      section.topicKey === sectionIdentifier || String(section.position) === sectionIdentifier)?.title
    const labels: Record<string, string> = {
      searchRuleEvidence: title ? `找到“${title}”的规则依据` : '找到一组规则依据',
      composeTeachingSection: title ? `写完“${title}”的讲解初稿` : '写完一节讲解初稿',
      reviseTeachingSection: title ? `修正“${title}”的讲解` : '修正一节讲解',
      reviewGeneratedContent: title ? `核对“${title}”的规则内容` : '完成一次规则核对',
    }
    return { sequence: activity.sequence, label: labels[operation] ?? '完成一项讲解处理' }
  })
})
const generationElapsed = computed(() => {
  const minutes = Math.floor(generationElapsedSeconds.value / 60)
  const seconds = generationElapsedSeconds.value % 60
  return minutes > 0 ? `${minutes} 分 ${seconds.toString().padStart(2, '0')} 秒` : `${seconds} 秒`
})

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
    const [response, modelResponse] = await Promise.all([
      checkedFetch('/api/v1/games'),
      checkedFetch('/api/v1/model-configuration'),
    ])
    if (!response.ok) throw new Error('无法读取游戏目录。')
    games.value = await response.json() as GameResponse[]
    if (modelResponse.ok) modelConfiguration.value = await modelResponse.json() as ModelConfigurationResponse
    const requestedEdition = typeof route.query.editionId === 'string' ? route.query.editionId : ''
    editionId.value = editionOptions.value.some((item) => item.id === requestedEdition)
      ? requestedEdition
      : editionOptions.value.length === 1 ? editionOptions.value[0]!.id : ''
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
    const lessonRequest = checkedFetch(`/api/v1/teaching-plans/${plan.id}/illustrated-lessons`, {
      method: 'POST',
      headers: { [csrf.headerName]: csrf.token },
    })
    startGenerationFeedback(plan)
    const lessonResponse = await lessonRequest
    if (!lessonResponse.ok) throw new Error('讲解生成没有完成，可以稍后从“我的讲解”继续。')
    localStorage.setItem('rulepilot:last-plan-id', plan.id)
    await router.push({ name: 'lesson', params: { planId: plan.id } })
  } finally {
    stopGenerationFeedback()
    preparingVersionId.value = ''
  }
}

function startGenerationFeedback(plan: TeachingPlanResponse) {
  stopGenerationFeedback()
  generationPlan.value = plan
  generationRun.value = null
  generationElapsedSeconds.value = 0
  generationStartedAt = Date.now()
  generationClockTimer = setInterval(() => {
    generationElapsedSeconds.value = Math.floor((Date.now() - generationStartedAt) / 1000)
  }, 1000)
  void pollGenerationRun(plan.id)
}

async function pollGenerationRun(planId: string) {
  try {
    const response = await checkedFetch(`/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`)
    if (response.ok) generationRun.value = await response.json() as AssistantRunDetails
  } catch {
    // The generation request remains authoritative; progress polling is best-effort.
  }
  if (generationPlan.value?.id === planId) {
    generationPollTimer = setTimeout(() => void pollGenerationRun(planId), 1000)
  }
}

function stopGenerationFeedback() {
  if (generationPollTimer) clearTimeout(generationPollTimer)
  if (generationClockTimer) clearInterval(generationClockTimer)
  generationPollTimer = undefined
  generationClockTimer = undefined
  generationPlan.value = null
  generationRun.value = null
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
onBeforeUnmount(stopGenerationFeedback)
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

          <label v-if="editionOptions.length > 1" class="mt-4 block text-sm font-semibold">这本规则书属于
            <select v-model="editionId" required class="mt-2 w-full rounded-lg border border-ink/15 bg-canvas px-4 py-3">
              <option value="" disabled>请选择游戏和版本</option>
              <option v-for="edition in editionOptions" :key="edition.id" :value="edition.id">{{ edition.label }}</option>
            </select>
          </label>
          <div v-else-if="selectedEdition" class="mt-4 flex items-center justify-between gap-4 rounded-lg bg-ink/5 px-4 py-3 text-sm">
            <span class="text-ink/50">将加入</span>
            <span class="truncate font-semibold">{{ selectedEdition.label }}</span>
          </div>
          <div v-else class="mt-4 rounded-lg bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900">
            上传前需要先添加游戏版本。
            <RouterLink :to="{ name: 'catalog' }" class="font-semibold underline">从 BGG 查找或手动添加</RouterLink>
          </div>

          <div v-if="modelConfiguration && !visualVisionCapable" class="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950" role="status">
            <p><span class="font-semibold">{{ visualProviderLabel }}目前不读取页面图片。</span>讲解仍可生成并附上原文页，但不会识别组件照片、版图位置或图标。</p>
            <RouterLink :to="{ name: 'model-settings' }" class="mt-1 inline-block font-semibold text-indigo underline underline-offset-2">连接支持图片的 Gemini 或 OpenAI</RouterLink>
          </div>

          <details class="mt-4 border-t border-ink/10 pt-4">
            <summary class="cursor-pointer text-sm font-semibold text-ink/55">人数、时长和资料类型</summary>
            <div class="mt-4 grid gap-4 sm:grid-cols-3">
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

        <p v-if="message && !generationPlan" class="mt-5 rounded-lg bg-indigo/5 px-4 py-3 text-sm text-indigo" aria-live="polite">{{ message }}</p>
        <p v-if="errorMessage" class="mt-5 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
        <section v-if="generationPlan" class="mt-5 rounded-xl border border-copper/25 bg-paper p-5 text-left shadow-sm" aria-live="polite" aria-busy="true">
          <div class="flex items-start gap-3">
            <span class="mt-1 size-4 shrink-0 animate-spin rounded-full border-2 border-copper/25 border-t-copper" aria-hidden="true" />
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <h2 class="font-display text-xl font-semibold">正在把规则整理成讲解</h2>
                <span class="text-xs tabular-nums text-ink/45">已用时 {{ generationElapsed }}</span>
              </div>
              <p class="mt-2 text-sm font-medium leading-6 text-ink/75">{{ generationStatus }}</p>
              <p class="mt-1 text-xs leading-5 text-ink/45">复杂规则书和严格核对可能需要几分钟，请保持此页打开。{{ visualVisionCapable ? `${visualProviderLabel} 会读取关键页面图片。` : '本次只读取文字，页面图片仅作为原文供你查看。' }}</p>
            </div>
          </div>

          <div class="mt-5 flex items-center justify-between gap-3 text-xs text-ink/45">
            <span>讲解章节</span>
            <span v-if="generationSection">第 {{ generationSectionIndex + 1 }} / {{ generationPlan.sections.length }} 节</span>
          </div>
          <ol class="mt-2 flex flex-wrap gap-2" aria-label="讲解章节生成进度">
            <li
              v-for="(section, index) in generationPlan.sections"
              :key="section.topicKey"
              class="flex min-h-8 items-center gap-1.5 rounded-full border px-2.5 text-xs"
              :class="index < generationSectionIndex
                ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
                : index === generationSectionIndex
                  ? 'border-copper/40 bg-copper/10 font-semibold text-copper'
                  : 'border-ink/10 text-ink/35'"
              :aria-current="index === generationSectionIndex ? 'step' : undefined"
              :title="section.title"
            >
              <span aria-hidden="true">{{ index < generationSectionIndex ? '✓' : section.position }}</span>
              <span class="max-w-32 truncate">{{ section.title }}</span>
            </li>
          </ol>

          <div v-if="generationActivityItems.length" class="mt-5 border-t border-ink/10 pt-4">
            <p class="text-xs font-semibold uppercase tracking-wider text-ink/35">刚刚完成</p>
            <ul class="mt-2 space-y-1.5 text-sm text-ink/60">
              <li v-for="item in generationActivityItems" :key="item.sequence" class="flex gap-2">
                <span class="text-copper" aria-hidden="true">·</span><span>{{ item.label }}</span>
              </li>
            </ul>
          </div>
        </section>
        <div v-if="processingVersionId && !generationPlan" class="mx-auto mt-4 h-1.5 max-w-md overflow-hidden rounded-full bg-ink/10">
          <div class="h-full bg-copper transition-all" :style="{ width: `${progress[processingVersionId]?.percentage ?? 0}%` }" />
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
        <p v-else-if="!editionId" class="mt-5 text-sm text-ink/45">选择上方的游戏版本后，这里会显示已有规则书。</p>
        <p v-else-if="documents.length === 0" class="mt-5 text-sm text-ink/45">这个版本还没有规则书。</p>
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
