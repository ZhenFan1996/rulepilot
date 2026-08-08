<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { notifyLoginRequired } from '@/lib/authSession'
import type { ShelfCatalogEntry, ShelfDocument, ShelfPlan } from '@/lib/gameShelf'
import { useLocale } from '@/lib/locale'

interface RichBggDetails {
  bggId: number
  name: string
  description: string
  imageUrl: string
  thumbnailUrl: string
  averageRating: number | null
  averageWeight: number | null
  categories: string[]
  mechanics: string[]
  designers: string[]
  publishers: string[]
  bggUrl: string
}

interface WorkspaceDocument extends ShelfDocument {
  document: ShelfDocument['document'] & { officialSourceUrl?: string | null }
}

const route = useRoute()
const { locale } = useLocale()
const catalog = ref<ShelfCatalogEntry[]>([])
const documents = ref<WorkspaceDocument[]>([])
const plans = ref<ShelfPlan[]>([])
const details = ref<RichBggDetails | null>(null)
const loading = ref(true)
const errorMessage = ref('')

const game = computed(() => catalog.value.find(entry => entry.game.id === String(route.params.gameId)) ?? null)
const copy = computed(() => locale.value === 'zh-CN' ? {
  back: '返回我的游戏', eyebrow: '桌游工作区', error: '暂时无法读取这款桌游。', notFound: '这款桌游不在你的目录中。', retry: '重试',
  rating: 'BGG 评分', weight: '复杂度', designers: '设计师', publishers: '出版社', mechanics: '机制', categories: '类别',
  evidence: 'BGG 信息用于识别、推荐与展示；规则讲解和答疑只引用已处理的规则书。', editions: '版本、规则书与讲解',
  editionEmpty: '这个版本还没有规则书。', addRulebook: '找规则书', processing: '规则书处理中', failed: '规则书需要处理', ready: '规则书可用',
  openGuide: '打开讲解', ask: '规则答疑', generate: '开始讲解', source: '官方来源', bgg: '查看 BGG 原始资料',
} : {
  back: 'Back to my games', eyebrow: 'Game workspace', error: 'This game is unavailable right now.', notFound: 'This game is not in your catalog.', retry: 'Try again',
  rating: 'BGG rating', weight: 'Complexity', designers: 'Designers', publishers: 'Publishers', mechanics: 'Mechanics', categories: 'Categories',
  evidence: 'BGG data supports identification, recommendations, and presentation. Teaching and Q&A cite only processed rulebooks.', editions: 'Editions, rulebooks, and guides',
  editionEmpty: 'This edition has no rulebook yet.', addRulebook: 'Find rulebook', processing: 'Processing rulebook', failed: 'Rulebook needs attention', ready: 'Rulebook ready',
  openGuide: 'Open guide', ask: 'Ask rules', generate: 'Start teaching', source: 'Official source', bgg: 'View original BGG data',
})

function editionDocuments(editionId: string) {
  return documents.value.filter(document => document.document.gameEditionId === editionId)
}

function documentPlans(document: WorkspaceDocument) {
  return plans.value
    .filter(plan => plan.documentVersionId === document.latestVersion.id)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
}

function statusLabel(status: string) {
  if (status === 'READY') return copy.value.ready
  if (status === 'FAILED') return copy.value.failed
  return copy.value.processing
}

async function checkedFetch(path: string) {
  const response = await fetch(path, { credentials: 'include' })
  if (response.status === 401) {
    notifyLoginRequired()
    throw new Error(copy.value.error)
  }
  if (!response.ok) throw new Error(copy.value.error)
  return response
}

