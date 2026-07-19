<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface TeachingPlan {
  id: string
  gameTitle: string
  premise: string
  playerCount: number
  durationMinutes: number
  createdAt: string
}

const username = ref('')
const plans = ref<TeachingPlan[]>([])
const recentPlans = computed(() => plans.value.slice(0, 3))
const latestPlan = computed(() => recentPlans.value[0] ?? null)

function createdLabel(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(date)
}

async function loadPersonalHome() {
  try {
    const sessionResponse = await fetch('/api/auth/session', { credentials: 'include' })
    if (!sessionResponse.ok) return
    username.value = ((await sessionResponse.json()) as { username: string }).username
    const plansResponse = await fetch('/api/v1/teaching-plans', { credentials: 'include' })
    if (plansResponse.ok) plans.value = await plansResponse.json() as TeachingPlan[]
  } catch {
    username.value = ''
  }
}

onMounted(loadPersonalHome)
</script>

<template>
  <AppShell>
    <div class="mx-auto max-w-6xl px-5 py-10 sm:px-8 sm:py-14 lg:px-12 lg:py-16">
      <section class="grid gap-10 border-b border-ink/10 pb-14 lg:grid-cols-[1.2fr_0.8fr] lg:items-end">
        <div>
          <p class="text-sm font-medium text-copper">{{ username ? `${username} 的规则桌` : '桌游规则助手' }}</p>
          <h1 v-if="latestPlan" class="mt-4 max-w-3xl font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">继续讲<br class="hidden sm:block">{{ latestPlan.gameTitle }}</h1>
          <h1 v-else class="mt-4 max-w-3xl font-display text-4xl font-semibold leading-tight tracking-[-0.03em] sm:text-6xl">少翻几次规则书，<br class="hidden sm:block">多花点时间玩游戏。</h1>
          <p class="mt-6 max-w-2xl text-base leading-8 text-ink/60">{{ latestPlan ? latestPlan.premise : '把手边的规则书放进来，整理出一份适合这桌玩家的讲解。需要确认细节时，再回到对应页查看原文。' }}</p>
          <div class="mt-8 flex flex-wrap gap-3">
            <RouterLink v-if="latestPlan" :to="{ name: 'lesson', params: { planId: latestPlan.id } }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">继续上次的讲解</RouterLink>
            <RouterLink v-else :to="{ name: 'teach' }" class="inline-flex min-h-12 items-center rounded-lg bg-copper px-5 font-semibold text-white hover:bg-copper-dark">添加规则书</RouterLink>
            <RouterLink :to="{ name: latestPlan ? 'teach' : 'lessons' }" class="inline-flex min-h-12 items-center rounded-lg border border-ink/15 bg-paper px-5 font-semibold hover:border-ink/30">{{ latestPlan ? '准备新讲解' : '我的讲解' }}</RouterLink>
          </div>
        </div>

        <aside class="border-l-2 border-copper/50 pl-5 text-sm leading-7 text-ink/60">
          <p class="font-semibold text-ink">{{ latestPlan ? `${latestPlan.playerCount} 人 · ${latestPlan.durationMinutes} 分钟` : '开桌前可以这样用' }}</p>
          <p class="mt-2">{{ latestPlan ? '阅读位置保存在当前设备，可以随时回来继续。' : '选好游戏版本和人数，先跟着讲解完成摆桌，再按顺序说明回合、结束条件和计分。' }}</p>
        </aside>
      </section>

      <section v-if="recentPlans.length" class="border-b border-ink/10 py-10">
        <div class="flex items-center justify-between gap-4">
          <h2 class="font-display text-2xl font-semibold">最近准备的讲解</h2>
          <RouterLink :to="{ name: 'lessons' }" class="text-sm font-semibold text-indigo">查看全部</RouterLink>
        </div>
        <ol class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
          <li v-for="plan in recentPlans" :key="plan.id">
            <RouterLink :to="{ name: 'lesson', params: { planId: plan.id } }" class="group grid gap-1 py-4 sm:grid-cols-[1fr_auto] sm:items-center">
              <span class="font-semibold">{{ plan.gameTitle }}</span>
              <span class="text-sm text-ink/45">{{ plan.playerCount }} 人 · {{ plan.durationMinutes }} 分钟 · {{ createdLabel(plan.createdAt) }} →</span>
            </RouterLink>
          </li>
        </ol>
      </section>

      <section class="py-14">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-ink/45">常用入口</p>
            <h2 class="mt-2 font-display text-3xl font-semibold">现在要做什么？</h2>
          </div>
        </div>

        <div class="mt-7 divide-y divide-ink/10 border-y border-ink/10">
          <RouterLink :to="{ name: 'catalog' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">找一款游戏</span>
            <span class="text-sm leading-6 text-ink/50">从 BGG 读取资料，或自己添加游戏和版本。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink :to="{ name: 'teach' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">整理规则书</span>
            <span class="text-sm leading-6 text-ink/50">上传 PDF，确认内容是否足够支持一场完整讲解。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
          <RouterLink :to="{ name: 'lessons' }" class="group grid gap-2 py-5 sm:grid-cols-[10rem_1fr_auto] sm:items-center">
            <span class="font-semibold">回到讲解</span>
            <span class="text-sm leading-6 text-ink/50">从上次的位置继续，或换一种人数和时长重新准备。</span>
            <span class="text-ink/35 transition-transform group-hover:translate-x-1" aria-hidden="true">→</span>
          </RouterLink>
        </div>
      </section>

      <footer class="flex flex-col gap-2 border-t border-ink/10 py-7 text-xs leading-5 text-ink/40 sm:flex-row sm:items-center sm:justify-between">
        <p>讲解中的规则结论可以回到原始页码核对。</p>
        <RouterLink :to="{ name: 'model-settings' }" class="hover:text-ink">模型设置</RouterLink>
      </footer>
    </div>
  </AppShell>
</template>
