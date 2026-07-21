export interface TeachingProgressSection {
  position: number
  title: string
  visualEvidenceRecommended: boolean
}

export interface TeachingProgressPlan {
  sections: TeachingProgressSection[]
}

export interface TeachingActivity {
  sequence: number
  type: 'TOOL' | 'MODEL' | 'CRITIC' | 'VALIDATION'
  operation: string
  summary: string
  outcome: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'REJECTED'
  latencyMs: number
  occurredAt: string
}

export interface TeachingRunProgress {
  run: {
    id: string
    state: string
    createdAt: string
    updatedAt: string
    completedAt: string | null
    lastErrorCode: string | null
  }
  budget: { usedModelCalls: number; maxModelCalls: number }
  activities: TeachingActivity[]
}

export function teachingActivityCursor(run: TeachingRunProgress | null) {
  if (!run) return ''
  const sequence = run.activities.at(-1)?.sequence ?? 0
  return `&activityRunId=${encodeURIComponent(run.run.id)}&afterActivitySequence=${sequence}`
}

export function mergeTeachingRunProgress(
  previous: TeachingRunProgress | null,
  incoming: TeachingRunProgress | null,
) {
  if (!incoming || previous?.run.id !== incoming.run.id) return incoming
  return {
    ...incoming,
    activities: Array.from(new Map(
      [...previous.activities, ...incoming.activities]
        .map((activity) => [activity.sequence, activity]),
    ).values()).sort((left, right) => left.sequence - right.sequence),
  }
}

export function teachingActivityText(
  plan: TeachingProgressPlan,
  activities: TeachingActivity[],
  activity: TeachingActivity | undefined,
) {
  if (!activity) return '正在准备规则依据和章节顺序'
  const chapter = chapterForActivity(plan, activities, activity)
  const target = chapter ? `“${chapter.title}”` : '当前内容'
  if (activity.operation.startsWith('searchRuleEvidence')) return `正在为${target}查找规则依据`
  if (activity.operation.startsWith('composeTeachingSection')) {
    return chapter?.visualEvidenceRecommended
      ? `正在阅读规则书图片并编写${target}`
      : `正在编写${target}`
  }
  if (activity.operation.startsWith('correctTeachingSection')) return `正在根据勘误修正${target}`
  if (activity.operation.startsWith('reviseTeachingSection')) return `正在根据核对结果修正${target}`
  if (activity.operation.startsWith('confirmGeneratedClaims')) return `正在逐条复核${target}的规则陈述`
  if (activity.operation.startsWith('reviewGeneratedContent')) return `正在核对${target}的规则和出处`
  if (activity.operation.startsWith('reviewPublishedTeachingSection')) return `基础讲解已可用，正在核对${target}的细节`
  if (activity.operation.startsWith('reviewObjectiveCoverage')) return `正在检查${target}有没有漏讲关键步骤`
  if (activity.operation.startsWith('validateTeachingSection')) {
    return activity.outcome === 'SUCCEEDED' ? `${target}已通过结构检查` : `${target}需要继续修正`
  }
  if (activity.operation.startsWith('publishTeachingSection')) {
    if (activity.summary.includes('CITED_DRAFT_PUBLISHED')) return `${target}的基础内容已经可读`
    return activity.outcome === 'SUCCEEDED' ? `${target}已经完成核对` : `${target}保留基础内容，稍后继续核对`
  }
  return '正在整理并核对讲解'
}

export function processedTeachingChapterCount(run: TeachingRunProgress | null) {
  return publishedPositions(run?.activities ?? []).size
}

export function supportedTeachingChapterCount(run: TeachingRunProgress | null) {
  return publishedPositions((run?.activities ?? []).filter((activity) =>
    activity.outcome === 'SUCCEEDED'
      && (activity.summary.includes('POST_PUBLICATION_REVIEW_ACCEPTED')
        || activity.summary.includes('REUSED_VERIFIED_SECTION')
        || activity.summary.includes('DRAFT_ACCEPTED')),
  )).size
}

export function teachingElapsedLabel(run: TeachingRunProgress | null, now: number) {
  const startedAt = run?.run.createdAt
  const seconds = startedAt ? Math.max(0, Math.floor((now - new Date(startedAt).getTime()) / 1000)) : 0
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

export function teachingRemainingTimeText(
  plan: TeachingProgressPlan,
  run: TeachingRunProgress | null,
  now: number,
) {
  const completed = processedTeachingChapterCount(run)
  const total = plan.sections.length
  if (completed === 0) return '第一节完成后，会按这本规则书的真实速度估算剩余时间。'
  if (completed >= total) return '完整基础讲解已经可读，后台正在核对细节。'
  const startedAt = run?.run.createdAt
  if (!startedAt) return '已有章节完成，正在继续处理后续内容。'
  const elapsedMinutes = Math.max(0.1, (now - new Date(startedAt).getTime()) / 60_000)
  const estimatedMinutes = elapsedMinutes / completed * (total - completed)
  const low = Math.max(1, Math.floor(estimatedMinutes * 0.7))
  const high = Math.max(low + 1, Math.ceil(estimatedMinutes * 1.5))
  return `按目前速度，剩余章节大约还需 ${low}–${high} 分钟；图片章节可能更久。`
}

function operationPosition(operation: string) {
  const value = Number(operation.split('|')[1])
  return Number.isInteger(value) && value > 0 ? value : null
}

function chapterFor(plan: TeachingProgressPlan, operation: string) {
  const position = operationPosition(operation)
  if (!position) return null
  return plan.sections.find((section) => section.position === position) ?? plan.sections[position - 1] ?? null
}

function chapterForActivity(
  plan: TeachingProgressPlan,
  activities: TeachingActivity[],
  activity: TeachingActivity,
) {
  const direct = chapterFor(plan, activity.operation)
  if (direct) return direct
  for (let index = activities.findIndex((candidate) => candidate.sequence === activity.sequence) - 1; index >= 0; index--) {
    const recent = chapterFor(plan, activities[index]!.operation)
    if (recent) return recent
  }
  return null
}

function publishedPositions(activities: TeachingActivity[]) {
  return new Set(activities
    .filter((activity) => activity.operation.startsWith('publishTeachingSection|'))
    .map((activity) => operationPosition(activity.operation))
    .filter((position): position is number => position !== null))
}
