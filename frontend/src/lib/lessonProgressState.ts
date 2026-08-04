export interface LessonProgressSummary {
  id: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: Array<{ evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE' }>
}

export function mergeLessonProgress(
  previous: LessonProgressSummary | null,
  incoming: LessonProgressSummary | null,
): LessonProgressSummary | null {
  if (!incoming) return previous
  if (!previous || previous.id !== incoming.id) return incoming
  const status = lessonStatusRank(incoming.status) >= lessonStatusRank(previous.status)
    ? incoming.status
    : previous.status
  const sections = incoming.sections.length >= previous.sections.length ? incoming.sections : previous.sections
  return { ...incoming, status, sections }
}

export function hasReadableLesson(lesson: LessonProgressSummary | null | undefined) {
  return Boolean(lesson?.sections.some((section) =>
    section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT'))
}

function lessonStatusRank(status: LessonProgressSummary['status']) {
  return { INCOMPLETE: 1, DRAFT_READY: 2, COMPLETE: 3 }[status]
}
