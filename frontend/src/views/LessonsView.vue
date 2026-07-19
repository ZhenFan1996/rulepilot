<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'

interface TeachingPlan {
  id: string
  documentVersionId: string
  playerCount: number
  beginnerCount: number
  durationMinutes: number
  gameTitle: string
  premise: string
  createdAt: string
  sections: Array<{ required: boolean; topicKey: string; title: string }>
}

const router = useRouter()
const plans = ref<TeachingPlan[]>([])
const loading = ref(true)
const errorMessage = ref('')
const rememberedPlanId = localStorage.getItem('rulepilot:last-plan-id')

const readyCount = computed(
  () => plans.value.length,
)

function isReady(plan: TeachingPlan) {
  return plan.sections.length > 0
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
  <AppShell>
    <section class="mx-auto max-w-6xl px-5 py-10 sm:px-8 lg:px-12 lg:py-14">
      <p class="text-sm font-medium text-copper">我的讲解</p>
      <div class="mt-4 flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 class="font-display text-4xl font-semibold tracking-tight">从上次停下的地方继续</h1>
          <p class="mt-4 max-w-2xl leading-7 text-ink/55">这里保存了你准备过的讲解。阅读位置只留在当前设备。</p>
        </div>
        <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center justify-center rounded-lg bg-copper px-4 text-sm font-semibold text-white">准备新讲解</RouterLink>
      </div>

      <p v-if="plans.length" class="mt-6 text-sm text-ink/45">共 {{ plans.length }} 份，其中 {{ readyCount }} 份可以直接开始。</p>

      <div v-if="loading" class="mt-8 rounded-xl border border-ink/10 bg-paper p-8 text-ink/50" role="status">正在读取讲解…</div>

      <div v-else-if="errorMessage" class="mt-10 rounded-3xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p>
        <button class="mt-4 text-sm font-semibold underline underline-offset-4" @click="loadPlans">重新加载</button>
      </div>

      <div v-else-if="plans.length === 0" class="mt-8 rounded-xl border border-dashed border-ink/20 px-6 py-14 text-center">
        <h2 class="font-display text-2xl font-semibold">还没有准备过讲解</h2>
        <p class="mx-auto mt-3 max-w-lg leading-7 text-ink/55">先添加一本规则书，选择玩家人数和预计时长。</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-lg bg-copper px-5 py-3 font-semibold text-white">添加规则书</RouterLink>
      </div>

      <ol v-else class="mt-10 grid gap-5 md:grid-cols-2">
        <li v-for="plan in plans" :key="plan.id" class="rounded-xl border border-ink/10 bg-paper p-6">
          <div class="flex items-start justify-between gap-4">
            <div>
              <p class="text-xs font-medium text-ink/40">{{ createdLabel(plan.createdAt) }}</p>
              <h2 class="mt-2 font-display text-2xl font-semibold">{{ plan.gameTitle }}</h2>
            </div>
            <span :class="isReady(plan) ? 'bg-emerald-50 text-emerald-800' : 'bg-amber-50 text-amber-800'" class="rounded-full px-3 py-1.5 text-xs font-semibold">
              {{ isReady(plan) ? '目录已生成' : '等待整理' }}
            </span>
          </div>
          <dl class="mt-6 grid grid-cols-2 gap-3 rounded-2xl bg-canvas p-4 text-sm">
            <div><dt class="text-ink/45">新手人数</dt><dd class="mt-1 font-semibold">{{ plan.beginnerCount }} 人</dd></div>
            <div><dt class="text-ink/45">计划时长</dt><dd class="mt-1 font-semibold">{{ plan.durationMinutes }} 分钟</dd></div>
            <div class="col-span-2"><dt class="text-ink/45">适合</dt><dd class="mt-1 font-semibold">{{ plan.beginnerCount ? `${plan.beginnerCount} 位新手` : '熟悉桌游的玩家' }}</dd></div>
          </dl>
          <p class="mt-4 line-clamp-2 text-sm leading-6 text-ink/55">{{ plan.premise }}</p>
          <div class="mt-6 flex items-center justify-between gap-3">
            <span v-if="plan.id === rememberedPlanId" class="text-xs font-semibold text-indigo">上次打开</span>
            <span v-else class="text-xs text-ink/35">{{ plan.sections.length }} 个章节</span>
            <RouterLink :to="{ name: 'lesson', params: { planId: plan.id } }" class="rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">打开</RouterLink>
          </div>
        </li>
      </ol>
    </section>
  </AppShell>
</template>
