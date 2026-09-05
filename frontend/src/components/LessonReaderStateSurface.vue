<script setup lang="ts">
import { useLocale } from '@/lib/locale'

defineProps<{
  errorMessage: string
  online: boolean
}>()

const emit = defineEmits<{
  retry: []
}>()

const { t } = useLocale()
</script>

<template>
  <section v-if="errorMessage" class="mx-auto max-w-xl px-5 py-20 text-center">
    <p class="font-display text-2xl font-semibold">{{ t('lesson.reader.state.error.title') }}</p>
    <p class="mt-3 text-ink/60" role="alert">{{ online ? errorMessage : t('lesson.reader.state.error.offline') }}</p>
    <button v-if="online" class="mt-6 rounded-xl bg-copper px-5 py-3 font-semibold text-on-accent" @click="emit('retry')">{{ t('lesson.reader.state.error.retry') }}</button>
  </section>
  <section v-else class="mx-auto max-w-xl px-5 py-20 text-center">
    <h1 class="font-display text-4xl font-semibold">{{ t('lesson.reader.state.empty.title') }}</h1>
    <p class="mt-4 leading-7 text-ink/60">{{ t('lesson.reader.state.empty.detail') }}</p>
    <RouterLink :to="{ name: 'teach' }" class="mt-7 inline-flex rounded-xl bg-copper px-5 py-3 font-semibold text-on-accent">{{ t('lesson.reader.state.empty.action') }}</RouterLink>
  </section>
</template>
