<script setup lang="ts">
import { computed } from 'vue'

import { useLocale } from '@/lib/locale'
import { playerFacingLanguageName } from '@/lib/playerFacingLanguage'
import type {
  RulebookCandidate,
  RulebookDiscoveryCopy,
  RulebookDiscoveryStatus,
  RulebookDiscoverySummary,
  SelectedEditionContext,
} from './types'

const props = defineProps<{
  selectedEdition: SelectedEditionContext | null
  status: RulebookDiscoveryStatus
  candidates: RulebookCandidate[]
  elapsedSeconds: number
  discoverySummary: RulebookDiscoverySummary | null
  copy: RulebookDiscoveryCopy
}>()

const emit = defineEmits<{
  discover: []
  choose: [candidate: RulebookCandidate]
}>()

const { locale, t } = useLocale()
const hasImportableCandidate = computed(() => props.candidates.some(isImportable))
const sourceCandidates = computed(() => props.candidates.filter(candidate => candidate.capability !== 'GAME_INFO_ONLY'))
const identityCandidates = computed(() => props.candidates.filter(candidate => candidate.capability === 'GAME_INFO_ONLY'))
const terminalNotice = computed(() => {
  const summary = props.discoverySummary
  if (!summary || summary.completion === 'COMPLETE') return ''
  return props.copy.terminal[summary.completion]
})
const terminalTiming = computed(() => {
  const summary = props.discoverySummary
  if (!summary) return ''
  return props.copy.terminalTiming(
    Math.max(1, Math.ceil(summary.elapsedMs / 1_000)),
    Math.max(1, Math.ceil(summary.totalBudgetMs / 1_000)),
  )
})

function isImportable(candidate: RulebookCandidate) {
  return candidate.capability === 'DIRECT_DOCUMENT' && candidate.acquisitionMode === 'DIRECT_PDF'
    || candidate.capability === 'CONTIGUOUS_RULE_PAGES' && candidate.acquisitionMode === 'IMAGE_GALLERY'
}

function canContinue(candidate: RulebookCandidate) {
  return isImportable(candidate)
    || candidate.capability === 'DOCUMENT_LISTING'
    || candidate.capability === 'UNVERIFIED_PAGE'
}

function actionLabel(candidate: RulebookCandidate) {
  if (isImportable(candidate)) return props.copy.use
  if (candidate.capability === 'DOCUMENT_LISTING') return props.copy.continueListing
  return props.copy.reviewUnverified
}

function candidateLanguage(candidate: RulebookCandidate) {
  const name = playerFacingLanguageName(candidate.language, locale.value)
  if (!candidate.language) return name
  return `${name}（${candidate.languageVerified
    ? props.copy.languageVerified
    : props.copy.languageReview}）`
}
</script>

