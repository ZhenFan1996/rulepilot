import { computed, type Ref } from 'vue'

import { useLocale } from '@/lib/locale'
import { teachingRunIsActive } from '@/lib/liveLesson'
import {
  processedTeachingChapterCount,
  recentTeachingActivitySteps,
  supportedTeachingChapterCount,
  terminalTeachingIssueSteps,
  teachingActivityText,
  teachingElapsedLabel,
  teachingRemainingTimeText,
  type TeachingProgressPlan,
  type TeachingRunProgress,
} from '@/lib/teachingProgress'

interface UseLessonGenerationPresentationOptions {
  plan: Readonly<Ref<TeachingProgressPlan | null>>
  generationRun: Ref<TeachingRunProgress | null>
  generationStatusUnknown: Ref<boolean>
  now: Ref<number>
}

export function useLessonGenerationPresentation(options: UseLessonGenerationPresentationOptions) {
  const { locale, t } = useLocale()
  const generationActive = computed(
    () => options.generationStatusUnknown.value || teachingRunIsActive(options.generationRun.value?.run.state),
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
  const recentGenerationActivities = computed(() => options.plan.value
    ? recentTeachingActivitySteps(options.plan.value, generationActivities.value, locale.value).reverse()
    : [])
  const terminalGenerationIssues = computed(() => options.plan.value
    ? terminalTeachingIssueSteps(options.plan.value, generationActivities.value, locale.value)
    : [])

  return {
    generationActive,
    currentGenerationText,
    generationElapsed,
    processedGenerationChapters,
    supportedGenerationChapters,
    generationProgressWidth,
    generationRemainingTime,
    recentGenerationActivities,
    terminalGenerationIssues,
  }
}
