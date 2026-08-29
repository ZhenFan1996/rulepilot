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
  | 'completed-after-correction'
  | 'processing'
  | 'local-unavailable'

export type TeachingVisualPageRuleGroupAttempt = 'direct' | 'correction'
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
  budget: { usedModelCalls: number }
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
  const visualSelection = visualCandidateSelectionProgress(activity.operation)
  if (visualSelection) return visualCandidateSelectionActivityText(activity.outcome, visualSelection, locale)
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
    if (activity.operation.startsWith('continueTeachingSectionAfterRejection')) return `The same section Agent is generating a complete replacement for ${target} from the exact validation feedback`
    if (activity.operation.startsWith('settleTeachingSectionNoProgress')) return `The section Agent returned the same invalid candidate again, so ${target} stopped locally; other published chapters remain available`
    if (activity.operation.startsWith('confirmGeneratedClaims')) return `Checking each rule claim in ${target}`
    if (activity.operation.startsWith('reviewGeneratedContent')) return `Reviewing rules and sources for ${target}`
    if (activity.operation.startsWith('reviewObjectiveCoverage')) return `Checking ${target} for missing key steps`
    if (activity.operation.startsWith('validateTeachingSection')) {
      return activity.outcome === 'SUCCEEDED'
        ? `${target} passed citation-ownership, rulebook-version, and structure checks`
        : `${target}'s candidate was rejected; its complete candidate and exact error are being returned to the same Agent`
    }
    if (activity.operation.startsWith('publishTeachingSection')) {
      return activity.outcome === 'SUCCEEDED'
        ? `${target} is now readable`
        : `${target} was not published; other validated chapters remain available`
    }
    if (activity.operation.startsWith('enrichTeachingSectionVisual')) {
      const settledSelection = latestVisualCandidateSelectionBefore(activities, activity.sequence)
      if (activity.outcome !== 'SUCCEEDED' && settledSelection?.reasonCode === 'EXPLICIT_NO_REGION') {
        return `The visual Agent selected NO_VISUAL for ${target}; this is a valid local result and its cited text remains readable`
      }
      return activity.outcome === 'SUCCEEDED'
        ? `${target}'s optional visual and cited text were published together`
        : `${target}'s bounded visual selection is unavailable; only the visual is omitted and its cited text remains readable`
    }
    return 'Organising and reviewing the guide'
  }
  if (activity.operation.startsWith('readTeachingSourcePages')
    || activity.operation.startsWith('readRuleEvidencePages')) return `正在读取${target}引用的规则书页面`
  if (activity.operation.startsWith('searchRuleEvidence')) return `正在为${target}查找规则依据`
  if (activity.operation.startsWith('composeTeachingSection')) {
    return `正在依据规则书编写${target}`
  }
  if (activity.operation.startsWith('continueTeachingSectionAfterRejection')) return `同一章节 Agent 正在依据完整候选和准确校验原因，重新生成${target}`
  if (activity.operation.startsWith('settleTeachingSectionNoProgress')) return `同一章节 Agent 连续返回完全相同的无效候选，${target}已局部停止；其他已发布章节仍然保留`
  if (activity.operation.startsWith('confirmGeneratedClaims')) return `正在逐条复核${target}的规则陈述`
  if (activity.operation.startsWith('reviewGeneratedContent')) return `正在核对${target}的规则和出处`
  if (activity.operation.startsWith('reviewObjectiveCoverage')) return `正在检查${target}有没有漏讲关键步骤`
  if (activity.operation.startsWith('validateTeachingSection')) {
    return activity.outcome === 'SUCCEEDED'
      ? `${target}已完成引用归属、规则书版本与结构校验`
      : `${target}的候选未通过校验；完整候选和准确原因会返回同一 Agent`
  }
  if (activity.operation.startsWith('publishTeachingSection')) {
    return activity.outcome === 'SUCCEEDED'
      ? `${target}已经可以阅读`
      : `${target}本次未发布，其他已校验章节不受影响`
  }
  if (activity.operation.startsWith('enrichTeachingSectionVisual')) {
    const settledSelection = latestVisualCandidateSelectionBefore(activities, activity.sequence)
    if (activity.outcome !== 'SUCCEEDED' && settledSelection?.reasonCode === 'EXPLICIT_NO_REGION') {
      return `视觉 Agent 为${target}选择了 NO_VISUAL；这是有效的局部结果，已校验正文仍可阅读`
    }
    return activity.outcome === 'SUCCEEDED'
      ? `${target}的可选配图已与引用正文同步发布`
      : `${target}经过有限选择后仍没有可用配图；仅省略图片，已校验正文仍可阅读`
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
 * Summarises the latest real page candidate settlement that leads to typed rule groups. Earlier candidates remain
 * available in activity history; a later accepted candidate or local settlement is authoritative for that page.
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
        progress.candidateState,
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

/** Explains only an authoritative whole-run stop; local page, chapter, and visual omissions stay local. */
export function teachingRunStopReasonText(
  run: TeachingRunProgress | null,
  locale: AppLocale = 'zh-CN',
) {
  const state = teachingRunPresentationState(run)
  const code = run?.run.lastErrorCode
  if (state !== 'FAILED' && state !== 'CANCELLED') return ''
  if (state === 'CANCELLED' || code === 'AGENT_CANCELLED') {
    return locale === 'en'
      ? 'The player cancelled the run. Already published chapters remain available.'
      : '本轮由用户取消；已经发布的章节仍然保留。'
  }
  if (code === 'AGENT_TIMEOUT') {
    return locale === 'en'
      ? 'The run reached its wall-time deadline after bounded recovery. Already published chapters remain available.'
      : '本轮在有限恢复后到达总时限；已经发布的章节仍然保留。'
  }
  if (code === 'AGENT_TOKEN_BUDGET') {
    return locale === 'en'
      ? 'The run exhausted its token budget. Already published chapters remain available.'
      : '本轮用完了令牌预算；已经发布的章节仍然保留。'
  }
  if (code === 'AGENT_STEP_BUDGET'
    || code === 'AGENT_TOOL_BUDGET'
    || code === 'AGENT_MODEL_BUDGET') {
    return locale === 'en'
      ? 'This historical run was stopped by a retired call-count limit. Current runs record these counts without treating them as a failure boundary. Already published chapters remain available.'
      : '这是一条历史任务：它曾被现已取消的调用次数上限停止。当前任务只记录这些次数，不再据此判定失败；已经发布的章节仍然保留。'
  }
  if (code === 'TEACHING_COMPLETION_FAILED') {
    return locale === 'en'
      ? 'The saved guide could not be marked complete after bounded persistence recovery. Its last durable chapters remain available.'
      : '讲解在有限持久化恢复后仍无法标记完成；最后一次成功保存的章节仍然保留。'
  }
  if (code === 'APPLICATION_RESTARTED') {
    return locale === 'en'
      ? 'The service restarted and could not safely resume this run. Its last durable chapters remain available.'
      : '服务重启后无法安全续跑本轮任务；最后一次成功保存的章节仍然保留。'
  }
  if (code === 'TEACHING_PREPARATION_QUEUE_TIMEOUT' || code === 'TEACHING_QUEUE_TIMEOUT') {
    return locale === 'en'
      ? 'The guide did not acquire a worker within its bounded queue wait. No model work started; retrying creates a fresh attempt.'
      : '讲解任务在限定排队时间内没有获得 worker；模型工作尚未开始，可以直接重试新任务。'
  }
  if (code === 'TEACHING_PREPARATION_QUEUE_FULL') {
    return locale === 'en'
      ? 'The guide-preparation queue was full. No model work started; retrying creates a fresh attempt.'
      : '讲解准备队列已满；模型工作尚未开始，可以直接重试新任务。'
  }
  if (code === 'TEACHING_PREPARATION_WORKER_ADMISSION_FAILED'
    || code === 'TEACHING_WORKER_ADMISSION_FAILED') {
    return locale === 'en'
      ? 'A worker was assigned but could not durably claim the run. No model work started; retrying creates a fresh attempt.'
      : '任务获得 worker 后未能持久接管；模型工作尚未开始，可以直接重试新任务。'
  }
  if (code === 'TEACHING_CONTINUATION_QUEUE_FULL'
    || code === 'TEACHING_CONTINUATION_QUEUE_TIMEOUT'
    || code === 'TEACHING_CONTINUATION_ADMISSION_FAILED') {
    return locale === 'en'
      ? 'The first cited section remains readable. The remaining chapters did not acquire and durably claim a worker.'
      : '第一段带引用讲解仍可阅读；其余章节没有获得并持久接管 worker。'
  }
  if (code === 'TEACHING_QUEUE_FULL') {
    return locale === 'en'
      ? 'The service could not schedule the next bounded work unit. Any chapter already published remains available.'
      : '服务未能调度下一个有限工作单元；已经发布的章节仍然保留。'
  }
  if (code === 'TEACHING_WORKFLOW_FAILED') {
    return locale === 'en'
      ? 'A teaching service or persistence step still failed after its bounded recovery. Any durable chapter remains available.'
      : '讲解服务或持久化步骤在有限恢复后仍失败；已经持久化的章节仍然保留。'
  }
  return locale === 'en'
    ? 'The run stopped at a whole-run service boundary. Local visual or page omissions alone do not produce this state.'
    : '本轮在整任务服务边界停止；单页或单章配图不可用本身不会产生这个状态。'
}

/** Maps the persisted cancellation error code to its player-facing terminal state. */
export function teachingRunPresentationState(run: TeachingRunProgress | null) {
  if (run?.run.state === 'FAILED' && run.run.lastErrorCode === 'AGENT_CANCELLED') return 'CANCELLED'
  return run?.run.state
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
    ? 'The complete starter guide is readable. Every publishable chapter has completed its text, citation checks, and synchronous optional visual step; final review may still be settling.'
    : '完整基础讲解已经可读；所有可发布章节都已完成正文、引用校验与同步可选配图，最终复核可能仍在收尾。'
  const startedAt = run?.run.createdAt
  if (!startedAt) return locale === 'en'
    ? 'Some chapters are ready; the remaining content is still being processed.'
    : '已有章节完成，正在继续处理后续内容。'
  const elapsedMinutes = Math.max(0.1, (now - new Date(startedAt).getTime()) / 60_000)
  const estimatedMinutes = elapsedMinutes / completed * (total - completed)
  const low = Math.max(1, Math.floor(estimatedMinutes * 0.7))
  const high = Math.max(low + 1, Math.ceil(estimatedMinutes * 1.5))
  return locale === 'en'
    ? `At the current pace, the remaining chapters may take about ${low}–${high} minutes. Each chapter publishes after its text, citation checks, and synchronous optional visual step settle.`
    : `按目前速度，剩余章节大约还需 ${low}–${high} 分钟；每章会在正文、引用校验与同步可选配图完成后立即发布。`
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

function isPlayerFacingTeachingOperation(operation: string) {
  return operation.startsWith('readTeachingSourcePages')
    || operation.startsWith('readRuleEvidencePages')
    || operation.startsWith('searchRuleEvidence')
    || operation.startsWith('composeTeachingSection')
    || operation.startsWith('continueTeachingSectionAfterRejection')
    || operation.startsWith('settleTeachingSectionNoProgress')
    || operation.startsWith('validateTeachingSection')
    || operation.startsWith('publishTeachingSection')
    || operation.startsWith('settleVisualCandidateSelection')
    || operation.startsWith('enrichTeachingSectionVisual')
}

function visualCandidateSelectionProgress(operation: string) {
  const [kind, batchText, attemptText, reasonCode, stateText, ...extra] = operation.split('|')
  if (kind !== 'settleVisualCandidateSelection' || extra.length > 0 || !reasonCode) return null
  const batch = Number(batchText)
  const attempt = Number(attemptText)
  if (!Number.isInteger(batch) || batch < 1 || !Number.isInteger(attempt) || attempt < 1) return null
  const currentStates = ['correction-follows', 'no-progress', 'accepted', 'no-visual', 'local-unavailable'] as const
  const state = currentStates.find(candidate => candidate === stateText)
    ?? (stateText === undefined
      ? outcomeForLegacyVisualSelection(reasonCode, attempt)
      : null)
  return state ? { batch, attempt, reasonCode, state } : null
}

function outcomeForLegacyVisualSelection(reasonCode: string, attempt: number) {
  if (reasonCode === 'NONE') return 'accepted' as const
  if (reasonCode === 'EXPLICIT_NO_REGION') return 'no-visual' as const
  if (reasonCode === 'MALFORMED_JSON' || reasonCode === 'UNSUPPORTED_SCOPE') {
    return attempt === 1 ? 'correction-follows' as const : 'local-unavailable' as const
  }
  return 'local-unavailable' as const
}

function latestVisualCandidateSelectionBefore(
  activities: readonly TeachingActivitySummary<TeachingActivityDisplayOutcome>[],
  sequence: number,
) {
  let latest: { sequence: number; reasonCode: string } | null = null
  for (const activity of activities) {
    if (activity.sequence >= sequence || (latest && activity.sequence <= latest.sequence)) continue
    const progress = visualCandidateSelectionProgress(activity.operation)
    if (progress) latest = { sequence: activity.sequence, reasonCode: progress.reasonCode }
  }
  return latest
}

function visualCandidateSelectionActivityText(
  outcome: TeachingActivityDisplayOutcome,
  progress: NonNullable<ReturnType<typeof visualCandidateSelectionProgress>>,
  locale: AppLocale,
) {
  if (outcome === 'UNKNOWN') {
    return locale === 'en'
      ? 'The latest visual-candidate status is unrecognized; use the chapter and whole-run states'
      : '最新配图候选状态无法识别，请以章节状态和整轮任务状态为准'
  }
  const reason = visualCandidateSelectionReason(progress.reasonCode, locale)
  if (progress.reasonCode === 'NONE' && outcome === 'SUCCEEDED') {
    return locale === 'en'
      ? progress.attempt === 1
        ? 'The visual candidate passed validation'
        : `The visual Agent’s complete replacement passed validation on candidate ${progress.attempt}`
      : progress.attempt === 1
        ? '配图候选已经通过校验'
        : `视觉 Agent 返回的第 ${progress.attempt} 个完整候选已经通过校验`
  }
  if (progress.reasonCode === 'EXPLICIT_NO_REGION' && outcome === 'SUCCEEDED') {
    return locale === 'en'
      ? 'The visual Agent selected NO_VISUAL; this is a valid local result and the cited text remains unchanged'
      : '视觉 Agent 明确选择 NO_VISUAL；这是有效的局部结果，引用正文保持不变'
  }
  if (progress.reasonCode === 'CANDIDATE_PREPARATION_FAILED') {
    return locale === 'en'
      ? 'The candidate crop could not be prepared; only this optional visual is omitted and the cited text remains readable'
      : '候选截图无法生成；仅省略这张可选配图，已校验正文仍可阅读'
  }
  if (progress.state === 'correction-follows') {
    return locale === 'en'
      ? `${reason}; the complete candidate, exact error, JSON contract, and allowed identities returned to the same visual Agent. It may produce another complete candidate while the observation changes and resources remain.`
      : `${reason}；完整候选、准确错误、JSON 合同和允许身份已返回同一个视觉 Agent。只要 observation 仍在变化且资源尚未耗尽，它可以继续返回新的完整候选。`
  }
  if (progress.state === 'no-progress') {
    return locale === 'en'
      ? `${reason}; the visual Agent repeated the same complete candidate and exact error, so this batch stopped for no progress. Only this optional visual is omitted and the cited text remains readable.`
      : `${reason}；视觉 Agent 重复了相同完整候选和准确错误，这个批次因无进展停止。仅省略这张可选配图，已校验正文仍可阅读。`
  }
  return locale === 'en'
    ? `${reason}; this is a local visual unavailability, so only this optional visual is omitted and the cited text remains readable.`
    : `${reason}；这是局部配图不可用，仅省略这张可选配图，已校验正文仍可阅读。`
}

function visualCandidateSelectionReason(code: string, locale: AppLocale) {
  if (locale === 'en') {
    if (code === 'MALFORMED_JSON') return 'The returned selection structure did not pass validation'
    if (code === 'UNSUPPORTED_SCOPE') return 'The selected candidate or evidence binding was outside the offered scope'
    if (code === 'PROVIDER_FAILURE') return 'The visual provider call did not complete'
    return 'The visual candidate did not pass its application boundary'
  }
  if (code === 'MALFORMED_JSON') return '返回的候选选择结构没有通过校验'
  if (code === 'UNSUPPORTED_SCOPE') return '所选候选或依据归属超出了本次提供范围'
  if (code === 'PROVIDER_FAILURE') return '视觉服务调用本次未完成'
  return '配图候选没有通过应用边界'
}

function isPlayerFacingTeachingPreparationOperation(operation: string) {
  return visualPreparationPageProgress(operation) !== null
    || operation.startsWith('organizeTeachingOutline')
}

function outlineValidationProgress(operation: string) {
  const [origin, kind, stageText, stateText, ...extra] = operation.split('|')
  if (extra.length > 0
    || kind !== 'validation'
    || origin !== 'organizeTeachingOutline') return null
  const localShard = stageText?.startsWith('local-')
    ? Number(stageText.slice('local-'.length))
    : null
  const stage = stageText === 'whole' || stageText === 'global'
    ? stageText
    : localShard !== null && Number.isInteger(localShard) && localShard > 0
      ? 'local' as const
      : null
  if (!stage) return null
  if (stateText === 'no-progress') return { stage, state: 'no-progress' as const }
  if (!stateText?.startsWith('candidate-')) return null
  const candidateNumber = Number(stateText.slice('candidate-'.length))
  return Number.isInteger(candidateNumber) && candidateNumber > 0
    ? { stage, state: 'candidate-rejected' as const, candidateNumber }
    : null
}

function outlineValidationActivityText(
  progress: NonNullable<ReturnType<typeof outlineValidationProgress>>,
  locale: AppLocale,
) {
  if (progress.state === 'candidate-rejected') {
    return locale === 'en'
      ? 'The chapter-plan candidate did not pass validation; its complete JSON, exact error, output contract, and allowed identities were returned to the same Agent, which may continue while the observation changes'
      : '章节规划候选没有通过校验；完整 JSON、准确错误、输出契约和允许身份已退回同一个 Agent，只要 observation 仍在变化就会继续修正'
  }
  if (progress.stage === 'local') {
    return locale === 'en'
      ? 'The local rule-group Agent repeated the same rejected observation, so that shard fell back to independent source-owned units; sibling shards and global planning continue'
      : '局部规则分组 Agent 重复了完全相同的无效 observation；该分片已回退为逐条来源单元，兄弟分片和全局规划继续'
  }
  const scope = progress.stage === 'global'
    ? locale === 'en' ? 'global chapter plan' : '全局章节规划'
    : locale === 'en' ? 'whole chapter plan' : '整份章节规划'
  return locale === 'en'
    ? `The ${scope} Agent repeated the same complete candidate and validation observation, so preparation stopped for no progress; no invalid plan is published`
    : `${scope} Agent 重复了完全相同的完整候选和校验 observation；准备因无进展停止，不会发布不合格规划`
}

function teachingPreparationActivityText(
  activity: TeachingActivitySummary<TeachingActivityDisplayOutcome>,
  locale: AppLocale,
) {
  const visualPageProgress = visualPreparationPageProgress(activity.operation)
  if (visualPageProgress?.kind === 'grouping') {
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
  const outlineValidation = outlineValidationProgress(activity.operation)
  if (outlineValidation && activity.outcome === 'REJECTED') {
    return outlineValidationActivityText(outlineValidation, locale)
  }
  if (locale === 'en') {
    if (activity.operation.startsWith('organizeTeachingOutline')) {
      if (activity.outcome === 'FAILED') return 'The chapter plan did not complete this time'
      if (activity.outcome === 'REJECTED') return 'The chapter plan did not pass validation this time'
      return activity.outcome === 'SUCCEEDED'
        ? 'A chapter-plan candidate has returned and is being checked for rulebook support, chapter ownership, and structure'
        : 'Reading across the rulebook to build a whole-game view before planning chapters'
    }
    return 'The chapter plan is ready for writing'
  }
  if (activity.operation.startsWith('organizeTeachingOutline')) {
    if (activity.outcome === 'FAILED') return '讲解章节规划本次未完成'
    if (activity.outcome === 'REJECTED') return '讲解章节规划本次校验未通过'
    return activity.outcome === 'SUCCEEDED'
      ? '章节规划候选已返回，正在校验规则依据、章节归属和结构'
      : '正在通读规则书，先形成整局认识再规划讲解章节'
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
  if (!progress || progress.kind !== 'grouping') {
    return locale === 'en' ? 'The visual page candidate changed state' : '图像规则页候选状态已变化'
  }
  if (progress.legacyKind) {
    const target = locale === 'en'
      ? `visual rulebook page ${progress.page} of ${progress.total}`
      : `图像规则页第 ${progress.page} / ${progress.total} 页`
    if (outcome === 'RUNNING') return locale === 'en'
      ? `Organising the rules on ${target}`
      : `正在整理${target}的规则组`
    if (outcome === 'SUCCEEDED') return locale === 'en'
      ? `Rule grouping for ${target} generated a result; saving it now`
      : `${target}的规则整理已生成结果，正在保存`
    if (outcome === 'FAILED') return locale === 'en'
      ? `Rule grouping for ${target} did not complete this time`
      : `${target}的规则整理本次未完成`
    return locale === 'en'
      ? `Rule grouping for ${target} did not pass validation this time`
      : `${target}的规则整理本次校验未通过`
  }
  const candidate = locale === 'en'
    ? `candidate ${progress.candidateNumber}`
    : `第 ${progress.candidateNumber} 个完整候选`
  const target = locale === 'en'
    ? `visual rulebook page ${progress.page} of ${progress.total}`
    : `图像规则页第 ${progress.page} / ${progress.total} 页`
  if (progress.candidateState === 'accepted') {
    return locale === 'en'
      ? `${candidate} for ${target} passed validation; saving its typed rule groups now`
      : `${target}的${candidate}已通过校验，正在保存结构化规则组`
  }
  if (progress.candidateState === 'correction-follows') {
    return locale === 'en'
      ? `${candidate} for ${target} did not pass ${visualPageCandidateReason(progress.reasonCode, locale)}; its complete JSON, exact error, original contract, and allowed page IDs returned to the same Agent, which may continue while the observation changes and run resources remain`
      : `${target}的${candidate}未通过${visualPageCandidateReason(progress.reasonCode, locale)}；完整结果、具体错误、格式要求和可用页码已交回同一个模型，只要返回内容仍在变化且本轮还有文字预算和有效工作时间，就会继续修正`
  }
  if (progress.candidateState === 'no-progress') {
    return locale === 'en'
      ? `${candidate} for ${target} repeated an earlier complete rejected observation; this page stopped for no progress, while successful sibling pages remain available`
      : `${target}的${candidate}与此前已经拒绝的一份完整结果完全相同；为避免重复消耗，本页因无进展停止，其他成功页面继续保留`
  }
  if (progress.reasonCode === 'PROVIDER_FAILURE' || progress.reasonCode === 'PROVIDER_TIMEOUT') {
    return locale === 'en'
      ? `The provider did not complete ${candidate} for ${target}; this is a transport failure, not a JSON correction, and only this page is unavailable`
      : `模型服务没有完成${target}的${candidate}；这不是格式校验失败，仅本页暂不可用`
  }
  if (progress.reasonCode === 'IMAGE_UNAVAILABLE') {
    return locale === 'en'
      ? `The source image for ${target} could not be read; only this page is unavailable and successful siblings remain`
      : `${target}的原图无法读取；仅本页暂不可用，其他已成功页面继续保留`
  }
  return locale === 'en'
    ? `${candidate} for ${target} became locally unavailable at the workflow boundary; successful sibling pages remain available`
    : `${target}的${candidate}在当前处理阶段局部不可用；其他成功页面继续保留`
}

function visualPreparationPageProgress(operation: string) {
  const parts = operation.split('|')
  const [kind, pageText, totalText, candidateText, candidateState, reasonCode] = parts
  const legacyGrouping = kind === 'inspectTeachingVisualPage'
    || kind === 'inspectTeachingVisualRetry'
    || kind === 'inspectTeachingVisualRepair'
  const grouping = kind === 'settleTeachingVisualPageCandidate'
  const persistence = kind === 'persistTeachingVisualPage'
  if (!grouping && !persistence && !legacyGrouping) return null
  const legacyRepair = kind === 'inspectTeachingVisualRepair'
  if (parts.length !== (grouping ? 6 : legacyRepair ? 4 : 3)) return null
  const page = Number(pageText)
  const total = Number(totalText)
  if (!Number.isInteger(page) || page < 1 || !Number.isInteger(total) || total < page) return null
  if (legacyGrouping) return {
    kind: 'grouping' as const,
    page,
    total,
    attempt: legacyRepair || kind === 'inspectTeachingVisualRetry'
      ? 'correction' as const
      : 'direct' as const,
    candidateNumber: legacyRepair || kind === 'inspectTeachingVisualRetry' ? 2 : 1,
    candidateState: null,
    reasonCode: legacyRepair ? candidateText ?? null : null,
    legacyKind: kind,
  }
  if (persistence) return {
    kind: 'persistence' as const,
    page,
    total,
    attempt: 'direct' as TeachingVisualPageRuleGroupAttempt,
    candidateNumber: null,
    candidateState: null,
    reasonCode: null,
    legacyKind: null,
  }
  if (!candidateText?.startsWith('candidate-') || !reasonCode) return null
  const candidateNumber = Number(candidateText.slice('candidate-'.length))
  const states = ['correction-follows', 'no-progress', 'accepted', 'local-unavailable'] as const
  const settledState = states.find(state => state === candidateState)
  if (!Number.isInteger(candidateNumber) || candidateNumber < 1 || !settledState) return null
  return {
    kind: 'grouping' as const,
    page,
    total,
    attempt: candidateNumber === 1 ? 'direct' as const : 'correction' as const,
    candidateNumber,
    candidateState: settledState,
    reasonCode,
    legacyKind: null,
  }
}

function visualPageRuleGroupState(
  outcome: TeachingActivityDisplayOutcome,
  stage: TeachingVisualPageRuleGroupStage,
  attempt: TeachingVisualPageRuleGroupAttempt,
  runCanProgress: boolean,
  candidateState: 'correction-follows' | 'no-progress' | 'accepted' | 'local-unavailable' | null,
): TeachingVisualPageRuleGroupState {
  if (outcome === 'UNKNOWN') return 'local-unavailable'
  if (stage === 'grouping' && candidateState === 'correction-follows') {
    return runCanProgress ? 'processing' : 'local-unavailable'
  }
  if (outcome === 'RUNNING') return runCanProgress ? 'processing' : 'local-unavailable'
  if (outcome === 'SUCCEEDED') {
    if (stage === 'grouping') {
      return runCanProgress ? 'processing' : 'local-unavailable'
    }
    return attempt === 'direct' ? 'directly-completed' : 'completed-after-correction'
  }
  return 'local-unavailable'
}

function visualPageCandidateReason(code: string | null, locale: AppLocale) {
  if (locale === 'en') {
    if (code === 'MALFORMED_JSON') return 'JSON syntax validation'
    if (code === 'DUPLICATE_RULE_GROUP') return 'duplicate rule-group validation'
    if (code === 'PAGE_BINDING_MISMATCH') return 'page-identity validation'
    return 'the V6 typed contract'
  }
  if (code === 'MALFORMED_JSON') return 'JSON 语法校验'
  if (code === 'DUPLICATE_RULE_GROUP') return '重复规则组校验'
  if (code === 'PAGE_BINDING_MISMATCH') return '页码身份校验'
  return 'V6 typed 合同校验'
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