<template>
  <div v-if="selectedEdition" class="mt-7 flex items-center gap-4 rounded-xl border border-copper/20 bg-copper/5 p-4 text-left">
    <img
      v-if="selectedEdition.bggMetadata?.thumbnailUrl"
      :src="selectedEdition.bggMetadata.thumbnailUrl"
      :alt="t('documents.game.selectedCover', { game: selectedEdition.game.name })"
      class="h-20 w-16 shrink-0 rounded-lg bg-paper object-contain"
      referrerpolicy="no-referrer"
    >
    <div class="min-w-0 flex-1">
      <p class="text-xs font-bold uppercase tracking-[0.12em] text-copper">{{ t('documents.game.selectedEyebrow') }}</p>
      <h2 class="mt-1 truncate font-display text-xl font-semibold">{{ selectedEdition.game.name }}</h2>
      <p class="mt-1 text-sm text-muted">{{ t('documents.game.selectedEdition', { edition: selectedEdition.edition.name }) }}</p>
      <a
        v-if="selectedEdition.bggMetadata?.bggUrl"
        :href="selectedEdition.bggMetadata.bggUrl"
        target="_blank"
        rel="noopener noreferrer"
        class="mt-1 inline-block text-xs font-semibold text-indigo"
      >{{ t('documents.game.selectedSource') }} ↗</a>
    </div>
  </div>

  <div v-if="selectedEdition" class="mt-4 text-left">
    <button
      type="button"
      :disabled="status === 'loading'"
      class="min-h-11 rounded-xl bg-indigo px-5 text-sm font-semibold text-white disabled:opacity-50"
      @click="emit('discover')"
    >
      {{ status === 'loading' ? `${copy.loading} · ${copy.elapsed(elapsedSeconds)}` : copy.action }}
    </button>
    <ol
      v-if="status === 'loading'"
      class="mt-4 grid gap-2 rounded-xl border border-indigo/15 bg-indigo/[0.035] p-4 text-sm sm:grid-cols-3"
      role="status"
    >
      <li v-for="(step, index) in copy.searchSteps" :key="step" class="flex items-center gap-2 text-muted">
        <span class="grid size-6 shrink-0 place-items-center rounded-full bg-indigo/10 text-xs font-bold text-indigo">{{ index + 1 }}</span>
        <span>{{ step }}</span>
      </li>
    </ol>
    <section
      v-if="status === 'success'"
      class="mt-4 rounded-xl border border-indigo/15 bg-paper p-4 sm:p-5"
      aria-live="polite"
    >
      <h2 class="font-display text-xl font-semibold">{{ hasImportableCandidate ? copy.title : copy.noImportableTitle }}</h2>
      <p class="mt-1 text-xs leading-5 text-muted">{{ hasImportableCandidate ? copy.detail : copy.noImportableDetail }}</p>
      <div
        v-if="terminalNotice && discoverySummary"
        data-testid="rulebook-discovery-summary"
        class="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-950"
        role="status"
      >
        <p>{{ terminalNotice }} {{ terminalTiming }}</p>
        <ul class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
          <li v-for="provider in discoverySummary.providers" :key="provider.provider">
            {{ copy.providers[provider.provider] }}: {{ copy.providerStates[provider.state] }}
          </li>
        </ul>
      </div>
      <ul v-if="sourceCandidates.length" class="mt-4 stack-y-md">
        <li v-for="candidate in sourceCandidates" :key="candidate.url" :data-capability="candidate.capability" class="rounded-lg border border-ink/10 bg-canvas p-4">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div class="min-w-0">
              <p class="font-semibold">{{ candidate.title }}</p>
              <p class="mt-1 break-all text-xs text-muted">{{ candidate.sourceDomain }}</p>
              <p class="mt-2 text-xs leading-5 text-muted">
                {{ copy.publisher }}: {{ candidate.publisher || '—' }} · {{ copy.language }}: {{ candidateLanguage(candidate) }} · {{ copy.edition }}: {{ candidate.edition || '—' }}
              </p>
              <p class="mt-1 text-xs font-semibold" :class="candidate.sourceType === 'PUBLIC_WEB' ? 'text-amber-700' : 'text-emerald-700'">
                {{ copy.sources[candidate.sourceType] }}
              </p>
              <p class="mt-1 text-xs text-muted">
                {{ candidate.acquisitionMode === 'DIRECT_PDF' ? copy.direct : candidate.acquisitionMode === 'IMAGE_GALLERY' ? copy.gallery : copy.page }}
              </p>
              <p class="mt-1 text-xs font-semibold text-indigo">{{ copy.capabilities[candidate.capability] }}</p>
            </div>
            <button
              v-if="canContinue(candidate)"
              type="button"
              class="min-h-11 shrink-0 rounded-lg border border-indigo/30 px-4 text-sm font-semibold text-indigo"
              @click="emit('choose', candidate)"
            >
              {{ actionLabel(candidate) }}
            </button>
          </div>
        </li>
      </ul>
      <p v-else-if="!candidates.length" class="mt-4 text-sm text-muted">{{ copy.empty }}</p>
      <div v-if="!hasImportableCandidate" class="mt-4 flex flex-wrap gap-x-4 gap-y-2">
        <button
          type="button"
          class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline underline-offset-2"
          @click="emit('discover')"
        >
          {{ copy.retrySearch }}
        </button>
        <a
          href="#rulebook-file"
          class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo underline underline-offset-2"
        >{{ copy.localUpload }}</a>
      </div>
      <section v-if="identityCandidates.length" class="mt-5 border-t border-ink/10 pt-4" :aria-label="copy.identityOnlyTitle">
        <h3 class="text-sm font-semibold text-ink/70">{{ copy.identityOnlyTitle }}</h3>
        <p class="mt-1 text-xs leading-5 text-muted">{{ copy.identityOnlyDetail }}</p>
        <ul class="mt-3 stack-y-sm">
          <li v-for="candidate in identityCandidates" :key="candidate.url" :data-capability="candidate.capability" class="rounded-lg border border-ink/10 bg-canvas p-3 text-xs">
            <p class="font-semibold text-ink/70">{{ candidate.title }}</p>
            <a :href="candidate.url" target="_blank" rel="noopener noreferrer" class="mt-1 block break-all font-semibold text-indigo underline underline-offset-2">{{ candidate.sourceDomain }} ↗</a>
            <p class="mt-1 text-muted">{{ copy.capabilities[candidate.capability] }}</p>
          </li>
        </ul>
      </section>
    </section>
    <div
      v-else-if="status === 'unavailable' || status === 'error'"
      class="mt-3 rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-900"
      role="status"
    >
      <p>{{ status === 'unavailable' ? copy.unavailable : copy.error }}</p>
      <div v-if="terminalNotice && discoverySummary" data-testid="rulebook-discovery-summary" class="mt-2 text-xs leading-5">
        <p>{{ terminalNotice }} {{ terminalTiming }}</p>
        <ul class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
          <li v-for="provider in discoverySummary.providers" :key="provider.provider">
            {{ copy.providers[provider.provider] }}: {{ copy.providerStates[provider.state] }}
          </li>
        </ul>
      </div>
      <div class="mt-3 flex flex-wrap gap-x-4 gap-y-2">
        <button type="button" class="inline-flex min-h-11 items-center font-semibold text-indigo underline" @click="emit('discover')">{{ copy.retrySearch }}</button>
        <a href="#rulebook-file" class="inline-flex min-h-11 items-center font-semibold text-indigo underline">{{ copy.localUpload }}</a>
      </div>
    </div>
  </div>
</template>
