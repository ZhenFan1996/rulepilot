<script setup lang="ts">
import { computed } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import type { RecommendedGame, ResearchSource } from '@/components/gameRecommendationTypes'
import { useLocale, type AppLocale } from '@/lib/locale'

const props = defineProps<{
  entry: RecommendedGame
  sources: ResearchSource[]
  loading: boolean
  responseLocale?: 'zh-CN' | 'en'
}>()
defineEmits<{
  introduce: [bggId: number, name: string, responseLocale: AppLocale]
  select: [game: RecommendedGame['game']]
  details: [game: RecommendedGame['game']]
}>()

const { locale } = useLocale()
const cardLocale = computed(() => props.responseLocale ?? locale.value)
const labels = computed(() => cardLocale.value === 'zh-CN'
  ? {
      introduce: '介绍一下', select: '选这款，找规则书', details: '查看完整资料', source: '来源', noCover: '封面加载中', cover: '的 BGG 封面',
      ready: '现成讲解可读', readyScope: '可从讲解发起引用答疑；实时答疑仍取决于当时服务和可引用证据。', openReady: '直接进入讲解', readySize: (sections: number, steps: number) => `${sections} 章 · ${steps} 步`,
      whyFit: '为什么选它', verifiedFact: '已核对资料', tradeoff: '需要留意',
      players: (min: number, max: number) => `${min}–${max} 人`, minutes: (min: number, max: number) => min === max ? `约 ${max} 分钟` : `${min}–${max} 分钟`, weight: (value: number) => `复杂度 ${value.toFixed(1)}`, designer: (value: string) => `设计：${value}`,
    }
  : {
      introduce: 'Tell me more', select: 'Choose and find rulebook', details: 'View full details', source: 'Source', noCover: 'Cover loading', cover: ' BGG cover',
      ready: 'Ready guide available', readyScope: 'Start cited Q&A from the guide; a live answer still depends on the service and citable evidence available at that time.', openReady: 'Open guide now', readySize: (sections: number, steps: number) => `${sections} sections · ${steps} steps`,
      whyFit: 'Why it fits', verifiedFact: 'Verified detail', tradeoff: 'Tradeoff',
      players: (min: number, max: number) => `${min}–${max} players`, minutes: (min: number, max: number) => min === max ? `About ${max} min` : `${min}–${max} min`, weight: (value: number) => `Weight ${value.toFixed(1)}`, designer: (value: string) => `By ${value}`,
    })

const quickFacts = computed(() => {
  const game = props.entry.game
  const values: string[] = []
  if (game.minPlayers !== null && game.maxPlayers !== null) values.push(labels.value.players(game.minPlayers, game.maxPlayers))
  const minimum = game.minimumPlayTimeMinutes ?? game.playingTimeMinutes
  const maximum = game.maximumPlayTimeMinutes ?? game.playingTimeMinutes
  if (minimum !== null && maximum !== null) values.push(labels.value.minutes(minimum, maximum))
  if (game.averageWeight !== null) values.push(labels.value.weight(game.averageWeight))
  if (game.designers?.[0]) values.push(labels.value.designer(game.designers[0]))
  return values
})

function hideBrokenImage(event: Event) {
  ;(event.currentTarget as HTMLImageElement).hidden = true
}

function replyPartLabel(role: NonNullable<RecommendedGame['replyParts']>[number]['role']) {
  if (role === 'tradeoff') return labels.value.tradeoff
  if (role === 'verified_fact') return labels.value.verifiedFact
  return labels.value.whyFit
}
</script>

<template>
  <article class="game-tile min-w-0 p-3 sm:p-4" data-testid="recommendation-game-card" :data-bgg-id="entry.game.bggId">
    <button type="button" class="block w-full text-left" :aria-label="`${labels.details}${cardLocale === 'zh-CN' ? '：' : ': '}${entry.game.name}`" @click="$emit('details', entry.game)">
      <div class="flex aspect-[16/10] items-center justify-center overflow-hidden rounded-lg border border-ink/6 bg-canvas p-3 text-ink/25">
        <img v-if="entry.game.thumbnailUrl" :src="entry.game.thumbnailUrl" :alt="`${entry.game.name}${labels.cover}`" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
        <TabletopGlyph v-else name="cards" :size="44" :aria-label="labels.noCover" />
      </div>
      <h3 class="mt-3 line-clamp-2 font-display text-xl font-semibold leading-tight">{{ entry.game.name }}</h3>
      <p v-if="entry.game.nameLocalized" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ entry.game.originalName }}</p>
      <p v-if="quickFacts.length" class="mt-2 line-clamp-2 text-xs leading-5 text-ink/55">{{ quickFacts.join(' · ') }}</p>
    </button>

    <dl v-if="entry.replyParts?.length" class="mt-3 grid gap-2 border-t border-ink/8 pt-3 text-sm leading-5">
      <div v-for="(part, index) in entry.replyParts" :key="`${part.role}-${part.subject}-${index}`" class="grid gap-0.5">
        <dt class="text-[0.6875rem] font-semibold uppercase tracking-[0.08em]" :class="part.role === 'tradeoff' ? 'text-copper' : 'text-felt'">{{ replyPartLabel(part.role) }}</dt>
        <dd class="text-ink/65">{{ part.text }}</dd>
      </div>
    </dl>

    <p v-if="entry.teachingContinuation" data-testid="ready-teaching-continuation" class="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs leading-5 text-emerald-900">
      <span class="block font-semibold">{{ labels.ready }} · {{ labels.readySize(entry.teachingContinuation.sectionCount, entry.teachingContinuation.stepCount) }}</span>
      <span class="mt-1 block">{{ labels.readyScope }}</span>
    </p>

    <div class="mt-3 flex flex-wrap gap-3">
      <a v-if="entry.teachingContinuation" data-testid="open-ready-teaching" :href="`/read/${encodeURIComponent(entry.teachingContinuation.teachingPlanId)}`" class="inline-flex min-h-11 items-center rounded-lg bg-felt px-4 text-sm font-semibold text-white" :aria-label="`${labels.openReady}${cardLocale === 'zh-CN' ? '：' : ': '}${entry.game.name}`">{{ labels.openReady }}</a>
      <button v-else type="button" :disabled="loading" class="min-h-11 rounded-lg bg-felt px-4 text-sm font-semibold text-white disabled:opacity-40" @click="$emit('select', entry.game)">{{ labels.select }}</button>
      <button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-felt disabled:opacity-40" @click="$emit('introduce', entry.game.bggId, entry.game.name, cardLocale)">{{ labels.introduce }}</button>
      <button type="button" class="min-h-11 text-sm font-semibold text-indigo" @click="$emit('details', entry.game)">{{ labels.details }} →</button>
    </div>
  </article>
</template>
