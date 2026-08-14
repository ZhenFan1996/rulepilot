<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import type { ShelfItem } from '@/lib/gameShelf'
import { useLocale } from '@/lib/locale'

const props = defineProps<{ item: ShelfItem; index: number }>()
const coverUnavailable = ref(false)
const { t } = useLocale()

const tone = computed(() => [
  ['#3f5474', '#eff2f5'],
  ['#8f4c34', '#fff4e8'],
  ['#557451', '#f1f5ea'],
  ['#714764', '#fff1fa'],
][props.index % 4]!)
const initials = computed(() => props.item.title.trim().slice(0, 2).toUpperCase())
const playerCount = computed(() => {
  if (!props.item.players) return t('shelf.card.missing')
  const count = props.item.players.min === props.item.players.max
    ? props.item.players.min
    : `${props.item.players.min}–${props.item.players.max}`
  return t('shelf.card.playerCount', { count })
})
const status = computed(() => ({
  IMPORTING: { label: t('shelf.card.status.importing'), className: 'bg-sky-50 text-sky-900' },
  READY: { label: t('shelf.card.status.ready'), className: 'bg-emerald-50 text-emerald-800' },
  READING: { label: t('shelf.card.status.reading'), className: 'bg-amber-50 text-amber-900' },
  NEEDS_ATTENTION: { label: t('shelf.card.status.attention'), className: 'bg-red-50 text-red-800' },
}[props.item.documentStatus]))
const detailTarget = computed(() => props.item.gameId
  ? { name: 'game-workspace', params: { gameId: props.item.gameId } }
  : null)
const rulebookTarget = computed(() => ({
  name: 'teach',
  query: props.item.editionId ? { editionId: props.item.editionId } : undefined,
}))
const guideLabel = computed(() => {
  if (props.item.lessonCount) return t('shelf.card.guides', { count: props.item.lessonCount })
  return {
    LOADING: t('shelf.card.guideLoading'),
    PREPARING: t('shelf.card.guidePreparing'),
    READY: t('shelf.card.guides', { count: props.item.lessonCount }),
    NONE: t('shelf.card.noGuide'),
    FAILED: t('shelf.card.guideFailed'),
    UNAVAILABLE: t('shelf.card.guideUnavailable'),
  }[props.item.guideStatus]
})
const canManageRulebook = computed(() => props.item.documentCount > 0 || props.item.pendingImportCount === 0)
</script>

