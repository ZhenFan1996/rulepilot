<script setup lang="ts">
import { computed } from 'vue'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import SafeMarkdown from '@/components/SafeMarkdown.vue'
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
      bgg: '已核对信息', inferred: '结合你刚才说的', researched: '进一步了解', tradeoff: '选择前留意',
      fit: '条件核对', hard: '硬条件', soft: '偏好', satisfied: '满足', conflict: '冲突', unknown: '待核对',
      introduce: '介绍一下', select: '选这款，找规则书', details: '查看完整资料', source: '来源', noCover: '封面加载中', cover: '的 BGG 封面',
      players: (min: number, max: number) => `${min}–${max} 人`, minutes: (min: number, max: number) => min === max ? `约 ${max} 分钟` : `${min}–${max} 分钟`, weight: (value: number) => `复杂度 ${value.toFixed(1)}`, designer: (value: string) => `设计：${value}`,
    }
  : {
      bgg: 'Verified facts', inferred: 'Based on what you said', researched: 'A closer look', tradeoff: 'Worth checking',
      fit: 'Constraint check', hard: 'Hard', soft: 'Preference', satisfied: 'Satisfied', conflict: 'Conflict', unknown: 'Unknown',
      introduce: 'Tell me more', select: 'Choose and find rulebook', details: 'View full details', source: 'Source', noCover: 'Cover loading', cover: ' BGG cover',
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

const groupedReasons = computed(() => {
  const reasons = props.entry.reasons?.length
    ? props.entry.reasons
    : props.entry.matches.map(text => ({ kind: 'bgg_fact' as const, text, sourceIndexes: [] }))
  return {
    bgg_fact: reasons.filter(reason => reason.kind === 'bgg_fact'),
    preference_inference: reasons.filter(reason => reason.kind === 'preference_inference'),
    web_research: reasons.filter(reason => reason.kind === 'web_research'),
  }
})

const fitClaims = computed(() => props.entry.fitClaims ?? [])

function fitLabel(relation: 'satisfied' | 'conflict' | 'unknown') {
  return labels.value[relation]
}

function fitIcon(relation: 'satisfied' | 'conflict' | 'unknown') {
  if (relation === 'satisfied') return '✓'
  if (relation === 'conflict') return '!'
  return '?'
}

function source(index: number) {
  const candidate = props.sources.find(item => item.index === index)
  if (!candidate) return undefined
  try {
    const url = new URL(candidate.url)
    return url.protocol === 'http:' || url.protocol === 'https:' ? candidate : undefined
  } catch {
    return undefined
  }
}

function hideBrokenImage(event: Event) {
  ;(event.currentTarget as HTMLImageElement).hidden = true
}
</script>

<template>
  <article class="game-tile min-w-0 p-3 sm:p-4" data-testid="recommendation-game-card">
    <button type="button" class="block w-full text-left" :aria-label="`${labels.details}${cardLocale === 'zh-CN' ? '：' : ': '}${entry.game.name}`" @click="$emit('details', entry.game)">
      <div class="flex aspect-[16/10] items-center justify-center overflow-hidden rounded-lg border border-ink/6 bg-canvas p-3 text-ink/25">
        <img v-if="entry.game.thumbnailUrl" :src="entry.game.thumbnailUrl" :alt="`${entry.game.name}${labels.cover}`" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
        <TabletopGlyph v-else name="cards" :size="44" :aria-label="labels.noCover" />
      </div>
      <h3 class="mt-3 line-clamp-2 font-display text-xl font-semibold leading-tight">{{ entry.game.name }}</h3>
      <p v-if="entry.game.nameLocalized" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ entry.game.originalName }}</p>
      <p v-if="quickFacts.length" class="mt-2 line-clamp-2 text-xs leading-5 text-ink/55">{{ quickFacts.join(' · ') }}</p>
    </button>

    <section v-if="fitClaims.length" class="mt-4 rounded-lg border border-ink/10 bg-paper p-3" data-testid="candidate-fit-claims">
      <p class="tabletop-rule text-copper">{{ labels.fit }}</p>
      <ul class="mt-2 stack-y-s text-sm leading-5">
        <li v-for="claim in fitClaims" :key="`${claim.subject}-${claim.relation}`" class="flex gap-2" :class="claim.relation === 'conflict' ? 'text-red-700' : claim.relation === 'unknown' ? 'text-ink/55' : 'text-ink/68'">
          <span aria-hidden="true" class="font-bold">{{ fitIcon(claim.relation) }}</span>
          <span class="min-w-0"><span class="mr-1.5 text-[0.6875rem] font-bold uppercase tracking-wide">{{ claim.strength === 'hard' ? labels.hard : labels.soft }} · {{ fitLabel(claim.relation) }}</span><SafeMarkdown :source="claim.text" class="inline" /></span>
        </li>
      </ul>
    </section>

    <section v-if="groupedReasons.bgg_fact.length" class="mt-4">
      <p class="tabletop-rule text-copper">{{ labels.bgg }}</p>
      <ul class="mt-2 stack-y-s text-sm leading-5 text-ink/65">
        <li v-for="reason in groupedReasons.bgg_fact" :key="reason.text" class="flex gap-2"><span aria-hidden="true" class="text-copper">✓</span><SafeMarkdown :source="reason.text" class="min-w-0 flex-1" /></li>
      </ul>
    </section>
    <section v-if="groupedReasons.preference_inference.length" class="mt-4 border-l-2 border-indigo/35 pl-3">
      <p class="text-xs font-bold text-indigo">{{ labels.inferred }}</p>
      <ul class="mt-2 stack-y-s text-sm leading-5 text-ink/65"><li v-for="reason in groupedReasons.preference_inference" :key="reason.text"><SafeMarkdown :source="reason.text" /></li></ul>
    </section>
    <section v-if="groupedReasons.web_research.length" class="mt-4 rounded-lg border border-copper/15 bg-copper/5 p-3">
      <p class="text-xs font-bold text-copper">{{ labels.researched }}</p>
      <div v-for="reason in groupedReasons.web_research" :key="reason.text" class="mt-2 text-sm leading-5 text-ink/65">
        <SafeMarkdown :source="reason.text" />
        <p class="mt-1 flex flex-wrap gap-2 text-xs">
          <template v-for="index in reason.sourceIndexes" :key="index">
            <a v-if="source(index)" :href="source(index)!.url" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo underline decoration-indigo-soft underline-offset-2">[{{ index }}] {{ source(index)!.domain }}</a>
          </template>
        </p>
      </div>
    </section>
    <div v-if="entry.tradeoffs.length" class="mt-3 border-t border-ink/8 pt-3"><p class="text-xs font-bold text-copper">{{ labels.tradeoff }}</p><ul class="mt-1 stack-y-s text-xs leading-5 text-ink/60"><li v-for="tradeoff in entry.tradeoffs" :key="tradeoff"><SafeMarkdown :source="tradeoff" /></li></ul></div>

    <div class="mt-3 flex flex-wrap gap-3">
      <button type="button" :disabled="loading" class="min-h-11 rounded-lg bg-felt px-4 text-sm font-semibold text-white disabled:opacity-40" @click="$emit('select', entry.game)">{{ labels.select }}</button>
      <button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-felt disabled:opacity-40" @click="$emit('introduce', entry.game.bggId, entry.game.name, cardLocale)">{{ labels.introduce }}</button>
      <button type="button" class="min-h-11 text-sm font-semibold text-indigo" @click="$emit('details', entry.game)">{{ labels.details }} →</button>
    </div>
  </article>
</template>
