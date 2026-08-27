import type { AppLocale } from './locale'
import { playerJourneyRunIsTerminal } from './playerJourney'

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
  outcome: TeachingActivityOutcome
  latencyMs: number
  occurredAt: string
}

export type TeachingActivityOutcome =
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
export type TeachingActivityDisplayOutcome = TeachingActivityOutcome | 'UNKNOWN'

export type TeachingActivitySummary<
  TOutcome extends TeachingActivityDisplayOutcome = TeachingActivityOutcome,
> = Pick<
  TeachingActivity,
  'sequence' | 'operation' | 'summary'
> & { outcome: TOutcome }

export interface PlayerFacingTeachingActivity<
  TOutcome extends TeachingActivityDisplayOutcome = TeachingActivityOutcome,
> {
  sequence: number
  outcome: TOutcome
  text: string
}

export type TeachingVisualPageRuleGroupState =
  | 'directly-completed'
  | 'completed-after-recovery'
  | 'processing'
  | 'no-rule-groups'

export type TeachingVisualPageRuleGroupAttempt = 'direct' | 'repair' | 'temporary-retry'
export type TeachingVisualPageRuleGroupStage = 'grouping' | 'persistence'

export interface TeachingVisualPageRuleGroupSummary {
  pageNumber: number
  totalPages: number
  latestSequence: number
  latestStage: TeachingVisualPageRuleGroupStage
  latestAttempt: TeachingVisualPageRuleGroupAttempt
  latestOutcome: TeachingActivityDisplayOutcome
  state: TeachingVisualPageRuleGroupState
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
  if (previous?.run.id !== incoming.run.id) {
    return { ...incoming, activities: normalizeTeachingActivities(incoming.activities) }
  }
  const previousUpdatedAt = Date.parse(previous.run.updatedAt)
  const incomingUpdatedAt = Date.parse(incoming.run.updatedAt)
  const latest = !Number.isNaN(previousUpdatedAt)
    && !Number.isNaN(incomingUpdatedAt)
    && previousUpdatedAt > incomingUpdatedAt
    ? previous
    : incoming
  return {
    ...latest,
    activities: normalizeTeachingActivities([...previous.activities, ...incoming.activities]),
  }
}

function normalizeTeachingActivities(activities: readonly TeachingActivity[]) {
  return Array.from(new Map(
    activities.map((activity) => [activity.sequence, activity]),
  ).values()).sort((left, right) => left.sequence - right.sequence)
}

