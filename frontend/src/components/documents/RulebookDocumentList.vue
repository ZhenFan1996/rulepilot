<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

import PlayerWorkStatusText from '@/components/PlayerWorkStatusText.vue'
import { useLocale } from '@/lib/locale'
import { playerWorkStatus } from '@/lib/playerWorkStatus'
import type { BggSuggestion, BggSuggestionState, DocumentResponse } from './types'

const props = defineProps<{
  loading: boolean
  documents: DocumentResponse[]
  suggestionStates: Record<string, BggSuggestionState>
  deletingDocumentId: string
  preparingVersionId: string
}>()

const emit = defineEmits<{
  'load-suggestions': [documentId: string]
  'select-suggestion': [documentId: string, bggId: number]
  'confirm-suggestion': [documentId: string]
  'start-lesson': [versionId: string]
  'request-delete': [entry: DocumentResponse]
}>()

const { locale, t } = useLocale()
const heading = ref<HTMLElement | null>(null)

function documentStatusLabel(status: string) {
  return {
    UPLOADED: t('documents.status.uploaded'),
    EXTRACTING: t('documents.status.extracting'),
    READY: t('documents.status.ready'),
    FAILED: t('documents.status.failed'),
  }[status] ?? t('documents.status.processing')
}

function documentWorkStatus(status: string) {
  if (status === 'READY') {
    return playerWorkStatus('RULEBOOK_READY', {
      capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'none',
    }, locale.value)
  }
  if (status === 'FAILED') {
    return playerWorkStatus('NEEDS_ACTION', {
      capability: 'none', readiness: 'unavailable', terminality: 'terminal', outcome: 'needs-action',
    }, locale.value)
  }
  return playerWorkStatus('READING_RULEBOOK', {
    capability: 'none', readiness: 'unavailable', terminality: 'active', outcome: 'none',
  }, locale.value)
}

function suggestionState(documentId: string) {
  return props.suggestionStates[documentId]
}

function candidatePlayerLabel(candidate: BggSuggestion) {
  if (candidate.minPlayers == null || candidate.maxPlayers == null) return ''
  return candidate.minPlayers === candidate.maxPlayers
    ? t('documents.bgg.playersExact', { players: candidate.minPlayers })
    : t('documents.bgg.playersRange', { min: candidate.minPlayers, max: candidate.maxPlayers })
}

function focusTarget() {
  return heading.value
}

defineExpose({ focusTarget })
</script>

