import { createHash } from 'node:crypto'
import { readFile, mkdir, rename, writeFile } from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const TERMINAL_RUN_STATES = new Set(['COMPLETED', 'FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'])
const FAILED_RUN_STATES = new Set(['FAILED', 'DEGRADED', 'INSUFFICIENT_EVIDENCE'])

function usage() {
  console.log(`Usage: node scripts/generate-public-corpus-entry.mjs --title <manifest title> [options]

Options:
  --manifest <path>         Default: .local/public-corpus/source-preflight.json
  --pdf-dir <path>          Default: .local/public-corpus/pdfs
  --output <path>           Default: .local/public-corpus/runs/<title>.json
  --base-url <url>          Default: http://localhost:8080
  --timeout-minutes <n>     Default: 20
  --teaching <provider>     Required real provider for lesson prose
  --visual <provider>       Required real vision-capable provider
  --answer <provider>       Required real provider for Q&A
  --critic <provider>       Required real provider for factual review
  --restart                 Ignore a previous local checkpoint
  --refresh-plan            Build a new outline instead of reusing the latest one
  --help                    Show this help

Credentials are read from RULEPILOT_ADMIN_USERNAME and RULEPILOT_ADMIN_PASSWORD,
including values in the local .env file. They are never written to the report.`)
}

export function parseArguments(argv) {
  const options = {}
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help') options.help = true
    else if (argument === '--restart') options.restart = true
    else if (argument === '--refresh-plan') options.refreshPlan = true
    else if (argument === '--title') options.title = argv[++index]
    else if (argument === '--manifest') options.manifest = argv[++index]
    else if (argument === '--pdf-dir') options.pdfDir = argv[++index]
    else if (argument === '--output') options.output = argv[++index]
    else if (argument === '--base-url') options.baseUrl = argv[++index]
    else if (argument === '--timeout-minutes') options.timeoutMinutes = Number(argv[++index])
    else if (argument === '--teaching') options.teaching = argv[++index]
    else if (argument === '--visual') options.visual = argv[++index]
    else if (argument === '--answer') options.answer = argv[++index]
    else if (argument === '--critic') options.critic = argv[++index]
    else throw new Error(`Unknown argument: ${argument}`)
  }
  return options
}

export function selectEntry(manifest, title) {
  if (!title?.trim()) throw new Error('--title is required')
  const matches = (manifest.qualifiedRulebooks ?? []).filter(
    (entry) => entry.title.toLocaleLowerCase('en-US') === title.trim().toLocaleLowerCase('en-US'),
  )
  if (matches.length !== 1) throw new Error(`Qualified manifest title not found: ${title}`)
  return matches[0]
}

export function needsBggCatalog(entry) {
  return !entry?.publisherCover && Number.isInteger(entry?.bggId) && entry.bggId > 0
}

export function shouldImportBggCatalog(entry, bggStatus) {
  return needsBggCatalog(entry) && bggStatus?.configured === true
}

export function requestedCoverSource(entry) {
  return entry?.publisherCover ? 'PUBLISHER' : 'BGG_OR_RULEBOOK_FRONT'
}

export function slugify(value) {
  const slug = value.normalize('NFKD').replace(/[^a-zA-Z0-9]+/g, '-').replace(/^-|-$/g, '').toLowerCase()
  return slug || 'rulebook'
}

export function summarizeLesson(lesson) {
  const sections = lesson.sections ?? []
  const steps = sections.flatMap((section) => section.steps ?? [])
  return {
    lessonId: lesson.id,
    status: lesson.status,
    generatorVersion: lesson.generatorVersion,
    sectionCount: sections.length,
    stepCount: steps.length,
    visualStepCount: steps.filter((step) => step.visualFocus).length,
    evidenceStatuses: sections.map((section) => section.evidenceStatus),
    sectionTitles: sections.map((section) => section.title),
  }
}

export function hasPublicCover(publicLesson, rulebookFrontAvailable) {
  return Boolean(publicLesson?.gameCover?.imageUrl) || rulebookFrontAvailable === true
}

export function publicCoverRequestPath(planId) {
  if (!planId || typeof planId !== 'string') throw new Error('public lesson identifier is required')
  return `/api/public/lessons/${encodeURIComponent(planId)}/cover`
}

