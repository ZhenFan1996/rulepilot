<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
  createdAt: string
  sections: Array<{ required: boolean; evidenceAvailable: boolean }>
}

const router = useRouter()
const plans = ref<TeachingPlan[]>([])
const loading = ref(true)
const errorMessage = ref('')
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')

const readyCount = computed(
  () => plans.value.filter((plan) => plan.sections.filter((section) => section.required).every((section) => section.evidenceAvailable)).length,
)

function isReady(plan: TeachingPlan) {
  return plan.sections.filter((section) => section.required).every((section) => section.evidenceAvailable)
}

function createdLabel(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '创建时间未知'
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function loadPlans() {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await fetch('/api/v1/teaching-plans', { credentials: 'include' })
    if (response.status === 401) {
      await router.push({ name: 'login' })
      return
    }
    if (!response.ok) throw new Error('无法读取你的讲解，请稍后重试。')
    plans.value = (await response.json()) as TeachingPlan[]
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '无法读取你的讲解。'
  } finally {
    loading.value = false
  }
}

onMounted(loadPlans)
</script>

<template>
  <main class="min-h-screen bg-canvas text-ink">
    <header class="border-b border-ink/10 bg-paper/70 backdrop-blur">
      <div class="mx-auto flex max-w-6xl items-center justify-between px-5 py-4 sm:px-8">
        <RouterLink :to="{ name: 'home' }" class="font-display text-xl font-semibold">RulePilot</RouterLink>
        <RouterLink :to="{ name: 'teach' }" class="rounded-xl bg-copper px-4 py-2 text-sm font-semibold text-white">新建讲解</RouterLink>
      </div>
    </header>

    <section class="mx-auto max-w-6xl px-5 py-10 sm:px-8 sm:py-16">
      <p class="eyebrow">YOUR LESSONS</p>
      <div class="mt-4 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="font-display text-4xl font-semibold tracking-tight sm:text-5xl">继续你的规则讲解</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/60">讲解保存在服务端账户下，可以从任何已登录设备重新打开；章节阅读进度仍只保存在当前浏览器。</p>
        </div>
        <p v-if="plans.length" class="text-sm font-semibold text-ink/50">{{ plans.length }} 份计划 · {{ readyCount }} 份证据完整</p>
      </div>

      <div v-if="loading" class="mt-10 rounded-3xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">正在读取讲解…</div>

      <div v-else-if="errorMessage" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 text-sm font-semibold underline underline-offset-4" @click="loadPlans">重新加载</button>
      </div>

      <div v-else-if="plans.length === 0" class="mt-10 rounded-[2rem] border border-dashed border-ink/20 bg-paper/45 px-6 py-14 text-center">
        <p class="text-4xl" aria-hidden="true">⌁</p>
        <h2 class="mt-5 font-display text-2xl font-semibold">还没有生成过讲解</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">导入规则书并确认游戏版本后，RulePilot 会从 Setup 一直组织到结束条件、计分和同分处理。</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-xl bg-copper px-5 py-3 font-semibold text-white">导入第一本规则书</RouterLink>
      </div>

      <ol v-else class="mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="(plan, index) in plans" :key="plan.id" class="rounded-[1.75rem] border border-ink/10 bg-paper p-6 shadow-sm">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="text-xs font-semibold uppercase tracking-[0.18em] text-ink/40">讲解计划 {{ String(plans.length - index).padStart(2, '0') }}</p>
              <h2 class="mt-3 font-display text-2xl font-semibold">{{ plan.playerCount }} 人规则讲解</h2>
            </div>
            <span :class="isReady(plan) ? 'bg-emerald-50 text-emerald-800' : 'bg-amber-50 text-amber-800'" class="rounded-full px-3 py-1.5 text-xs font-semibold">
              {{ isReady(plan) ? '证据完整' : '需要复核' }}
            </span>
          </div>
          <dl class="mt-6 grid grid-cols-2 gap-3 rounded-2xl bg-canvas p-4 text-sm">
            <div><dt class="text-ink/45">新手人数</dt><dd class="mt-1 font-semibold">{{ plan.beginnerCount }} 人</dd></div>
            <div><dt class="text-ink/45">计划时长</dt><dd class="mt-1 font-semibold">{{ plan.durationMinutes }} 分钟</dd></div>
            <div class="col-span-2"><dt class="text-ink/45">创建时间</dt><dd class="mt-1 font-semibold">{{ createdLabel(plan.createdAt) }}</dd></div>
          </dl>
          <div class="mt-6 flex items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">上次打开</span>
            <span v-else class="font-mono text-[0.65rem] text-ink/30">{{ plan.documentVersionId.slice(0, 8) }}</span>
            <RouterLink :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-xl bg-indigo px-4 py-2.5 text-sm font-semibold text-white">继续讲解</RouterLink>
          </div>
        </li>
      </ol>
    </section>
  </main>
</template>