<template>
  <article class="group overflow-hidden rounded-[1.65rem] border border-ink/10 bg-paper game-card-shadow transition duration-200 hover:-translate-y-1 ">
    <div class="relative aspect-[16/8] overflow-hidden border-b border-ink/10">
      <img v-if="item.coverUrl && !coverUnavailable" :src="item.coverUrl" :alt="t('shelf.card.coverAlt', { title: item.title })" class="size-full object-cover" referrerpolicy="no-referrer" @error="coverUnavailable = true">
      <div v-else class="shelf-cover size-full" :style="{ '--shelf-cover': tone[0], '--shelf-ink': tone[1] }">
        <div class="absolute inset-4 rounded-2xl border border-current/35" />
        <TabletopGlyph name="meeple" :size="64" class="absolute right-5 top-5 opacity-80" />
        <span class="absolute bottom-4 left-5 font-display text-4xl font-semibold tracking-tight">{{ initials }}</span>
      </div>
      <span :class="status.className" class="absolute right-3 top-3 rounded-full px-3 py-1.5 text-xs font-bold elevation-sm">{{ status.label }}</span>
      <a v-if="item.coverAttributionUrl" :href="item.coverAttributionUrl" target="_blank" rel="noopener noreferrer" class="absolute bottom-2 right-3 rounded-md bg-ink/75 px-2 py-1 text-[0.65rem] font-semibold text-canvas opacity-0 transition group-hover:opacity-100 focus:opacity-100">{{ t('shelf.card.bggCover') }} ↗</a>
    </div>

    <div class="p-5 sm:p-6">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <p class="text-xs font-bold uppercase tracking-[0.14em] text-copper">{{ t('shelf.card.eyebrow') }}</p>
          <h2 class="mt-2 truncate font-display text-2xl font-semibold tracking-tight">
            <RouterLink v-if="detailTarget" :to="detailTarget" class="hover:text-indigo">{{ item.title }}</RouterLink>
            <template v-else>{{ item.title }}</template>
          </h2>
          <p v-if="item.editionLabel" class="mt-1 truncate text-sm text-ink/50">{{ item.editionLabel }}</p>
          <p v-else class="mt-1 text-sm text-ink/45">{{ t('shelf.card.standalone') }}</p>
        </div>
        <TabletopGlyph name="cards" :size="25" class="shrink-0 text-copper" />
      </div>

      <dl v-if="item.players || item.playtimeMinutes || item.minimumAge" class="mt-5 grid grid-cols-3 gap-2 rounded-2xl bg-canvas p-3 text-center">
        <div>
          <dt class="sr-only">{{ t('shelf.card.players') }}</dt>
          <TabletopGlyph name="players" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ playerCount }}</dd>
        </div>
        <div>
          <dt class="sr-only">{{ t('shelf.card.playtime') }}</dt>
          <TabletopGlyph name="timer" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ item.playtimeMinutes ? t('shelf.card.minuteCount', { count: item.playtimeMinutes }) : t('shelf.card.missing') }}</dd>
        </div>
        <div>
          <dt class="sr-only">{{ t('shelf.card.age') }}</dt>
          <TabletopGlyph name="dice" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ item.minimumAge ? t('shelf.card.ageCount', { count: item.minimumAge }) : t('shelf.card.game') }}</dd>
        </div>
      </dl>

      <div class="mt-5 flex flex-wrap gap-x-4 gap-y-2 text-sm text-ink/60">
        <span class="inline-flex items-center gap-1.5"><TabletopGlyph name="rulebook" :size="17" class="text-copper" />{{ item.pendingImportCount && item.documentCount === 0 ? item.documentStatus === 'NEEDS_ATTENTION' ? t('shelf.card.rulebookImportFailed') : t('shelf.card.rulebookImporting') : t('shelf.card.rulebooks', { count: item.documentCount }) }}</span>
        <span class="inline-flex items-center gap-1.5"><TabletopGlyph name="cards" :size="17" class="text-copper" />{{ guideLabel }}</span>
        <span v-if="item.expansionCount" class="inline-flex items-center gap-1.5"><TabletopGlyph name="cards" :size="17" class="text-copper" />{{ t('shelf.card.expansions', { count: item.expansionCount }) }}</span>
      </div>

      <div class="mt-6 flex items-center gap-3">
        <RouterLink v-if="item.latestPlanId" :to="{ name: 'lesson', params: { planId: item.latestPlanId } }" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-ink px-4 text-sm font-semibold text-canvas transition hover:bg-ink/90">
          {{ t('shelf.card.continue') }} <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-else-if="item.guideStatus === 'FAILED' && item.documentCount > 0 && item.documentStatus !== 'NEEDS_ATTENTION'" :to="{ name: 'lessons' }" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-red-700 px-4 text-sm font-semibold text-white transition hover:bg-red-800">
          {{ t('shelf.card.recoverGuide') }} <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-else-if="item.gameId && item.pendingImportCount" :to="detailTarget!" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-ink px-4 text-sm font-semibold text-canvas transition hover:bg-ink/90">
          {{ t('shelf.card.viewProgress') }} <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-else :to="rulebookTarget" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-copper px-4 text-sm font-semibold text-white transition hover:bg-copper-dark">
          <TabletopGlyph name="rulebook" :size="17" /> {{ item.documentStatus === 'READY' ? t('shelf.card.start') : t('shelf.card.viewRulebook') }}
        </RouterLink>
        <RouterLink v-if="canManageRulebook" :to="rulebookTarget" class="grid min-h-11 min-w-11 place-items-center rounded-xl border border-ink/12 text-ink/55 transition hover:border-indigo hover:text-indigo" :aria-label="t('shelf.card.manageRulebooks')">
          <TabletopGlyph name="library" :size="20" />
        </RouterLink>
      </div>
    </div>
  </article>
</template>
