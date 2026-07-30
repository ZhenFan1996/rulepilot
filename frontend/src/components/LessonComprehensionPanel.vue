<script setup lang="ts">
import { ref } from 'vue'

import type { LessonComprehensionReport, VisualFocus } from '@/composables/lessonSupportingContent'
import { useLocale } from '@/lib/locale'

type ComprehensionTask = LessonComprehensionReport['tasks'][number]

defineProps<{
  comprehension: LessonComprehensionReport | null
  errorMessage: string
  saving: string | null
  online: boolean
  pageImageUrl: (page: number) => string
  focusedPageImageUrl: (focus: VisualFocus) => string
  visualFocusStyle: (focus: VisualFocus) => Record<string, string>
}>()

const emit = defineEmits<{
  rateTask: [taskType: ComprehensionTask['type'], result: 'CAN_DO' | 'NEEDS_HELP']
  rateVisualAid: [key: string, result: 'HELPFUL' | 'NOT_HELPFUL']
  revisitChapter: [index: number]
}>()

const failedImagePages = ref<number[]>([])
const { locale, t } = useLocale()

function imageFailed(pageNumber: number) {
  if (!failedImagePages.value.includes(pageNumber)) failedImagePages.value.push(pageNumber)
}

function taskStatusLabel(result: ComprehensionTask['result']) {
  if (result === 'CAN_DO') return t('lesson.comprehension.status.canDo')
  if (result === 'NEEDS_HELP') return t('lesson.comprehension.status.needsHelp')
  return t('lesson.comprehension.status.notTried')
}

function pageList(pages: number[]) {
  return pages.join(locale.value === 'en' ? ', ' : '、')
}
</script>

