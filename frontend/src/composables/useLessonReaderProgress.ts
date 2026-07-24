import { computed, ref, type Ref } from 'vue'

import {
  finishSection,
  initialLessonProgress,
  restoreLessonProgress,
  type LessonProgress,
} from '@/lib/lessonProgress'

interface ProgressLesson {
  id: string
  sections: unknown[]
}

interface UseLessonReaderProgressOptions {
  lesson: Readonly<Ref<ProgressLesson | null>>
  onSectionSelected: (index: number) => void
}

export function useLessonReaderProgress(options: UseLessonReaderProgressOptions) {
  const progress = ref<LessonProgress>(initialLessonProgress())
  const waitingForNextChapter = ref(false)
  const completedCount = computed(() => new Set([...progress.value.completed, ...progress.value.skipped]).size)
  const progressPercent = computed(() => {
    const count = options.lesson.value?.sections.length ?? 0
    return count ? Math.round((completedCount.value / count) * 100) : 0
  })

  function sectionCount() {
    return options.lesson.value?.sections.length ?? 0
  }

  function persist() {
    const lessonId = options.lesson.value?.id
    if (lessonId) localStorage.setItem(`rulepilot:lesson-progress:${lessonId}`, JSON.stringify(progress.value))
  }

  function reset() {
    progress.value = initialLessonProgress()
    waitingForNextChapter.value = false
  }

  function restore() {
    const lesson = options.lesson.value
    if (!lesson) return
    progress.value = {
      ...restoreLessonProgress(
        localStorage.getItem(`rulepilot:lesson-progress:${lesson.id}`),
        lesson.sections.length,
      ),
      paused: false,
    }
    waitingForNextChapter.value = false
  }

  function selectSection(index: number) {
    if (index < 0 || index >= sectionCount()) return
    waitingForNextChapter.value = false
    progress.value = { ...progress.value, currentIndex: index }
    options.onSectionSelected(index)
    persist()
  }

  function synchronizeChapter(index: number) {
    if (index < 0 || index >= sectionCount()) return
    progress.value = { ...progress.value, currentIndex: index }
    persist()
  }

  function previousSection() {
    if (progress.value.currentIndex === 0) return
    selectSection(progress.value.currentIndex - 1)
  }

  function finish(outcome: 'completed' | 'skipped', waitForNextChapter: boolean) {
    const count = sectionCount()
    if (!count || progress.value.paused) return
    waitingForNextChapter.value = waitForNextChapter
    progress.value = finishSection(progress.value, count, outcome)
    persist()
  }

  return {
    progress,
    waitingForNextChapter,
    completedCount,
    progressPercent,
    reset,
    restore,
    selectSection,
    synchronizeChapter,
    previousSection,
    finish,
  }
}
