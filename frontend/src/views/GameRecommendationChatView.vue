<script setup lang="ts">
import AppShell from '@/components/AppShell.vue'
import GameRecommendationAgent from '@/components/GameRecommendationAgent.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

const { locale } = useLocale()
const copy = {
  'zh-CN': {
    eyebrow: '一起挑一款',
    title: '先聊聊今晚想玩什么',
    description: '告诉我人数、时间、喜欢的主题，或者上一款为什么不满意。对话里会直接出现候选、详情和规则书入口。',
    catalogEyebrow: '想自己慢慢看？',
    catalogTitle: '打开完整桌游目录',
    catalogDescription: '搜索 BGG 收录的桌游，按热度、玩家评分和细分类型浏览，不会打断这里的对话。',
    catalogAction: '浏览目录',
  },
  en: {
    eyebrow: 'Choose together',
    title: 'Start with what you want to play tonight',
    description: 'Share the group size, time, theme, or why the last suggestion missed. Candidates, details, and rulebook actions appear directly in the conversation.',
    catalogEyebrow: 'Prefer to browse?',
    catalogTitle: 'Open the complete game catalog',
    catalogDescription: 'Search BGG games and browse by heat, player rating, and detailed type without interrupting this conversation.',
    catalogAction: 'Browse catalog',
  },
} as const
const t = (key: keyof typeof copy['zh-CN']) => copy[locale.value][key]
</script>

<template>
  <AppShell>
    <div class="tabletop-page">
      <header class="grid gap-7 pb-7 xl:grid-cols-[minmax(0,1fr)_22rem] xl:items-end">
        <div class="tabletop-heading">
          <p class="tabletop-kicker">{{ t('eyebrow') }}</p>
          <h1 class="tabletop-title">{{ t('title') }}</h1>
          <p class="tabletop-lede">{{ t('description') }}</p>
        </div>

        <RouterLink :to="{ name: 'game-catalog-browse' }" class="game-tile player-board group grid min-h-36 grid-cols-[1fr_auto] items-center gap-4 p-5 hover:border-copper/45">
          <span>
            <span class="tabletop-kicker">{{ t('catalogEyebrow') }}</span>
            <strong class="mt-1 block font-display text-xl leading-tight">{{ t('catalogTitle') }}</strong>
            <span class="mt-2 block text-xs leading-5 text-ink/55">{{ t('catalogDescription') }}</span>
          </span>
          <span class="hex-token size-12 text-copper" aria-hidden="true"><span><TabletopGlyph name="cards" :size="22" /></span></span>
          <span class="col-span-2 text-sm font-semibold text-felt">{{ t('catalogAction') }} →</span>
        </RouterLink>
      </header>

      <GameRecommendationAgent />
    </div>
  </AppShell>
</template>