<template>
  <section class="mt-8 rounded-3xl border border-copper/20 bg-copper/[0.06] p-5 sm:p-6" aria-labelledby="comprehension-title">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="text-xs font-semibold text-copper">{{ t('lesson.comprehension.eyebrow') }}</p>
        <h3 id="comprehension-title" class="mt-1 font-display text-2xl font-semibold">{{ t('lesson.comprehension.title') }}</h3>
        <p class="mt-2 text-sm leading-6 text-ink/55">{{ t('lesson.comprehension.description') }}</p>
      </div>
      <div v-if="comprehension" class="text-right text-sm font-semibold text-copper">
        <p>{{ t('lesson.comprehension.mastered', { completed: comprehension.canDoCount, total: comprehension.readyTaskCount }) }}</p>
        <p v-if="comprehension.visualAidRatedCount" class="mt-1 text-xs text-ink/50">{{ t('lesson.comprehension.visualHelpful', { helpful: comprehension.visualAidHelpfulCount, rated: comprehension.visualAidRatedCount, percent: comprehension.visualAidHelpfulPercent ?? 0 }) }}</p>
      </div>
    </div>

    <p v-if="errorMessage" class="mt-4 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
    <ol v-if="comprehension" class="mt-5 grid gap-3 sm:grid-cols-2">
      <li v-for="task in comprehension.tasks" :key="task.type" class="rounded-2xl border border-ink/10 bg-paper p-4">
        <div class="flex items-start justify-between gap-3">
          <h4 class="font-semibold leading-6">{{ task.label }}</h4>
          <span class="shrink-0 text-xs font-semibold" :class="task.result === 'CAN_DO' ? 'text-emerald-700' : task.result === 'NEEDS_HELP' ? 'text-amber-800' : 'text-ink/40'">
            {{ taskStatusLabel(task.result) }}
          </span>
        </div>
        <figure v-if="task.visualFocus && !failedImagePages.includes(task.visualFocus.pageNumber)" class="mt-3 overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
          <a :href="pageImageUrl(task.visualFocus.pageNumber)" target="_blank" rel="noopener" :title="t('lesson.comprehension.openFullPage')" class="relative block">
            <img :src="pageImageUrl(task.visualFocus.pageNumber)" :alt="t('lesson.comprehension.visual.alt', { page: task.visualFocus.pageNumber, label: task.visualFocus.label })" class="block h-auto w-full" loading="lazy" @error="imageFailed(task.visualFocus.pageNumber)">
            <span class="pointer-events-none absolute rounded-md border-2 border-copper bg-copper/10 shadow-[0_0_0_2px_rgba(255,255,255,0.8)]" :style="visualFocusStyle(task.visualFocus)" aria-hidden="true" />
          </a>
          <figcaption class="flex items-center justify-between gap-2 px-3 py-2 text-xs font-semibold text-indigo">
            <span>{{ t('lesson.comprehension.visual.focus', { label: task.visualFocus.label }) }}</span><span>{{ t('lesson.comprehension.page', { page: task.visualFocus.pageNumber }) }}</span>
          </figcaption>
          <p v-if="task.visualFocus.visibleDescription" class="border-t border-indigo/10 px-3 py-2 text-xs leading-5 text-ink/60">{{ task.visualFocus.visibleDescription }}</p>
        </figure>
        <p v-else-if="task.visualFocus" class="mt-3 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{{ t('lesson.comprehension.imageUnavailable.before') }} <a :href="pageImageUrl(task.visualFocus.pageNumber)" target="_blank" rel="noopener" class="font-semibold underline">{{ t('lesson.comprehension.imageUnavailable.link') }}</a>{{ t('lesson.comprehension.imageUnavailable.after') }}</p>
        <p class="mt-2 text-sm leading-6 text-ink/65">{{ task.prompt }}</p>
        <p v-if="task.sourcePages.length" class="mt-2 text-xs font-semibold text-indigo">{{ t('lesson.comprehension.sourcePages', { pages: pageList(task.sourcePages) }) }}</p>
        <div v-if="task.readiness === 'READY' && task.type !== 'VERIFY_VISUAL_AID'" class="mt-4 grid grid-cols-2 gap-2">
          <button type="button" class="min-h-11 rounded-xl border px-3 text-sm font-semibold disabled:opacity-40" :class="task.result === 'CAN_DO' ? 'border-emerald-700 bg-emerald-50 text-emerald-900' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateTask', task.type, 'CAN_DO')">{{ t('lesson.comprehension.action.canDo') }}</button>
          <button type="button" class="min-h-11 rounded-xl border px-3 text-sm font-semibold disabled:opacity-40" :class="task.result === 'NEEDS_HELP' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateTask', task.type, 'NEEDS_HELP')">{{ t('lesson.comprehension.action.needsHelp') }}</button>
        </div>
        <button v-if="task.result === 'NEEDS_HELP' && task.chapterPositions.length" type="button" class="mt-3 min-h-10 w-full rounded-xl bg-copper px-3 text-sm font-semibold text-white" @click="emit('revisitChapter', (task.chapterPositions.at(0) ?? 1) - 1)">{{ t('lesson.comprehension.action.revisit', { chapter: task.chapterPositions.at(0) ?? 1 }) }}</button>
      </li>
    </ol>
    <div v-if="comprehension?.visualAids.length" class="mt-6 border-t border-ink/10 pt-5">
      <h4 class="font-display text-xl font-semibold">{{ t('lesson.comprehension.visualAids.title') }}</h4>
      <p class="mt-1 text-sm leading-6 text-ink/55">{{ t('lesson.comprehension.visualAids.description') }}</p>
      <ol class="mt-4 grid gap-3 sm:grid-cols-2">
        <li v-for="aid in comprehension.visualAids" :key="aid.key" class="rounded-2xl border border-indigo/15 bg-paper p-4">
          <figure v-if="!failedImagePages.includes(aid.visualFocus.pageNumber)" class="overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
            <a :href="pageImageUrl(aid.visualFocus.pageNumber)" target="_blank" rel="noopener" :title="t('lesson.comprehension.openOriginalPage')" class="block">
              <img :src="focusedPageImageUrl(aid.visualFocus)" :alt="t('lesson.comprehension.visualAid.alt', { page: aid.visualFocus.pageNumber, label: aid.label })" class="block h-auto w-full" loading="lazy" @error="imageFailed(aid.visualFocus.pageNumber)">
            </a>
            <figcaption v-if="aid.visualFocus.visibleDescription" class="border-t border-indigo/10 px-3 py-2 text-xs leading-5 text-ink/60">{{ aid.visualFocus.visibleDescription }}</figcaption>
          </figure>
          <p class="mt-3 text-sm font-semibold">{{ aid.label }}</p>
          <p class="mt-1 text-xs font-semibold text-indigo">{{ t('lesson.comprehension.visualAid.meta', { page: aid.visualFocus.pageNumber, chapter: aid.chapterPosition }) }}</p>
          <div class="mt-3 grid grid-cols-2 gap-2">
            <button type="button" class="min-h-10 rounded-xl border px-2 text-xs font-semibold disabled:opacity-40" :class="aid.result === 'HELPFUL' ? 'border-indigo bg-indigo/8 text-indigo' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateVisualAid', aid.key, 'HELPFUL')">{{ t('lesson.comprehension.visualAid.helpful') }}</button>
            <button type="button" class="min-h-10 rounded-xl border px-2 text-xs font-semibold disabled:opacity-40" :class="aid.result === 'NOT_HELPFUL' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateVisualAid', aid.key, 'NOT_HELPFUL')">{{ t('lesson.comprehension.visualAid.notHelpful') }}</button>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>
