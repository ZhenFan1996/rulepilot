import { writeFile } from 'node:fs/promises'

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'

const enabled = process.env.RULEPILOT_PRODUCTION_RECOMMENDATION_JOURNEY === 'true'
const TARGET_BGG_ID = positiveIntegerEnvironment('RULEPILOT_RECOMMENDATION_TARGET_BGG_ID', 230802)
const TARGET_NAME = aliasPattern(process.env.RULEPILOT_RECOMMENDATION_TARGET_NAMES ?? '花砖物语|Azul')
const RECOMMENDATION_PROMPT = process.env.RULEPILOT_RECOMMENDATION_PROMPT
  ?? '我们今晚第一次玩花砖物语，规则书还没看。能帮我把这款找出来，然后带我们从规则书、讲解一路到现场答疑吗？'
const PRESERVED_DRAFT = '下次我还想给完全没玩过桌游的家人找一款更轻松的。'
const RULE_QUESTION = process.env.RULEPILOT_RECOMMENDATION_RULE_QUESTION
  ?? '我从一个工厂展示板拿走同色砖以后，剩下的砖要放到哪里？请用日常的话简短回答，并引用规则书页码。'
const REQUIRE_FRESH_IMPORT = process.env.RULEPILOT_RECOMMENDATION_REQUIRE_FRESH_IMPORT === 'true'

function positiveIntegerEnvironment(name: string, fallback: number) {
  const raw = process.env[name]
  if (raw === undefined || raw.trim() === '') return fallback
  if (!/^[1-9]\d*$/.test(raw)) throw new Error(`${name} must be a positive integer`)
  const value = Number(raw)
  if (!Number.isSafeInteger(value)) throw new Error(`${name} must be a safe positive integer`)
  return value
}

