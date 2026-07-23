<script setup lang="ts">
import { ref } from 'vue'

import type { LessonComprehensionReport, VisualFocus } from '@/composables/lessonSupportingContent'

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

function imageFailed(pageNumber: number) {
  if (!failedImagePages.value.includes(pageNumber)) failedImagePages.value.push(pageNumber)
}
</script>

<template>
  <section class="mt-8 rounded-3xl border border-copper/20 bg-copper/[0.06] p-5 sm:p-6" aria-labelledby="comprehension-title">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="text-xs font-semibold text-copper">上桌前，自己试一遍</p>
        <h3 id="comprehension-title" class="mt-1 font-display text-2xl font-semibold">这些关键动作，你现在会做了吗？</h3>
        <p class="mt-2 text-sm leading-6 text-ink/55">不用背原文，试着认组件、照图摆放，再完成一轮和计分。不会的可以直接回到相关章节。</p>
      </div>
      <div v-if="comprehension" class="text-right text-sm font-semibold text-copper">
        <p>已掌握 {{ comprehension.canDoCount }} / {{ comprehension.readyTaskCount }}</p>
        <p v-if="comprehension.visualAidRatedCount" class="mt-1 text-xs text-ink/50">焦点图有帮助 {{ comprehension.visualAidHelpfulCount }} / {{ comprehension.visualAidRatedCount }}（{{ comprehension.visualAidHelpfulPercent }}%）</p>
      </div>
    </div>

    <p v-if="errorMessage" class="mt-4 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{{ errorMessage }}</p>
    <ol v-if="comprehension" class="mt-5 grid gap-3 sm:grid-cols-2">
      <li v-for="task in comprehension.tasks" :key="task.type" class="rounded-2xl border border-ink/10 bg-paper p-4">
        <div class="flex items-start justify-between gap-3">
          <h4 class="font-semibold leading-6">{{ task.label }}</h4>
          <span class="shrink-0 text-xs font-semibold" :class="task.result === 'CAN_DO' ? 'text-emerald-700' : task.result === 'NEEDS_HELP' ? 'text-amber-800' : 'text-ink/40'">
            {{ task.result === 'CAN_DO' ? '会了' : task.result === 'NEEDS_HELP' ? '待补一遍' : '未检查' }}
          </span>
        </div>
        <figure v-if="task.visualFocus && !failedImagePages.includes(task.visualFocus.pageNumber)" class="mt-3 overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
          <a :href="pageImageUrl(task.visualFocus.pageNumber)" target="_blank" rel="noopener" title="打开规则书大图" class="relative block">
            <img :src="pageImageUrl(task.visualFocus.pageNumber)" :alt="`规则书第 ${task.visualFocus.pageNumber} 页，框选 ${task.visualFocus.label}`" class="block h-auto w-full" loading="lazy" @error="imageFailed(task.visualFocus.pageNumber)">
            <span class="pointer-events-none absolute rounded-md border-2 border-copper bg-copper/10 shadow-[0_0_0_2px_rgba(255,255,255,0.8)]" :style="visualFocusStyle(task.visualFocus)" aria-hidden="true" />
          </a>
          <figcaption class="flex items-center justify-between gap-2 px-3 py-2 text-xs font-semibold text-indigo">
            <span>先看框内：{{ task.visualFocus.label }}</span><span>第 {{ task.visualFocus.pageNumber }} 页</span>
          </figcaption>
        </figure>
        <p v-else-if="task.visualFocus" class="mt-3 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">图片暂时没有载入，仍可回到相关章节或 <a :href="pageImageUrl(task.visualFocus.pageNumber)" target="_blank" rel="noopener" class="font-semibold underline">打开原图</a>。</p>
        <p class="mt-2 text-sm leading-6 text-ink/65">{{ task.prompt }}</p>
        <p v-if="task.sourcePages.length" class="mt-2 text-xs font-semibold text-indigo">可核对规则书第 {{ task.sourcePages.join('、') }} 页</p>
        <div v-if="task.readiness === 'READY' && task.type !== 'VERIFY_VISUAL_AID'" class="mt-4 grid grid-cols-2 gap-2">
          <button type="button" class="min-h-11 rounded-xl border px-3 text-sm font-semibold disabled:opacity-40" :class="task.result === 'CAN_DO' ? 'border-emerald-700 bg-emerald-50 text-emerald-900' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateTask', task.type, 'CAN_DO')">我能做到</button>
          <button type="button" class="min-h-11 rounded-xl border px-3 text-sm font-semibold disabled:opacity-40" :class="task.result === 'NEEDS_HELP' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateTask', task.type, 'NEEDS_HELP')">还不清楚</button>
        </div>
        <button v-if="task.result === 'NEEDS_HELP' && task.chapterPositions.length" type="button" class="mt-3 min-h-10 w-full rounded-xl bg-copper px-3 text-sm font-semibold text-white" @click="emit('revisitChapter', (task.chapterPositions.at(0) ?? 1) - 1)">回到第 {{ task.chapterPositions.at(0) }} 节再讲一遍</button>
      </li>
    </ol>
    <div v-if="comprehension?.visualAids.length" class="mt-6 border-t border-ink/10 pt-5">
      <h4 class="font-display text-xl font-semibold">逐张看看这些规则书裁剪图</h4>
      <p class="mt-1 text-sm leading-6 text-ink/55">每张图单独评分；你的反馈只用于判断这张图是否真的帮上忙。</p>
      <ol class="mt-4 grid gap-3 sm:grid-cols-2">
        <li v-for="aid in comprehension.visualAids" :key="aid.key" class="rounded-2xl border border-indigo/15 bg-paper p-4">
          <figure v-if="!failedImagePages.includes(aid.visualFocus.pageNumber)" class="overflow-hidden rounded-xl border border-indigo/15 bg-canvas">
            <a :href="pageImageUrl(aid.visualFocus.pageNumber)" target="_blank" rel="noopener" title="打开规则书原页" class="block">
              <img :src="focusedPageImageUrl(aid.visualFocus)" :alt="`规则书第 ${aid.visualFocus.pageNumber} 页的 ${aid.label}`" class="block h-auto w-full" loading="lazy" @error="imageFailed(aid.visualFocus.pageNumber)">
            </a>
          </figure>
          <p class="mt-3 text-sm font-semibold">{{ aid.label }}</p>
          <p class="mt-1 text-xs font-semibold text-indigo">第 {{ aid.visualFocus.pageNumber }} 页 · 第 {{ aid.chapterPosition }} 节</p>
          <div class="mt-3 grid grid-cols-2 gap-2">
            <button type="button" class="min-h-10 rounded-xl border px-2 text-xs font-semibold disabled:opacity-40" :class="aid.result === 'HELPFUL' ? 'border-indigo bg-indigo/8 text-indigo' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateVisualAid', aid.key, 'HELPFUL')">有帮助</button>
            <button type="button" class="min-h-10 rounded-xl border px-2 text-xs font-semibold disabled:opacity-40" :class="aid.result === 'NOT_HELPFUL' ? 'border-amber-700 bg-amber-50 text-amber-950' : 'border-ink/15'" :disabled="saving !== null || !online" @click="emit('rateVisualAid', aid.key, 'NOT_HELPFUL')">没帮上忙</button>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>