export function teachingActivityText(
  plan: TeachingProgressPlan,
  activities: readonly TeachingActivitySummary<TeachingActivityDisplayOutcome>[],
  activity: TeachingActivitySummary<TeachingActivityDisplayOutcome> | undefined,
  locale: AppLocale = 'zh-CN',
) {
  if (!activity) return locale === 'en' ? 'Preparing rulebook support and chapter order' : '正在准备规则依据和章节顺序'
  if (activity.outcome === 'UNKNOWN') {
    return locale === 'en'
      ? 'The latest guide activity has an unrecognized status; use the overall task state'
      : '最新讲解活动的状态无法识别，请以整条任务状态为准'
  }
  const chapter = chapterForActivity(plan, activities, activity)
  const target = chapter
    ? locale === 'en'
      ? chapter.title ? `chapter ${chapter.position} “${chapter.title}”` : `chapter ${chapter.position}`
      : chapter.title ? `第 ${chapter.position} 章“${chapter.title}”` : `第 ${chapter.position} 章`
    : locale === 'en' ? 'this part of the guide' : '当前内容'
  if (locale === 'en') {
    if (activity.operation.startsWith('readTeachingSourcePages')
      || activity.operation.startsWith('readRuleEvidencePages')) return `Reading the cited rulebook pages for ${target}`
    if (activity.operation.startsWith('searchRuleEvidence')) return `Finding rulebook support for ${target}`
    if (activity.operation.startsWith('composeTeachingSection')) return `Writing ${target} from the rulebook`
    if (activity.operation.startsWith('correctTeachingSection')
      || activity.operation.startsWith('reviseTeachingSection')
      || activity.operation.startsWith('reviseTextTeachingSection')) return `Revising ${target} after its checks found a local issue`
    if (isTeachingContractRepair(activity.operation)) return `Repairing the chapter structure for ${target}`
    if (activity.operation.startsWith('confirmGeneratedClaims')) return `Checking each rule claim in ${target}`
    if (activity.operation.startsWith('reviewGeneratedContent')) return `Reviewing rules and sources for ${target}`
    if (activity.operation.startsWith('reviewObjectiveCoverage')) return `Checking ${target} for missing key steps`
    if (activity.operation.startsWith('validateTeachingSection')) {
      return activity.outcome === 'SUCCEEDED'
        ? `${target} passed citation-ownership, rulebook-version, and structure checks`
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
  if (activity.operation.startsWith('reviewObjectiveCoverage')) return `正在检查${target}有没有漏讲关键步骤`
  if (activity.operation.startsWith('validateTeachingSection')) {
    return activity.outcome === 'SUCCEEDED'
      ? `${target}已完成引用归属、规则书版本与结构校验`
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
export function recentTeachingActivitySteps<TOutcome extends TeachingActivityDisplayOutcome>(
  plan: TeachingProgressPlan,
  activities: readonly TeachingActivitySummary<TOutcome>[],
  locale: AppLocale = 'zh-CN',
): PlayerFacingTeachingActivity<TOutcome>[] {
  return activities
    .filter(activity => isPlayerFacingTeachingOperation(activity.operation))
    .map(activity => ({
      sequence: activity.sequence,
      outcome: activity.outcome,
      text: teachingActivityText(plan, activities, activity, locale),
    }))
}

/** Player-safe preparation events emitted before a chapter plan or teaching run exists. */
export function recentTeachingPreparationActivitySteps<TOutcome extends TeachingActivityDisplayOutcome>(
  activities: readonly TeachingActivitySummary<TOutcome>[],
  locale: AppLocale = 'zh-CN',
): PlayerFacingTeachingActivity<TOutcome>[] {
  return activities
    .filter(activity => isPlayerFacingTeachingPreparationOperation(activity.operation))
    .map(activity => ({
      sequence: activity.sequence,
      outcome: activity.outcome,
      text: teachingPreparationActivityText(activity, locale),
    }))
}

/**
 * Summarises the latest real page activity that leads to typed rule groups. Earlier attempts remain
 * available in the activity history; they do not override a later repair or temporary retry.
 */
export function summarizeTeachingVisualPageRuleGroups(
  activities: readonly TeachingActivitySummary<TeachingActivityDisplayOutcome>[],
  preparationRunState?: string | null,
): TeachingVisualPageRuleGroupSummary[] {
  const latestByPage = new Map<number, TeachingVisualPageRuleGroupSummary>()
  for (const activity of activities) {
    const progress = visualPreparationPageProgress(activity.operation)
    if (!progress) continue
    const current = latestByPage.get(progress.page)
    if (current && current.latestSequence > activity.sequence) continue
    const attempt = progress.kind === 'persistence'
      ? current?.latestAttempt ?? 'direct'
      : progress.attempt
    latestByPage.set(progress.page, {
      pageNumber: progress.page,
      totalPages: progress.total,
      latestSequence: activity.sequence,
      latestStage: progress.kind,
      latestAttempt: attempt,
      latestOutcome: activity.outcome,
      state: visualPageRuleGroupState(
        activity.outcome,
        progress.kind,
        attempt,
        !playerJourneyRunIsTerminal(preparationRunState),
      ),
    })
  }
  return [...latestByPage.values()].sort((left, right) => left.pageNumber - right.pageNumber)
}

export function processedTeachingChapterCount(run: TeachingRunProgress | null) {
  return latestPublicationActivities(run?.activities ?? []).size
}

export function supportedTeachingChapterCount(run: TeachingRunProgress | null) {
  return publishedPositions([...latestPublicationActivities(run?.activities ?? []).values()].filter((activity) =>
    activity.outcome === 'SUCCEEDED'
      && (activity.summary.includes('POST_PUBLICATION_REVIEW_ACCEPTED')
        || activity.summary.includes('REUSED_VERIFIED_SECTION')
        || activity.summary.includes('CITED_BASE_SECTION_PUBLISHED')
        || activity.summary.includes('DRAFT_ACCEPTED')),
  )).size
}

export function rejectedTeachingChapterCount(run: TeachingRunProgress | null) {
  return [...latestPublicationActivities(run?.activities ?? []).values()]
    .filter(activity => activity.outcome === 'REJECTED').length
}

export function teachingChapterFailureText(
  run: TeachingRunProgress | null,
  locale: AppLocale = 'zh-CN',
) {
  const rejected = [...latestPublicationActivities(run?.activities ?? []).values()]
    .filter(activity => activity.outcome === 'REJECTED')
  if (rejected.length === 0) return ''
  const summaries = rejected.map(activity => activity.summary)
  if (summaries.some(summary => summary.includes('TOOL_BUDGET_EXHAUSTED'))) {
    return locale === 'en'
      ? 'The bounded rule-evidence read budget was exhausted before every chapter was supported.'
      : '规则依据读取次数已用完，仍有章节没有得到依据。'
  }
  if (summaries.some(summary => summary.includes('RETRIEVED_EVIDENCE_INVALID')
    || summary.includes('BASE_EVIDENCE_IDENTITY_INVALID'))) {
    return locale === 'en'
      ? 'The cited page evidence did not pass source or rulebook-version validation.'
      : '引用页依据没有通过来源或规则书版本校验。'
  }
  if (summaries.some(summary => summary.includes('NO_VALID_BASE_EVIDENCE')
    || summary.includes('NO_RETRIEVED_EVIDENCE'))) {
    return locale === 'en'
      ? 'The cited pages did not yield publishable rule evidence for these chapters.'
      : '引用页没有形成可供这些章节发布的规则依据。'
  }
  if (summaries.some(summary => summary.includes('DRAFT_WITHHELD')
    || summary.includes('BASE_DRAFT_WITHHELD'))) {
    return locale === 'en'
      ? 'The chapter draft still failed citation or structure validation after its bounded repair.'
      : '章节草稿在有限修正后仍未通过引用或结构校验。'
  }
  return locale === 'en'
    ? 'Some chapters did not pass the publication boundary.'
    : '有章节没有通过发布校验。'
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
  return plan.sections.find((section) => section.position === position)
    ?? plan.sections[position - 1]
    ?? { position, title: '', visualEvidenceRecommended: false }
}

function chapterForActivity(
  plan: TeachingProgressPlan,
  activities: readonly TeachingActivitySummary<TeachingActivityDisplayOutcome>[],
  activity: TeachingActivitySummary<TeachingActivityDisplayOutcome>,
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
    || operation.startsWith('composeTeachingSection')
    || operation.startsWith('correctTeachingSection')
    || operation.startsWith('reviseTeachingSection')
    || operation.startsWith('reviseTextTeachingSection')
    || isTeachingContractRepair(operation)
    || operation.startsWith('validateTeachingSection')
    || operation.startsWith('publishTeachingSection')
}

function isPlayerFacingTeachingPreparationOperation(operation: string) {
  return visualPreparationPageProgress(operation) !== null
    || operation.startsWith('organizeTeachingOutline')
    || operation.startsWith('refineTeachingOutlineCoverage')
    || operation.startsWith('refineTeachingOutlineOwnership')
}

function teachingPreparationActivityText(
  activity: TeachingActivitySummary<TeachingActivityDisplayOutcome>,
  locale: AppLocale,
) {
  const visualPageProgress = visualPreparationPageProgress(activity.operation)
  if (activity.operation.startsWith('inspectTeachingVisual')) {
    return visualPageActivityText(activity.outcome, visualPageProgress, locale)
  }
  if (activity.operation.startsWith('persistTeachingVisualPage')) {
    return visualPagePersistenceActivityText(activity.outcome, visualPageProgress, locale)
  }
  if (activity.outcome === 'UNKNOWN') {
    return locale === 'en'
      ? 'The latest preparation activity has an unrecognized status; use the overall task state'
      : '最新讲解准备活动的状态无法识别，请以整条任务状态为准'
  }
  const unsuccessful = activity.outcome === 'FAILED' || activity.outcome === 'REJECTED'
  if (locale === 'en') {
    if (activity.operation.startsWith('organizeTeachingOutline')) {
      if (activity.outcome === 'FAILED') return 'The chapter plan did not complete this time'
      if (activity.outcome === 'REJECTED') return 'The chapter plan did not pass validation this time'
      return activity.outcome === 'SUCCEEDED'
        ? 'A whole-game view is ready and the rulebook is organized into teachable chapters'
        : 'Reading across the rulebook to build a whole-game view before planning chapters'
    }
    if (activity.operation.startsWith('refineTeachingOutlineCoverage')) {
      return unsuccessful ? 'Keeping the usable chapter plan' : 'Checking the chapter plan for omitted rulebook material'
    }
    if (activity.operation.startsWith('refineTeachingOutlineOwnership')) {
      return unsuccessful ? 'Keeping the usable chapter boundaries' : 'Giving each rule one clear chapter home'
    }
    return 'The chapter plan is ready for writing'
  }
  if (activity.operation.startsWith('organizeTeachingOutline')) {
    if (activity.outcome === 'FAILED') return '讲解章节规划本次未完成'
    if (activity.outcome === 'REJECTED') return '讲解章节规划本次校验未通过'
    return activity.outcome === 'SUCCEEDED'
      ? '已形成整局认识，并把规则书整理成可讲解的章节'
      : '正在通读规则书，先形成整局认识再规划讲解章节'
  }
  if (activity.operation.startsWith('refineTeachingOutlineCoverage')) {
    return unsuccessful ? '保留当前可用的章节规划' : '正在检查章节规划有没有漏掉规则内容'
  }
  if (activity.operation.startsWith('refineTeachingOutlineOwnership')) {
    return unsuccessful ? '保留当前可用的章节边界' : '正在为每条规则安排清晰的讲解章节'
  }
  return '讲解章节规划已完成，准备编写正文'
}

function visualPageActivityText(
  outcome: TeachingActivityDisplayOutcome,
  progress: ReturnType<typeof visualPreparationPageProgress>,
  locale: AppLocale,
) {
  if (outcome === 'UNKNOWN') {
    const target = progress
      ? locale === 'en'
        ? `visual rulebook page ${progress.page} of ${progress.total}`
        : `图像规则页第 ${progress.page} / ${progress.total} 页`
      : locale === 'en' ? 'the visual rulebook page' : '图像规则页'
    return locale === 'en'
      ? `The latest activity status for ${target} is unrecognized; use the overall task state`
      : `${target}的最新活动状态无法识别，请以整条任务状态为准`
  }
  if (progress?.attempt === 'repair' && progress.repairCode) {
    const reason = visualContractRepairReason(progress.repairCode, locale)
    if (locale === 'en') {
      const target = `visual rulebook page ${progress.page} of ${progress.total}`
      if (outcome === 'RUNNING') return `${reason}; correcting the rule grouping for ${target}`
      if (outcome === 'SUCCEEDED') return `Rule grouping correction generated for ${target}; saving it now`
      if (outcome === 'FAILED') return `Rule grouping for ${target} still did not complete after one correction; only this page stays unavailable`
      return `Rule grouping for ${target} still did not pass validation after one correction; only this page stays unavailable`
    }
    const target = `图像规则页第 ${progress.page} / ${progress.total} 页的规则整理`
    if (outcome === 'RUNNING') return `${reason}，正在修正${target}`
    if (outcome === 'SUCCEEDED') return `${target}修正结果已生成，正在保存`
    if (outcome === 'FAILED') return `${target}经过一次修正后仍未完成；仅本页暂不可用，其他页面继续保留`
    return `${target}经过一次修正后仍未通过校验；仅本页暂不可用，其他页面继续保留`
  }
  if (locale === 'en') {
    const label = 'Rule grouping'
    const target = progress ? ` for visual rulebook page ${progress.page} of ${progress.total}` : ''
    const subject = progress ? `${label}${target}` : 'Visual rulebook page grouping'
    if (progress?.attempt === 'temporary-retry') {
      if (outcome === 'RUNNING') return `A temporary service error occurred; retrying ${label.toLocaleLowerCase()}${target}`
      if (outcome === 'SUCCEEDED') return `${label} retry generated a result after a temporary service error${target}; saving it now`
      if (outcome === 'FAILED') return `${label} retry${target} still did not complete after a temporary service error`
      return `${label} retry${target} did not pass validation after a temporary service error`
    }
    if (outcome === 'SUCCEEDED') return `${subject} generated a result; saving it now`
    if (outcome === 'FAILED') return `${subject} did not complete this time`
    if (outcome === 'REJECTED') return `${subject} did not pass validation this time`
    return progress
      ? `Organising the rules on visual rulebook page ${progress.page} of ${progress.total}`
      : 'Organising the visual rulebook page into rule groups'
  }
  const subject = progress
    ? `图像规则页第 ${progress.page} / ${progress.total} 页的规则整理`
    : '图像规则页的规则整理'
  if (progress?.attempt === 'temporary-retry') {
    if (outcome === 'RUNNING') return `临时服务异常，正在重试${subject}`
    if (outcome === 'SUCCEEDED') return `${subject}在临时服务异常后已生成结果，正在保存`
    if (outcome === 'FAILED') return `${subject}在临时服务异常后重试仍未完成`
    return `${subject}在临时服务异常后重试仍未通过校验`
  }
  if (outcome === 'SUCCEEDED') return `${subject}已生成结果，正在保存`
  if (outcome === 'FAILED') return `${subject}本次未完成`
  if (outcome === 'REJECTED') return `${subject}本次校验未通过`
  return progress
    ? `正在整理图像规则页第 ${progress.page} / ${progress.total} 页的规则组`
    : '正在把图像规则页整理成规则组'
}

function visualPreparationPageProgress(operation: string) {
  const parts = operation.split('|')
  const [kind, pageText, totalText, repairCode] = parts
  const grouping = kind === 'inspectTeachingVisualPage'
    || kind === 'inspectTeachingVisualRetry'
    || kind === 'inspectTeachingVisualRepair'
  const persistence = kind === 'persistTeachingVisualPage'
  if (!grouping && !persistence) return null
  const groupingRepair = kind === 'inspectTeachingVisualRepair'
  if (parts.length !== (groupingRepair ? 4 : 3)) return null
  const page = Number(pageText)
  const total = Number(totalText)
  if (!Number.isInteger(page) || page < 1 || !Number.isInteger(total) || total < page) return null
  const attempt: TeachingVisualPageRuleGroupAttempt = groupingRepair
    ? 'repair'
    : kind === 'inspectTeachingVisualRetry'
      ? 'temporary-retry'
      : 'direct'
  if (groupingRepair && !repairCode) return null
  return {
    kind: grouping ? 'grouping' as const : 'persistence' as const,
    page,
    total,
    attempt,
    repairCode: groupingRepair ? repairCode ?? null : null,
  }
}

function visualPageRuleGroupState(
  outcome: TeachingActivityDisplayOutcome,
  stage: TeachingVisualPageRuleGroupStage,
  attempt: TeachingVisualPageRuleGroupAttempt,
  runCanProgress: boolean,
): TeachingVisualPageRuleGroupState {
  if (outcome === 'UNKNOWN') return 'no-rule-groups'
  if (outcome === 'RUNNING') return runCanProgress ? 'processing' : 'no-rule-groups'
  if (outcome === 'SUCCEEDED') {
    if (stage === 'grouping') {
      return runCanProgress ? 'processing' : 'no-rule-groups'
    }
    return attempt === 'direct' ? 'directly-completed' : 'completed-after-recovery'
  }
  return 'no-rule-groups'
}

function visualPagePersistenceActivityText(
  outcome: TeachingActivityDisplayOutcome,
  progress: ReturnType<typeof visualPreparationPageProgress>,
  locale: AppLocale,
) {
  const target = progress
    ? locale === 'en'
      ? `visual rulebook page ${progress.page} of ${progress.total}`
      : `图像规则页第 ${progress.page} / ${progress.total} 页`
    : locale === 'en' ? 'the visual rulebook page' : '图像规则页'
  if (outcome === 'UNKNOWN') {
    return locale === 'en'
      ? `The saved-rule-group status for ${target} is unrecognized; use the overall task state`
      : `${target}的规则组保存状态无法识别，请以整条任务状态为准`
  }
  if (locale === 'en') {
    if (outcome === 'SUCCEEDED') return `Saved the typed rule groups for ${target}`
    if (outcome === 'RUNNING') return `Saving the typed rule groups for ${target}`
    return `The typed rule groups for ${target} were not saved`
  }
  if (outcome === 'SUCCEEDED') return `${target}的规则组已经保存`
  if (outcome === 'RUNNING') return `正在保存${target}的规则组`
  return `${target}的规则组没有保存成功`
}

function visualContractRepairReason(code: string, locale: AppLocale) {
  if (locale === 'en') {
    if (code === 'MALFORMED_JSON') return 'The returned format did not pass validation'
    if (code === 'SCHEMA_MISMATCH') return 'This page returned fields that need correction; other pages are unaffected'
    if (code === 'DUPLICATE_RULE_GROUP') return 'The returned result contained an exactly duplicated rule group'
    if (code === 'PAGE_BINDING_MISMATCH') return 'The returned result was not safely bound to this page'
    return 'The returned rule-group structure did not pass validation'
  }
  if (code === 'MALFORMED_JSON') return '返回格式没有通过校验'
  if (code === 'SCHEMA_MISMATCH') return '这一页返回的字段需要修正，其他页面不受影响'
  if (code === 'DUPLICATE_RULE_GROUP') return '返回结果含有完全重复的规则组'
  if (code === 'PAGE_BINDING_MISMATCH') return '返回结果无法安全绑定到这一页'
  return '返回的规则组结构没有通过校验'
}

function publishedPositions(activities: TeachingActivity[]) {
  return new Set(activities
    .filter((activity) => activity.operation.startsWith('publishTeachingSection|'))
    .map((activity) => operationPosition(activity.operation))
    .filter((position): position is number => position !== null))
}

function latestPublicationActivities(activities: TeachingActivity[]) {
  const latest = new Map<number, TeachingActivity>()
  for (const activity of activities) {
    if (!activity.operation.startsWith('publishTeachingSection|')) continue
    const position = operationPosition(activity.operation)
    if (position !== null) latest.set(position, activity)
  }
  return latest
}