function aliasPattern(raw: string) {
  const aliases = raw.split('|').map(alias => alias.trim()).filter(Boolean)
  if (aliases.length === 0) throw new Error('At least one target game name is required')
  return new RegExp(aliases.map(alias => alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'i')
}

interface RulebookCandidate {
  title: string
  url: string
  sourceDomain: string
  language: string
  acquisitionMode: 'DIRECT_PDF' | 'IMAGE_GALLERY' | 'SOURCE_PAGE'
  capability: 'DIRECT_DOCUMENT' | 'CONTIGUOUS_RULE_PAGES' | 'DOCUMENT_LISTING' | 'GAME_INFO_ONLY' | 'UNVERIFIED_PAGE'
}

interface CandidateResponse {
  configured: boolean
  identity: { editionId: string; gameName: string; editionName: string; language: string }
  candidates: RulebookCandidate[]
}

interface ImportJob {
  id: string
  title?: string
  rulebookTitle?: string
  editionId: string | null
  sourceDomain?: string
  stage: 'QUEUED' | 'CONNECTING' | 'DOWNLOADING' | 'COMPRESSING' | 'VERIFYING_FILE' | 'SAVING' | 'COMPLETED' | 'FAILED'
  downloadedBytes: number
  totalBytes: number | null
  documentVersionId: string | null
  duplicate: boolean
  errorCode: string | null
  teachingHandoffState: 'NOT_REQUESTED' | 'WAITING_FOR_DOCUMENT' | 'LAUNCHING' | 'LAUNCHED' | 'FAILED'
  teachingPreparationRunId: string | null
  teachingErrorCode: string | null
  teachingAutomaticRecoveryCount: number
  downloadCompletedAt: string | null
  importCompletedAt: string | null
  teachingHandoffUpdatedAt: string | null
  createdAt: string
  reused: boolean
}

interface BoundGameResponse {
  game: { id: string; name: string }
  edition: { id: string; name: string; language: string }
  bggId: number
}

interface CatalogGameResponse {
  game: { id: string; name: string }
  editions: Array<{ id: string; name: string }>
}

interface TeachingPlanResponse {
  id: string
  documentVersionId: string
  gameTitle: string
}

interface AnswerResponse {
  answer: {
    status: string
    citations: Array<{ pageFrom: number; pageTo: number }>
  }
}

interface DocumentProgressResponse {
  stage: string
  complete: boolean
}

interface RuleDocumentResponse {
  document: { gameEditionId: string | null; title: string }
  latestVersion: { id: string }
}

interface RunDetailsResponse {
  run: {
    state: string
    createdAt: string
    lastErrorCode: string | null
  }
  activities: Array<{
    operation: string
    outcome: string
    summary: string
    occurredAt: string
  }>
}

interface LessonMilestoneResponse {
  id: string
  status: 'COMPLETE' | 'DRAFT_READY' | 'INCOMPLETE'
  sections: Array<{
    position?: number
    title?: string
    evidenceStatus: 'SUPPORTED' | 'CITED_DRAFT' | 'INSUFFICIENT_EVIDENCE'
  }>
}

interface ImportMilestoneObservation {
  job: ImportJob
  pdfDownloadCompleteMs: number | null
  documentReadyMs: number | null
  teachingHandoffLaunchedMs: number | null
}

interface CsrfToken {
  headerName: string
  token: string
}

interface ModelConfigurationResponse {
  providers: Array<{
    id: string
    configured: boolean
    visionCapable: boolean
  }>
  assignments: {
    teaching: string
    visual: string
    answer: string
    critic: string
    recommendation: string
  }
}

interface FirstCitedLessonObservation {
  planId: string
  teachingPreparationStartedMs: number
  firstCitedLessonMs: number
  preparationRunCreatedAt: string | null
  firstCitedPublicationActivityAt: string | null
}

interface CompletedLessonObservation {
  teachingRunState: string
  lessonStatus: LessonMilestoneResponse['status']
  sectionCount: number
  citedDraftSectionCount: number
  insufficientSectionCount: number
}

interface TeachingWaitProgress {
  phase: 'FIRST_CITED_SECTION' | 'COMPLETE_LESSON'
  observedAt: string
  preparationState: string | null
  preparationOperation: string | null
  preparationErrorCode: string | null
  planId: string | null
  teachingState: string | null
  teachingOperation: string | null
  teachingErrorCode: string | null
  lessonStatus: LessonMilestoneResponse['status'] | null
  sectionCount: number
  publishedSectionCount: number
  citedDraftSectionCount: number
  insufficientSectionCount: number
  teachingHandoffState: ImportJob['teachingHandoffState'] | null
  teachingHandoffErrorCode: string | null
  teachingAutomaticRecoveryCount: number | null
}

interface ProductionJourneyReport {
  generatedAt: string
  completed: boolean
  stage: string
  targetBggId: number
  modelAssignments: ModelConfigurationResponse['assignments'] | null
  visualModelVisionCapable: boolean | null
  routeStayedOnDiscover: boolean
  journeyBackdropVisible: boolean
  journeySurfaceOpaque: boolean
  lessonBackdropVisible: boolean
  lessonSurfaceOpaque: boolean
  confirmedMilestonesAtSourceReview: number
  confirmedMilestonesFinal: number
  boundGameInCatalog: boolean
  boundBggId: number | null
  boundGameName: string | null
  boundEditionId: string | null
  candidateEditionMatchesSelection: boolean
  importEditionMatchesSelection: boolean
  documentEditionMatchesSelection: boolean
  myGuidesEntryVisibleBeforeLesson: boolean
  myGuidesPlanListed: boolean
  planGameTitleMatchesSelection: boolean
  globalStatusVisibleAfterClosing: boolean
  globalStatusReopened: boolean
  recommendationMs: number | null
  detailsDialogOpenedAndClosed: boolean
  discoveryMs: number | null
  sourceDomain: string | null
  sourceUrl: string | null
  sourceMode: string | null
  importRequestCount: number
  importReused: boolean | null
  teachingEvidenceRefreshRequested: boolean
  importDuplicate: boolean | null
  downloadedBytes: number | null
  importMs: number | null
  downloadCompletedAt: string | null
  importCompletedAt: string | null
  teachingHandoffUpdatedAt: string | null
  persistedDownloadToImportCompleteMs: number | null
  persistedImportCompleteToHandoffMs: number | null
  persistedDownloadToHandoffMs: number | null
  preparationRunCreatedAt: string | null
  firstCitedPublicationActivityAt: string | null
  persistedHandoffToPreparationRunMs: number | null
  persistedPreparationToFirstCitedActivityMs: number | null
  persistedDownloadToFirstCitedActivityMs: number | null
  pdfDownloadCompleteMs: number | null
  documentReadyMs: number | null
  teachingHandoffLaunchedMs: number | null
  teachingPreparationStartedMs: number | null
  firstCitedLessonMs: number | null
  pdfDownloadToTeachingStartMs: number | null
  pdfDownloadToFirstCitedLessonMs: number | null
  documentProgressStage: string | null
  documentProgressComplete: boolean | null
  teachingHandoffState: string | null
  teachingHandoffErrorCode: string | null
  teachingAutomaticRecoveryCount: number | null
  teachingPreparationState: string | null
  teachingPreparationErrorCode: string | null
  teachingGenerationState: string | null
  teachingProgressObservedAt: string | null
  teachingObservedPlanId: string | null
  teachingLatestPreparationOperation: string | null
  teachingLatestGenerationOperation: string | null
  teachingPublishedSectionCount: number
  lessonStatus: string | null
  citedDraftSectionCount: number
  insufficientSectionCount: number
  lessonCompletionMs: number | null
  rulebookReadableMs: number | null
  renderedRulebookPage: boolean
  lessonReadableMs: number | null
  lessonDockText: string | null
  lessonSectionCount: number
  citedLessonStep: boolean
  answerMs: number | null
  answerStatus: string | null
  answerCitationCount: number
  citedAnswer: boolean
  recommendationRestored: boolean
  answerRestored: boolean
  pageErrorCount: number
}

function elapsed(startedAt: number) {
  return Math.round(performance.now() - startedAt)
}

function ssePayload<T>(body: string, eventName: string): T {
  for (const block of body.replaceAll('\r\n', '\n').split('\n\n')) {
    let event = 'message'
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (event === eventName && data.length) return JSON.parse(data.join('\n')) as T
  }
  throw new Error(`SSE stream ended without ${eventName}`)
}

function persistedDuration(from: string | null, to: string | null) {
  if (!from || !to) return null
  const startedAt = Date.parse(from)
  const reachedAt = Date.parse(to)
  if (!Number.isFinite(startedAt) || !Number.isFinite(reachedAt) || reachedAt < startedAt) return null
  return Math.round(reachedAt - startedAt)
}

function configuredProductionRole(
  configuration: ModelConfigurationResponse,
  role: 'teaching' | 'visual' | 'answer' | 'recommendation',
) {
  const assignment = configuration.assignments[role]
  expect(assignment, `Production ${role} role has no model assignment`).not.toBe('fake')
  const provider = configuration.providers.find(candidate => candidate.id === assignment)
  expect(provider?.configured, `Production ${role} provider '${assignment}' is not configured`).toBe(true)
  return provider!
}

async function login(page: Page, username: string, password: string) {
  await page.addInitScript(() => localStorage.setItem('rulepilot:locale', 'zh-CN'))
  await page.goto('/login')
  const homeUrl = new URL('/', page.url()).toString()
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill(password)
  await page.locator('form button[type="submit"]').click()
  await expect(page).toHaveURL(homeUrl)
}

function observedDownloadComplete(job: ImportJob) {
  if (job.downloadedBytes <= 0) return false
  if (job.totalBytes !== null) return job.downloadedBytes >= job.totalBytes
  return job.stage !== 'QUEUED' && job.stage !== 'CONNECTING' && job.stage !== 'DOWNLOADING'
}

async function waitForCompletedImport(
  request: APIRequestContext,
  jobId: string,
  importStartedAt: number,
): Promise<ImportMilestoneObservation> {
  const deadline = Date.now() + 8 * 60_000
  let latest: ImportJob | null = null
  let pdfDownloadCompleteMs: number | null = null
  let documentReadyMs: number | null = null
  let teachingHandoffLaunchedMs: number | null = null
  while (Date.now() < deadline) {
    const response = await request.get(`/api/v1/documents/official-imports/${encodeURIComponent(jobId)}`)
    expect(response.ok(), `Import progress returned HTTP ${response.status()}`).toBe(true)
    latest = await response.json() as ImportJob
    if (pdfDownloadCompleteMs === null && observedDownloadComplete(latest)) {
      pdfDownloadCompleteMs = elapsed(importStartedAt)
    }
    if (latest.documentVersionId && documentReadyMs === null) {
      const progressResponse = await request.get(
        `/api/v1/document-versions/${encodeURIComponent(latest.documentVersionId)}/progress/snapshot`,
      )
      expect([200, 404], `Document progress returned HTTP ${progressResponse.status()}`)
        .toContain(progressResponse.status())
      if (progressResponse.ok()) {
        const progress = await progressResponse.json() as DocumentProgressResponse
        if (progress.stage === 'READY' && progress.complete) documentReadyMs = elapsed(importStartedAt)
      }
    }
    if (teachingHandoffLaunchedMs === null && latest.teachingHandoffState === 'LAUNCHED') {
      teachingHandoffLaunchedMs = elapsed(importStartedAt)
    }
    if (latest.stage === 'COMPLETED' && latest.teachingHandoffState === 'LAUNCHED') {
      return { job: latest, pdfDownloadCompleteMs, documentReadyMs, teachingHandoffLaunchedMs }
    }
    if (latest.teachingHandoffState === 'FAILED') {
      throw new Error(`Teaching handoff failed with ${latest.teachingErrorCode ?? 'UNKNOWN_TEACHING_HANDOFF_ERROR'}`)
    }
    if (latest.stage === 'FAILED') {
      throw new Error(`Official import failed with ${latest.errorCode ?? 'UNKNOWN_IMPORT_ERROR'}`)
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`Official import did not complete; latest stage was ${latest?.stage ?? 'UNKNOWN'}`)
}

async function retryFailedReusedTeaching(
  request: APIRequestContext,
  job: ImportJob,
): Promise<ImportJob> {
  if (!job.reused || !job.teachingPreparationRunId) return job

  const runResponse = await request.get(
    `/api/v1/assistant-runs/${encodeURIComponent(job.teachingPreparationRunId)}`,
  )
  expect([200, 404], `Existing teaching preparation returned HTTP ${runResponse.status()}`)
    .toContain(runResponse.status())
  if (!runResponse.ok()) return job

  const existing = await runResponse.json() as RunDetailsResponse
  if (existing.run.state !== 'FAILED') return job

  const csrfResponse = await request.get('/api/auth/csrf')
  expect(csrfResponse.ok(), `CSRF token returned HTTP ${csrfResponse.status()}`).toBe(true)
  const csrf = await csrfResponse.json() as CsrfToken
  const retryResponse = await request.post(
    `/api/v1/documents/official-imports/${encodeURIComponent(job.id)}/teaching-retry`,
    {
      headers: { [csrf.headerName]: csrf.token },
      data: { expectedPreparationRunId: job.teachingPreparationRunId },
    },
  )
  expect(retryResponse.status(), 'The failed reused teaching preparation was not accepted for retry')
    .toBe(202)
  const retried = await retryResponse.json() as ImportJob
  expect(retried.id, 'Teaching retry changed the persisted import identity').toBe(job.id)
  return retried
}

async function waitForFirstCitedLesson(
  request: APIRequestContext,
  importJobId: string,
  versionId: string,
  initialPreparationRunId: string,
  importStartedAt: number,
  requireCurrentPublicationActivity: boolean,
  currentHandoffAt: string | null,
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
): Promise<FirstCitedLessonObservation> {
  const deadline = Date.now() + 20 * 60_000
  let plan: TeachingPlanResponse | null = null
  let teachingPreparationStartedMs: number | null = null
  let preparationRunCreatedAt: string | null = null
  let firstCitedPublicationActivityAt: string | null = null
  let preparationDetails: RunDetailsResponse | null = null
  let teachingDetails: RunDetailsResponse | null = null
  let latestLesson: LessonMilestoneResponse | null = null
  let preparationRunId = initialPreparationRunId
  let terminalObservedAt: number | null = null
  let terminalGenerationKey = ''
  const progress = teachingProgressReporter(onProgress)
  while (Date.now() < deadline) {
    const importResponse = await request.get(
      `/api/v1/documents/official-imports/${encodeURIComponent(importJobId)}`,
    )
    expect(importResponse.ok(), `Import reconciliation returned HTTP ${importResponse.status()}`).toBe(true)
    const currentJob = await importResponse.json() as ImportJob
    if (currentJob.teachingPreparationRunId) preparationRunId = currentJob.teachingPreparationRunId
    const runResponse = await request.get(`/api/v1/assistant-runs/${encodeURIComponent(preparationRunId)}`)
    expect([200, 404], `Teaching preparation returned HTTP ${runResponse.status()}`)
      .toContain(runResponse.status())
    if (runResponse.ok()) {
      const details = await runResponse.json() as RunDetailsResponse
      preparationDetails = details
      preparationRunCreatedAt = details.run.createdAt
      if (details.run.state !== 'RECEIVED' && teachingPreparationStartedMs === null) {
        teachingPreparationStartedMs = elapsed(importStartedAt)
      }
    }
    if (!plan) {
      const planResponse = await request.get(
        `/api/v1/document-versions/${encodeURIComponent(versionId)}/teaching-plans/latest`,
      )
      expect([200, 404], `Teaching plan returned HTTP ${planResponse.status()}`)
        .toContain(planResponse.status())
      if (planResponse.ok()) plan = await planResponse.json() as TeachingPlanResponse
    }
    if (plan) {
      const teachingRunResponse = await request.get(
        `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(plan.id)}`,
      )
      expect([200, 404], `Teaching run returned HTTP ${teachingRunResponse.status()}`)
        .toContain(teachingRunResponse.status())
      if (teachingRunResponse.ok()) {
        teachingDetails = await teachingRunResponse.json() as RunDetailsResponse
        const handoffTimestamp = currentHandoffAt ? Date.parse(currentHandoffAt) : Number.NEGATIVE_INFINITY
        const firstPublication = teachingDetails.activities
          .filter(activity =>
            activity.operation.startsWith('publishTeachingSection|')
            && activity.outcome === 'SUCCEEDED'
            && (activity.summary.includes('CITED_BASE_SECTION_PUBLISHED')
              || activity.summary.includes('CITED_DRAFT_PUBLISHED')
              || activity.summary.includes('REUSED_VERIFIED_SECTION'))
            && (!requireCurrentPublicationActivity
              || Date.parse(activity.occurredAt) >= handoffTimestamp))
          .sort((left, right) => Date.parse(left.occurredAt) - Date.parse(right.occurredAt))[0]
        if (firstPublication) firstCitedPublicationActivityAt = firstPublication.occurredAt
      }
      const lessonResponse = await request.get(
        `/api/v1/teaching-plans/${encodeURIComponent(plan.id)}/illustrated-lessons/latest`,
      )
      expect([200, 404], `Illustrated lesson returned HTTP ${lessonResponse.status()}`)
        .toContain(lessonResponse.status())
      if (lessonResponse.ok()) {
        const lesson = await lessonResponse.json() as LessonMilestoneResponse
        latestLesson = lesson
        if (lesson.sections.some(section =>
          section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT')
          && (!requireCurrentPublicationActivity || firstCitedPublicationActivityAt !== null)) {
          await progress.emit(teachingWaitProgress(
            'FIRST_CITED_SECTION', preparationDetails, plan.id, teachingDetails, latestLesson, currentJob,
          ))
          return {
            planId: plan.id,
            teachingPreparationStartedMs: teachingPreparationStartedMs ?? elapsed(importStartedAt),
            firstCitedLessonMs: elapsed(importStartedAt),
            preparationRunCreatedAt,
            firstCitedPublicationActivityAt,
          }
        }
      }
    }
    const terminalTeaching = teachingDetails
      && ['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(teachingDetails.run.state)
    const nextTerminalGenerationKey = terminalTeaching
      ? `${teachingDetails?.run.createdAt}:${teachingDetails?.run.state}`
      : ''
    if (nextTerminalGenerationKey !== terminalGenerationKey) {
      terminalGenerationKey = nextTerminalGenerationKey
      terminalObservedAt = terminalTeaching ? Date.now() : null
    }
    await progress.emit(teachingWaitProgress(
      'FIRST_CITED_SECTION', preparationDetails, plan?.id ?? null, teachingDetails, latestLesson, currentJob,
    ))
    if (currentJob.teachingHandoffState === 'FAILED') {
      throw new Error(
        `Teaching recovery ended with ${currentJob.teachingErrorCode ?? 'UNKNOWN_TEACHING_HANDOFF_ERROR'}; ${teachingFailureDiagnostic(currentJob, teachingDetails, latestLesson)}`,
      )
    }
    if (terminalObservedAt !== null && Date.now() - terminalObservedAt >= 15_000) {
      throw new Error(
        `Teaching reached a terminal state but its persisted recovery did not advance within 15 seconds; ${teachingFailureDiagnostic(currentJob, teachingDetails, latestLesson)}`,
      )
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(`The first source-cited lesson section did not become readable; latest=${JSON.stringify(progress.latest())}`)
}

function unfinishedSectionSummary(lesson: LessonMilestoneResponse) {
  const unfinished = lesson.sections.filter(section => section.evidenceStatus !== 'SUPPORTED')
  if (unfinished.length === 0) return 'no unfinished section details were returned'
  return unfinished.map((section, index) => {
    const identity = section.title?.trim()
      || (section.position ? `section ${section.position}` : `section ${index + 1}`)
    return `${identity}=${section.evidenceStatus}`
  }).join(', ')
}

async function waitForCompletedLesson(
  request: APIRequestContext,
  planId: string,
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
): Promise<CompletedLessonObservation> {
  const deadline = Date.now() + 20 * 60_000
  let latestRunState = 'NOT_STARTED'
  let latestRunError: string | null = null
  let latestRunDetails: RunDetailsResponse | null = null
  let latestLesson: LessonMilestoneResponse | null = null
  const progress = teachingProgressReporter(onProgress)
  while (Date.now() < deadline) {
    const runResponse = await request.get(
      `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(planId)}`,
    )
    expect([200, 404], `Teaching run returned HTTP ${runResponse.status()}`)
      .toContain(runResponse.status())
    if (runResponse.ok()) {
      const details = await runResponse.json() as RunDetailsResponse
      latestRunDetails = details
      latestRunState = details.run.state
      latestRunError = details.run.lastErrorCode
      if (['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'].includes(latestRunState)) {
        const latestFailure = details.activities
          .filter(activity => activity.outcome !== 'SUCCEEDED')
          .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))[0]
        const diagnostic = latestFailure
          ? `${latestFailure.operation}: ${latestFailure.summary}`
          : latestRunError ?? 'no failure detail was recorded'
        throw new Error(`Teaching generation ended as ${latestRunState}: ${diagnostic}`)
      }
    }

    const lessonResponse = await request.get(
      `/api/v1/teaching-plans/${encodeURIComponent(planId)}/illustrated-lessons/latest`,
    )
    expect([200, 404], `Illustrated lesson returned HTTP ${lessonResponse.status()}`)
      .toContain(lessonResponse.status())
    if (lessonResponse.ok()) latestLesson = await lessonResponse.json() as LessonMilestoneResponse

    await progress.emit(teachingWaitProgress(
      'COMPLETE_LESSON', null, planId, latestRunDetails, latestLesson,
    ))

    if (latestLesson && latestRunState === 'COMPLETED') {
      const citedDraftSectionCount = latestLesson.sections
        .filter(section => section.evidenceStatus === 'CITED_DRAFT').length
      const insufficientSectionCount = latestLesson.sections
        .filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
      const everySectionSupported = latestLesson.sections.length > 0
        && latestLesson.sections.every(section => section.evidenceStatus === 'SUPPORTED')
      if (latestLesson.status === 'COMPLETE' && everySectionSupported) {
        return {
          teachingRunState: latestRunState,
          lessonStatus: latestLesson.status,
          sectionCount: latestLesson.sections.length,
          citedDraftSectionCount,
          insufficientSectionCount,
        }
      }
      throw new Error(
        `Teaching run completed without a complete lesson: lesson=${latestLesson.status}; ${unfinishedSectionSummary(latestLesson)}`,
      )
    }
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  throw new Error(
    `Teaching lesson did not complete; run=${latestRunState}; lesson=${latestLesson?.status ?? 'NOT_PUBLISHED'}; error=${latestRunError ?? 'NONE'}; ${latestLesson ? unfinishedSectionSummary(latestLesson) : 'no lesson was published'}; latest=${JSON.stringify(progress.latest())}`,
  )
}

function teachingWaitProgress(
  phase: TeachingWaitProgress['phase'],
  preparation: RunDetailsResponse | null,
  planId: string | null,
  teaching: RunDetailsResponse | null,
  lesson: LessonMilestoneResponse | null,
  handoff: ImportJob | null = null,
): TeachingWaitProgress {
  const sections = lesson?.sections ?? []
  return {
    phase,
    observedAt: new Date().toISOString(),
    preparationState: preparation?.run.state ?? null,
    preparationOperation: preparation?.activities.at(-1)?.operation ?? null,
    preparationErrorCode: preparation?.run.lastErrorCode ?? null,
    planId,
    teachingState: teaching?.run.state ?? null,
    teachingOperation: teaching?.activities.at(-1)?.operation ?? null,
    teachingErrorCode: teaching?.run.lastErrorCode ?? null,
    lessonStatus: lesson?.status ?? null,
    sectionCount: sections.length,
    publishedSectionCount: sections.filter(section =>
      section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT').length,
    citedDraftSectionCount: sections.filter(section => section.evidenceStatus === 'CITED_DRAFT').length,
    insufficientSectionCount: sections.filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length,
    teachingHandoffState: handoff?.teachingHandoffState ?? null,
    teachingHandoffErrorCode: handoff?.teachingErrorCode ?? null,
    teachingAutomaticRecoveryCount: handoff?.teachingAutomaticRecoveryCount ?? null,
  }
}

function teachingFailureDiagnostic(
  job: ImportJob,
  teaching: RunDetailsResponse | null,
  lesson: LessonMilestoneResponse | null,
) {
  const sections = lesson?.sections ?? []
  const published = sections.filter(section =>
    section.evidenceStatus === 'SUPPORTED' || section.evidenceStatus === 'CITED_DRAFT').length
  const insufficient = sections.filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
  const latestFailure = teaching?.activities
    .filter(activity => activity.outcome !== 'SUCCEEDED')
    .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))[0]
  const latest = latestFailure
    ? `${latestFailure.operation}: ${latestFailure.summary}`
    : teaching?.run.lastErrorCode ?? 'no rejected activity was recorded'
  return `attempt ${Math.min(2, job.teachingAutomaticRecoveryCount + 1)} of 2; chapters published=${published}, insufficient=${insufficient}, total=${sections.length}; latest=${latest}`
}

function teachingProgressReporter(
  onProgress?: (progress: TeachingWaitProgress) => Promise<void> | void,
) {
  let fingerprint = ''
  let loggedAt = 0
  let last: TeachingWaitProgress | null = null
  return {
    async emit(progress: TeachingWaitProgress) {
      const nextFingerprint = JSON.stringify({ ...progress, observedAt: undefined })
      const now = Date.now()
      if (nextFingerprint === fingerprint && now - loggedAt < 30_000) return
      fingerprint = nextFingerprint
      loggedAt = now
      last = progress
      console.log(`[production-teaching-progress] ${JSON.stringify(progress)}`)
      await onProgress?.(progress)
    },
    latest() {
      return last
    },
  }
}

async function retainReport(path: string, report: ProductionJourneyReport) {
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })
}

async function opaqueSurface(locator: Locator) {
  await expect(locator).toBeVisible()
  const appearance = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    const match = style.backgroundColor.match(/^rgba?\(([^)]+)\)$/)
    const channels = match?.[1]?.split(',').map(value => Number(value.trim())) ?? []
    const alpha = channels.length >= 4 ? channels[3]! : 1
    const bounds = element.getBoundingClientRect()
    return {
      alpha,
      hasOpaqueColor: style.backgroundColor !== 'transparent' && match !== null,
      opacity: Number(style.opacity),
      width: bounds.width,
      height: bounds.height,
    }
  })
  return appearance.hasOpaqueColor
    && appearance.alpha === 1
    && appearance.opacity === 1
    && appearance.width > 0
    && appearance.height > 0
}

