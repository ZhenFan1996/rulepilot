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
      whyFit: '为什么选它', verifiedFact: '已核对资料', tradeoff: '需要留意',
      publisherDescriptionGrounded: '参考 BGG 出版方简介',
      fitUnavailable: '这款候选的资料卡已核对，但本轮没有形成可安全发布的个别理由。可以点“介绍一下”继续。',
      players: (min: number, max: number) => `${min}–${max} 人`, minutes: (min: number, max: number) => min === max ? `约 ${max} 分钟` : `${min}–${max} 分钟`, weight: (value: number) => `复杂度 ${value.toFixed(1)}`, designer: (value: string) => `设计：${value}`,
    }
  : {
      introduce: 'Tell me more', select: 'Choose and find rulebook', details: 'View full details', source: 'Source', noCover: 'Cover loading', cover: ' BGG cover',
      whyFit: 'Why it fits', verifiedFact: 'Verified detail', tradeoff: 'Tradeoff',
      publisherDescriptionGrounded: 'Uses BGG publisher description',
      fitUnavailable: 'This candidate card was verified, but this turn did not produce a safe candidate-specific reason. Choose “Tell me more” to continue.',
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
  <article
    class="game-tile min-w-0 p-3 sm:p-4"
    data-testid="recommendation-game-card"
    :data-bgg-id="entry.game.bggId"
    :data-game-name="entry.game.name"
    :data-original-name="entry.game.originalName"
    :data-min-players="entry.game.minPlayers"
    :data-max-players="entry.game.maxPlayers"
    :data-minimum-play-time="entry.game.minimumPlayTimeMinutes ?? entry.game.playingTimeMinutes"
    :data-maximum-play-time="entry.game.maximumPlayTimeMinutes ?? entry.game.playingTimeMinutes"
  >
    <button type="button" class="block w-full text-left" :aria-label="`${labels.details}${cardLocale === 'zh-CN' ? '：' : ': '}${entry.game.name}`" @click="$emit('details', entry.game)">
      <div class="flex aspect-[16/10] items-center justify-center overflow-hidden rounded-lg border border-ink/6 bg-canvas p-3 text-ink/25">
        <img v-if="entry.game.thumbnailUrl" :src="entry.game.thumbnailUrl" :alt="`${entry.game.name}${labels.cover}`" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
        <TabletopGlyph v-else name="cards" :size="44" :aria-label="labels.noCover" />
      </div>
      <h3 data-testid="recommendation-game-title" class="mt-3 line-clamp-2 font-display text-xl font-semibold leading-tight">{{ entry.game.name }}</h3>
      <p v-if="entry.game.nameLocalized" data-testid="recommendation-game-original-title" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ entry.game.originalName }}</p>
      <p v-if="quickFacts.length" data-testid="recommendation-game-quick-facts" class="mt-2 line-clamp-2 text-xs leading-5 text-ink/55">{{ quickFacts.join(' · ') }}</p>
    </button>

    <dl v-if="entry.replyParts?.length" class="mt-3 grid gap-2 border-t border-ink/8 pt-3 text-sm leading-5">
      <div
        v-for="(part, index) in entry.replyParts"
        :key="`${part.role}-${part.subject}-${index}`"
        class="grid gap-0.5"
        :data-role="part.role"
        :data-claim-type="part.claimType"
        :data-subject="part.subject"
        :data-source-indexes="part.sourceIndexes.join(',')"
      >
        <dt class="text-[0.6875rem] font-semibold uppercase tracking-[0.08em]" :class="part.role === 'tradeoff' ? 'text-copper' : 'text-felt'">{{ replyPartLabel(part.role) }}</dt>
        <dd class="text-ink/65">{{ part.text }}</dd>
        <dd
          v-if="part.publisherDescriptionGrounded"
          data-testid="publisher-description-grounding"
          class="mt-0.5 w-fit rounded-full bg-indigo/8 px-2 py-0.5 text-[0.6875rem] font-medium text-indigo"
        >
          {{ labels.publisherDescriptionGrounded }}
        </dd>
      </div>
    </dl>
    <p
      v-else
      data-testid="recommendation-reason-unavailable"
      class="mt-3 border-t border-ink/8 pt-3 text-xs leading-5 text-ink/52"
    >
      {{ labels.fitUnavailable }}
    </p>

    <div class="mt-3 flex flex-wrap gap-3">
      <button type="button" :disabled="loading" class="min-h-11 rounded-lg bg-felt px-4 text-sm font-semibold text-white disabled:opacity-40" @click="$emit('select', entry.game)">{{ labels.select }}</button>
      <button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-felt disabled:opacity-40" @click="$emit('introduce', entry.game.bggId, entry.game.name, cardLocale)">{{ labels.introduce }}</button>
      <button type="button" class="min-h-11 text-sm font-semibold text-indigo" @click="$emit('details', entry.game)">{{ labels.details }} →</button>
    </div>
  </article>
</template>
