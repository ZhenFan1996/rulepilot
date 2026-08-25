import { computed, type Ref } from 'vue'

import { useLocale } from '@/lib/locale'
import { teachingRunIsActive } from '@/lib/liveLesson'
import {
  processedTeachingChapterCount,
  supportedTeachingChapterCount,
  teachingActivityText,
  teachingElapsedLabel,
  teachingRemainingTimeText,
  type TeachingProgressPlan,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'
import { visualEnrichmentResult } from '@/lib/visualEnrichment'

interface GenerationLesson {
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: unknown[]
}

interface UseLessonGenerationPresentationOptions {
  plan: Readonly<Ref<TeachingProgressPlan | null>>
  lesson: Readonly<Ref<GenerationLesson | null>>
  currentSectionIndex: Readonly<Ref<number>>
  generationRun: Ref<TeachingRunProgress | null>
  visualEnrichmentRun: Ref<TeachingRunProgress | null>
  generationStatusUnknown: Ref<boolean>
  now: Ref<number>
}

export function useLessonGenerationPresentation(options: UseLessonGenerationPresentationOptions) {
  const { locale, t } = useLocale()
  const generationActive = computed(
    () => options.generationStatusUnknown.value || teachingRunIsActive(options.generationRun.value?.run.state),
  )
  const visualEnrichmentActive = computed(() => teachingRunIsActive(options.visualEnrichmentRun.value?.run.state))
  const visualEnrichmentResultState = computed(() => visualEnrichmentResult(
    options.visualEnrichmentRun.value,
    visualEnrichmentActive.value,
  ))
  const visualEnrichmentFailed = computed(() => ['FAILED', 'PARTIAL'].includes(visualEnrichmentResultState.value.outcome))
  const visualEnrichmentSummary = computed(() => {
    if (visualEnrichmentActive.value) return t('lesson.generation.visual.active')
    const result = visualEnrichmentResultState.value
    if (result.outcome === 'ABSENT') return ''
    if (result.outcome === 'FAILED') return t('lesson.generation.visual.failed')
    if (result.outcome === 'PARTIAL') {
      return t('lesson.generation.visual.partial', { count: result.addedSectionCount })
    }
    if (result.outcome === 'EMPTY') return t('lesson.generation.visual.none')
    return t(
      result.addedSectionCount === 1
        ? 'lesson.generation.visual.added.one'
        : 'lesson.generation.visual.added.many',
      { count: result.addedSectionCount },
    )
  })
  const draftReady = computed(() => options.lesson.value?.status === 'DRAFT_READY')
  const lessonStillGrowing = computed(() => generationActive.value && !draftReady.value)
  const readingCurrentLastChapter = computed(
    () => Boolean(options.lesson.value?.sections.length)
      && options.currentSectionIndex.value === options.lesson.value!.sections.length - 1,
  )
  const generationActivities = computed(() => options.generationRun.value?.activities ?? [])
  const currentGenerationActivity = computed(() => generationActivities.value.at(-1))
  const currentGenerationText = computed(() => options.plan.value
    ? teachingActivityText(options.plan.value, generationActivities.value, currentGenerationActivity.value, locale.value)
    : t('lesson.generation.preparing'))
  const generationElapsed = computed(() => teachingElapsedLabel(options.generationRun.value, options.now.value))
  const processedGenerationChapters = computed(() => processedTeachingChapterCount(options.generationRun.value))
  const supportedGenerationChapters = computed(() => supportedTeachingChapterCount(options.generationRun.value))
  const generationProgressWidth = computed(() => `${Math.round(
    processedGenerationChapters.value / Math.max(1, options.plan.value?.sections.length ?? 1) * 100,
  )}%`)
  const generationRemainingTime = computed(() => options.plan.value
    ? teachingRemainingTimeText(options.plan.value, options.generationRun.value, options.now.value, locale.value)
    : '')
  const recentGenerationActivities = computed(() => [...generationActivities.value]
    .reverse()
    .map((activity) => ({
      sequence: activity.sequence,
      outcome: activity.outcome,
      text: options.plan.value
        ? teachingActivityText(options.plan.value, generationActivities.value, activity, locale.value)
        : t('lesson.generation.preparing'),
    })))

  return {
    generationActive,
    visualEnrichmentActive,
    visualEnrichmentFailed,
    visualEnrichmentSummary,
    draftReady,
    lessonStillGrowing,
    readingCurrentLastChapter,
    currentGenerationText,
    generationElapsed,
    processedGenerationChapters,
    supportedGenerationChapters,
    generationProgressWidth,
    generationRemainingTime,
    recentGenerationActivities,
  }
}
