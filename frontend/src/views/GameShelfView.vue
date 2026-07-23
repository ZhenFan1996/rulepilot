<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import GameShelfCard from '@/components/GameShelfCard.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import {
  buildPersonalShelf,
  type ShelfCatalogEntry,
  type ShelfDocument,
  type ShelfPlan,
} from '@/lib/gameShelf'

const router = useRouter()
const catalog = ref<ShelfCatalogEntry[]>([])
const documents = ref<ShelfDocument[]>([])
const plans = ref<ShelfPlan[]>([])
const loading = ref(true)
const errorMessage = ref('')
const search = ref('')

const shelf = computed(() => buildPersonalShelf(catalog.value, documents.value, plans.value))
const filteredShelf = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  if (!keyword) return shelf.value
  return shelf.value.filter((item) => item.title.toLocaleLowerCase().includes(keyword))
})
const readyLessons = computed(() => shelf.value.filter((item) => item.latestPlanId).length)

async function checkedFetch(path: string) {
  const response = await fetch(path, { credentials: 'include' })
  if (response.status === 401) {
    await router.push({ name: 'login' })
    throw new Error('请先登录后查看你的桌游书架。')
  }
  if (!response.ok) throw new Error('暂时无法读取你的桌游资料。')
  return response
}

async function loadShelf() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [catalogResponse, documentResponse, planResponse] = await Promise.all([
      checkedFetch('/api/v1/games'),
      checkedFetch('/api/v1/documents'),
      checkedFetch('/api/v1/teaching-plans'),
    ])
    catalog.value = await catalogResponse.json() as ShelfCatalogEntry[]
    documents.value = await documentResponse.json() as ShelfDocument[]
    plans.value = await planResponse.json() as ShelfPlan[]
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(loadShelf)
</script>

