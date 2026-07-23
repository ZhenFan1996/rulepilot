<script setup lang="ts">
interface VisualFocus {
  pageNumber: number
  label: string
  x: number
  y: number
  width: number
  height: number
}

interface ReaderStep {
  position: number
  heading: string
  kind: 'UNDERSTAND' | 'DO' | 'EXAMPLE' | 'WATCH' | 'CHECK' | 'VISUAL' | 'FLOW' | 'LEDGER'
  text: string
  sourcePages: number[]
  visualFocus: VisualFocus | null
}

interface ReaderSection {
  position: number
  title: string
  visualKind: 'REFERENCE_CARD' | 'TABLE_LAYOUT' | 'FLOW_DIAGRAM' | 'SCOREBOARD'
  visualCaption: string
  visualSourcePages: number[]
}

interface MoveMeta {
  label: string
  tone: string
}

defineProps<{
  section: ReaderSection
  leadStep: ReaderStep | null
  pathSteps: ReaderStep[]
  supportSteps: ReaderStep[]
  checkSteps: ReaderStep[]
  visualStepCount: number
  pathTitle: string
  currentVisualPageNumber: number | undefined
  visualFeedbackSaving: string | null
  online: boolean
  pageImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
  stepSourceLabel: (step: ReaderStep) => string
  moveMeta: (kind: ReaderStep['kind'] | undefined) => MoveMeta
  visualKindLabel: (kind: ReaderSection['visualKind']) => string
  hasVisualAid: (sectionPosition: number, stepPosition: number) => boolean
  visualAidResult: (sectionPosition: number, stepPosition: number) => 'NOT_RATED' | 'HELPFUL' | 'NOT_HELPFUL'
}>()

const emit = defineEmits<{
  rateVisualAid: [sectionPosition: number, stepPosition: number, result: 'HELPFUL' | 'NOT_HELPFUL']
}>()
</script>

