<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import AppShell from '@/components/AppShell.vue'
import TabletopGlyph from '@/components/TabletopGlyph.vue'
import { useLocale } from '@/lib/locale'

interface HotGame {
  rank: number
  bggId: number
  name: string
  originalName: string
  nameLocalized: boolean
  publicationYear: number | null
  thumbnailUrl: string
  bggUrl: string
}

const { locale } = useLocale()
const username = ref('')
const hotGames = ref<HotGame[]>([])
const loadingGames = ref(true)
const gameError = ref(false)
const randomOffset = ref(0)

const copy = computed(() => locale.value === 'zh-CN' ? {
  greeting: username.value ? `${username.value}，今晚想玩什么？` : '今晚想玩什么？',
  title: '把想玩的，变成今晚真的能开桌。',
  lede: '从热门桌游找灵感，随机碰一款新游戏，或者直接把规则书交给 RulePilot。找资料、读规则、生成讲解都能在后台继续。',
  recommend: '说说这桌人的偏好', catalog: '浏览全部桌游', source: '桌游资料来自 BGG',
  hotEyebrow: '桌游圈正在关注', hotTitle: 'BGG 热门桌游', hotHint: '热度只代表近期关注，不等于最适合你的排名。',
  randomEyebrow: '不想做功课？', randomTitle: '随机碰三款', randomHint: '从当前热门里随机抽取，点开后再看人数、时长和机制。',
  shuffle: '换一批', inspect: '查看游戏并找规则书', yearUnknown: '年份未知', loading: '正在读取 BGG 热门桌游…',
  unavailable: '暂时没拿到 BGG 热门资料。推荐对话、完整目录和规则书上传仍然可用。',
  guideEyebrow: '从想玩到开桌', guideTitle: '所有入口，最后汇成同一条路',
  guide: [
    { number: '01', title: '先找到想玩的', detail: '聊偏好、看热门或直接逛完整目录。', action: '开始找桌游', route: 'game-recommendations', icon: 'meeple' },
    { number: '02', title: '拿到可信规则书', detail: '优先搜索出版社官网，也可以上传 PDF 或拍摄页面。', action: '添加规则书', route: 'teach', icon: 'rulebook' },
    { number: '03', title: '让讲解在后台完成', detail: '继续浏览或离开页面，随时从“后台任务”查看进度。', action: '打开讲解中心', route: 'lessons', icon: 'cards' },
  ],
  boundary: 'BGG 只用于游戏识别、目录资料和封面；规则讲解与答疑只引用你确认的规则书。',
} : {
  greeting: username.value ? `${username.value}, what should we play tonight?` : 'What should we play tonight?',
  title: 'Turn a game idea into a table that is ready tonight.',
  lede: 'Start with what is hot, stumble onto something new, or hand RulePilot a rulebook. Discovery, reading, and lesson generation keep moving in the background.',
  recommend: 'Describe this group', catalog: 'Browse every game', source: 'Game data from BGG',
  hotEyebrow: 'What players are watching', hotTitle: 'Trending on BGG', hotHint: 'Hotness reflects recent attention, not an objective best-game ranking.',
  randomEyebrow: 'Skip the homework', randomTitle: 'Three random picks', randomHint: 'Drawn from the current hot list; open one to inspect players, time, and mechanics.',
  shuffle: 'Shuffle', inspect: 'View game and find its rulebook', yearUnknown: 'Year unknown', loading: 'Loading BGG hot games…',
  unavailable: 'BGG hot data is unavailable right now. Recommendations, the complete catalog, and rulebook upload still work.',
  guideEyebrow: 'From idea to table', guideTitle: 'Every entry joins the same continuous path',
  guide: [
    { number: '01', title: 'Find a game', detail: 'Talk through preferences, browse what is hot, or search the full catalog.', action: 'Find a game', route: 'game-recommendations', icon: 'meeple' },
    { number: '02', title: 'Get a trusted rulebook', detail: 'Search publisher sites first, upload a PDF, or photograph pages.', action: 'Add a rulebook', route: 'teach', icon: 'rulebook' },
    { number: '03', title: 'Let the lesson finish backstage', detail: 'Keep browsing or leave; Background work keeps every task findable.', action: 'Open lesson center', route: 'lessons', icon: 'cards' },
  ],
  boundary: 'BGG is used only for game identity, catalog data, and covers. Lessons and answers cite only your confirmed rulebook.',
})

