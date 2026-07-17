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
            <span class="block text-[0.65rem] font-semibold uppercase tracking-[0.24em] text-ink/45">table-side companion</span>
          </span>
        </RouterLink>

        <div class="flex items-center gap-2">
          <span class="hidden rounded-full border border-ink/10 bg-paper/60 px-3 py-1.5 text-xs font-medium text-ink/55 sm:inline-flex">Phase 0 · Foundation</span>
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
          <p class="eyebrow">RULES, WITH RECEIPTS</p>
          <h1 class="mt-5 max-w-3xl font-display text-5xl font-semibold leading-[0.98] tracking-[-0.045em] text-ink sm:text-7xl lg:text-[5.5rem]">
            把规则争议，<span class="text-copper">变成可以核对的答案。</span>
          </h1>
          <p class="mt-7 max-w-xl text-base leading-8 text-ink/65 sm:text-lg">
            RulePilot 是陪你坐在桌边的规则副驾驶。选择游戏、版本和扩展，得到带页码引用的讲解与实时答疑。
          </p>
          <div class="mt-9 flex flex-col gap-3 sm:flex-row">
            <RouterLink :to="{ name: 'teach' }" :class="buttonVariants({ variant: 'primary', size: 'lg' })">
              开始讲解 <span aria-hidden="true">↗</span>
            </RouterLink>
            <RouterLink :to="{ name: 'search' }" :class="buttonVariants({ variant: 'outline', size: 'lg' })">
              快速查规则
            </RouterLink>
          </div>
          <p class="mt-5 text-xs font-medium text-ink/45">答案会标明适用版本、扩展范围与证据位置。</p>
        </div>

        <aside class="relative mx-auto w-full max-w-md lg:ml-auto" aria-label="规则答案示例">
          <div class="rounded-[2rem] border border-ink/10 bg-ink-panel p-5 text-panel-text shadow-[0_30px_80px_-36px_rgba(26,35,42,0.65)] sm:p-7">
            <div class="flex items-center justify-between border-b border-white/10 pb-5">
              <div>
                <p class="text-xs font-semibold uppercase tracking-[0.22em] text-panel-text/45">LIVE RULE CHECK</p>
                <p class="mt-2 font-display text-lg font-semibold">Wingspan · 欧洲版</p>
              </div>
              <span class="rounded-full bg-emerald-400/15 px-3 py-1.5 text-xs font-semibold text-emerald-200">有依据</span>
            </div>
            <div class="py-6">
              <p class="text-xs font-semibold uppercase tracking-[0.2em] text-panel-text/40">你的问题</p>
              <p class="mt-3 text-xl font-medium leading-8">“鸟类食物不足时，可以使用储存的食物吗？”</p>
            </div>
            <div class="rounded-2xl bg-white/8 p-4">
              <div class="flex gap-3">
                <span class="mt-0.5 grid size-7 shrink-0 place-items-center rounded-full bg-copper text-sm font-bold">✓</span>
                <div>
                  <p class="font-semibold">可以，但仅限于本轮行动要求的食物。</p>
                  <p class="mt-2 text-sm leading-6 text-panel-text/60">依据基础规则第 7 页“喂食鸟类”段落。</p>
                </div>
              </div>
            </div>
            <div class="mt-5 flex items-center justify-between text-xs text-panel-text/40">
              <span>基础版 · 2.0</span>
              <span>第 7 页 · 规则原文</span>
            </div>
          </div>
          <div class="absolute -bottom-5 -left-4 rounded-2xl border border-copper/20 bg-[#fffaf2] px-4 py-3 shadow-xl shadow-copper/10 sm:-left-8" aria-hidden="true">
            <p class="text-[0.65rem] font-semibold uppercase tracking-[0.18em] text-copper">CITATION FIRST</p>
            <p class="mt-1 text-sm font-semibold text-ink">先给结论，再展开证据</p>
          </div>
        </aside>
      </section>
    </div>

    <section class="border-y border-ink/10 bg-paper/35">
      <div class="mx-auto grid w-full max-w-7xl gap-px px-5 sm:grid-cols-3 sm:px-8 lg:px-12">
        <div class="border-ink/10 py-8 sm:border-r sm:pr-8">
          <p class="text-3xl font-display font-semibold tracking-tight">01</p>
          <p class="mt-2 font-semibold">先确定范围</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">游戏、版本、扩展和当前阶段都清楚可见。</p>
        </div>
        <div class="border-ink/10 py-8 sm:border-r sm:px-8">
          <p class="text-3xl font-display font-semibold tracking-tight">02</p>
          <p class="mt-2 font-semibold">再给出裁定</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">短结论优先，复杂规则逐层展开。</p>
        </div>
        <div class="py-8 sm:pl-8">
          <p class="text-3xl font-display font-semibold tracking-tight">03</p>
          <p class="mt-2 font-semibold">最后交付证据</p>
          <p class="mt-2 text-sm leading-6 text-ink/55">引用页码和原文位置，方便全桌复核。</p>
        </div>
      </div>
    </section>

    <section class="mx-auto w-full max-w-7xl px-5 py-20 sm:px-8 lg:px-12 lg:py-28">
      <div class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="eyebrow">A CALMER TABLE</p>
          <h2 class="mt-4 max-w-xl font-display text-4xl font-semibold tracking-tight sm:text-5xl">把注意力留给游戏本身。</h2>
        </div>
        <RouterLink :to="{ name: 'session' }" class="text-sm font-semibold text-indigo underline decoration-indigo/30 underline-offset-4 hover:decoration-indigo">了解实时桌局 <span aria-hidden="true">→</span></RouterLink>
      </div>
      <div class="mt-10 grid gap-4 md:grid-cols-3">
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-copper/12 text-xl text-copper" aria-hidden="true">⌁</span>
          <h3 class="mt-8 font-display text-xl font-semibold">版本不会混淆</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">把基础版、扩展和本地修订放到同一个明确上下文里。</p>
        </article>
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-indigo/10 text-xl text-indigo" aria-hidden="true">⌖</span>
          <h3 class="mt-8 font-display text-xl font-semibold">证据随答案到达</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">每个结论都指向规则书中的具体位置，不靠“听起来像对的”。</p>
        </article>
        <article class="rounded-3xl border border-ink/10 bg-paper/55 p-6 transition-transform duration-200 hover:-translate-y-1">
          <span class="grid size-11 place-items-center rounded-2xl bg-ink/8 text-xl text-ink" aria-hidden="true">↯</span>
          <h3 class="mt-8 font-display text-xl font-semibold">不确定性会被说清</h3>
          <p class="mt-3 text-sm leading-6 text-ink/60">证据不足时明确告诉你缺什么，避免把猜测当成裁定。</p>
        </article>
      </div>
    </section>

    <nav class="fixed inset-x-0 bottom-0 z-10 border-t border-ink/10 bg-canvas/90 px-5 py-3 backdrop-blur sm:hidden" aria-label="主要导航">
      <div class="mx-auto grid max-w-md grid-cols-3 gap-2 text-center text-xs font-medium">
        <RouterLink :to="{ name: 'teach' }" class="rounded-xl px-2 py-2 text-ink/60 hover:bg-ink/5 hover:text-ink">讲解</RouterLink>
        <RouterLink :to="{ name: 'session' }" class="rounded-xl bg-ink-panel px-2 py-2 text-panel-text">桌局</RouterLink>
        <RouterLink :to="{ name: 'search' }" class="rounded-xl px-2 py-2 text-ink/60 hover:bg-ink/5 hover:text-ink">查规则</RouterLink>
      </div>
    </nav>
  </main>
</template>
