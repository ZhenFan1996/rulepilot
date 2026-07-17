export interface LessonProgress {
  currentIndex: number
  completed: number[]
  skipped: number[]
  paused: boolean
}

export function initialLessonProgress(): LessonProgress {
  return { currentIndex: 0, completed: [], skipped: [], paused: false }
}

export function restoreLessonProgress(raw: string | null, sectionCount: number): LessonProgress {
  if (!raw) return initialLessonProgress()
  try {
    const parsed = JSON.parse(raw) as Partial<LessonProgress>
    const validIndexes = (values: unknown) =>
      Array.isArray(values)
        ? values.filter((value): value is number => Number.isInteger(value) && value >= 0 && value < sectionCount)
        : []
    return {
      currentIndex:
        Number.isInteger(parsed.currentIndex) && parsed.currentIndex! >= 0 && parsed.currentIndex! < sectionCount
          ? parsed.currentIndex!
          : 0,
      completed: validIndexes(parsed.completed),
      skipped: validIndexes(parsed.skipped),
      paused: parsed.paused === true,
    }
  } catch {
    return initialLessonProgress()
  }
}

export function finishSection(
  progress: LessonProgress,
  sectionCount: number,
  outcome: 'completed' | 'skipped',
): LessonProgress {
  const completed = progress.completed.filter((index) => index !== progress.currentIndex)
  const skipped = progress.skipped.filter((index) => index !== progress.currentIndex)
  if (outcome === 'completed') completed.push(progress.currentIndex)
  else skipped.push(progress.currentIndex)
  return {
    currentIndex: Math.min(progress.currentIndex + 1, Math.max(sectionCount - 1, 0)),
    completed,
    skipped,
    paused: false,
  }
}
