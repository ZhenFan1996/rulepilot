export interface LessonTitleIdentity {
  rulebookTitle: string
  gameCover: { gameName: string } | null
  publicGame?: { name: string } | null
}

export interface PublicLessonIdentity extends LessonTitleIdentity {
  sectionCount: number
  stepCount: number
}

export interface PresentedPublicLesson<T extends PublicLessonIdentity> {
  lesson: T
  title: string
}

export interface PresentedPlan<T extends { gameTitle: string }> {
  plan: T
  title: string
  count: number
  plans: T[]
}

export function playerFacingTitle(rawTitle: string): string {
  const source = rawTitle.trim().replace(/\.pdf$/i, '').replace(/_+/g, ' ').replace(/\s+/g, ' ')
  return source || '未命名规则书'
}

export function publicLessonTitle(lesson: LessonTitleIdentity): string {
  return playerFacingTitle(lesson.publicGame?.name || lesson.gameCover?.gameName || lesson.rulebookTitle)
}

export function deduplicatePublicLessons<T extends PublicLessonIdentity>(lessons: T[]): PresentedPublicLesson<T>[] {
  const positions = new Map<string, number>()
  const result: PresentedPublicLesson<T>[] = []
  for (const lesson of lessons) {
    const title = publicLessonTitle(lesson)
    const key = titleKey(title)
    const entry = { lesson, title }
    const previousPosition = positions.get(key)
    if (previousPosition === undefined) {
      positions.set(key, result.length)
      result.push(entry)
      continue
    }
    const current = result[previousPosition]!
    if (publicLessonScore(entry.lesson) > publicLessonScore(current.lesson)) result[previousPosition] = entry
  }
  return result
}

export function groupPlansForReading<T extends { gameTitle: string; documentVersionId?: string; createdAt?: string }>(
  plans: T[],
  priority: (plan: T) => number = () => 0,
): PresentedPlan<T>[] {
  const positions = new Map<string, number>()
  const result: PresentedPlan<T>[] = []
  for (const plan of plans) {
    const title = playerFacingTitle(plan.gameTitle)
    const key = plan.documentVersionId?.trim() ? `document:${plan.documentVersionId}` : `title:${titleKey(title)}`
    const previousPosition = positions.get(key)
    if (previousPosition === undefined) {
      positions.set(key, result.length)
      result.push({ plan, title, count: 1, plans: [plan] })
      continue
    }
    const entry = result[previousPosition]!
    entry.count += 1
    entry.plans.push(plan)
    if (shouldPreferPlan(plan, entry.plan, priority)) {
      entry.plan = plan
      entry.title = title
    }
  }
  return result
}

function shouldPreferPlan<T extends { createdAt?: string }>(candidate: T, current: T, priority: (plan: T) => number) {
  const priorityDifference = priority(candidate) - priority(current)
  if (priorityDifference !== 0) return priorityDifference > 0
  return timestamp(candidate.createdAt) > timestamp(current.createdAt)
}

function timestamp(value: string | undefined) {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function publicLessonScore(lesson: PublicLessonIdentity): number {
  return (lesson.gameCover ? 1_000_000 : 0) + lesson.stepCount * 100 + lesson.sectionCount
}

function titleKey(title: string) {
  return title.normalize('NFKC').trim().replace(/\s+/g, ' ').toLocaleLowerCase()
}