const featuredGames = computed(() => hotGames.value.slice(0, 4))
const randomGames = computed(() => {
  if (!hotGames.value.length) return []
  const source = hotGames.value.slice(4).length >= 3 ? hotGames.value.slice(4) : hotGames.value
  return Array.from({ length: Math.min(3, source.length) }, (_, index) => source[(randomOffset.value + index * 3) % source.length]!)
})

function shuffleRandomGames() {
  const size = hotGames.value.length > 4 ? hotGames.value.length - 4 : hotGames.value.length
  if (size <= 1) return
  const step = 1 + Math.floor(Math.random() * (size - 1))
  randomOffset.value = (randomOffset.value + step) % size
}

async function load() {
  loadingGames.value = true
  gameError.value = false
  try {
    const [sessionResponse, hotResponse] = await Promise.all([
      fetch('/api/auth/session', { credentials: 'include' }).catch(() => null),
      fetch(`/api/v1/bgg/recommendations?locale=${encodeURIComponent(locale.value)}`, { credentials: 'include' }),
    ])
    if (sessionResponse?.ok) username.value = ((await sessionResponse.json()) as { username: string }).username
    else username.value = ''
    if (!hotResponse.ok) throw new Error('hot games unavailable')
    hotGames.value = (await hotResponse.json() as HotGame[]).filter(game => game.thumbnailUrl).slice(0, 12)
    randomOffset.value = hotGames.value.length > 4 ? Math.floor(Math.random() * (hotGames.value.length - 4)) : 0
  } catch {
    gameError.value = true
    hotGames.value = []
  } finally {
    loadingGames.value = false
  }
}

onMounted(load)
watch(locale, load)
</script>

