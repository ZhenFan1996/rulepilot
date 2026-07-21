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
  lesson: { id: string; status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'; sections: LessonSection[] }
}

const route = useRoute()
const loading = ref(true)
const errorMessage = ref('')
const publicLesson = ref<PublicLessonResponse | null>(null)
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

onMounted(() => { void load() })
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/95">
      <div class="mx-auto flex max-w-5xl items-center justify-between gap-4 px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold tracking-tight">RulePilot</RouterLink>
        <span class="text-sm text-ink/50">公开讲解</span>
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
        <p class="text-sm font-semibold text-copper">从规则书到开桌</p>
        <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ publicLesson.rulebookTitle }}</h1>
        <p class="mt-4 max-w-2xl leading-7 text-ink/60">这是一份公开的逐步讲解。先照着完成当前动作；需要核对时，可打开每一步的来源页。</p>
        <a v-if="publicLesson.officialSourceUrl" :href="`/api/public/lessons/${encodeURIComponent(planId)}/rulebook`" target="_blank" rel="noopener noreferrer" class="mt-5 inline-flex min-h-11 items-center rounded-lg border border-indigo/30 px-4 font-semibold text-indigo hover:bg-indigo/5">打开官方原规则书</a>
      </div>

      <section v-for="section in publicLesson.lesson.sections" :key="section.position" class="border-b border-ink/10 py-10">
        <p class="text-sm font-semibold text-copper">第 {{ section.position }} 章</p>
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
