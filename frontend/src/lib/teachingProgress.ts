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

export type TeachingActivitySummary = Pick<
  TeachingActivity,
  'sequence' | 'operation' | 'summary' | 'outcome'
>

export interface PlayerFacingTeachingActivity {
  sequence: number
  outcome: TeachingActivity['outcome']
  text: string
}

export interface TeachingRunProgress {
  run: {
    id: string
    subjectId: string
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
  if (!incoming) return previous
  if (previous?.run.id !== incoming.run.id) return incoming
  const previousUpdatedAt = Date.parse(previous.run.updatedAt)
  const incomingUpdatedAt = Date.parse(incoming.run.updatedAt)
  const latest = !Number.isNaN(previousUpdatedAt)
    && !Number.isNaN(incomingUpdatedAt)
    && previousUpdatedAt > incomingUpdatedAt
    ? previous
    : incoming
  return {
    ...latest,
    activities: Array.from(new Map(
      [...previous.activities, ...incoming.activities]
        .map((activity) => [activity.sequence, activity]),
    ).values()).sort((left, right) => left.sequence - right.sequence),
  }
}

export function teachingActivityText(
  plan: TeachingProgressPlan,
  activities: readonly TeachingActivitySummary[],
  activity: TeachingActivitySummary | undefined,
  locale: AppLocale = 'zh-CN',
) {
  if (!activity) return locale === 'en' ? 'Preparing rulebook support and chapter order' : '正在准备规则依据和章节顺序'
  const chapter = chapterForActivity(plan, activities, activity)
  const target = chapter
    ? locale === 'en'
      ? `chapter ${chapter.position} “${chapter.title}”`
      : `第 ${chapter.position} 章“${chapter.title}”`
    : locale === 'en' ? 'this part of the guide' : '当前内容'
  if (locale === 'en') {
    if (activity.operation.startsWith('readTeachingSourcePages')
      || activity.operation.startsWith('readRuleEvidencePages')) return `Reading the cited rulebook pages for ${target}`
    if (activity.operation.startsWith('readProgressiveVisualPages')) return `Starter guide ready; opening the remaining cited pages from the PDF`
    if (activity.operation.startsWith('prefetchProgressiveVisualPages')) return `Starter guide ready; reading the remaining page facts in one background pass`
    if (activity.operation.startsWith('inspectRequiredVisualPage')) return `Reading the exact cited page needed for ${target}`
    if (activity.operation.startsWith('searchRuleEvidence')) return `Finding rulebook support for ${target}`
    if (activity.operation.startsWith('composeTeachingSection')) return `Writing ${target} from the rulebook`
    if (activity.operation.startsWith('correctTeachingSection')
      || activity.operation.startsWith('reviseTeachingSection')
      || activity.operation.startsWith('reviseTextTeachingSection')) return `Revising ${target} after its checks found a local issue`
    if (isTeachingContractRepair(activity.operation)) return `Repairing the chapter structure for ${target}`
    if (activity.operation.startsWith('confirmGeneratedClaims')) return `Checking each rule claim in ${target}`
    if (activity.operation.startsWith('reviewGeneratedContent')) return `Reviewing rules and sources for ${target}`
    if (activity.operation.startsWith('reviewPublishedTeachingSection')) return `Starter guide ready; reviewing details for ${target}`
    if (activity.operation.startsWith('reviewObjectiveCoverage')) return `Checking ${target} for missing key steps`
    if (activity.operation.startsWith('validateTeachingSection')) {
      return activity.outcome === 'SUCCEEDED'
        ? `${target} passed citation, structure, and quantity checks`
        : `${target} needs a local revision before publication`
    }
    if (activity.operation.startsWith('publishTeachingSection')) {
      return activity.outcome === 'SUCCEEDED'
        ? `${target} is now readable`
        : `${target} was not published; other validated chapters remain available`
    }
    return 'Organising and reviewing the guide'
  }
  if (activity.operation.startsWith('readTeachingSourcePages')
    || activity.operation.startsWith('readRuleEvidencePages')) return `正在读取${target}引用的规则书页面`
  if (activity.operation.startsWith('readProgressiveVisualPages')) return '基础讲解已可读，正在打开 PDF 中其余引用页'
  if (activity.operation.startsWith('prefetchProgressiveVisualPages')) return '基础讲解已可读，正在一次性读取后续页面要点'
  if (activity.operation.startsWith('inspectRequiredVisualPage')) return `正在读取${target}所需的引用原页`
  if (activity.operation.startsWith('searchRuleEvidence')) return `正在为${target}查找规则依据`
  if (activity.operation.startsWith('composeTeachingSection')) {
    return `正在依据规则书编写${target}`
  }
  if (activity.operation.startsWith('correctTeachingSection')
    || activity.operation.startsWith('reviseTeachingSection')
    || activity.operation.startsWith('reviseTextTeachingSection')) return `校验发现局部问题，正在修正${target}`
  if (isTeachingContractRepair(activity.operation)) return `正在整理${target}的章节结构`
  if (activity.operation.startsWith('confirmGeneratedClaims')) return `正在逐条复核${target}的规则陈述`
  if (activity.operation.startsWith('reviewGeneratedContent')) return `正在核对${target}的规则和出处`
  if (activity.operation.startsWith('reviewPublishedTeachingSection')) return `基础讲解已可用，正在核对${target}的细节`
  if (activity.operation.startsWith('reviewObjectiveCoverage')) return `正在检查${target}有没有漏讲关键步骤`
  if (activity.operation.startsWith('validateTeachingSection')) {
    return activity.outcome === 'SUCCEEDED'
      ? `${target}已完成引用、结构与数量校验`
      : `${target}需要局部修正后再发布`
  }
  if (activity.operation.startsWith('publishTeachingSection')) {
    return activity.outcome === 'SUCCEEDED'
      ? `${target}已经可以阅读`
      : `${target}本次未发布，其他已校验章节不受影响`
  }
  return '正在整理并核对讲解'
}

/**
 * Returns only real, player-relevant chapter activities. It never invents a timed sequence when the
 * backend has not emitted one, and it never exposes model/tool operation names or audit summaries.
 */
export function recentTeachingActivitySteps(
  plan: TeachingProgressPlan,
  activities: readonly TeachingActivitySummary[],
  locale: AppLocale = 'zh-CN',
  limit = 6,
): PlayerFacingTeachingActivity[] {
  const boundedLimit = Math.max(1, Math.min(12, limit))
  return activities
    .filter(activity => isPlayerFacingTeachingOperation(activity.operation))
    .slice(-boundedLimit)
    .map(activity => ({
      sequence: activity.sequence,
      outcome: activity.outcome,
      text: teachingActivityText(plan, activities, activity, locale),
    }))
}

/** Player-safe preparation events emitted before a chapter plan or teaching run exists. */
export function recentTeachingPreparationActivitySteps(
  activities: readonly TeachingActivitySummary[],
  locale: AppLocale = 'zh-CN',
  limit = 4,
): PlayerFacingTeachingActivity[] {
  const boundedLimit = Math.max(1, Math.min(8, limit))
  return activities
    .filter(activity => isPlayerFacingTeachingPreparationOperation(activity.operation))
    .slice(-boundedLimit)
    .map(activity => ({
      sequence: activity.sequence,
      outcome: activity.outcome,
      text: teachingPreparationActivityText(activity, locale),
    }))
}

export function processedTeachingChapterCount(run: TeachingRunProgress | null) {
  return publishedPositions(run?.activities ?? []).size
}

export function supportedTeachingChapterCount(run: TeachingRunProgress | null) {
  return publishedPositions((run?.activities ?? []).filter((activity) =>
    activity.outcome === 'SUCCEEDED'
      && (activity.summary.includes('POST_PUBLICATION_REVIEW_ACCEPTED')
        || activity.summary.includes('REUSED_VERIFIED_SECTION')
        || activity.summary.includes('CITED_BASE_SECTION_PUBLISHED')
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
  locale: AppLocale = 'zh-CN',
) {
  const completed = processedTeachingChapterCount(run)
  const total = plan.sections.length
  if (completed === 0) return locale === 'en'
    ? 'After the first chapter is ready, we will estimate the remaining time from this rulebook’s actual pace.'
    : '第一节完成后，会按这本规则书的真实速度估算剩余时间。'
  if (completed >= total) return locale === 'en'
    ? 'The complete starter guide is readable; background detail review is still running.'
    : '完整基础讲解已经可读，后台正在核对细节。'
  const startedAt = run?.run.createdAt
  if (!startedAt) return locale === 'en'
    ? 'Some chapters are ready; the remaining content is still being processed.'
    : '已有章节完成，正在继续处理后续内容。'
  const elapsedMinutes = Math.max(0.1, (now - new Date(startedAt).getTime()) / 60_000)
  const estimatedMinutes = elapsedMinutes / completed * (total - completed)
  const low = Math.max(1, Math.floor(estimatedMinutes * 0.7))
  const high = Math.max(low + 1, Math.ceil(estimatedMinutes * 1.5))
  return locale === 'en'
    ? `At the current pace, the remaining chapters may take about ${low}–${high} minutes. The starter guide publishes first; visuals and detail review follow.`
    : `按目前速度，剩余章节大约还需 ${low}–${high} 分钟；基础讲解会优先发布，配图与细节核对随后补充。`
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
  activities: readonly TeachingActivitySummary[],
  activity: TeachingActivitySummary,
) {
  const direct = chapterFor(plan, activity.operation)
  if (direct) return direct
  for (let index = activities.findIndex((candidate) => candidate.sequence === activity.sequence) - 1; index >= 0; index--) {
    const recent = chapterFor(plan, activities[index]!.operation)
    if (recent) return recent
  }
  return null
}

function isTeachingContractRepair(operation: string) {
  return operation.startsWith('repairTeachingSection')
    || operation.startsWith('repairTextTeachingSection')
    || operation.startsWith('repairCorrectedTeachingSection')
}

function isPlayerFacingTeachingOperation(operation: string) {
  return operation.startsWith('readTeachingSourcePages')
    || operation.startsWith('readRuleEvidencePages')
    || operation.startsWith('searchRuleEvidence')
    || operation.startsWith('inspectRequiredVisualPage')
    || operation.startsWith('composeTeachingSection')
    || operation.startsWith('correctTeachingSection')
    || operation.startsWith('reviseTeachingSection')
    || operation.startsWith('reviseTextTeachingSection')
    || isTeachingContractRepair(operation)
    || operation.startsWith('validateTeachingSection')
    || operation.startsWith('publishTeachingSection')
}

function isPlayerFacingTeachingPreparationOperation(operation: string) {
  return operation.startsWith('inspectTeachingVisualBatch')
    || operation.startsWith('inspectTeachingVisualRetry')
    || operation.startsWith('selectProgressiveTeachingStart')
    || operation.startsWith('organizeTeachingOutline')
    || operation.startsWith('refineTeachingOutlineCoverage')
    || operation.startsWith('refineTeachingOutlineOwnership')
    || operation.startsWith('publishProgressiveVisualTeachingPlan')
}

function teachingPreparationActivityText(
  activity: TeachingActivitySummary,
  locale: AppLocale,
) {
  const failed = activity.outcome === 'FAILED' || activity.outcome === 'REJECTED'
  if (locale === 'en') {
    if (activity.operation.startsWith('inspectTeachingVisual')) {
      return failed ? 'Some visual rulebook pages need another pass' : 'Reading and grouping the visual rulebook pages'
    }
    if (activity.operation.startsWith('selectProgressiveTeachingStart')) {
      return failed ? 'Continuing with the complete rulebook plan' : 'Choosing the first rule pages that can be taught clearly'
    }
    if (activity.operation.startsWith('organizeTeachingOutline')) {
      return failed ? 'The chapter plan needs another attempt' : activity.outcome === 'SUCCEEDED'
        ? 'A whole-game view is ready and the rulebook is organized into teachable chapters'
        : 'Reading across the rulebook to build a whole-game view before planning chapters'
    }
    if (activity.operation.startsWith('refineTeachingOutlineCoverage')) {
      return failed ? 'Keeping the usable chapter plan' : 'Checking the chapter plan for omitted rulebook material'
    }
    if (activity.operation.startsWith('refineTeachingOutlineOwnership')) {
      return failed ? 'Keeping the usable chapter boundaries' : 'Giving each rule one clear chapter home'
    }
    return 'The chapter plan is ready for writing'
  }
  if (activity.operation.startsWith('inspectTeachingVisual')) {
    return failed ? '部分图像规则页需要重新读取' : '正在读取并归纳图像规则页'
  }
  if (activity.operation.startsWith('selectProgressiveTeachingStart')) {
    return failed ? '正在改用完整规则书规划讲解' : '正在选择最先能够讲清楚的规则页'
  }
  if (activity.operation.startsWith('organizeTeachingOutline')) {
    return failed ? '讲解章节规划需要重新尝试' : activity.outcome === 'SUCCEEDED'
      ? '已形成整局认识，并把规则书整理成可讲解的章节'
      : '正在通读规则书，先形成整局认识再规划讲解章节'
  }
  if (activity.operation.startsWith('refineTeachingOutlineCoverage')) {
    return failed ? '保留当前可用的章节规划' : '正在检查章节规划有没有漏掉规则内容'
  }
  if (activity.operation.startsWith('refineTeachingOutlineOwnership')) {
    return failed ? '保留当前可用的章节边界' : '正在为每条规则安排清晰的讲解章节'
  }
  return '讲解章节规划已完成，准备编写正文'
}

function publishedPositions(activities: TeachingActivity[]) {
  return new Set(activities
    .filter((activity) => activity.operation.startsWith('publishTeachingSection|'))
    .map((activity) => operationPosition(activity.operation))
    .filter((position): position is number => position !== null))
}
import type { AppLocale } from './locale'