<template>
  <AppShell>
    <main class="tabletop-page max-w-7xl">
      <section class="home-hero player-board overflow-hidden" aria-labelledby="home-title">
        <div class="home-hero__copy">
          <p class="tabletop-kicker">{{ copy.greeting }}</p>
          <h1 id="home-title" class="mt-3 max-w-[11ch] font-display text-[clamp(2.8rem,6vw,5.8rem)] font-semibold leading-[0.94] tracking-[-0.055em]">{{ copy.title }}</h1>
          <p class="mt-6 max-w-xl text-base leading-8 text-ink/62">{{ copy.lede }}</p>
          <div class="mt-7 flex flex-col gap-3 sm:flex-row">
            <RouterLink :to="{ name: 'game-recommendations' }" class="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-copper px-6 font-semibold text-white hover:bg-copper/90">
              {{ copy.recommend }} <TabletopGlyph name="arrow" :size="18" />
            </RouterLink>
            <RouterLink :to="{ name: 'game-catalog-browse' }" class="inline-flex min-h-12 items-center justify-center rounded-xl border border-ink/15 bg-paper/80 px-6 font-semibold text-indigo hover:border-indigo/40">
              {{ copy.catalog }}
            </RouterLink>
          </div>
          <div class="mt-8 flex flex-wrap items-center gap-3 border-t border-ink/10 pt-5">
            <span class="text-xs font-semibold text-ink/45">{{ copy.source }}</span>
            <a href="https://boardgamegeek.com" target="_blank" rel="noopener noreferrer"><img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-[137px]" width="342" height="76"></a>
          </div>
        </div>
        <div class="home-hero__art" aria-hidden="true">
          <img src="/illustrations/tabletop-gathering-v2.webp" alt="" width="1672" height="941" fetchpriority="high">
        </div>
      </section>

      <section class="mt-14" aria-labelledby="hot-games-title">
        <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p class="tabletop-kicker">{{ copy.hotEyebrow }}</p>
            <h2 id="hot-games-title" class="mt-2 font-display text-3xl font-semibold sm:text-4xl">{{ copy.hotTitle }}</h2>
            <p class="mt-2 text-sm leading-6 text-ink/50">{{ copy.hotHint }}</p>
          </div>
          <a href="https://boardgamegeek.com/hotness" target="_blank" rel="noopener noreferrer" class="shrink-0"><img src="/powered-by-bgg-rgb.svg" alt="Powered by BoardGameGeek" class="h-auto w-[137px]" width="342" height="76"></a>
        </div>
        <p v-if="loadingGames" class="mt-6 rounded-xl border border-dashed border-ink/15 bg-paper p-8 text-center text-sm text-ink/45" role="status">{{ copy.loading }}</p>
        <p v-else-if="gameError || featuredGames.length === 0" class="mt-6 rounded-xl border border-amber-200 bg-amber-50 p-5 text-sm leading-6 text-amber-950">{{ copy.unavailable }}</p>
        <ol v-else class="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <li v-for="game in featuredGames" :key="game.bggId">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="game-card group">
              <div class="game-card__cover">
                <img :src="game.thumbnailUrl" :alt="game.name" loading="lazy" referrerpolicy="no-referrer">
                <span class="game-card__rank">#{{ game.rank }}</span>
              </div>
              <div class="p-4">
                <h3 class="font-display text-xl font-semibold leading-tight">{{ game.name }}</h3>
                <p v-if="game.nameLocalized && game.originalName !== game.name" class="mt-1 text-xs text-ink/45">{{ game.originalName }}</p>
                <p class="mt-2 text-xs text-ink/45">{{ game.publicationYear ?? copy.yearUnknown }}</p>
                <span class="mt-4 inline-flex items-center gap-1 text-sm font-semibold text-indigo">{{ copy.inspect }} <TabletopGlyph name="arrow" :size="16" /></span>
              </div>
            </RouterLink>
          </li>
        </ol>
      </section>

      <section v-if="randomGames.length" class="random-board mt-14" aria-labelledby="random-games-title">
        <div class="relative z-10 max-w-sm">
          <p class="tabletop-kicker !text-gold">{{ copy.randomEyebrow }}</p>
          <h2 id="random-games-title" class="mt-2 font-display text-3xl font-semibold text-white sm:text-4xl">{{ copy.randomTitle }}</h2>
          <p class="mt-3 text-sm leading-6 text-white/60">{{ copy.randomHint }}</p>
          <button type="button" class="mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-gold px-5 font-semibold text-ink" @click="shuffleRandomGames">↻ {{ copy.shuffle }}</button>
        </div>
        <ul class="relative z-10 grid min-w-0 flex-1 gap-3 sm:grid-cols-3">
          <li v-for="game in randomGames" :key="`${randomOffset}-${game.bggId}`">
            <RouterLink :to="{ name: 'game-discovery', params: { bggId: game.bggId } }" class="flex h-full min-h-36 items-center gap-3 rounded-xl border border-white/10 bg-white/7 p-3 text-white transition hover:-translate-y-1 hover:bg-white/12">
              <img :src="game.thumbnailUrl" :alt="game.name" loading="lazy" referrerpolicy="no-referrer" class="h-24 w-20 shrink-0 rounded-lg bg-paper object-contain">
              <span class="min-w-0">
                <strong class="block font-display text-lg leading-tight">{{ game.name }}</strong>
                <small class="mt-2 block text-white/50">#{{ game.rank }} · {{ game.publicationYear ?? copy.yearUnknown }}</small>
              </span>
            </RouterLink>
          </li>
        </ul>
      </section>

      <section class="mt-16 border-t border-ink/10 pt-10" aria-labelledby="guide-title">
        <p class="tabletop-kicker">{{ copy.guideEyebrow }}</p>
        <h2 id="guide-title" class="mt-2 max-w-2xl font-display text-3xl font-semibold sm:text-4xl">{{ copy.guideTitle }}</h2>
        <ol class="mt-7 grid gap-4 lg:grid-cols-3">
          <li v-for="item in copy.guide" :key="item.number" class="guide-card">
            <div class="flex items-start justify-between gap-4">
              <span class="text-xs font-black tracking-[0.15em] text-copper">{{ item.number }}</span>
              <span class="grid size-10 place-items-center rounded-full bg-felt text-white"><TabletopGlyph :name="item.icon" :size="20" /></span>
            </div>
            <h3 class="mt-8 font-display text-2xl font-semibold">{{ item.title }}</h3>
            <p class="mt-2 min-h-12 text-sm leading-6 text-ink/52">{{ item.detail }}</p>
            <RouterLink :to="{ name: item.route }" class="mt-5 inline-flex min-h-11 items-center gap-1 text-sm font-semibold text-indigo">{{ item.action }} <TabletopGlyph name="arrow" :size="16" /></RouterLink>
          </li>
        </ol>
        <p class="mt-7 border-l-2 border-indigo/35 pl-4 text-xs leading-6 text-ink/45">{{ copy.boundary }}</p>
      </section>
    </main>
  </AppShell>