async function load() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [catalogResponse, documentResponse, planResponse] = await Promise.all([
      checkedFetch('/api/v1/games'), checkedFetch('/api/v1/documents'), checkedFetch('/api/v1/teaching-plans'),
    ])
    catalog.value = await catalogResponse.json() as ShelfCatalogEntry[]
    documents.value = await documentResponse.json() as WorkspaceDocument[]
    plans.value = await planResponse.json() as ShelfPlan[]
    if (game.value?.bggMetadata?.bggId) {
      const response = await fetch(`/api/v1/bgg/games/${game.value.bggMetadata.bggId}`, { credentials: 'include' })
      if (response.ok) details.value = await response.json() as RichBggDetails
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : copy.value.error
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell>
    <main class="tabletop-page max-w-6xl">
      <RouterLink :to="{ name: 'catalog' }" class="text-sm font-semibold text-indigo">← {{ copy.back }}</RouterLink>

      <div v-if="loading" class="mt-8 h-96 animate-pulse rounded-2xl bg-ink/8" aria-live="polite" />
      <section v-else-if="errorMessage" class="mt-8 rounded-2xl border border-red-200 bg-red-50 p-6 text-red-800" role="alert">
        <p>{{ errorMessage }}</p><button type="button" class="mt-3 font-semibold underline" @click="load">{{ copy.retry }}</button>
      </section>
      <section v-else-if="!game" class="mt-8 rounded-2xl border border-dashed border-ink/20 bg-paper p-8 text-center">
        <h1 class="font-display text-3xl font-semibold">{{ copy.notFound }}</h1>
      </section>

      <template v-else>
        <section class="tabletop-panel player-board mt-8 grid gap-7 overflow-hidden p-5 sm:p-8 lg:grid-cols-[16rem_1fr]">
          <div class="rounded-2xl bg-canvas p-4">
            <img v-if="details?.imageUrl || game.bggMetadata?.thumbnailUrl" :src="details?.imageUrl || game.bggMetadata?.thumbnailUrl" :alt="game.game.name" class="mx-auto aspect-[4/5] size-full object-contain" referrerpolicy="no-referrer">
            <TabletopGlyph v-else name="meeple" :size="96" class="mx-auto my-16 text-copper" />
          </div>
          <div class="self-center">
            <p class="text-sm font-semibold text-copper">{{ copy.eyebrow }}</p>
            <h1 class="mt-2 font-display text-4xl font-semibold tracking-tight sm:text-5xl">{{ game.game.name }}</h1>
            <div v-if="details?.averageRating || details?.averageWeight" class="mt-4 flex flex-wrap gap-2 text-sm">
              <span v-if="details.averageRating" class="rounded-full bg-canvas px-3 py-1.5">{{ copy.rating }} {{ details.averageRating.toFixed(1) }}</span>
              <span v-if="details.averageWeight" class="rounded-full bg-canvas px-3 py-1.5">{{ copy.weight }} {{ details.averageWeight.toFixed(1) }} / 5</span>
            </div>
            <p v-if="details?.description" class="mt-5 whitespace-pre-line leading-7 text-ink/65">{{ details.description }}</p>
            <dl v-if="details" class="mt-5 grid gap-3 text-sm sm:grid-cols-2">
              <div v-if="details.designers?.length"><dt class="font-semibold text-ink/45">{{ copy.designers }}</dt><dd>{{ details.designers.join('、') }}</dd></div>
              <div v-if="details.publishers?.length"><dt class="font-semibold text-ink/45">{{ copy.publishers }}</dt><dd>{{ details.publishers.join('、') }}</dd></div>
              <div v-if="details.mechanics?.length"><dt class="font-semibold text-ink/45">{{ copy.mechanics }}</dt><dd>{{ details.mechanics.join('、') }}</dd></div>
              <div v-if="details.categories?.length"><dt class="font-semibold text-ink/45">{{ copy.categories }}</dt><dd>{{ details.categories.join('、') }}</dd></div>
            </dl>
            <p class="mt-5 text-xs leading-5 text-ink/45">{{ copy.evidence }}</p>
            <a v-if="details?.bggUrl || game.bggMetadata?.bggUrl" :href="details?.bggUrl || game.bggMetadata?.bggUrl" target="_blank" rel="noopener noreferrer" class="mt-3 inline-block text-sm font-semibold text-indigo">{{ copy.bgg }} ↗</a>
          </div>
        </section>

        <section class="mt-10">
          <h2 class="font-display text-3xl font-semibold">{{ copy.editions }}</h2>
          <div class="mt-5 stack-y-xl">
            <article v-for="edition in game.editions" :key="edition.id" class="tabletop-panel player-board p-5 sm:p-6">
              <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div><h3 class="font-display text-2xl font-semibold">{{ edition.name }}</h3><p class="mt-1 text-sm text-ink/45">{{ edition.language }}<span v-if="edition.publicationYear"> · {{ edition.publicationYear }}</span></p></div>
                <RouterLink :to="{ name: 'teach', query: { editionId: edition.id, onboarding: 'selected-game' } }" class="inline-flex min-h-11 items-center justify-center rounded-xl bg-copper px-5 text-sm font-semibold text-white">{{ copy.addRulebook }}</RouterLink>
              </div>
              <p v-if="editionDocuments(edition.id).length === 0" class="mt-5 rounded-xl bg-canvas px-4 py-5 text-sm text-ink/55">{{ copy.editionEmpty }}</p>
              <ul v-else class="mt-5 stack-y-md">
                <li v-for="document in editionDocuments(edition.id)" :key="document.document.id" class="rounded-xl border border-ink/10 bg-canvas p-4">
                  <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div><p class="font-semibold">{{ document.document.title }}</p><p class="mt-1 text-xs text-ink/45">{{ statusLabel(document.latestVersion.status) }}</p></div>
                    <div class="flex flex-wrap gap-2">
                      <template v-if="documentPlans(document)[0]">
                        <RouterLink :to="{ name: 'lesson', params: { planId: documentPlans(document)[0]!.id } }" class="min-h-10 rounded-lg bg-ink px-4 py-2.5 text-sm font-semibold text-canvas">{{ copy.openGuide }}</RouterLink>
                        <RouterLink :to="{ name: 'lesson-questions', params: { planId: documentPlans(document)[0]!.id } }" class="min-h-10 rounded-lg border border-indigo/25 px-4 py-2.5 text-sm font-semibold text-indigo">{{ copy.ask }}</RouterLink>
                      </template>
                      <RouterLink v-else-if="document.latestVersion.status === 'READY'" :to="{ name: 'teach', query: { editionId: edition.id } }" class="min-h-10 rounded-lg border border-ink/15 px-4 py-2.5 text-sm font-semibold">{{ copy.generate }}</RouterLink>
                      <a v-if="document.document.officialSourceUrl" :href="document.document.officialSourceUrl" target="_blank" rel="noopener noreferrer" class="min-h-10 px-2 py-2.5 text-sm font-semibold text-indigo">{{ copy.source }} ↗</a>
                    </div>
                  </div>
                </li>
              </ul>
            </article>
          </div>
        </section>
      </template>
    </main>
  </AppShell>
</template>
