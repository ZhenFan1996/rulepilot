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

interface GenerationLesson {
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: unknown[]
}

interface UseLessonGenerationPresentationOptions {
  plan: Readonly<Ref<TeachingProgressPlan | null>>
  lesson: Readonly<Ref<GenerationLesson | null>>
  currentSectionIndex: Readonly<Ref<number>>
  generationRun: Ref<TeachingRunProgress | null>
  generationStatusUnknown: Ref<boolean>
  now: Ref<number>
}

export function useLessonGenerationPresentation(options: UseLessonGenerationPresentationOptions) {
  const { locale, t } = useLocale()
  const generationActive = computed(
    () => options.generationStatusUnknown.value || teachingRunIsActive(options.generationRun.value?.run.state),
  )
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