export function summarizeRunProgress(details) {
  if (!details?.run) return String(details)
  const budget = details.budget ?? {}
  const running = (details.activities ?? []).filter((activity) => activity.outcome === 'RUNNING')
  const operation = running.length ? ` · 当前 ${running.at(-1).operation}` : ''
  return `${details.run.state} · 模型 ${budget.usedModelCalls ?? 0}/${budget.maxModelCalls ?? '?'} · `
    + `工具 ${budget.usedToolCalls ?? 0}/${budget.maxToolCalls ?? '?'} · 活动 ${(details.activities ?? []).length}${operation}`
}

export function resetGeneratedLessonStateForPlanRefresh(state) {
  const { preparation, plan, teaching, visual, localization, result, ...reusableState } = state
  return reusableState
}

/** A local checkpoint cannot outlive a replaced development or production database. */
export function resetStaleServerState(state) {
  const { document, catalog, preparation, plan, teaching, visual, localization, result, ...localState } = state
  return localState
}

export function selectReusableDocument(documents, entry, checksum) {
  const expectedTitle = `${entry.title} Rules`.toLocaleLowerCase('en-US')
  return (documents ?? [])
    .filter((candidate) => candidate.latestVersion?.checksum === checksum
      && candidate.document?.officialSourceUrl === entry.source)
    .sort((left, right) => {
      const leftAssigned = left.document.gameEditionId ? 1 : 0
      const rightAssigned = right.document.gameEditionId ? 1 : 0
      if (leftAssigned !== rightAssigned) return rightAssigned - leftAssigned
      const leftTitle = left.document.title.toLocaleLowerCase('en-US') === expectedTitle ? 1 : 0
      const rightTitle = right.document.title.toLocaleLowerCase('en-US') === expectedTitle ? 1 : 0
      return rightTitle - leftTitle
    })[0] ?? null
}

async function localEnvironment(path = '.env') {
  const values = {}
  try {
    const text = await readFile(path, 'utf8')
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim()
      if (!line || line.startsWith('#')) continue
      const separator = line.indexOf('=')
      if (separator < 1) continue
      const key = line.slice(0, separator).trim()
      let value = line.slice(separator + 1).trim()
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1)
      }
      values[key] = value
    }
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
  }
  return { ...values, ...process.env }
}

class RulePilotClient {
  constructor(baseUrl, username, password, fetchImpl = fetch) {
    const url = new URL(baseUrl)
    if (!['localhost', '127.0.0.1', '::1'].includes(url.hostname)) {
      throw new Error('Corpus generation accepts only a local RulePilot base URL')
    }
    this.baseUrl = url.toString().replace(/\/$/, '')
    this.authorization = `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`
    this.fetch = fetchImpl
    this.cookie = null
    this.csrf = null
  }

  async request(path, options = {}) {
    const method = options.method ?? 'GET'
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !this.csrf) await this.loadCsrf()
    const headers = new Headers(options.headers)
    headers.set('Authorization', this.authorization)
    if (this.cookie) headers.set('Cookie', this.cookie)
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set(this.csrf.headerName, this.csrf.token)
    const response = await this.fetch(`${this.baseUrl}${path}`, { ...options, method, headers })
    this.captureSession(response)
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 500)
      const error = new Error(`${method} ${path} failed with HTTP ${response.status}${detail ? `: ${detail}` : ''}`)
      error.status = response.status
      throw error
    }
    if (response.status === 204) return null
    return response.json()
  }

  async loadCsrf() {
    const response = await this.fetch(`${this.baseUrl}/api/auth/csrf`, {
      headers: { Authorization: this.authorization },
    })
    this.captureSession(response)
    if (!response.ok) throw new Error(`Could not establish local authenticated session (HTTP ${response.status})`)
    this.csrf = await response.json()
  }

  captureSession(response) {
    const setCookies = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [response.headers.get('set-cookie')].filter(Boolean)
    for (const value of setCookies) {
      const match = value.match(/(?:^|,\s*)SESSION=([^;]+)/)
      if (match) this.cookie = `SESSION=${match[1]}`
    }
  }

  async rulebookFrontCoverAvailable(planId) {
    const response = await this.fetch(`${this.baseUrl}${publicCoverRequestPath(planId)}`, {
      method: 'HEAD',
      headers: { Authorization: this.authorization },
    })
    this.captureSession(response)
    return response.ok && response.headers.get('content-type')?.toLowerCase().startsWith('image/jpeg')
  }

  /**
   * A public card must never make its first reader wait for a publisher/BGG fetch. Corpus generation owns this
   * bounded warm-up step and waits until the durable thumbnail endpoint has returned a real JPEG.
   */
  async cachePublicCover(planId) {
    const headers = new Headers({ Authorization: this.authorization })
    if (this.cookie) headers.set('Cookie', this.cookie)
    const response = await this.fetch(`${this.baseUrl}${publicCoverRequestPath(planId)}`, { headers })
    this.captureSession(response)
    if (!response.ok) throw new Error(`Could not cache public cover (HTTP ${response.status})`)
    const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
    const content = await response.arrayBuffer()
    if (!contentType.startsWith('image/jpeg') || content.byteLength === 0) {
      throw new Error('Public cover warm-up did not return a JPEG')
    }
    return content.byteLength
  }
}