<template>
  <div class="mt-7 grid items-start gap-8 2xl:grid-cols-[minmax(0,1fr)_19rem]">
    <div class="min-w-0">
      <section v-if="leadStep" class="rounded-2xl bg-ink-panel p-4 text-panel-text sm:p-6" aria-labelledby="chapter-core-title">
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-copper">先记住这一件事</p>
        <h3 id="chapter-core-title" class="mt-2 font-display text-xl font-semibold leading-7 sm:text-2xl sm:leading-8">{{ leadStep.heading || section.title }}</h3>
        <p class="mt-3 text-sm leading-6 text-panel-text/80 sm:text-base sm:leading-8">{{ leadStep.text }}</p>
        <figure v-if="leadStep.visualFocus" class="mt-5 overflow-hidden rounded-xl border border-panel-text/15 bg-canvas text-ink sm:max-w-2xl">
          <a :href="pageImageUrl(leadStep.visualFocus.pageNumber)" target="_blank" rel="noopener" title="打开完整规则书页面">
            <img :src="focusedPageImageUrl(leadStep.visualFocus)" :alt="`${leadStep.visualFocus.label}，截自规则书第 ${leadStep.visualFocus.pageNumber} 页`" class="block max-h-[30rem] w-full object-contain" loading="lazy">
          </a>
          <figcaption class="border-t border-ink/10 px-3 py-2 text-xs font-semibold text-copper">{{ leadStep.visualFocus.label }} · 直接对应这一条核心规则</figcaption>
        </figure>
        <a v-if="stepSourceLabel(leadStep)" :href="pageImageUrl(leadStep.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-4 inline-flex text-xs font-semibold text-panel-text/60 hover:text-panel-text">
          {{ stepSourceLabel(leadStep) }} ↗
        </a>
      </section>

      <nav v-if="pathSteps.length || supportSteps.length || checkSteps.length" class="mt-5 flex flex-wrap gap-2 text-xs font-semibold" aria-label="本节内容导航">
        <a v-if="pathSteps.length" href="#chapter-path" class="rounded-full bg-copper/10 px-3 py-2 text-copper">{{ pathTitle }}</a>
        <a v-if="supportSteps.length" href="#chapter-support" class="rounded-full bg-amber-100 px-3 py-2 text-amber-900">易错与例子</a>
        <a v-if="checkSteps.length" href="#chapter-check" class="rounded-full bg-ink/8 px-3 py-2 text-ink/65">学会了吗</a>
      </nav>

      <section v-if="pathSteps.length" id="chapter-path" class="mt-8 scroll-mt-28" aria-labelledby="chapter-path-title">
        <div class="flex items-end justify-between gap-4">
          <div>
            <p class="text-xs font-semibold text-copper">从理解到会玩</p>
            <h3 id="chapter-path-title" class="mt-1 font-display text-2xl font-semibold">{{ pathTitle }}</h3>
          </div>
          <span class="text-xs font-semibold text-ink/40">{{ pathSteps.length }} 个要点</span>
        </div>
        <ol class="relative mt-5 space-y-0 before:absolute before:bottom-5 before:left-[1.15rem] before:top-5 before:w-px before:bg-ink/15">
          <li v-for="(step, index) in pathSteps" :key="step.position" class="relative grid grid-cols-[2.4rem_1fr] gap-3 py-4 first:pt-1">
            <span class="relative z-[1] grid size-9 place-items-center rounded-full border border-copper/30 bg-paper text-sm font-bold text-copper">{{ index + 1 }}</span>
            <div class="min-w-0 pb-1">
              <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
                <h4 class="font-display text-xl font-semibold leading-7">{{ step.heading || `要点 ${index + 1}` }}</h4>
                <span class="text-xs font-semibold" :class="moveMeta(step.kind).tone.split(' ')[1]">{{ moveMeta(step.kind).label }}</span>
              </div>
              <div v-if="step.kind === 'VISUAL' && step.visualFocus" class="mt-4 min-w-0" data-testid="lesson-visual-step">
                <div>
                  <p class="max-w-3xl text-[0.95rem] leading-7 text-ink/72">{{ step.text }}</p>
                  <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo hover:underline">查看第 {{ step.visualFocus.pageNumber }} 页上下文 ↗</a>
                </div>
                <figure class="mt-4 max-w-3xl overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
                  <a :href="pageImageUrl(step.visualFocus.pageNumber)" target="_blank" rel="noopener" title="打开完整规则书页面">
                    <img :src="focusedPageImageUrl(step.visualFocus)" :alt="`${step.visualFocus.label}，截自规则书第 ${step.visualFocus.pageNumber} 页`" class="block max-h-[30rem] w-full object-contain" loading="lazy">
                  </a>
                  <figcaption class="border-t border-indigo/10 px-3 py-2 text-xs font-semibold text-copper">{{ step.visualFocus.label }} · 第 {{ step.visualFocus.pageNumber }} 页局部</figcaption>
                  <div v-if="hasVisualAid(section.position, step.position)" class="border-t border-indigo/10 px-3 py-2">
                    <p class="text-xs font-semibold text-ink/55">这张图有帮到你吗？</p>
                    <div class="mt-2 grid grid-cols-2 gap-2">
                      <button type="button" class="min-h-9 rounded-lg border px-2 text-xs font-semibold disabled:opacity-40" :class="visualAidResult(section.position, step.position) === 'HELPFUL' ? 'border-indigo bg-indigo/8 text-indigo' : 'border-ink/15'" :disabled="visualFeedbackSaving !== null || !online" @click="emit('rateVisualAid', section.position, step.position, 'HELPFUL')">有帮助</button>
                      <button type="button" class="min-h-9 rounded-lg border px-2 text-xs font-semibold disabled:opacity-40" :class="visualAidResult(section.position, step.position) === 'NOT_HELPFUL' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="visualFeedbackSaving !== null || !online" @click="emit('rateVisualAid', section.position, step.position, 'NOT_HELPFUL')">没帮上忙</button>
                    </div>
                  </div>
                </figure>
              </div>
              <template v-else>
                <p class="mt-2 text-[0.95rem] leading-7 text-ink/72">{{ step.text }}</p>
                <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo hover:underline">{{ stepSourceLabel(step) }} ↗</a>
              </template>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="supportSteps.length" id="chapter-support" class="mt-8 scroll-mt-28" aria-labelledby="chapter-support-title">
        <p class="text-xs font-semibold text-amber-800">经验比原文多走一步</p>
        <h3 id="chapter-support-title" class="mt-1 font-display text-2xl font-semibold">易错、例子与算账</h3>
        <div class="mt-4 grid gap-3 sm:grid-cols-2">
          <article v-for="step in supportSteps" :key="step.position" class="rounded-2xl border p-4" :class="step.kind === 'WATCH' ? 'border-amber-300 bg-amber-50' : 'border-emerald-200 bg-emerald-50/70'">
            <p class="text-xs font-bold" :class="step.kind === 'WATCH' ? 'text-amber-900' : 'text-emerald-800'">{{ moveMeta(step.kind).label }}</p>
            <h4 class="mt-1 font-display text-lg font-semibold leading-6">{{ step.heading }}</h4>
            <p class="mt-2 text-sm leading-6 text-ink/70">{{ step.text }}</p>
            <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo">{{ stepSourceLabel(step) }} ↗</a>
          </article>
        </div>
      </section>

      <section v-if="checkSteps.length" id="chapter-check" class="mt-8 scroll-mt-28 rounded-2xl border border-copper/25 bg-copper/[0.07] p-5" aria-labelledby="chapter-check-title">
        <p class="text-xs font-semibold text-copper">别急着翻页</p>
        <h3 id="chapter-check-title" class="mt-1 font-display text-2xl font-semibold">不用看答案，你能做到吗？</h3>
        <article v-for="step in checkSteps" :key="step.position" class="mt-4 border-t border-copper/15 pt-4 first:mt-3">
          <h4 class="font-semibold">{{ step.heading }}</h4>
          <p class="mt-2 text-sm leading-7 text-ink/70">{{ step.text }}</p>
          <a v-if="stepSourceLabel(step)" :href="pageImageUrl(step.sourcePages[0]!)" target="_blank" rel="noopener" class="mt-2 inline-flex text-xs font-semibold text-indigo">不会时核对 {{ step.sourcePages.join('、') }} 页 ↗</a>
        </article>
      </section>
    </div>

    <aside class="min-w-0 rounded-2xl border border-indigo/12 bg-indigo/[0.035] p-4 2xl:sticky 2xl:top-28" aria-label="本节原文与桌面图">
      <div class="flex items-start justify-between gap-3">
        <div>
          <p class="text-xs font-semibold text-indigo">边看边对照</p>
          <h3 class="mt-1 font-display text-lg font-semibold">{{ visualKindLabel(section.visualKind) }}</h3>
        </div>
        <span v-if="currentVisualPageNumber" class="shrink-0 rounded-full bg-paper px-2.5 py-1 text-xs font-semibold text-ink/55">第 {{ currentVisualPageNumber }} 页</span>
      </div>
      <p class="mt-3 text-sm leading-6 text-ink/65">{{ section.visualCaption }}</p>
      <p v-if="visualStepCount" class="mt-3 rounded-xl bg-paper px-3 py-2 text-xs leading-5 text-ink/55">本节的局部截图已放在对应规则旁，阅读时不用来回对照整页。</p>
      <p v-else class="mt-3 text-xs leading-5 text-ink/50">本节没有可靠的局部视觉焦点，因此不展示整页截图冒充讲解。</p>
      <div v-if="section.visualSourcePages.length" class="mt-4 flex flex-wrap gap-2">
        <a v-for="page in section.visualSourcePages" :key="page" :href="pageImageUrl(page)" target="_blank" rel="noopener" class="rounded-full border border-indigo/15 bg-paper px-3 py-2 text-xs font-semibold text-indigo">查看原文第 {{ page }} 页</a>
      </div>
    </aside>
  </div>
</template>
