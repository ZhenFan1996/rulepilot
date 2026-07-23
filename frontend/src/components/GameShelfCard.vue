<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import TabletopGlyph from '@/components/TabletopGlyph.vue'
import type { ShelfItem } from '@/lib/gameShelf'

const props = defineProps<{ item: ShelfItem; index: number }>()
const coverUnavailable = ref(false)

const tone = computed(() => [
  ['#3f5474', '#eff2f5'],
  ['#8f4c34', '#fff4e8'],
  ['#557451', '#f1f5ea'],
  ['#714764', '#fff1fa'],
][props.index % 4]!)
const initials = computed(() => props.item.title.trim().slice(0, 2).toUpperCase())
const status = computed(() => ({
  READY: { label: '规则书可用', className: 'bg-emerald-50 text-emerald-800' },
  READING: { label: '正在读规则书', className: 'bg-amber-50 text-amber-900' },
  NEEDS_ATTENTION: { label: '需要处理', className: 'bg-red-50 text-red-800' },
}[props.item.documentStatus]))
</script>

<template>
  <article class="group overflow-hidden rounded-[1.65rem] border border-ink/10 bg-paper shadow-[0_12px_32px_rgba(26,35,42,0.06)] transition duration-200 hover:-translate-y-1 hover:shadow-[0_18px_40px_rgba(26,35,42,0.12)]">
    <div class="relative aspect-[16/8] overflow-hidden border-b border-ink/10">
      <img v-if="item.coverUrl && !coverUnavailable" :src="item.coverUrl" :alt="`${item.title} 的游戏封面`" class="size-full object-cover" referrerpolicy="no-referrer" @error="coverUnavailable = true">
      <div v-else class="shelf-cover size-full" :style="{ '--shelf-cover': tone[0], '--shelf-ink': tone[1] }">
        <div class="absolute inset-4 rounded-2xl border border-current/35" />
        <TabletopGlyph name="meeple" :size="64" class="absolute right-5 top-5 opacity-80" />
        <span class="absolute bottom-4 left-5 font-display text-4xl font-semibold tracking-tight">{{ initials }}</span>
      </div>
      <span :class="status.className" class="absolute right-3 top-3 rounded-full px-3 py-1.5 text-xs font-bold shadow-sm">{{ status.label }}</span>
      <a v-if="item.coverAttributionUrl" :href="item.coverAttributionUrl" target="_blank" rel="noopener noreferrer" class="absolute bottom-2 right-3 rounded-md bg-ink/75 px-2 py-1 text-[0.65rem] font-semibold text-canvas opacity-0 transition group-hover:opacity-100 focus:opacity-100">BGG 封面 ↗</a>
    </div>

    <div class="p-5 sm:p-6">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <p class="text-xs font-bold uppercase tracking-[0.14em] text-copper">桌边资料</p>
          <h2 class="mt-2 truncate font-display text-2xl font-semibold tracking-tight">{{ item.title }}</h2>
          <p v-if="item.editionLabel" class="mt-1 truncate text-sm text-ink/50">{{ item.editionLabel }}</p>
          <p v-else class="mt-1 text-sm text-ink/45">独立规则书</p>
        </div>
        <TabletopGlyph name="cards" :size="25" class="shrink-0 text-copper" />
      </div>

      <dl v-if="item.players || item.playtime || item.age" class="mt-5 grid grid-cols-3 gap-2 rounded-2xl bg-canvas p-3 text-center">
        <div>
          <dt class="sr-only">人数</dt>
          <TabletopGlyph name="players" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ item.players ?? '待补充' }}</dd>
        </div>
        <div>
          <dt class="sr-only">时长</dt>
          <TabletopGlyph name="timer" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ item.playtime ?? '待补充' }}</dd>
        </div>
        <div>
          <dt class="sr-only">建议年龄</dt>
          <TabletopGlyph name="dice" :size="17" class="mx-auto text-indigo" />
          <dd class="mt-1 text-xs font-semibold">{{ item.age ?? '桌游' }}</dd>
        </div>
      </dl>

      <div class="mt-5 flex flex-wrap gap-x-4 gap-y-2 text-sm text-ink/60">
        <span class="inline-flex items-center gap-1.5"><TabletopGlyph name="rulebook" :size="17" class="text-copper" />{{ item.documentCount }} 本规则书</span>
        <span class="inline-flex items-center gap-1.5"><TabletopGlyph name="spark" :size="17" class="text-copper" />{{ item.lessonCount ? `${item.lessonCount} 份讲解` : '还没生成讲解' }}</span>
        <span v-if="item.expansionCount" class="inline-flex items-center gap-1.5"><TabletopGlyph name="cards" :size="17" class="text-copper" />{{ item.expansionCount }} 个扩展</span>
      </div>

      <div class="mt-6 flex items-center gap-3">
        <RouterLink v-if="item.latestPlanId" :to="{ name: 'lesson', params: { planId: item.latestPlanId } }" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-ink px-4 text-sm font-semibold text-canvas transition hover:bg-ink/90">
          继续讲解 <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-else :to="{ name: 'teach' }" class="inline-flex min-h-11 flex-1 items-center justify-center gap-2 rounded-xl bg-copper px-4 text-sm font-semibold text-white transition hover:bg-copper-dark">
          <TabletopGlyph name="rulebook" :size="17" /> {{ item.documentStatus === 'READY' ? '开始讲解' : '查看规则书' }}
        </RouterLink>
        <RouterLink :to="{ name: 'teach' }" class="grid min-h-11 min-w-11 place-items-center rounded-xl border border-ink/12 text-ink/55 transition hover:border-indigo hover:text-indigo" aria-label="管理规则书">
          <TabletopGlyph name="library" :size="20" />
        </RouterLink>
      </div>
    </div>
  </article>
</template>