<template>
  <AppShell>
    <div class="shelf-page mx-auto max-w-7xl px-4 py-7 sm:px-8 sm:py-10 lg:px-12 lg:py-14">
      <section class="relative overflow-hidden rounded-[2rem] border border-ink/10 bg-ink px-6 py-8 text-canvas shadow-[0_18px_42px_rgba(26,35,42,0.16)] sm:px-9 sm:py-10">
        <div class="shelf-hero-mark" aria-hidden="true">
          <TabletopGlyph name="meeple" :size="128" />
        </div>
        <div class="relative max-w-2xl">
          <p class="text-xs font-bold uppercase tracking-[0.2em] text-copper">我的桌游书架</p>
          <h1 class="mt-3 font-display text-4xl font-semibold tracking-tight sm:text-5xl">今晚想开哪一局？</h1>
          <p class="mt-4 max-w-xl text-base leading-7 text-canvas/70">规则书、讲解和游戏放在一起。挑一款继续读，或直接丢进一本新规则书。</p>
          <div class="mt-7 flex flex-wrap gap-3">
            <RouterLink :to="{ name: 'teach' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl bg-copper px-5 text-sm font-bold text-white transition hover:bg-copper-dark">
              <TabletopGlyph name="plus" :size="18" /> 添加规则书
            </RouterLink>
            <RouterLink :to="{ name: 'public-library' }" class="inline-flex min-h-11 items-center gap-2 rounded-xl border border-canvas/25 px-5 text-sm font-bold text-canvas transition hover:bg-canvas/10">
              <TabletopGlyph name="library" :size="18" /> 逛公开讲解
            </RouterLink>
          </div>
        </div>
      </section>

      <section class="mt-8 flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p class="text-sm font-semibold text-copper">你的资料</p>
          <h2 class="mt-1 font-display text-3xl font-semibold tracking-tight">桌上正在玩的游戏</h2>
          <p class="mt-2 text-sm leading-6 text-ink/55">{{ shelf.length }} 款游戏 · {{ documents.length }} 本规则书 · {{ readyLessons }} 份可继续的讲解</p>
        </div>
        <label class="relative block w-full lg:w-72">
          <span class="sr-only">搜索我的游戏</span>
          <TabletopGlyph name="compass" :size="18" class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-ink/45" />
          <input v-model="search" type="search" placeholder="搜一款游戏" class="min-h-11 w-full rounded-xl border border-ink/12 bg-paper py-3 pl-11 pr-4 text-sm outline-none transition placeholder:text-ink/40 focus:border-indigo focus:ring-2 focus:ring-indigo/15">
        </label>
      </section>

      <section v-if="loading" class="mt-7 grid gap-5 sm:grid-cols-2 xl:grid-cols-3" aria-live="polite" aria-label="正在读取你的桌游书架">
        <div v-for="index in 3" :key="index" class="h-[25rem] animate-pulse rounded-[1.65rem] border border-ink/8 bg-paper" />
      </section>

      <section v-else-if="errorMessage" class="mt-7 rounded-[1.5rem] border border-red-200 bg-red-50 p-6 text-red-900" role="alert">
        <h2 class="font-display text-xl font-semibold">书架暂时打不开</h2>
        <p class="mt-2 text-sm leading-6">{{ errorMessage }}</p>
        <button type="button" class="mt-5 min-h-11 rounded-xl bg-ink px-5 text-sm font-bold text-canvas" @click="loadShelf">再试一次</button>
      </section>

      <section v-else-if="shelf.length === 0" class="mt-7 overflow-hidden rounded-[1.75rem] border border-dashed border-ink/25 bg-paper px-6 py-12 text-center sm:px-10">
        <div class="mx-auto grid size-16 place-items-center rounded-2xl bg-copper/10 text-copper">
          <TabletopGlyph name="rulebook" :size="32" />
        </div>
        <h2 class="mt-5 font-display text-3xl font-semibold">书架还空着</h2>
        <p class="mx-auto mt-3 max-w-md leading-7 text-ink/60">不用先创建游戏。选一本 PDF，RulePilot 会以规则书的标题建好第一张卡片。</p>
        <RouterLink :to="{ name: 'teach' }" class="mt-6 inline-flex min-h-11 items-center gap-2 rounded-xl bg-copper px-5 text-sm font-bold text-white transition hover:bg-copper-dark">
          <TabletopGlyph name="plus" :size="18" /> 上传第一本规则书
        </RouterLink>
      </section>

      <section v-else-if="filteredShelf.length" class="mt-7 grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
        <GameShelfCard v-for="(item, index) in filteredShelf" :key="item.id" :item="item" :index="index" />
      </section>

      <section v-else class="mt-7 rounded-[1.5rem] border border-ink/10 bg-paper p-8 text-center">
        <TabletopGlyph name="compass" :size="30" class="mx-auto text-indigo" />
        <h2 class="mt-4 font-display text-2xl font-semibold">没在书架上找到</h2>
        <p class="mt-2 text-sm text-ink/55">试试游戏原名，或清空搜索再看看。</p>
        <button type="button" class="mt-4 min-h-11 text-sm font-bold text-indigo" @click="search = ''">清空搜索</button>
      </section>

      <aside class="mt-10 flex flex-col gap-4 rounded-[1.5rem] border border-ink/10 bg-paper p-5 sm:flex-row sm:items-center sm:justify-between">
        <div class="flex items-center gap-3">
          <span class="grid size-11 place-items-center rounded-xl bg-indigo/10 text-indigo"><TabletopGlyph name="cards" :size="23" /></span>
          <div>
            <h2 class="font-semibold">需要补版本或扩展？</h2>
            <p class="mt-1 text-sm text-ink/55">这些是偶尔才用的资料维护，不打断你开始讲解。</p>
          </div>
        </div>
        <RouterLink :to="{ name: 'catalog-manage' }" class="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-ink/12 px-4 text-sm font-bold text-ink/75 transition hover:border-indigo hover:text-indigo">
          管理游戏资料 <TabletopGlyph name="arrow" :size="17" />
        </RouterLink>
      </aside>
    </div>
  </AppShell>
</template>

<style scoped>
.shelf-page {
  background-image: radial-gradient(rgba(26, 35, 42, 0.055) 0.7px, transparent 0.7px);
  background-size: 12px 12px;
}

.shelf-hero-mark {
  position: absolute;
  right: clamp(1.25rem, 8vw, 7rem);
  bottom: -2.5rem;
  color: rgba(245, 240, 232, 0.08);
  transform: rotate(-8deg);
}
</style>