async function readJson(path) {
  try {
    return JSON.parse(await readFile(path, 'utf8'))
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
}

async function writeJson(path, value) {
  await mkdir(dirname(path), { recursive: true })
  const temporary = `${path}.tmp`
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 })
  await rename(temporary, path)
}

function elapsed(startedAt) {
  return `${Math.round((Date.now() - new Date(startedAt).getTime()) / 1000)}s`
}

function progress(state, message) {
  console.error(`[本次 ${elapsed(state.attemptStartedAt ?? state.startedAt)}] ${message}`)
}

async function checkpoint(path, state, change = {}) {
  Object.assign(state, change, { updatedAt: new Date().toISOString() })
  await writeJson(path, state)
}

async function poll(label, state, operation, complete, deadline, interval = 2_000, summarize = null) {
  let previous = ''
  let lastMessageAt = 0
  while (Date.now() < deadline) {
    const value = await operation()
    const current = summarize ? summarize(value) : value?.run ? summarizeRunProgress(value) : String(value)
    if (current !== previous || Date.now() - lastMessageAt >= 10_000) {
      progress(state, `${label}: ${current}`)
      previous = current
      lastMessageAt = Date.now()
    }
    if (complete(value)) return value
    await new Promise((resolvePromise) => setTimeout(resolvePromise, interval))
  }
  throw new Error(`${label} exceeded the configured timeout`)
}

async function ensureModelAssignments(client, requested) {
  const snapshot = await client.request('/api/v1/model-configuration')
  const assignments = {
    teaching: requested.teaching ?? snapshot.assignments.teaching,
    visual: requested.visual ?? snapshot.assignments.visual,
    answer: requested.answer ?? snapshot.assignments.answer,
    critic: requested.critic ?? snapshot.assignments.critic,
  }
  for (const [role, provider] of Object.entries(assignments)) {
    if (!provider || provider === 'fake') {
      throw new Error(`Corpus generation requires a real ${role} provider; pass --${role} <provider>`)
    }
    const configured = snapshot.providers.find((candidate) => candidate.id === provider)
    if (!configured?.configured) throw new Error(`${role} provider '${provider}' is not configured`)
    if (role === 'visual' && !configured.visionCapable) {
      throw new Error(`visual provider '${provider}' does not accept page images`)
    }
  }
  const changed = Object.entries(assignments)
    .some(([role, provider]) => snapshot.assignments[role] !== provider)
  const assigned = changed
    ? await client.request('/api/v1/model-configuration/assignments', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(assignments),
      })
    : snapshot
  return {
    assignments: assigned.assignments,
    providers: Object.fromEntries(Object.entries(assignments).map(([role, provider]) => {
      const view = assigned.providers.find((candidate) => candidate.id === provider)
      return [role, { id: provider, model: view.model, visionCapable: view.visionCapable }]
    })),
  }
}

async function versionStatus(client, documentId, versionId, editionId) {
  const path = editionId ? `/api/v1/editions/${editionId}/documents` : '/api/v1/documents'
  const documents = await client.request(path)
  const found = documents.find((candidate) => candidate.document.id === documentId
    && candidate.latestVersion.id === versionId)
  if (!found) throw new Error('Uploaded document version is no longer visible to the corpus owner')
  return found.latestVersion.status
}

async function runDetails(client, runId) {
  return client.request(`/api/v1/assistant-runs/${runId}`)
}

async function latestRun(client, planId, mode) {
  try {
    return await client.request(`/api/v1/assistant-runs/latest?mode=${mode}&subjectId=${planId}`)
  } catch (error) {
    if (error.status === 404) return null
    throw error
  }
}

/** Starts a missing lesson run instead of waiting indefinitely for an event that only a client can trigger. */
export async function ensureTeachingRun(client, planId) {
  const existing = await latestRun(client, planId, 'TEACHING')
  if (existing && !FAILED_RUN_STATES.has(existing.run.state)) {
    return { runId: existing.run.id, state: existing.run.state, reused: true }
  }
  const launch = await client.request(`/api/v1/teaching-plans/${planId}/illustrated-lessons`, {
    method: 'POST',
  })
  return { runId: launch.assistantRunId, state: launch.state, reused: launch.reused }
}