</template>

<style scoped>
.home-hero {
  position: relative;
  display: grid;
  min-height: min(42rem, calc(100vh - 5rem));
  border: 1px solid color-mix(in srgb, var(--color-ink) 12%, transparent);
  background: var(--color-paper);
  box-shadow: 0 30px 90px -60px rgb(24 34 67 / 70%);
}

.home-hero__copy {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(1.6rem, 5vw, 5rem);
}

.home-hero__art {
  position: relative;
  min-height: 22rem;
  overflow: hidden;
  background: #f3ead8;
}

.home-hero__art img { width: 100%; height: 100%; object-fit: cover; object-position: 67% center; }

.game-card {
  display: block;
  height: 100%;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-ink) 11%, transparent);
  border-radius: 1rem 1rem 2rem 1rem;
  background: var(--color-paper);
  box-shadow: 0 8px 0 color-mix(in srgb, var(--color-indigo) 10%, transparent);
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.game-card:hover { transform: translateY(-4px); box-shadow: 0 12px 0 color-mix(in srgb, var(--color-indigo) 18%, transparent); }
.game-card__cover { position: relative; display: grid; min-height: 15rem; place-items: center; overflow: hidden; background: color-mix(in srgb, var(--color-canvas) 78%, var(--color-indigo) 4%); }
.game-card__cover img { width: 100%; height: 15rem; object-fit: contain; padding: 1rem; transition: transform 220ms ease; }
.game-card:hover .game-card__cover img { transform: scale(1.035); }
.game-card__rank { position: absolute; top: 0.75rem; left: 0.75rem; display: grid; min-width: 2.5rem; height: 2.5rem; place-items: center; border: 3px solid var(--color-paper); border-radius: 999px; color: white; background: var(--color-copper); font-size: 0.72rem; font-weight: 900; }

.random-board { position: relative; display: flex; flex-direction: column; gap: 2rem; overflow: hidden; border-radius: 1.75rem 1.75rem 4rem 1.75rem; padding: clamp(1.5rem, 4vw, 3.5rem); background: var(--color-ink-panel); }
.random-board::after { position: absolute; right: -8rem; bottom: -10rem; width: 28rem; aspect-ratio: 1; border: 4rem solid rgb(55 90 160 / 32%); border-radius: 50%; content: ''; }
.guide-card { border-top: 3px solid var(--color-copper); border-radius: 0 0 1.25rem 1.25rem; padding: 1.5rem; background: var(--color-paper); box-shadow: 0 10px 28px -26px rgb(24 34 67 / 60%); }

@media (min-width: 1024px) {
  .home-hero { grid-template-columns: minmax(0, 0.92fr) minmax(30rem, 1.08fr); }
  .home-hero__art { min-height: 100%; }
  .home-hero__art::before { position: absolute; z-index: 1; inset: 0 auto 0 0; width: 22%; background: linear-gradient(90deg, var(--color-paper), transparent); content: ''; }
  .random-board { flex-direction: row; align-items: center; }
}

@media (max-width: 639px) {
  .home-hero__copy { padding: 1.35rem; }
  .home-hero__art { min-height: 15rem; order: -1; }
  .home-hero__art img { object-position: 70% center; }
  #home-title { font-size: 2.45rem; line-height: 0.96; }
  .game-card__cover, .game-card__cover img { min-height: 12rem; height: 12rem; }
}

@media (prefers-reduced-motion: reduce) {
  .game-card, .game-card__cover img { transition: none; }
}
</style>
