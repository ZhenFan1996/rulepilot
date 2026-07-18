<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import { buttonVariants } from '@/lib/variants'

const route = useRoute()
const title = computed(() => String(route.meta.title ?? '功能即将到来'))
const description = computed(() => String(route.meta.description ?? '这一条产品路径正在建设中。'))
</script>

<template>
  <AppShell>
    <section class="mx-auto w-full max-w-xl px-5 py-20 text-center sm:p-12">
      <h1 class="font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ title }}</h1>
      <p class="mx-auto mt-5 max-w-md text-base leading-7 text-ink/65">{{ description }}</p>
      <RouterLink :to="{ name: 'home' }" :class="buttonVariants({ variant: 'primary', size: 'md' })" class="mt-8">
        返回首页
      </RouterLink>
    </section>
  </AppShell>
</template>