/** Public corpus entries promise a readable English projection, not only an English navigation shell. */
export async function ensureEnglishLocalization(client, planId) {
  const path = `/api/v1/teaching-plans/${planId}/illustrated-lessons/latest/localizations/en`
  const existing = await client.request(path)
  if (existing.status && existing.status !== 'FAILED') {
    return { state: existing.status, failureCode: existing.failureCode ?? null, reused: true }
  }
  const launch = await client.request(path, { method: 'POST' })
  return { state: launch.status, failureCode: launch.failureCode ?? null, reused: false }
}

export function summarizeLocalization(view) {
  if (!view) return '英文讲解状态未知'
  const suffix = view.failureCode ? ` · ${view.failureCode}` : ''
  return `英文讲解 ${view.status ?? 'NOT_PREPARED'}${suffix}`
}

async function uploadEntry(client, entry, pdfPath, editionId) {
  const bytes = await readFile(pdfPath)
  const form = new FormData()
  form.append('title', `${entry.title} Rules`)
  form.append('sourceType', 'BASE_RULEBOOK')
  form.append('officialSourceUrl', entry.source)
  if (entry.publisherCover) form.append('officialCoverUrl', entry.publisherCover)
  form.append('file', new Blob([bytes], { type: 'application/pdf' }), basename(pdfPath))
  const path = editionId ? `/api/v1/editions/${editionId}/documents` : '/api/v1/documents'
  return client.request(path, { method: 'POST', body: form })
}

