<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

interface VisualFocus {
  pageNumber: number
  label: string
  x: number
  y: number
  width: number
  height: number
}

interface LessonStep {
  position: number
  heading: string
  kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER'
  text: string
  sourcePages: number[]
  visualFocus: VisualFocus | null
}

interface LessonSection {
  position: number
  title: string
  visualCaption: string
  steps: LessonStep[]
}

interface PublicLessonResponse {
  teachingPlanId: string
  documentVersionId: string
  rulebookTitle: string
  officialSourceUrl: string | null
  gameCover: { gameName: string; imageUrl: string; attributionUrl: string; attributionLabel: string } | null
  lesson: { id: string; status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'; sections: LessonSection[] }
}

interface RuleCitation { heading: string; pageFrom: number; pageTo: number }
interface PublicAnswer {
  answer: {
    status: 'ANSWERED' | 'CLARIFICATION_REQUIRED' | 'INSUFFICIENT_EVIDENCE' | 'INVALID_MODEL_OUTPUT' | 'MODEL_TIMEOUT'
    shortVerdict: string
    explanation: string | null
    citations: RuleCitation[]
    exceptions: string[]
    confidence: 'LOW' | 'MEDIUM' | 'HIGH'
    clarification: string | null
  }
  visualAids: Array<{ visualFocus: VisualFocus; relatedStep: string }>
  examples: Array<{ heading: string; text: string; sourcePages: number[] }>
}

interface PublicAnswerTurn { question: string; answer: PublicAnswer }

const route = useRoute()
const loading = ref(true)
const errorMessage = ref('')
const publicLesson = ref<PublicLessonResponse | null>(null)
const publicQuestion = ref('')
const selectedSectionPosition = ref<number | null>(null)
const publicAnswerTurns = ref<PublicAnswerTurn[]>([])
const publicAnswerLoading = ref(false)
const publicAnswerError = ref('')
const planId = computed(() => typeof route.params.planId === 'string' ? route.params.planId : '')

function sourcePageUrl(pageNumber: number) {
  return `/api/public/lessons/${encodeURIComponent(planId.value)}/pages/${pageNumber}/image`
}

function cropUrl(focus: VisualFocus) {
  const query = new URLSearchParams({
    x: String(focus.x), y: String(focus.y), width: String(focus.width), height: String(focus.height),
  })
  return `${sourcePageUrl(focus.pageNumber)}/crop?${query.toString()}`
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (!planId.value) throw new Error('这份公开讲解不存在。')
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}`)
    if (response.status === 404) throw new Error('这份公开讲解尚未发布，或链接已经失效。')
    if (!response.ok) throw new Error('暂时无法打开这份讲解。请稍后再试。')
    publicLesson.value = await response.json() as PublicLessonResponse
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '暂时无法打开这份讲解。'
  } finally {
    loading.value = false
  }
}

function confidenceLabel(confidence: PublicAnswer['answer']['confidence']) {
  return { LOW: '依据较少', MEDIUM: '已核对依据', HIGH: '依据充分' }[confidence]
}

function answerFailureMessage(answer: PublicAnswer['answer']) {
  if (answer.status === 'CLARIFICATION_REQUIRED') return answer.clarification ?? '请补充你正在看的步骤或局面。'
  if (answer.status === 'MODEL_TIMEOUT') return '这次核对超时了，可以稍后再问，或直接查看下方来源页。'
  return answer.shortVerdict
}

function askAboutSection(section: LessonSection) {
  selectedSectionPosition.value = section.position
  publicQuestion.value = `请把“${section.title}”这一章最容易弄错的步骤讲清楚，并走一个规则书允许的例子。`
  document.getElementById('public-question')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => (document.getElementById('public-question') as HTMLTextAreaElement | null)?.focus(), 250)
}

async function submitPublicQuestion() {
  const question = publicQuestion.value.trim()
  if (!question || publicAnswerLoading.value || !planId.value) return
  publicAnswerLoading.value = true
  publicAnswerError.value = ''
  try {
    const previousTurn = publicAnswerTurns.value.at(-1)
    const response = await fetch(`/api/public/lessons/${encodeURIComponent(planId.value)}/answers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question,
        sectionPosition: selectedSectionPosition.value,
        previousQuestion: previousTurn?.question ?? null,
      }),
    })
    if (response.status === 404) throw new Error('这份讲解已不再公开，无法继续答疑。')
    if (!response.ok) throw new Error('暂时无法核对规则书，请稍后再试。')
    const received = await response.json() as PublicAnswer
    publicAnswerTurns.value.push({ question, answer: received })
    publicQuestion.value = ''
  } catch (error) {
    publicAnswerError.value = error instanceof Error ? error.message : '暂时无法核对规则书。'
  } finally {
    publicAnswerLoading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/95">
      <div class="mx-auto flex max-w-5xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'public-library' }" class="font-display text-xl font-semibold tracking-tight">RulePilot</RouterLink>
        <RouterLink :to="{ name: 'public-library' }" class="text-sm font-semibold text-indigo">公开讲解库</RouterLink>
      </div>
    </header>

    <section v-if="loading" class="mx-auto max-w-3xl px-5 py-20 text-center sm:px-8" role="status">
      <p class="font-display text-2xl font-semibold">正在打开讲解…</p>
      <p class="mt-3 text-ink/55">规则和图片正在整理到阅读页。</p>
    </section>

    <section v-else-if="errorMessage" class="mx-auto max-w-2xl px-5 py-20 text-center sm:px-8">
      <p class="font-display text-2xl font-semibold">暂时打不开</p>
      <p class="mt-3 text-ink/60">{{ errorMessage }}</p>
      <button type="button" class="mt-6 rounded-lg bg-ink px-4 py-2.5 font-semibold text-paper" @click="load">重新尝试</button>
    </section>

    <article v-else-if="publicLesson" class="mx-auto max-w-4xl px-5 py-10 sm:px-8 lg:py-14">
      <div class="border-b border-ink/10 pb-8">
        <div class="flex items-start gap-5 sm:gap-7">
          <a v-if="publicLesson.gameCover" :href="publicLesson.gameCover.attributionUrl" target="_blank" rel="noopener noreferrer" class="w-24 shrink-0 overflow-hidden rounded-lg border border-ink/10 bg-paper shadow-sm sm:w-32" :aria-label="`查看 ${publicLesson.gameCover.gameName} 的${publicLesson.gameCover.attributionLabel}`">
            <img :src="publicLesson.gameCover.imageUrl" :alt="`${publicLesson.gameCover.gameName} 的游戏封面`" class="aspect-[3/4] h-full w-full object-cover" referrerpolicy="no-referrer">
          </a>
          <div>
            <p class="text-sm font-semibold text-copper">从规则书到开桌</p>
            <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ publicLesson.gameCover?.gameName ?? publicLesson.rulebookTitle }}</h1>
            <p v-if="publicLesson.gameCover && publicLesson.gameCover.gameName !== publicLesson.rulebookTitle" class="mt-2 text-sm font-medium text-ink/50">{{ publicLesson.rulebookTitle }}</p>
            <p class="mt-4 max-w-2xl leading-7 text-ink/60">这是一份公开的逐步讲解。先照着完成当前动作；需要核对时，可打开每一步的来源页。</p>
          </div>
        </div>
        <a v-if="publicLesson.officialSourceUrl" :href="`/api/public/lessons/${encodeURIComponent(planId)}/rulebook`" target="_blank" rel="noopener noreferrer" class="mt-5 inline-flex min-h-11 items-center rounded-lg border border-indigo/30 px-4 font-semibold text-indigo hover:bg-indigo/5">打开官方原规则书</a>
      </div>

      <section class="mt-8 rounded-3xl border border-indigo/20 bg-indigo/[0.045] p-5 shadow-[0_18px_50px_-36px_rgba(40,57,128,0.75)] sm:p-7" aria-labelledby="public-question-title">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p class="text-xs font-semibold uppercase tracking-[0.14em] text-indigo">桌边答疑</p>
            <h2 id="public-question-title" class="mt-2 font-display text-3xl font-semibold tracking-tight">读到哪一步卡住了？</h2>
            <p class="mt-2 max-w-2xl leading-7 text-ink/60">直接提问。回答只根据这份公开规则书的引用组织；命中同页图例或示例时，也会一并带回来。</p>
          </div>
          <span class="w-fit rounded-full bg-paper px-3 py-1.5 text-xs font-semibold text-indigo">无需登录</span>
        </div>

        <div class="mt-5 grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
          <label class="text-sm font-semibold text-ink/70">正在看的章节 <span class="font-normal text-ink/40">（可选）</span>
            <select v-model="selectedSectionPosition" :disabled="publicAnswerLoading" class="mt-2 min-h-11 w-full rounded-xl border border-ink/15 bg-paper px-3 font-normal outline-none focus:border-indigo disabled:opacity-50">
              <option :value="null">整本规则书</option>
              <option v-for="section in publicLesson.lesson.sections" :key="section.position" :value="section.position">第 {{ section.position }} 章 · {{ section.title }}</option>
            </select>
          </label>
          <div class="self-end rounded-xl border border-indigo/10 bg-paper px-4 py-3 text-xs leading-5 text-ink/50">例如：<br><span class="font-semibold text-ink/65">“这一步之后是谁行动？”</span></div>
        </div>

        <form class="mt-4" @submit.prevent="submitPublicQuestion">
          <label for="public-question" class="sr-only">向公开规则讲解提问</label>
          <textarea id="public-question" v-model="publicQuestion" rows="3" maxlength="800" :disabled="publicAnswerLoading" placeholder="例如：完成这个动作后，接下来要做什么？为什么？" class="w-full resize-y rounded-2xl border border-ink/15 bg-paper px-4 py-3 leading-7 outline-none transition placeholder:text-ink/35 focus:border-indigo focus:ring-4 focus:ring-indigo/10 disabled:opacity-55" />
          <div class="mt-3 flex flex-wrap items-center justify-between gap-3">
            <p class="text-xs text-ink/45">{{ publicQuestion.length }}/800 · 每个结论都会附来源页</p>
            <button type="submit" :disabled="publicAnswerLoading || !publicQuestion.trim()" class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40">{{ publicAnswerLoading ? '正在查找规则依据…' : '问规则书' }}</button>
          </div>
        </form>

        <p v-if="publicAnswerError" class="mt-4 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">{{ publicAnswerError }}</p>
        <div v-else-if="publicAnswerLoading" class="mt-5 rounded-2xl border border-indigo/12 bg-paper p-5" role="status" aria-live="polite">
          <div class="flex items-center gap-3"><span class="size-3 animate-pulse rounded-full bg-copper" /><p class="text-sm font-semibold">正在查找引用、核对原文，再组织成可执行的回答…</p></div>
          <div class="mt-4 grid gap-2 text-xs text-ink/50 sm:grid-cols-3"><span>1. 对齐问题</span><span>2. 查找规则书</span><span>3. 附上来源与图例</span></div>
        </div>

        <ol v-if="publicAnswerTurns.length" class="mt-6 space-y-5" aria-label="本次公开答疑">
          <li v-for="(turn, index) in publicAnswerTurns" :key="`${index}-${turn.question}`" class="space-y-3">
            <div class="ml-auto max-w-[92%] rounded-2xl rounded-tr-md bg-copper px-4 py-3 text-sm font-medium leading-6 text-white sm:max-w-[78%]">{{ turn.question }}</div>
            <article class="max-w-[96%] overflow-hidden rounded-3xl border border-ink/10 bg-paper shadow-sm sm:max-w-[88%]">
              <div class="p-5 sm:p-6">
                <div class="flex flex-wrap items-center gap-2"><span class="rounded-full bg-indigo/8 px-3 py-1 text-xs font-semibold text-indigo">{{ confidenceLabel(turn.answer.answer.confidence) }}</span><span class="text-xs font-semibold text-ink/40">规则书答复</span></div>
                <p class="mt-4 font-display text-xl font-semibold leading-8">{{ turn.answer.answer.shortVerdict }}</p>
                <p v-if="turn.answer.answer.status === 'ANSWERED' && turn.answer.answer.explanation" class="mt-3 leading-7 text-ink/75">{{ turn.answer.answer.explanation }}</p>
                <p v-else class="mt-3 rounded-2xl bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">{{ answerFailureMessage(turn.answer.answer) }}</p>
                <ul v-if="turn.answer.answer.exceptions.length" class="mt-4 list-disc space-y-1 pl-5 text-sm leading-6 text-ink/65"><li v-for="exception in turn.answer.answer.exceptions" :key="exception">{{ exception }}</li></ul>

                <div v-if="turn.answer.answer.citations.length" class="mt-5 border-t border-ink/10 pt-4">
                  <p class="text-xs font-semibold uppercase tracking-[0.12em] text-ink/40">依据</p>
                  <ul class="mt-2 flex flex-wrap gap-2"><li v-for="citation in turn.answer.answer.citations" :key="`${citation.heading}-${citation.pageFrom}`" class="rounded-xl bg-canvas px-3 py-2 text-xs font-semibold text-indigo">{{ citation.heading }} · 第 {{ citation.pageFrom }}{{ citation.pageTo !== citation.pageFrom ? `–${citation.pageTo}` : '' }} 页</li></ul>
                </div>

                <div v-if="turn.answer.visualAids.length || turn.answer.examples.length" class="mt-5 border-t border-ink/10 pt-4">
                  <p class="text-sm font-semibold text-indigo">把这段答案和规则书放在一起看</p>
                  <div v-if="turn.answer.visualAids.length" class="mt-3 grid gap-3 sm:grid-cols-2">
                    <a v-for="aid in turn.answer.visualAids" :key="`${aid.visualFocus.pageNumber}-${aid.visualFocus.x}-${aid.visualFocus.y}`" :href="sourcePageUrl(aid.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="overflow-hidden rounded-2xl border border-ink/10 bg-canvas transition hover:border-indigo/35">
                      <img :src="cropUrl(aid.visualFocus)" :alt="`${aid.visualFocus.label}（规则书第 ${aid.visualFocus.pageNumber} 页）`" class="aspect-[4/3] w-full object-contain">
                      <span class="block border-t border-ink/10 px-3 py-2 text-xs font-semibold text-indigo">同页图例 · {{ aid.relatedStep }}</span>
                    </a>
                  </div>
                  <ul v-if="turn.answer.examples.length" class="mt-3 space-y-2"><li v-for="example in turn.answer.examples" :key="`${example.heading}-${example.text}`" class="rounded-2xl bg-copper/[0.07] px-4 py-3"><p class="text-sm font-semibold text-copper">照这个例子走：{{ example.heading }}</p><p class="mt-1 text-sm leading-6 text-ink/70">{{ example.text }}</p><p v-if="example.sourcePages.length" class="mt-2 text-xs text-ink/45">同样来自第 {{ example.sourcePages.join('、') }} 页</p></li></ul>
                </div>
              </div>
            </article>
          </li>
        </ol>
      </section>

      <section v-for="section in publicLesson.lesson.sections" :key="section.position" class="border-b border-ink/10 py-10">
        <div class="flex flex-wrap items-center justify-between gap-3"><p class="text-sm font-semibold text-copper">第 {{ section.position }} 章</p><button type="button" class="rounded-lg border border-indigo/20 px-3 py-2 text-xs font-semibold text-indigo hover:bg-indigo/5" @click="askAboutSection(section)">问这一章</button></div>
        <h2 class="mt-2 font-display text-3xl font-semibold tracking-tight">{{ section.title }}</h2>
        <p v-if="section.visualCaption" class="mt-3 max-w-2xl leading-7 text-ink/60">{{ section.visualCaption }}</p>

        <ol class="mt-7 space-y-5">
          <li v-for="step in section.steps" :key="step.position" class="rounded-xl border border-ink/10 bg-paper p-5 sm:p-6">
            <div class="flex gap-4">
              <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-copper/15 text-sm font-bold text-copper">{{ step.position }}</span>
              <div class="min-w-0 flex-1">
                <h3 class="font-display text-xl font-semibold">{{ step.heading }}</h3>
                <p class="mt-2 leading-7 text-ink/75">{{ step.text }}</p>
                <a v-if="step.visualFocus" :href="sourcePageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener noreferrer" class="mt-5 block overflow-hidden rounded-lg border border-ink/10 bg-canvas">
                  <img :src="cropUrl(step.visualFocus)" :alt="`${step.visualFocus.label}（规则书第 ${step.visualFocus.pageNumber} 页）`" class="max-h-96 w-full object-contain">
                  <span class="block border-t border-ink/10 px-3 py-2 text-sm font-semibold text-indigo">查看来源页：{{ step.visualFocus.label }}</span>
                </a>
                <p v-if="step.sourcePages.length" class="mt-4 text-sm text-ink/45">来源：第 {{ step.sourcePages.join('、') }} 页</p>
              </div>
            </div>
          </li>
        </ol>
      </section>
    </article>
  </main>
</template>
