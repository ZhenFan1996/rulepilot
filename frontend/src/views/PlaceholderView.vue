<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { buttonVariants } from '@/lib/variants'

const route = useRoute()
const eyebrow = computed(() => String(route.meta.eyebrow ?? 'RULEPILOT'))
const title = computed(() => String(route.meta.title ?? '功能即将到来'))
const description = computed(() => String(route.meta.description ?? '这一条产品路径正在建设中。'))
</script>

<template>
  <main class="grid min-h-screen place-items-center bg-canvas px-5 py-10 text-ink">
    <section class="w-full max-w-xl rounded-[2rem] border border-ink/10 bg-paper/75 p-8 text-center shadow-[0_24px_80px_-48px_rgba(26,35,42,0.6)] sm:p-12">
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1 class="mt-4 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ title }}</h1>
      <p class="mx-auto mt-5 max-w-md text-base leading-7 text-ink/65">{{ description }}</p>
      <RouterLink :to="{ name: 'home' }" :class="buttonVariants({ variant: 'primary', size: 'md' })" class="mt-8">
        返回首页
      </RouterLink>
    </section>
  </main>
</template>
