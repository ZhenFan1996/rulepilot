<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import type { RecommendedGame, ResearchSource } from '@/components/gameRecommendationTypes'
import { useLocale } from '@/lib/locale'

const props = defineProps<{ entry: RecommendedGame; sources: ResearchSource[]; loading: boolean }>()
defineEmits<{ introduce: [bggId: number, name: string] }>()

const { locale } = useLocale()
const labels = computed(() => locale.value === 'zh-CN'
  ? {
      bgg: 'BGG 资料', inferred: '根据你的表达（推测）', researched: '联网调查', tradeoff: '选择前留意',
      introduce: '介绍一下', details: '查看完整资料', source: '来源', noCover: '封面加载中', cover: '的 BGG 封面',
    }
  : {
      bgg: 'BGG facts', inferred: 'Inferred from your words', researched: 'Web research', tradeoff: 'Worth checking',
      introduce: 'Tell me more', details: 'View full details', source: 'Source', noCover: 'Cover loading', cover: ' BGG cover',
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

function source(index: number) {
  return props.sources.find(item => item.index === index)
}

function hideBrokenImage(event: Event) {
  ;(event.currentTarget as HTMLImageElement).hidden = true
}
</script>

<template>
  <article class="min-w-0 rounded-2xl border border-ink/10 bg-paper p-4 shadow-sm">
    <RouterLink :to="{ name: 'game-discovery', params: { bggId: entry.game.bggId } }" class="block">
      <div class="flex aspect-[16/10] items-center justify-center overflow-hidden rounded-xl bg-canvas p-3 text-ink/25">
        <img v-if="entry.game.thumbnailUrl" :src="entry.game.thumbnailUrl" :alt="`${entry.game.name}${labels.cover}`" loading="lazy" class="h-full w-full object-contain" @error="hideBrokenImage">
        <TabletopGlyph v-else name="cards" :size="44" :aria-label="labels.noCover" />
      </div>
      <h3 class="mt-3 line-clamp-2 font-display text-xl font-semibold">{{ entry.game.name }}</h3>
      <p v-if="entry.game.nameLocalized" class="mt-1 line-clamp-1 text-xs text-ink/45">{{ entry.game.originalName }}</p>
    </RouterLink>

    <section v-if="groupedReasons.bgg_fact.length" class="mt-4">
      <p class="text-xs font-bold uppercase tracking-[0.1em] text-copper">{{ labels.bgg }}</p>
      <ul class="mt-2 space-y-1.5 text-sm leading-5 text-ink/65">
        <li v-for="reason in groupedReasons.bgg_fact" :key="reason.text" class="flex gap-2"><span aria-hidden="true" class="text-copper">✓</span><span>{{ reason.text }}</span></li>
      </ul>
    </section>
    <section v-if="groupedReasons.preference_inference.length" class="mt-4 rounded-xl bg-indigo/5 p-3">
      <p class="text-xs font-bold text-indigo">{{ labels.inferred }}</p>
      <ul class="mt-2 space-y-1.5 text-sm leading-5 text-ink/65"><li v-for="reason in groupedReasons.preference_inference" :key="reason.text">{{ reason.text }}</li></ul>
    </section>
    <section v-if="groupedReasons.web_research.length" class="mt-4 rounded-xl border border-copper/15 bg-copper/5 p-3">
      <p class="text-xs font-bold text-copper">{{ labels.researched }}</p>
      <div v-for="reason in groupedReasons.web_research" :key="reason.text" class="mt-2 text-sm leading-5 text-ink/65">
        <p>{{ reason.text }}</p>
        <p class="mt-1 flex flex-wrap gap-2 text-xs">
          <template v-for="index in reason.sourceIndexes" :key="index">
            <a v-if="source(index)" :href="source(index)!.url" target="_blank" rel="noopener noreferrer" class="font-semibold text-indigo underline decoration-indigo/25 underline-offset-2">[{{ index }}] {{ source(index)!.domain }}</a>
          </template>
        </p>
      </div>
    </section>
    <div v-if="entry.tradeoffs.length" class="mt-3 rounded-lg bg-copper/7 p-3"><p class="text-xs font-bold text-copper">{{ labels.tradeoff }}</p><p class="mt-1 text-xs leading-5 text-ink/60">{{ entry.tradeoffs.join('；') }}</p></div>

    <div class="mt-3 flex flex-wrap gap-3">
      <button type="button" :disabled="loading" class="min-h-11 text-sm font-semibold text-copper disabled:opacity-40" @click="$emit('introduce', entry.game.bggId, entry.game.name)">{{ labels.introduce }}</button>
      <RouterLink :to="{ name: 'game-discovery', params: { bggId: entry.game.bggId } }" class="inline-flex min-h-11 items-center text-sm font-semibold text-indigo">{{ labels.details }} →</RouterLink>
    </div>
  </article>
</template>