<template>
  <section class="mt-14 border-t border-ink/10 pt-8">
    <div class="flex items-center justify-between gap-4">
      <div>
        <h2 ref="heading" tabindex="-1" class="font-display text-2xl font-semibold outline-none">{{ t('documents.list.title') }}</h2>
        <p class="mt-1 text-sm text-muted">{{ t('documents.list.description') }}</p>
      </div>
      <RouterLink :to="{ name: 'catalog' }" class="shrink-0 text-sm font-semibold text-indigo">{{ t('documents.list.manage') }}</RouterLink>
    </div>
    <p v-if="loading" class="mt-5 text-sm text-muted">{{ t('documents.list.loading') }}</p>
    <div v-else-if="documents.length === 0" class="mt-5 rounded-xl border border-dashed border-ink/20 p-8 text-center">
      <p class="font-semibold">{{ t('documents.empty.title') }}</p>
      <p class="mt-2 text-sm text-muted">{{ t('documents.empty.description') }}</p>
    </div>
    <ul v-else class="mt-5 divide-y divide-ink/10 border-y border-ink/10">
      <li v-for="entry in documents" :key="entry.document.id" class="py-5">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div class="min-w-0">
            <p class="truncate font-semibold">{{ entry.document.title }}</p>
            <PlayerWorkStatusText
              :status="documentWorkStatus(entry.latestVersion.status)"
              class="mt-1 text-sm font-semibold text-muted"
            />
            <p class="mt-1 text-xs text-muted">{{ documentStatusLabel(entry.latestVersion.status) }} · {{ Math.ceil(entry.latestVersion.size / 1024) }} KiB</p>
          </div>
          <div class="flex shrink-0 flex-wrap gap-2">
            <button v-if="entry.latestVersion.status === 'READY'" type="button" :disabled="suggestionState(entry.document.id)?.status === 'loading' || Boolean(deletingDocumentId)" class="min-h-11 rounded-lg border border-indigo/20 px-4 py-2.5 text-sm font-semibold text-indigo hover:border-indigo/50 disabled:opacity-40" @click="emit('load-suggestions', entry.document.id)">{{ suggestionState(entry.document.id)?.status === 'loading' ? t('documents.bgg.loading') : t('documents.bgg.open') }}</button>
            <RouterLink v-if="entry.latestVersion.status === 'READY'" :to="{ name: 'rulebook-reader', params: { versionId: entry.latestVersion.id } }" class="inline-flex min-h-11 items-center rounded-lg bg-indigo px-4 py-2.5 text-sm font-semibold text-white">{{ t('documents.read') }}</RouterLink>
            <button v-if="entry.latestVersion.status === 'READY'" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold hover:border-copper/50 disabled:opacity-40" @click="emit('start-lesson', entry.latestVersion.id)">{{ t('documents.start') }}</button>
            <button type="button" :disabled="Boolean(preparingVersionId) || Boolean(deletingDocumentId)" class="rounded-lg px-3 py-2.5 text-sm font-semibold text-muted hover:bg-red-50 hover:text-red-700 disabled:opacity-40" @click="emit('request-delete', entry)">{{ deletingDocumentId === entry.document.id ? t('documents.deleting') : t('documents.delete') }}</button>
          </div>
        </div>
        <div v-if="suggestionState(entry.document.id)" class="mt-4 rounded-xl border border-indigo/15 bg-indigo/[0.035] p-4">
          <p v-if="suggestionState(entry.document.id)?.status === 'loading'" class="text-sm text-muted" role="status">{{ t('documents.bgg.loadingDetail') }}</p>
          <div v-else-if="suggestionState(entry.document.id)?.status === 'error'" role="alert">
            <p class="text-sm leading-6 text-red-700">{{ t('documents.bgg.error') }}</p>
            <button type="button" class="mt-3 min-h-11 rounded-lg border border-red-200 px-4 py-2 text-sm font-semibold text-red-700" @click="emit('load-suggestions', entry.document.id)">{{ t('documents.bgg.retry') }}</button>
          </div>
          <div v-else-if="suggestionState(entry.document.id)?.candidates.length === 0">
            <p class="text-sm font-semibold">{{ t('documents.bgg.noneTitle') }}</p>
            <p class="mt-1 text-sm leading-6 text-muted">{{ t('documents.bgg.noneDetail') }}</p>
          </div>
          <template v-else>
            <p class="text-sm font-semibold">{{ suggestionState(entry.document.id)!.candidates.length === 1 ? t('documents.bgg.oneTitle') : t('documents.bgg.manyTitle', { count: suggestionState(entry.document.id)!.candidates.length }) }}</p>
            <p class="mt-1 text-xs leading-5 text-muted">{{ t('documents.bgg.review') }}</p>
            <ul class="mt-4 grid gap-3 lg:grid-cols-2">
              <li v-for="candidate in suggestionState(entry.document.id)!.candidates" :key="candidate.bggId" class="flex gap-3 rounded-lg border bg-paper p-3" :class="suggestionState(entry.document.id)?.selectedBggId === candidate.bggId ? 'border-indigo/50 ring-1 ring-indigo/20' : 'border-ink/10'">
                <img v-if="candidate.coverUrl" :src="candidate.coverUrl" :alt="t('documents.bgg.coverAlt', { name: candidate.name })" class="h-24 w-20 shrink-0 rounded object-contain" loading="lazy">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <p class="font-semibold">{{ candidate.name }}<span v-if="candidate.publicationYear" class="font-normal text-muted"> · {{ candidate.publicationYear }}</span></p>
                    <span v-if="candidate.normalizedTitleMatch" class="rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-800">{{ t('documents.bgg.titleMatch') }}</span>
                  </div>
                  <p v-if="candidatePlayerLabel(candidate) || candidate.playingTimeMinutes" class="mt-1 text-xs text-muted">
                    {{ [candidatePlayerLabel(candidate), candidate.playingTimeMinutes ? t('documents.bgg.minutes', { minutes: candidate.playingTimeMinutes }) : ''].filter(Boolean).join(' · ') }}
                  </p>
                  <div class="mt-3 flex flex-wrap items-center gap-3">
                    <button type="button" class="min-h-11 rounded-lg bg-indigo px-3 py-2 text-sm font-semibold text-white" :aria-pressed="suggestionState(entry.document.id)?.selectedBggId === candidate.bggId" @click="emit('select-suggestion', entry.document.id, candidate.bggId)">{{ suggestionState(entry.document.id)?.selectedBggId === candidate.bggId ? t('documents.bgg.selected') : t('documents.bgg.select') }}</button>
                    <a :href="candidate.bggUrl" target="_blank" rel="noopener noreferrer" class="py-2 text-xs font-semibold text-indigo underline underline-offset-2">{{ t('documents.bgg.view') }}</a>
                  </div>
                </div>
              </li>
            </ul>
            <div v-if="suggestionState(entry.document.id)?.selectedBggId" class="mt-4 rounded-lg bg-indigo/8 px-3 py-3">
              <p class="text-sm leading-6 text-indigo">{{ t('documents.bgg.handoff') }}</p>
              <button v-if="suggestionState(entry.document.id)?.linkStatus !== 'linked'" type="button" :disabled="suggestionState(entry.document.id)?.linkStatus === 'confirming'" class="mt-3 min-h-11 rounded-lg bg-ink px-4 py-2 text-sm font-semibold text-paper disabled:opacity-50" @click="emit('confirm-suggestion', entry.document.id)">{{ suggestionState(entry.document.id)?.linkStatus === 'confirming' ? t('documents.bgg.confirming') : t('documents.bgg.confirm') }}</button>
              <p v-if="suggestionState(entry.document.id)?.linkStatus === 'error'" class="mt-2 text-sm text-red-700" role="alert">{{ t('documents.bgg.linkError') }}</p>
              <p v-if="suggestionState(entry.document.id)?.linkStatus === 'linked'" class="mt-2 text-sm font-semibold text-emerald-800" role="status">{{ suggestionState(entry.document.id)?.linkAlreadyImported ? t('documents.bgg.reused') : t('documents.bgg.linked') }}</p>
            </div>
            <p class="mt-4 text-[11px] text-muted">{{ t('documents.bgg.attribution') }}</p>
          </template>
        </div>
      </li>
    </ul>
  </section>
</template>