function requiresPersistedPublicationActivity(
  report: Pick<ProductionJourneyReport, 'importReused' | 'teachingEvidenceRefreshRequested'>,
) {
  return !report.importReused || report.teachingEvidenceRefreshRequested
}

test('requires current publication telemetry for fresh imports and stale-evidence refreshes', () => {
  expect(requiresPersistedPublicationActivity({
    importReused: false,
    teachingEvidenceRefreshRequested: false,
  })).toBe(true)
  expect(requiresPersistedPublicationActivity({
    importReused: true,
    teachingEvidenceRefreshRequested: true,
  })).toBe(true)
  expect(requiresPersistedPublicationActivity({
    importReused: true,
    teachingEvidenceRefreshRequested: false,
  })).toBe(false)
})

test('production target aliases are literal and cannot widen the title match', () => {
  const pattern = aliasPattern('奋进号：深海|Endeavor: Deep Sea (2024)')

  expect('奋进号：深海').toMatch(pattern)
  expect('Endeavor: Deep Sea (2024)').toMatch(pattern)
  expect('Endeavor: Deep Sea 2024').not.toMatch(pattern)
})

test('recommendation becomes one readable, taught, and answerable production journey', async ({ page }) => {
  test.skip(!enabled, 'Runs only through the credentialed production recommendation workflow')
  test.setTimeout(40 * 60_000)
  const username = process.env.RULEPILOT_RECOMMENDATION_USER
  const password = process.env.RULEPILOT_RECOMMENDATION_PASSWORD
  const reportFile = process.env.RULEPILOT_RECOMMENDATION_REPORT
  if (!username || !password || !reportFile) {
    throw new Error('Production recommendation credentials and report path are required')
  }

  const pageErrors: Error[] = []
  let guidesPage: Page | null = null
  let importRequestCount = 0
  let observedDocumentVersionId: string | null = null
  let observedPreparationRunId: string | null = null
  let observedTeachingPlanId: string | null = null
  let observedImportRequest: {
    editionId?: string
    discoveredForEditionId?: string
    officialSourceUrl?: string
    identityConfirmed?: boolean
  } | null = null
  page.on('pageerror', error => pageErrors.push(error))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/documents/official-imports' && request.method() === 'POST') {
      importRequestCount += 1
      observedImportRequest = request.postDataJSON() as typeof observedImportRequest
    }
  })

  const report: ProductionJourneyReport = {
    generatedAt: new Date().toISOString(), completed: false, stage: 'login', targetBggId: TARGET_BGG_ID,
    modelAssignments: null, visualModelVisionCapable: null,
    routeStayedOnDiscover: false, journeyBackdropVisible: false, journeySurfaceOpaque: false,
    lessonBackdropVisible: false, lessonSurfaceOpaque: false,
    confirmedMilestonesAtSourceReview: 0, confirmedMilestonesFinal: 0,
    boundGameInCatalog: false, boundBggId: null, boundGameName: null, boundEditionId: null,
    candidateEditionMatchesSelection: false, importEditionMatchesSelection: false,
    documentEditionMatchesSelection: false, myGuidesEntryVisibleBeforeLesson: false, myGuidesPlanListed: false,
    planGameTitleMatchesSelection: false, globalStatusVisibleAfterClosing: false, globalStatusReopened: false,
    recommendationMs: null, detailsDialogOpenedAndClosed: false,
    discoveryMs: null, sourceDomain: null, sourceUrl: null, sourceMode: null, importRequestCount: 0,
    importReused: null, teachingEvidenceRefreshRequested: false,
    importDuplicate: null, downloadedBytes: null, importMs: null,
    downloadCompletedAt: null, importCompletedAt: null, teachingHandoffUpdatedAt: null,
    persistedDownloadToImportCompleteMs: null, persistedImportCompleteToHandoffMs: null,
    persistedDownloadToHandoffMs: null,
    preparationRunCreatedAt: null, firstCitedPublicationActivityAt: null,
    persistedHandoffToPreparationRunMs: null, persistedPreparationToFirstCitedActivityMs: null,
    persistedDownloadToFirstCitedActivityMs: null,
    pdfDownloadCompleteMs: null, documentReadyMs: null, teachingHandoffLaunchedMs: null,
    teachingPreparationStartedMs: null, firstCitedLessonMs: null,
    pdfDownloadToTeachingStartMs: null, pdfDownloadToFirstCitedLessonMs: null,
    documentProgressStage: null, documentProgressComplete: null, teachingHandoffState: null,
    teachingHandoffErrorCode: null,
    teachingAutomaticRecoveryCount: null,
    teachingPreparationState: null, teachingPreparationErrorCode: null,
    teachingGenerationState: null, teachingProgressObservedAt: null, teachingObservedPlanId: null,
    teachingLatestPreparationOperation: null, teachingLatestGenerationOperation: null,
    teachingPublishedSectionCount: 0,
    lessonStatus: null, citedDraftSectionCount: 0,
    insufficientSectionCount: 0, lessonCompletionMs: null,
    rulebookReadableMs: null, renderedRulebookPage: false, lessonReadableMs: null,
    lessonDockText: null,
    lessonSectionCount: 0, citedLessonStep: false, answerMs: null, answerStatus: null,
    answerCitationCount: 0, citedAnswer: false,
    recommendationRestored: false, answerRestored: false, pageErrorCount: 0,
  }

  const recordTeachingProgress = async (progress: TeachingWaitProgress) => {
    report.stage = progress.phase === 'FIRST_CITED_SECTION'
      ? 'teaching-first-cited-section'
      : 'teaching-complete-lesson'
    report.teachingProgressObservedAt = progress.observedAt
    report.teachingObservedPlanId = progress.planId
    if (progress.preparationState) report.teachingPreparationState = progress.preparationState
    if (progress.preparationErrorCode) report.teachingPreparationErrorCode = progress.preparationErrorCode
    if (progress.preparationOperation) {
      report.teachingLatestPreparationOperation = progress.preparationOperation
    }
    if (progress.teachingState) report.teachingGenerationState = progress.teachingState
    if (progress.teachingHandoffState) report.teachingHandoffState = progress.teachingHandoffState
    if (progress.teachingHandoffErrorCode) report.teachingHandoffErrorCode = progress.teachingHandoffErrorCode
    if (progress.teachingAutomaticRecoveryCount !== null) {
      report.teachingAutomaticRecoveryCount = progress.teachingAutomaticRecoveryCount
    }
    if (progress.teachingOperation) report.teachingLatestGenerationOperation = progress.teachingOperation
    if (progress.lessonStatus) report.lessonStatus = progress.lessonStatus
    report.lessonSectionCount = progress.sectionCount
    report.teachingPublishedSectionCount = progress.publishedSectionCount
    report.citedDraftSectionCount = progress.citedDraftSectionCount
    report.insufficientSectionCount = progress.insufficientSectionCount
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }

  try {
    await login(page, username, password)
    report.stage = 'model-role-preflight'
    const modelConfigurationResponse = await page.request.get('/api/v1/model-configuration')
    expect(modelConfigurationResponse.ok(),
      `Model configuration returned HTTP ${modelConfigurationResponse.status()}`).toBe(true)
    const modelConfiguration = await modelConfigurationResponse.json() as ModelConfigurationResponse
    configuredProductionRole(modelConfiguration, 'recommendation')
    configuredProductionRole(modelConfiguration, 'teaching')
    const visualProvider = configuredProductionRole(modelConfiguration, 'visual')
    configuredProductionRole(modelConfiguration, 'answer')
    expect(visualProvider.visionCapable,
      `Production visual provider '${visualProvider.id}' cannot inspect rulebook page images`).toBe(true)
    report.modelAssignments = modelConfiguration.assignments
    report.visualModelVisionCapable = visualProvider.visionCapable
    await retainReport(reportFile, report)
    report.stage = 'recommendation'
    await page.goto('/discover')
    const recommendationStartedAt = performance.now()
    const composer = page.getByLabel('聊聊你想玩的游戏')
    await composer.fill(RECOMMENDATION_PROMPT)
    await page.getByRole('button', { name: '发送', exact: true }).click()

    const targetDetailsButton = page.getByRole('button', { name: new RegExp(`查看完整资料：(?:${TARGET_NAME.source})`, 'i') })
    await expect(targetDetailsButton).toBeVisible({ timeout: 2 * 60_000 })
    report.recommendationMs = elapsed(recommendationStartedAt)
    await composer.fill(PRESERVED_DRAFT)

    report.stage = 'details-dialog'
    await targetDetailsButton.click()
    let details = page.getByRole('dialog', { name: '桌游详细资料' })
    await expect(details.getByRole('heading', { name: TARGET_NAME })).toBeVisible({ timeout: 60_000 })
    await expect(page).toHaveURL(/\/discover$/)
    await details.getByRole('button', { name: '关闭桌游资料' }).click()
    await expect(details).toBeHidden()
    report.detailsDialogOpenedAndClosed = true

    const discoveryStartedAt = performance.now()
    const candidatesResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/documents/rulebook-candidates' && response.ok()
    }, { timeout: 90_000 })
    const bindingResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/bgg/games/${TARGET_BGG_ID}/import`
        && response.request().method() === 'POST'
        && response.ok()
    }, { timeout: 90_000 })
    await targetDetailsButton.click()
    details = page.getByRole('dialog', { name: '桌游详细资料' })
    await details.getByRole('button', { name: '选这款，继续找规则书' }).click()
    const [candidatesResponse, bindingResponse] = await Promise.all([
      candidatesResponsePromise,
      bindingResponsePromise,
    ])
    const boundGame = await bindingResponse.json() as BoundGameResponse
    const candidateResult = await candidatesResponse.json() as CandidateResponse
    report.boundBggId = boundGame.bggId
    report.boundGameName = boundGame.game.name
    report.boundEditionId = boundGame.edition.id
    expect(boundGame.bggId, 'The binding response did not preserve the selected BGG identity').toBe(TARGET_BGG_ID)
    expect(boundGame.game.name, 'The binding response used an unexpected game title').toMatch(TARGET_NAME)
    report.candidateEditionMatchesSelection = new URL(candidatesResponse.url()).searchParams.get('editionId')
      === boundGame.edition.id
    expect(report.candidateEditionMatchesSelection,
      'Rulebook discovery used a different edition from the selected recommendation').toBe(true)
    report.discoveryMs = elapsed(discoveryStartedAt)
    expect(candidateResult.configured).toBe(true)
    expect(candidateResult.identity.editionId,
      'Rulebook discovery response lost the selected edition identity').toBe(boundGame.edition.id)
    const gstoneCandidate = candidateResult.candidates.find(candidate =>
      candidate.sourceDomain.endsWith('gstonegames.com')
      && candidate.language.toLowerCase().startsWith('zh')
      && (candidate.capability === 'DIRECT_DOCUMENT' && candidate.acquisitionMode === 'DIRECT_PDF'
        || candidate.capability === 'CONTIGUOUS_RULE_PAGES' && candidate.acquisitionMode === 'IMAGE_GALLERY'))
    expect(gstoneCandidate, 'No importable Chinese Gstone rulebook was discovered').toBeDefined()
    report.sourceDomain = gstoneCandidate!.sourceDomain
    report.sourceUrl = gstoneCandidate!.url
    report.sourceMode = gstoneCandidate!.acquisitionMode

    const catalogResponse = await page.request.get('/api/v1/games')
    expect(catalogResponse.ok(), `Catalog returned HTTP ${catalogResponse.status()}`).toBe(true)
    const catalogGames = await catalogResponse.json() as CatalogGameResponse[]
    report.boundGameInCatalog = catalogGames.some(entry =>
      entry.game.id === boundGame.game.id
      && entry.game.name === boundGame.game.name
      && entry.editions.some(edition => edition.id === boundGame.edition.id))
    expect(report.boundGameInCatalog, 'The selected recommendation was not bound in My Games').toBe(true)

    report.stage = 'source-review'
    const journeyBackdrop = page.getByTestId('player-journey-backdrop')
    const journeySurface = page.getByTestId('player-journey-surface')
    report.journeyBackdropVisible = await journeyBackdrop.isVisible()
    report.journeySurfaceOpaque = await opaqueSurface(journeySurface)
    expect(report.journeyBackdropVisible).toBe(true)
    expect(report.journeySurfaceOpaque).toBe(true)
    report.confirmedMilestonesAtSourceReview = await journeySurface
      .locator('[data-fact-confirmed="true"]').count()
    expect(report.confirmedMilestonesAtSourceReview).toBe(1)
    const candidateCard = page.locator('li', {
      has: page.locator(`a[href="${gstoneCandidate!.url}"]`),
    }).first()
    await expect(candidateCard).toContainText('社区规则书来源')
    await candidateCard.getByRole('button', { name: '选择这份' }).click()
    const importButton = page.getByRole('button', { name: '下载规则书并生成讲解' })
    await expect(importButton).toBeDisabled()
    await page.getByRole('checkbox', { name: /我已比较以上游戏、版本和语言/ }).check()
    await expect(importButton).toBeDisabled()
    await page.getByRole('checkbox', { name: /我确认该链接来自有权提供/ }).check()
    await expect(importButton).toBeEnabled()

    report.stage = 'import'
    const importStartedAt = performance.now()
    const importResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/v1/documents/official-imports' && response.request().method() === 'POST'
    }, { timeout: 30_000 })
    await importButton.click()
    const importResponse = await importResponsePromise
    expect(importResponse.status()).toBe(202)
    let launchedJob = await importResponse.json() as ImportJob
    launchedJob = await retryFailedReusedTeaching(page.request, launchedJob)
    report.importReused = launchedJob.reused
    if (REQUIRE_FRESH_IMPORT) {
      expect(launchedJob.reused, 'The requested fresh-import journey reused an existing rulebook').toBe(false)
    }
    report.teachingEvidenceRefreshRequested = launchedJob.reused
      && launchedJob.teachingPreparationRunId === null
      && (launchedJob.teachingHandoffState === 'WAITING_FOR_DOCUMENT'
        || launchedJob.teachingHandoffState === 'LAUNCHING')
    report.importEditionMatchesSelection = launchedJob.editionId === boundGame.edition.id
      && observedImportRequest?.editionId === boundGame.edition.id
      && observedImportRequest?.discoveredForEditionId === boundGame.edition.id
      && observedImportRequest?.officialSourceUrl === gstoneCandidate!.url
      && observedImportRequest?.identityConfirmed === true
    expect(report.importEditionMatchesSelection,
      'The official import request or persisted job changed the selected edition/source identity').toBe(true)
    expect(launchedJob.title, 'The official import response did not retain the selected game title')
      .toBe(boundGame.game.name)
    expect(launchedJob.sourceDomain, 'The official import response changed the selected source domain')
      .toBe(gstoneCandidate!.sourceDomain)

    report.stage = 'close-and-recover-background-status'
    await page.getByTestId('player-journey-surface')
      .getByRole('button', { name: '关闭小窗' })
      .click()
    const globalStatusShortcut = page.getByTestId('background-work-persistent-shortcut')
    await expect(globalStatusShortcut).toBeVisible({ timeout: 60_000 })
    await expect(globalStatusShortcut).toContainText('讲解状态')
    report.globalStatusVisibleAfterClosing = true
    await globalStatusShortcut.click()
    const backgroundWork = page.getByRole('dialog', { name: '后台任务' })
    await expect(backgroundWork).toBeVisible()
    await expect(backgroundWork).toContainText(boundGame.game.name)
    report.globalStatusReopened = true
    await backgroundWork.getByRole('button', { name: '关闭后台任务' }).click()
    await page.getByTestId('player-journey-dock').click()
    await expect(page.getByTestId('player-journey-surface')).toBeVisible()
    report.stage = 'import'

    const importObservation = await waitForCompletedImport(page.request, launchedJob.id, importStartedAt)
    const completedJob = importObservation.job
    expect(completedJob.downloadedBytes).toBeGreaterThan(0)
    expect(completedJob.documentVersionId).not.toBeNull()
    expect(completedJob.teachingHandoffState).toBe('LAUNCHED')
    expect(completedJob.teachingPreparationRunId).not.toBeNull()
    expect(completedJob.editionId).toBe(boundGame.edition.id)
    observedDocumentVersionId = completedJob.documentVersionId
    observedPreparationRunId = completedJob.teachingPreparationRunId
    report.importDuplicate = completedJob.duplicate
    report.downloadedBytes = completedJob.downloadedBytes
    report.importMs = elapsed(importStartedAt)
    report.downloadCompletedAt = completedJob.downloadCompletedAt
    report.importCompletedAt = completedJob.importCompletedAt
    report.teachingHandoffUpdatedAt = completedJob.teachingHandoffUpdatedAt
    if (!report.importReused) {
      report.persistedDownloadToImportCompleteMs = persistedDuration(
        completedJob.downloadCompletedAt,
        completedJob.importCompletedAt,
      )
      report.persistedImportCompleteToHandoffMs = persistedDuration(
        completedJob.importCompletedAt,
        completedJob.teachingHandoffUpdatedAt,
      )
      report.persistedDownloadToHandoffMs = persistedDuration(
        completedJob.downloadCompletedAt,
        completedJob.teachingHandoffUpdatedAt,
      )
    }
    report.pdfDownloadCompleteMs = importObservation.pdfDownloadCompleteMs
    report.documentReadyMs = importObservation.documentReadyMs
    report.teachingHandoffLaunchedMs = importObservation.teachingHandoffLaunchedMs
    expect(report.pdfDownloadCompleteMs, 'The production probe did not observe PDF download completion')
      .not.toBeNull()
    expect(report.documentReadyMs, 'The production probe did not observe the document READY milestone')
      .not.toBeNull()
    expect(report.teachingHandoffLaunchedMs, 'The production probe did not observe teaching handoff launch')
      .not.toBeNull()
    if (!report.importReused) {
      expect(report.downloadCompletedAt, 'A fresh import did not persist PDF byte completion').not.toBeNull()
      expect(report.importCompletedAt, 'A fresh import did not persist import completion').not.toBeNull()
      expect(report.teachingHandoffUpdatedAt, 'A fresh import did not persist handoff launch').not.toBeNull()
      expect(report.persistedDownloadToHandoffMs,
        'Fresh import milestones could not be ordered from PDF completion to handoff').not.toBeNull()
    }
    report.teachingHandoffState = completedJob.teachingHandoffState
    report.teachingAutomaticRecoveryCount = completedJob.teachingAutomaticRecoveryCount
    const progressResponse = await page.request.get(
      `/api/v1/document-versions/${encodeURIComponent(completedJob.documentVersionId!)}/progress/snapshot`,
    )
    expect(progressResponse.ok(), `Document progress returned HTTP ${progressResponse.status()}`).toBe(true)
    const progressPayload = await progressResponse.json() as DocumentProgressResponse
    report.documentProgressStage = progressPayload.stage
    report.documentProgressComplete = progressPayload.complete
    expect(progressPayload).toMatchObject({ stage: 'READY', complete: true })
    expect(importRequestCount).toBe(1)
    const documentsResponse = await page.request.get('/api/v1/documents')
    expect(documentsResponse.ok(), `Documents returned HTTP ${documentsResponse.status()}`).toBe(true)
    const documents = await documentsResponse.json() as RuleDocumentResponse[]
    const importedDocument = documents.find(document => document.latestVersion.id === completedJob.documentVersionId)
    report.documentEditionMatchesSelection = importedDocument?.document.gameEditionId === boundGame.edition.id
    expect(report.documentEditionMatchesSelection,
      'The readable document was not persisted against the selected game edition').toBe(true)

    const firstCitedLesson = await waitForFirstCitedLesson(
      page.request,
      completedJob.id,
      completedJob.documentVersionId!,
      completedJob.teachingPreparationRunId!,
      importStartedAt,
      requiresPersistedPublicationActivity(report),
      completedJob.teachingHandoffUpdatedAt,
      recordTeachingProgress,
    )
    observedTeachingPlanId = firstCitedLesson.planId
    report.teachingPreparationStartedMs = firstCitedLesson.teachingPreparationStartedMs
    report.firstCitedLessonMs = firstCitedLesson.firstCitedLessonMs
    report.preparationRunCreatedAt = firstCitedLesson.preparationRunCreatedAt
    report.firstCitedPublicationActivityAt = firstCitedLesson.firstCitedPublicationActivityAt
    report.persistedHandoffToPreparationRunMs = persistedDuration(
      report.teachingHandoffUpdatedAt,
      report.preparationRunCreatedAt,
    )
    report.persistedPreparationToFirstCitedActivityMs = persistedDuration(
      report.preparationRunCreatedAt,
      report.firstCitedPublicationActivityAt,
    )
    if (!report.importReused) {
      report.persistedDownloadToFirstCitedActivityMs = persistedDuration(
        report.downloadCompletedAt,
        report.firstCitedPublicationActivityAt,
      )
    }
    expect(report.preparationRunCreatedAt, 'The production probe did not observe the real preparation Run')
      .not.toBeNull()
    if (requiresPersistedPublicationActivity(report)) {
      expect(report.firstCitedPublicationActivityAt,
        'A fresh production import did not persist a source-cited publication activity').not.toBeNull()
      expect(report.persistedPreparationToFirstCitedActivityMs,
        'The fresh preparation-to-first-cited activity duration could not be computed').not.toBeNull()
    }
    if (report.pdfDownloadCompleteMs !== null) {
      report.pdfDownloadToTeachingStartMs = Math.max(
        0,
        firstCitedLesson.teachingPreparationStartedMs - report.pdfDownloadCompleteMs,
      )
      report.pdfDownloadToFirstCitedLessonMs = Math.max(
        0,
        firstCitedLesson.firstCitedLessonMs - report.pdfDownloadCompleteMs,
      )
    }

    guidesPage = await page.context().newPage()
    guidesPage.on('pageerror', error => pageErrors.push(error))
    await guidesPage.goto('/lessons')
    const pendingGuideEntry = guidesPage.getByTestId('pending-guide-journey')
      .filter({ hasText: boundGame.game.name })
    const persistedGuideHeading = guidesPage.locator('h2').filter({ hasText: boundGame.game.name })
    await expect(pendingGuideEntry.or(persistedGuideHeading).first()).toBeVisible({ timeout: 60_000 })
    report.myGuidesEntryVisibleBeforeLesson = true

    report.stage = 'read-rulebook-while-teaching'
    const rulebookReadableStartedAt = performance.now()
    const openRulebook = page.getByRole('button', { name: '先阅读原规则书' })
    await expect(openRulebook).toBeVisible({ timeout: 8 * 60_000 })
    await expect.poll(() => journeySurface.locator('[data-fact-confirmed="true"]').count(), {
      timeout: 60_000,
      message: 'Persisted rulebook facts never confirmed the first three journey milestones',
    }).toBeGreaterThanOrEqual(3)
    report.rulebookReadableMs = elapsed(rulebookReadableStartedAt)
    await openRulebook.click()
    const rulebook = page.getByRole('dialog', { name: '原规则书阅读器' })
    await expect(rulebook.getByText('你可以先阅读原规则书；讲解仍在后台生成')).toBeVisible()
    const firstPage = rulebook.getByRole('img', { name: '规则书第 1 页' })
    await expect(firstPage).toBeVisible({ timeout: 90_000 })
    await expect.poll(async () => firstPage.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0), {
      timeout: 90_000,
      message: 'The first production rulebook page never rendered',
    }).toBe(true)
    report.renderedRulebookPage = true
    await rulebook.getByRole('button', { name: '关闭规则书' }).click()

    report.stage = 'lesson'
    const lessonStartedAt = performance.now()
    const completedLesson = await waitForCompletedLesson(
      page.request,
      firstCitedLesson.planId,
      recordTeachingProgress,
    )
    report.teachingGenerationState = completedLesson.teachingRunState
    report.lessonStatus = completedLesson.lessonStatus
    report.lessonSectionCount = completedLesson.sectionCount
    report.citedDraftSectionCount = completedLesson.citedDraftSectionCount
    report.insufficientSectionCount = completedLesson.insufficientSectionCount
    report.lessonCompletionMs = elapsed(lessonStartedAt)
    const journeyDock = page.getByTestId('player-journey-dock')
    await expect(journeyDock).toBeVisible()
    await expect(journeyDock).toContainText('打开讲解', { timeout: 60_000 })
    await expect(journeyDock).toContainText('基础讲解可读')
    await expect(journeyDock).not.toContainText('准备流程需要处理')
    report.lessonDockText = (await journeyDock.innerText()).trim()
    report.lessonReadableMs = elapsed(lessonStartedAt)
    await journeyDock.click()
    const lesson = page.getByRole('dialog', { name: '生成讲解阅读器' })
    report.lessonBackdropVisible = await page.getByTestId('recommendation-lesson-backdrop').isVisible()
    report.lessonSurfaceOpaque = await opaqueSurface(page.getByTestId('recommendation-lesson-surface'))
    expect(report.lessonBackdropVisible).toBe(true)
    expect(report.lessonSurfaceOpaque).toBe(true)
    await expect(lesson.getByText('每个步骤都保留原规则书页码；答疑只使用同一份规则书。')).toBeVisible({ timeout: 60_000 })
    const lessonSections = lesson.getByTestId('lesson-reading-column').locator('section')
    await expect.poll(async () => lessonSections.count(), {
      timeout: 60_000,
      message: 'The reopened guide reader did not reconcile to the completed lesson snapshot',
    }).toBe(report.lessonSectionCount)
    await expect(lesson.getByRole('link', { name: /来源：第 \d+(?:、\d+)* 页/ }).first()).toBeVisible()
    report.citedLessonStep = true
    report.confirmedMilestonesFinal = await page.getByTestId('player-journey-surface')
      .locator('[data-fact-confirmed="true"]').count()
    expect(report.confirmedMilestonesFinal).toBe(5)

    const plansResponse = await page.request.get('/api/v1/teaching-plans')
    expect(plansResponse.ok(), `My Guides returned HTTP ${plansResponse.status()}`).toBe(true)
    const plans = await plansResponse.json() as TeachingPlanResponse[]
    const persistedPlan = plans.find(plan => plan.documentVersionId === completedJob.documentVersionId)
    expect(persistedPlan, 'The generated plan was not listed in My Guides').toBeDefined()
    report.myGuidesPlanListed = persistedPlan != null
    report.planGameTitleMatchesSelection = persistedPlan?.gameTitle === boundGame.game.name
    expect(report.planGameTitleMatchesSelection,
      `My Guides used ${persistedPlan?.gameTitle ?? 'no title'} instead of ${boundGame.game.name}`).toBe(true)
    await guidesPage.reload()
    await expect(guidesPage.getByRole('heading', { name: boundGame.game.name, exact: true })).toBeVisible({ timeout: 60_000 })

    report.stage = 'grounded-answer'
    await lesson.getByRole('button', { name: '切换到规则答疑' }).click()
    const answerWorkspace = page.getByTestId('recommendation-answer-workspace')
    await expect(answerWorkspace).toContainText('已绑定规则书，可以开始提问', { timeout: 60_000 })
    const answerStartedAt = performance.now()
    const answerResponsePromise = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === `/api/v1/document-versions/${completedJob.documentVersionId}/answers/stream`
        && response.request().method() === 'POST'
    }, { timeout: 4 * 60_000 })
    await page.getByLabel('向规则书提问').fill(RULE_QUESTION)
    await page.getByRole('button', { name: '提交问题' }).click()
    const answerResponse = await answerResponsePromise
    expect(answerResponse.ok(), `Answer endpoint returned HTTP ${answerResponse.status()}`).toBe(true)
    const answerPayload = ssePayload<AnswerResponse>(
      (await answerResponse.body()).toString('utf8'),
      'result',
    )
    report.answerStatus = answerPayload.answer.status
    report.answerCitationCount = answerPayload.answer.citations.length
    expect(['ANSWERED', 'ANSWERED_WITH_WARNING']).toContain(report.answerStatus)
    expect(report.answerCitationCount).toBeGreaterThan(0)
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible({ timeout: 4 * 60_000 })
    await expect(answerWorkspace.getByText(/第 \d+(?:\s*[–-]\s*\d+)? 页/).first()).toBeVisible()
    report.answerMs = elapsed(answerStartedAt)
    report.citedAnswer = true

    report.stage = 'role-switching'
    const roleSwitcher = page.getByTestId('agent-role-switcher')
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()
    await expect(composer).toBeVisible()
    await expect(composer).toHaveValue(PRESERVED_DRAFT)
    await expect.poll(() => targetDetailsButton.count(), {
      message: 'The recommendation workspace did not restore a matching verified game card',
    }).toBeGreaterThan(0)
    await expect(targetDetailsButton.first()).toBeVisible()
    await roleSwitcher.getByRole('button', { name: '规则答疑' }).click()
    await expect(answerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible()
    await roleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    report.stage = 'refresh-restoration'
    await page.reload()
    const restoredComposer = page.getByLabel('聊聊你想玩的游戏')
    await expect(restoredComposer).toBeVisible({ timeout: 60_000 })
    await expect(restoredComposer).toHaveValue(PRESERVED_DRAFT)
    await expect(page.getByRole('button', {
      name: new RegExp(`查看完整资料：(?:${TARGET_NAME.source})`, 'i'),
    }).first()).toBeVisible({ timeout: 60_000 })
    report.recommendationRestored = true
    const restoredRoleSwitcher = page.getByTestId('agent-role-switcher')
    await restoredRoleSwitcher.getByRole('button', { name: '规则答疑' }).click()
    const restoredAnswerWorkspace = page.getByTestId('recommendation-answer-workspace')
    await expect(restoredAnswerWorkspace.locator('#lesson-answer-evidence-title')).toBeVisible({ timeout: 60_000 })
    report.answerRestored = true
    await restoredRoleSwitcher.getByRole('button', { name: '继续推荐' }).click()

    await expect(page).toHaveURL(/\/discover$/)
    expect(pageErrors, 'The production journey emitted uncaught browser errors').toEqual([])
    report.routeStayedOnDiscover = true
    report.completed = true
    report.stage = 'completed'
  } finally {
    if (observedDocumentVersionId) {
      try {
        const response = await page.request.get(
          `/api/v1/document-versions/${encodeURIComponent(observedDocumentVersionId)}/progress/snapshot`,
        )
        if (response.ok()) {
          const progress = await response.json() as DocumentProgressResponse
          report.documentProgressStage = progress.stage
          report.documentProgressComplete = progress.complete
        }
      } catch {
        // The primary assertion remains authoritative when diagnostic refresh is unavailable.
      }
    }
    if (observedPreparationRunId) {
      try {
        const response = await page.request.get(
          `/api/v1/assistant-runs/${encodeURIComponent(observedPreparationRunId)}`,
        )
        if (response.ok()) {
          const details = await response.json() as RunDetailsResponse
          report.teachingPreparationState = details.run.state
          report.teachingPreparationErrorCode = details.run.lastErrorCode
        }
      } catch {
        // The browser report must still be retained if its final diagnostic read is unavailable.
      }
    }
    if (observedTeachingPlanId) {
      try {
        const [runResponse, lessonResponse] = await Promise.all([
          page.request.get(
            `/api/v1/assistant-runs/latest?mode=TEACHING&subjectId=${encodeURIComponent(observedTeachingPlanId)}`,
          ),
          page.request.get(
            `/api/v1/teaching-plans/${encodeURIComponent(observedTeachingPlanId)}/illustrated-lessons/latest`,
          ),
        ])
        if (runResponse.ok()) {
          const details = await runResponse.json() as RunDetailsResponse
          report.teachingGenerationState = details.run.state
        }
        if (lessonResponse.ok()) {
          const latestLesson = await lessonResponse.json() as LessonMilestoneResponse
          report.lessonStatus = latestLesson.status
          report.lessonSectionCount = latestLesson.sections.length
          report.citedDraftSectionCount = latestLesson.sections
            .filter(section => section.evidenceStatus === 'CITED_DRAFT').length
          report.insufficientSectionCount = latestLesson.sections
            .filter(section => section.evidenceStatus === 'INSUFFICIENT_EVIDENCE').length
        }
      } catch {
        // Preserve the primary failure while retaining any earlier teaching diagnostics.
      }
    }
    await guidesPage?.close().catch(() => undefined)
    report.importRequestCount = importRequestCount
    report.pageErrorCount = pageErrors.length
    report.generatedAt = new Date().toISOString()
    await retainReport(reportFile, report)
  }
})
