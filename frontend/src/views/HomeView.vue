<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

import BrandButton from '@/components/ui/BrandButton.vue'
import { buttonVariants } from '@/lib/variants'

const isDark = ref(document.documentElement.classList.contains('dark'))

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
}
</script>

<template>
  <main class="min-h-screen overflow-hidden bg-canvas pb-20 text-ink sm:pb-0">
    <div class="relative isolate">
      <div class="pointer-events-none absolute -right-24 -top-32 -z-10 h-80 w-80 rounded-full bg-copper/15 blur-3xl" aria-hidden="true" />
      <div class="pointer-events-none absolute -left-40 top-[32rem] -z-10 h-96 w-96 rounded-full bg-indigo/10 blur-3xl" aria-hidden="true" />

      <header class="mx-auto flex w-full max-w-7xl items-center justify-between px-5 py-5 sm:px-8 lg:px-12">
        <RouterLink :to="{ name: 'home' }" class="group inline-flex items-center gap-3" aria-label="RulePilot 首页">
          <span class="grid size-10 place-items-center rounded-2xl bg-ink text-lg font-semibold text-canvas shadow-lg shadow-ink/10">R</span>
          <span>
            <span class="block font-display text-lg font-semibold tracking-tight">RulePilot</span>
            <span class="block text-[0.65rem] font-semibold uppercase tracking-[0.24em] text-ink/45">rulebook learning guide</span>
          </span>
        </RouterLink>

        <div class="flex items-center gap-2">
          <RouterLink :to="{ name: 'catalog' }" class="hidden text-sm font-semibold text-ink/65 hover:text-indigo md:inline-flex">游戏目录</RouterLink>
          <RouterLink :to="{ name: 'login' }" class="hidden text-sm font-semibold text-ink/65 hover:text-indigo sm:inline-flex">登录</RouterLink>
          <span class="hidden rounded-full border border-ink/10 bg-paper/60 px-3 py-1.5 text-xs font-medium text-ink/55 sm:inline-flex">从规则书到完整讲解</span>
          <BrandButton
            variant="ghost"
            size="sm"
            class="grid !size-10 !min-h-10 !rounded-full !border !border-ink/10 !bg-paper/60 !px-0 text-sm !text-ink/70 hover:!border-copper/50 hover:!text-ink"
            :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'"
            @click="toggleTheme"
          >
            <span aria-hidden="true">{{ isDark ? '☼' : '◐' }}</span>
          </BrandButton>
        </div>
      </header>

      <section class="mx-auto grid w-full max-w-7xl gap-12 px-5 pb-20 pt-12 sm:px-8 sm:pt-20 lg:grid-cols-[1.15fr_0.85fr] lg:items-center lg:px-12 lg:pb-28 lg:pt-24">
        <div>
          <p class="eyebrow">FROM RULEBOOK TO LESSON</p>
          <h1 class="mt-5 max-w-3xl font-display text-5xl font-semibold leading-[0.98] tracking-[-0.045em] text-ink sm:text-7xl lg:text-[5.5rem]">
            读完规则书，<span class="text-copper">把一局游戏完整讲清楚。</span>
          </h1>
          <p class="mt-7 max-w-xl text-base leading-8 text-ink/65 sm:text-lg">
            导入规则书后，RulePilot 会从组件和 setup 开始，逐步讲解回合流程、行动细节、结束条件与计分，再围绕讲解内容继续答疑。
          </p>
          <div class="mt-9 flex flex-col gap-3 sm:flex-row">
            <RouterLink :to="{ name: 'teach' }" :class="buttonVariants({ variant: 'primary', size: 'lg' })">
              导入规则书 <span aria-hidden="true">↗</span>
            </RouterLink>
            <RouterLink :to="{ name: 'lesson' }" :class="buttonVariants({ variant: 'outline', size: 'lg' })">
              查看讲解流程
            </RouterLink>
          </div>
          <p class="mt-5 text-xs font-medium text-ink/45">每个讲解步骤保留适用版本、扩展范围与规则页码。</p>
        </div>

        <aside class="relative mx-auto w-full max-w-md lg:ml-auto" aria-label="规则讲解目录示例">
          <div class="rounded-[2rem] border border-ink/10 bg-ink-panel p-5 text-panel-text shadow-[0_30px_80px_-36px_rgba(26,35,42,0.65)] sm:p-7">
            <div class="flex items-center justify-between border-b border-white/10 pb-5">
              <div>
                <p class="text-xs font-semibold uppercase tracking-[0.22em] text-panel-text/45">GUIDED LESSON</p>
                <p class="mt-2 font-display text-lg font-semibold">Wingspan · 基础版</p>
              </div>
              <span class="rounded-full bg-emerald-400/15 px-3 py-1.5 text-xs font-semibold text-emerald-200">18 分钟</span>
            </div>
            <div class="space-y-3 py-6">
              <div class="flex items-center gap-3 rounded-2xl bg-white/8 p-3">
                <span class="grid size-7 shrink-0 place-items-center rounded-full bg-copper text-sm font-bold">1</span>
                <div class="flex-1">
                  <p class="font-semibold">组件与 Setup</p>
                  <p class="mt-1 text-xs text-panel-text/50">6 个步骤 · 第 2–5 页</p>
                </div>
                <span class="text-emerald-200" aria-hidden="true">✓</span>
              </div>
              <div class="flex items-center gap-3 rounded-2xl bg-white/8 p-3">
                <span class="grid size-7 shrink-0 place-items-center rounded-full bg-copper text-sm font-bold">2</span>
                <div class="flex-1">
                  <p class="font-semibold">回合与行动流程</p>
                  <p class="mt-1 text-xs text-panel-text/50">4 类行动 · 第 6–11 页</p>
                </div>
                <span class="text-panel-text/35" aria-hidden="true">▶</span>
              </div>
              <div class="flex items-center gap-3 rounded-2xl bg-white/5 p-3 text-panel-text/55">
                <span class="grid size-7 shrink-0 place-items-center rounded-full border border-white/15 text-sm font-bold">3</span>
                <div class="flex-1">
                  <p class="font-semibold">结束条件与计分</p>
                  <p class="mt-1 text-xs">最终计分 · 第 14–15 页</p>
                </div>
              </div>
            </div>
            <div class="mt-5 flex items-center justify-between text-xs text-panel-text/40">
              <span>图文 + 语音</span>
              <span>进度 35%</span>
            </div>
          </div>
          <div class="absolute -bottom-5 -left-4 rounded-2xl border border-copper/20 bg-[#fffaf2] px-4 py-3 shadow-xl shadow-copper/10 sm:-left-8" aria-hidden="true">
            <p class="text-[0.65rem] font-semibold uppercase tracking-[0.18em] text-copper">STEP BY STEP</p>
            <p class="mt-1 text-sm font-semibold text-[#1a232a]">从摆桌到最终计分</p>
          </div>
        </aside>
      </section>
    </div>

    <section class="border-y border-ink/10 bg-paper/35">
      <div class="mx-auto grid w-full max-w-7xl gap-px px-5 sm:grid-cols-3 sm:px-8 lg:px-12">
        <div class="border-ink/10 py-8 sm:border-r sm:pr-8">
          <p class="text-3xl font-display font-semibold tracking-tight">01</p>
          <p class="mt-2 font-semibold">读取并确认规则书</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">识别页码、正文、图片和表格，确认版本与扩展范围。</p>
        </div>
        <div class="border-ink/10 py-8 sm:border-r sm:px-8">
          <p class="text-3xl font-display font-semibold tracking-tight">02</p>
          <p class="mt-2 font-semibold">组织完整讲解</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">从目标、组件和 setup，一直讲到结束条件与计分。</p>
        </div>
        <div class="py-8 sm:pl-8">
          <p class="text-3xl font-display font-semibold tracking-tight">03</p>
          <p class="mt-2 font-semibold">选择学习形式</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">用图文、语音或分章节视频按自己的节奏学习。</p>
        </div>
      </div>
    </section>

    <section class="mx-auto w-full max-w-7xl px-5 py-20 sm:px-8 lg:px-12 lg:py-28">
      <div class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="eyebrow">ONE LESSON, MANY FORMATS</p>
          <h2 class="mt-4 max-w-xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">同一套规则，选择最适合你的讲法。</h2>
        </div>
        <RouterLink :to="{ name: 'questions' }" class="text-sm font-semibold text-indigo underline decoration-indigo/30 underline-offset-4 hover:decoration-indigo">讲解后继续答疑 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="mt-10 grid gap-4 md:grid-cols-3">
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-copper/12 text-xl text-copper" aria-hidden="true">⌁</span>
          <h3 class="mt-8 font-display text-xl font-semibold">图文步骤</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">用组件图、摆放示意、步骤文字和页码完成可快速浏览的讲解。</p>
        </article>
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-indigo/10 text-xl text-indigo" aria-hidden="true">⌖</span>
          <h3 class="mt-8 font-display text-xl font-semibold">语音解说</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">讲解、字幕和阅读进度同步，支持暂停、调速和重复当前步骤。</p>
        </article>
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-ink/8 text-xl text-ink" aria-hidden="true">↯</span>
          <h3 class="mt-8 font-display text-xl font-semibold">分章节视频</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">将示意画面、字幕和解说组合起来，并可直接跳到 setup 或计分。</p>
        </article>
      </div>
    </section>

    <nav class="fixed inset-x-0 bottom-0 z-10 border-t border-ink/10 bg-canvas/90 px-5 py-3 backdrop-blur sm:hidden" aria-label="主要导航">
      <div class="mx-auto grid max-w-md grid-cols-3 gap-2 text-center text-xs font-medium">
        <RouterLink :to="{ name: 'teach' }" class="rounded-xl bg-ink-panel px-2 py-2 text-panel-text">导入</RouterLink>
        <RouterLink :to="{ name: 'lesson' }" class="rounded-xl px-2 py-2 text-ink/60 hover:bg-ink/5 hover:text-ink">讲解</RouterLink>
        <RouterLink :to="{ name: 'questions' }" class="rounded-xl px-2 py-2 text-ink/60 hover:bg-ink/5 hover:text-ink">答疑</RouterLink>
      </div>
    </nav>
  </main>
</template>
