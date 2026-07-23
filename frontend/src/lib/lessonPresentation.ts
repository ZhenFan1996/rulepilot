export interface LessonTitleIdentity {
  rulebookTitle: string
  gameCover: { gameName: string } | null
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
}

export function playerFacingTitle(rawTitle: string): string {
  const source = rawTitle.trim().replace(/\.pdf$/i, '').replace(/[_]+/g, ' ').replace(/\s+/g, ' ')
  const withoutLearningGuide = source
    .replace(/\s*[:：—–-]?\s*learning\s+to\s+play(?:\s+(?:rules?|corpus\s+replay))?$/i, '')
    .replace(/\s*[:：—–-]?\s*(?:base\s+game\s+)?rules?(?:\s+(?:corpus\s+)?replay)?$/i, '')
    .replace(/\s*[:：—–-]?\s*(?:corpus\s+)?replay$/i, '')
    .replace(/\s*[:：—–-]?\s*rulebook$/i, '')
    .replace(/\s*[:：—–-]?\s*verification(?:\s+[\da-f-]{8,})?$/i, '')
    .replace(/[\s:：—–-]+$/, '')
    .trim()
  return withoutLearningGuide || source || '未命名规则书'
}

export function publicLessonTitle(lesson: LessonTitleIdentity): string {
  return playerFacingTitle(lesson.gameCover?.gameName || lesson.rulebookTitle)
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

export function groupPlansForReading<T extends { gameTitle: string }>(plans: T[]): PresentedPlan<T>[] {
  const positions = new Map<string, number>()
  const result: PresentedPlan<T>[] = []
  for (const plan of plans) {
    const title = playerFacingTitle(plan.gameTitle)
    const key = titleKey(title)
    const previousPosition = positions.get(key)
    if (previousPosition === undefined) {
      positions.set(key, result.length)
      result.push({ plan, title, count: 1 })
      continue
    }
    result[previousPosition]!.count += 1
  }
  return result
}

function publicLessonScore(lesson: PublicLessonIdentity): number {
  return (lesson.gameCover ? 1_000_000 : 0) + lesson.stepCount * 100 + lesson.sectionCount
}

function titleKey(title: string) {
  return title
    .toLocaleLowerCase()
    .replace(/[\s:：,，.。'’"“”!！?？()（）[\]{}—–_-]+/g, '')
}
