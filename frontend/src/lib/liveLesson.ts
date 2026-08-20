interface LessonSnapshot {
  id: string
  sections: unknown[]
}

const terminalTeachingStates = new Set(['COMPLETED', 'INSUFFICIENT_EVIDENCE', 'DEGRADED', 'FAILED'])

export function teachingRunIsActive(state: string | null | undefined) {
  return Boolean(state && !terminalTeachingStates.has(state))
}

export function teachingLessonNeedsFinalSnapshot(
  runState: string | null | undefined,
  lessonStatus: string | null | undefined,
) {
  return runState === 'COMPLETED'
    && lessonStatus !== 'COMPLETE'
    && lessonStatus !== 'INCOMPLETE'
}

export function acceptProgressiveLesson<T extends LessonSnapshot>(current: T | null, incoming: T): T {
  if (current?.id === incoming.id && incoming.sections.length < current.sections.length) return current
  return incoming
}