export async function generatePublicCorpusEntry(options, dependencies = {}) {
  const manifestPath = resolve(options.manifest ?? '.local/public-corpus/source-preflight.json')
  const pdfDirectory = resolve(options.pdfDir ?? '.local/public-corpus/pdfs')
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))
  const entry = selectEntry(manifest, options.title)
  const pdfPath = resolve(pdfDirectory, entry.file)
  const pdf = await readFile(pdfPath)
  const checksum = createHash('sha256').update(pdf).digest('hex')
  if (checksum !== entry.sha256) throw new Error(`Local PDF checksum changed for ${entry.title}`)

  const outputPath = resolve(options.output ?? `.local/public-corpus/runs/${slugify(entry.title)}.json`)
  const environment = await localEnvironment()
  const username = environment.RULEPILOT_ADMIN_USERNAME || 'admin'
  const password = environment.RULEPILOT_ADMIN_PASSWORD || 'rulepilot-admin-local'
  const timeoutMinutes = options.timeoutMinutes ?? 20
  if (!Number.isFinite(timeoutMinutes) || timeoutMinutes < 1 || timeoutMinutes > 180) {
    throw new Error('--timeout-minutes must be between 1 and 180')
  }
  const deadline = Date.now() + timeoutMinutes * 60_000
  const client = dependencies.client ?? new RulePilotClient(options.baseUrl ?? 'http://localhost:8080', username, password)

  let state = options.restart ? null : await readJson(outputPath)
  if (state && (state.source?.sha256 !== checksum || state.source?.officialSourceUrl !== entry.source)) {
    throw new Error(`Checkpoint source does not match ${entry.title}; rerun with --restart`)
  }
  if (state?.visual) {
    delete state.visual
  }
  state ??= {
    schemaVersion: 1,
    title: entry.title,
    startedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    source: {
      file: entry.file,
      sha256: checksum,
      officialSourceUrl: entry.source,
      coverSource: requestedCoverSource(entry),
      bggId: entry.bggId ?? null,
    },
  }
  state.attemptStartedAt = new Date().toISOString()
  await checkpoint(outputPath, state)
  progress(state, `开始处理 ${entry.title}`)

  const models = await ensureModelAssignments(client, options)
  await checkpoint(outputPath, state, { models })
  progress(state, `模型角色：讲解 ${models.assignments.teaching}，视觉 ${models.assignments.visual}，答疑 ${models.assignments.answer}，复核 ${models.assignments.critic}`)

  const visibleDocuments = await client.request('/api/v1/documents')
  if (state.document && !visibleDocuments.some((candidate) => candidate.document.id === state.document.id
      && candidate.latestVersion.id === state.document.versionId)) {
    state = resetStaleServerState(state)
    await checkpoint(outputPath, state)
    progress(state, '本地断点对应的服务器数据已不存在，按规则书校验值重新连接当前服务器')
  }

  if (!state.document) {
    const reusable = selectReusableDocument(visibleDocuments, entry, checksum)
    if (reusable) {
      const change = {
        document: {
          id: reusable.document.id,
          versionId: reusable.latestVersion.id,
          status: reusable.latestVersion.status,
          duplicate: true,
        },
      }
      if (reusable.document.gameEditionId) {
        change.catalog = { editionId: reusable.document.gameEditionId, reused: true }
      }
      await checkpoint(outputPath, state, change)
      progress(state, '复用服务器中相同来源与校验值的规则书')
    }
  }

  if (!state.catalog && needsBggCatalog(entry)) {
    const status = await client.request('/api/v1/bgg/status')
    if (shouldImportBggCatalog(entry, status)) {
      const imported = await client.request(`/api/v1/bgg/games/${entry.bggId}/import`, { method: 'POST' })
      await checkpoint(outputPath, state, {
        catalog: { gameId: imported.game.id, editionId: imported.edition.id, bggId: imported.bggId },
      })
      progress(state, `已关联 BGG 封面与游戏版本：${imported.game.name}`)
    } else {
      progress(state, '未配置 BGG，规则书将独立生成公开讲解并使用规则书首页封面')
    }
  }

  if (!state.document) {
    const upload = await uploadEntry(client, entry, pdfPath, state.catalog?.editionId)
    await checkpoint(outputPath, state, {
      document: {
        id: upload.document.id,
        versionId: upload.version.id,
        status: upload.version.status,
        duplicate: upload.duplicate,
      },
    })
    progress(state, upload.duplicate ? '复用已上传的同一规则书版本' : '规则书上传完成')
  }

  if (state.document.status !== 'READY') {
    const status = await poll(
      '读取规则书',
      state,
      () => versionStatus(client, state.document.id, state.document.versionId, state.catalog?.editionId),
      (value) => value === 'READY' || value === 'FAILED',
      deadline,
    )
    if (status === 'FAILED') throw new Error('Rulebook extraction failed')
    state.document.status = status
    await checkpoint(outputPath, state)
  }

  if (options.refreshPlan && (state.preparation || state.plan || state.teaching || state.result)) {
    state = resetGeneratedLessonStateForPlanRefresh(state)
    await checkpoint(outputPath, state)
    progress(state, '已清除旧提纲与草稿检查点，重新理解规则书并生成新的讲解')
  }

  if (!state.plan && !options.refreshPlan) {
    try {
      const existingPlan = await client.request(
        `/api/v1/document-versions/${state.document.versionId}/teaching-plans/latest`,
      )
      await checkpoint(outputPath, state, {
        preparation: { state: 'COMPLETED', reused: true },
        plan: { id: existingPlan.id, sectionCount: existingPlan.sections?.length ?? 0, reused: true },
      })
      progress(state, `复用已有的 ${state.plan.sectionCount} 章讲解提纲`)
    } catch (error) {
      if (error.status !== 404) throw error
    }
  }

  if (!state.plan && (!state.preparation || FAILED_RUN_STATES.has(state.preparation.state))) {
    const launch = await client.request(`/api/v1/document-versions/${state.document.versionId}/teaching-plans`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({}),
    })
    await checkpoint(outputPath, state, {
      preparation: { runId: launch.assistantRunId, state: launch.state, reused: launch.reused },
    })
  }
  if (!state.plan && state.preparation.state !== 'COMPLETED') {
    const details = await poll(
      '理解页面并规划讲解',
      state,
      () => runDetails(client, state.preparation.runId),
      (value) => TERMINAL_RUN_STATES.has(value.run.state),
      deadline,
    )
    state.preparation.state = details.run.state
    state.preparation.lastErrorCode = details.run.lastErrorCode
    await checkpoint(outputPath, state)
    if (FAILED_RUN_STATES.has(details.run.state)) throw new Error(`Teaching preparation ended as ${details.run.state}`)
  }

  if (!state.plan) {
    const plan = await client.request(`/api/v1/document-versions/${state.document.versionId}/teaching-plans/latest`)
    await checkpoint(outputPath, state, { plan: { id: plan.id, sectionCount: plan.sections?.length ?? 0 } })
  }

  if (state.teaching && FAILED_RUN_STATES.has(state.teaching.state)) {
    const launch = await client.request(`/api/v1/teaching-plans/${state.plan.id}/illustrated-lessons`, {
      method: 'POST',
    })
    state.teaching = { runId: launch.assistantRunId, state: launch.state, reused: launch.reused }
    await checkpoint(outputPath, state)
    progress(state, '重新启动失败的讲解任务，复用已有提纲与规则书')
  } else if (!state.teaching) {
    state.teaching = await ensureTeachingRun(client, state.plan.id)
    await checkpoint(outputPath, state)
    progress(state, state.teaching.reused ? '复用已有讲解任务' : '已启动讲解任务')
  }
  if (state.teaching.state !== 'COMPLETED') {
    const details = await poll(
      '生成、复核并编排讲解',
      state,
      () => runDetails(client, state.teaching.runId),
      (value) => TERMINAL_RUN_STATES.has(value.run.state),
      deadline,
    )
    state.teaching.state = details.run.state
    state.teaching.lastErrorCode = details.run.lastErrorCode
    state.teaching.activityCount = details.activities?.length ?? 0
    await checkpoint(outputPath, state)
    if (FAILED_RUN_STATES.has(details.run.state)) throw new Error(`Teaching generation ended as ${details.run.state}`)
  }

  if (!state.localization || state.localization.state === 'FAILED') {
    state.localization = await ensureEnglishLocalization(client, state.plan.id)
    await checkpoint(outputPath, state)
    progress(state, state.localization.reused ? '复用已有英文讲解' : '已启动英文讲解本地化')
  }
  if (state.localization.state !== 'READY') {
    const localized = await poll(
      '生成英文讲解',
      state,
      () => client.request(`/api/v1/teaching-plans/${state.plan.id}/illustrated-lessons/latest/localizations/en`),
      (value) => value.status === 'READY' || value.status === 'FAILED',
      deadline,
      2_000,
      summarizeLocalization,
    )
    state.localization.state = localized.status
    state.localization.failureCode = localized.failureCode ?? null
    await checkpoint(outputPath, state)
    if (state.localization.state === 'FAILED') {
      throw new Error(`English lesson localization failed${state.localization.failureCode ? `: ${state.localization.failureCode}` : ''}`)
    }
  }

  const lesson = await client.request(`/api/v1/teaching-plans/${state.plan.id}/illustrated-lessons/latest`)
  const publicLesson = await client.request(`/api/public/lessons/${state.plan.id}`)
  const englishPublicLesson = await client.request(`/api/public/lessons/${state.plan.id}?language=en`)
  const hasRegisteredCover = Boolean(publicLesson.gameCover?.imageUrl)
  const hasRulebookFrontCover = hasRegisteredCover ? false : await client.rulebookFrontCoverAvailable(state.plan.id)
  const coverCachedBytes = await client.cachePublicCover(state.plan.id)
  const result = {
    ...summarizeLesson(lesson),
    publicTitle: publicLesson.rulebookTitle,
    hasCover: hasPublicCover(publicLesson, hasRulebookFrontCover),
    coverCachedBytes,
    coverSource: hasRegisteredCover ? 'REGISTERED' : hasRulebookFrontCover ? 'RULEBOOK_FRONT' : 'MISSING',
    hasOfficialRulebook: Boolean(publicLesson.officialSourceUrl),
    visualAssemblyMode: 'IN_TEACHING',
    englishLocalizationState: state.localization.state,
    hasEnglishLocalization: englishPublicLesson.contentLanguage === 'en'
      && englishPublicLesson.localizationStatus === 'READY',
    completedAt: new Date().toISOString(),
    elapsedSeconds: Math.round((Date.now() - new Date(state.startedAt).getTime()) / 1000),
    attemptElapsedSeconds: Math.round((Date.now() - new Date(state.attemptStartedAt).getTime()) / 1000),
  }
  if (result.status === 'INCOMPLETE') throw new Error('Generated lesson is incomplete and cannot enter the public corpus')
  if (!result.hasCover || !result.hasOfficialRulebook) throw new Error('Public lesson is missing its cover or official rulebook link')
  if (!result.hasEnglishLocalization) throw new Error('Public lesson is missing its English localization')
  await checkpoint(outputPath, state, { result })
  progress(state, `公开讲解可读：${result.sectionCount} 章、${result.stepCount} 步、${result.visualStepCount} 个同步局部图示`)
  return state
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) return usage()
  const result = await generatePublicCorpusEntry(options)
  console.log(JSON.stringify(result, null, 2))
}

const executedDirectly = process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url
if (executedDirectly) {
  main().catch((error) => {
    console.error(`CORPUS GENERATION FAILED: ${error.message}`)
    process.exitCode = 2
  })
}
