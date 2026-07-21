import {
  EXECUTION_SCHEMA,
  REPORT_SCHEMA,
  array,
  boundedString,
  check,
  cited,
  elapsedMillis,
  includesAny,
  inputError,
  isObject,
  nonEmptyArray,
  number,
  object,
  sha256,
  string,
  uuid,
} from './input.mjs'

const MOVE_KINDS = new Set(['UNDERSTAND', 'DO', 'EXAMPLE', 'WATCH', 'CHECK', 'VISUAL', 'FLOW', 'LEDGER'])
const PLAYER_RESULTS = new Set(['CAN_DO', 'NEEDS_HELP', 'NOT_RUN'])
const VISUAL_RESULTS = new Set(['HELPFUL', 'NOT_HELPFUL', 'NOT_RUN'])
const ROLE_MODES = new Set(['REAL', 'DETERMINISTIC', 'FAKE', 'NOT_CONFIGURED'])

function matchingSections(lesson, coverageTagsAny) {
  const expected = new Set(nonEmptyArray(coverageTagsAny, 'coverageTagsAny').map((tag) => string(tag, 'coverage tag')))
  return lesson.sections.filter((section) => Array.isArray(section.coverageTags)
    && section.coverageTags.some((tag) => expected.has(tag)))
}

function conceptMatchesStep(concept, step) {
  if (!cited(step)) return false
  const text = `${step.heading ?? ''} ${step.text ?? ''}`
  const termGroups = nonEmptyArray(concept.termGroups, `concept ${concept.id}.termGroups`)
  if (!termGroups.every((group) => includesAny(text, group))) return false
  const pages = concept.requiredPages ?? []
  return pages.length === 0 || pages.some((page) => step.sourcePages.includes(page))
}

function assessmentOf(value, label, acceptedResults) {
  const assessment = value === undefined ? { result: 'NOT_RUN' } : object(value, label)
  const result = assessment.result ?? 'NOT_RUN'
  if (!acceptedResults.has(result)) inputError(`${label} has invalid result ${result}`)
  return {
    result,
    method: assessment.method ? string(assessment.method, `${label} method`) : null,
    note: assessment.note ? string(assessment.note, `${label} note`) : null,
  }
}

function evaluateTask(task, lesson) {
  const id = string(task.id, 'task.id')
  const label = string(task.label, `task ${id}.label`)
  const sections = matchingSections(lesson, task.coverageTagsAny)
  const steps = sections.flatMap((section) => array(section.steps, `section ${section.topicKey}.steps`))
  const citedSteps = steps.filter(cited)
  const minimumCitedSteps = number(task.minimumCitedSteps, `task ${id}.minimumCitedSteps`, 1)
  const missingMoveGroups = nonEmptyArray(task.requiredMoveGroups, `task ${id}.requiredMoveGroups`)
    .map((group, index) => {
      const kinds = nonEmptyArray(group, `task ${id}.requiredMoveGroups[${index}]`)
        .map((kind) => string(kind, 'move kind'))
      kinds.forEach((kind) => {
        if (!MOVE_KINDS.has(kind)) inputError(`task ${id} contains unknown move kind ${kind}`)
      })
      return citedSteps.some((step) => kinds.includes(step.kind)) ? null : kinds
    })
    .filter(Boolean)
  const missingConcepts = nonEmptyArray(task.concepts, `task ${id}.concepts`)
    .filter((concept) => {
      object(concept, `task ${id} concept`)
      string(concept.id, `task ${id} concept.id`)
      if (concept.requiredPages !== undefined) {
        nonEmptyArray(concept.requiredPages, `concept ${concept.id}.requiredPages`)
          .forEach((page) => number(page, `concept ${concept.id} page`, 1))
      }
      return !citedSteps.some((step) => conceptMatchesStep(concept, step))
    })
    .map((concept) => concept.id)
  return {
    id,
    label,
    machineStatus: sections.length > 0 && citedSteps.length >= minimumCitedSteps
      && missingMoveGroups.length === 0 && missingConcepts.length === 0 ? 'PASS' : 'FAIL',
    matchingSectionCount: sections.length,
    citedStepCount: citedSteps.length,
    missingMoveGroups,
    missingConcepts,
    playerAssessment: assessmentOf(task.playerAssessment, `task ${id}.playerAssessment`, PLAYER_RESULTS),
  }
}

function evaluateVisualCheck(visualCheck, lesson) {
  const id = string(visualCheck.id, 'visualCheck.id')
  const label = string(visualCheck.label, `visualCheck ${id}.label`)
  const candidates = matchingSections(lesson, visualCheck.coverageTagsAny)
    .flatMap((section) => array(section.steps, `section ${section.topicKey}.steps`))
    .filter((step) => step.kind === 'VISUAL' && cited(step) && isObject(step.visualFocus))
  const expectedPages = nonEmptyArray(visualCheck.expectedPages, `visualCheck ${id}.expectedPages`)
  const termGroups = nonEmptyArray(visualCheck.termGroups, `visualCheck ${id}.termGroups`)
  const matched = candidates.find((step) => {
    const focus = step.visualFocus
    const content = `${focus.label ?? ''} ${step.heading ?? ''} ${step.text ?? ''}`
    return expectedPages.includes(focus.pageNumber) && step.sourcePages.includes(focus.pageNumber)
      && termGroups.every((group) => includesAny(content, group))
  })
  return {
    id,
    label,
    machineStatus: matched ? 'PASS' : 'FAIL',
    matchedPage: matched?.visualFocus?.pageNumber ?? null,
    cropIdentity: matched ? cropIdentity(matched.visualFocus) : null,
    playerAssessment: assessmentOf(
      visualCheck.playerAssessment,
      `visualCheck ${id}.playerAssessment`,
      VISUAL_RESULTS,
    ),
  }
}

function cropIdentity(focus) {
  return [focus.pageNumber, focus.x, focus.y, focus.width, focus.height].join(':')
}

function visualBenchmark(visualEvaluation, applicability) {
  const minimumRatedCrops = visualEvaluation.minimumRatedCrops
  const minimumHelpfulPercent = visualEvaluation.minimumHelpfulPercent
  if (minimumRatedCrops === undefined && minimumHelpfulPercent === undefined) return null
  if (applicability !== 'REQUIRED' || minimumRatedCrops === undefined || minimumHelpfulPercent === undefined) {
    inputError('visual benchmark requires REQUIRED applicability, minimumRatedCrops, and minimumHelpfulPercent')
  }
  const rated = number(minimumRatedCrops, 'visualEvaluation.minimumRatedCrops', 1)
  const helpful = number(minimumHelpfulPercent, 'visualEvaluation.minimumHelpfulPercent')
  if (!Number.isInteger(rated)) inputError('visualEvaluation.minimumRatedCrops must be an integer')
  if (helpful > 100) inputError('visualEvaluation.minimumHelpfulPercent must be <= 100')
  return { minimumRatedCrops: rated, minimumHelpfulPercent: helpful }
}

function ratedVisualCrops(visualResults) {
  const crops = new Map()
  visualResults.forEach((result) => {
    if (result.cropIdentity === null || result.playerAssessment.result === 'NOT_RUN') return
    const previous = crops.get(result.cropIdentity)
    if (previous && previous !== result.playerAssessment.result) {
      inputError(`visual crop ${result.cropIdentity} has conflicting player assessments`)
    }
    crops.set(result.cropIdentity, result.playerAssessment.result)
  })
  return [...crops.values()]
}

function validateRoles(roles) {
  return ['teaching', 'visual', 'answer', 'critic'].map((name) => {
    const role = object(roles[name], `execution.models.${name}`)
    const mode = string(role.mode, `execution.models.${name}.mode`)
    if (!ROLE_MODES.has(mode)) inputError(`execution.models.${name}.mode is invalid`)
    return {
      role: name,
      mode,
      provider: string(role.provider, `execution.models.${name}.provider`),
      model: string(role.model, `execution.models.${name}.model`),
    }
  })
}

function taskChecks(taskResults) {
  return [
    ...taskResults.map((task) => check(`task-${task.id}`, 'TEACHING', task.machineStatus,
      { citedSteps: task.citedStepCount, missingMoves: task.missingMoveGroups, missingConcepts: task.missingConcepts },
      'all required moves and document-specific concepts', task.label)),
    ...taskResults.map((task) => check(`player-${task.id}`, 'PLAYER',
      task.playerAssessment.result === 'CAN_DO' ? 'PASS' : task.playerAssessment.result === 'NOT_RUN' ? 'NOT_EVALUATED' : 'FAIL',
      task.playerAssessment.result, 'CAN_DO', task.label)),
  ]
}

function visualChecks(visualResults, benchmark, ratedCrops) {
  const checks = [
    ...visualResults.map((item) => check(`visual-${item.id}`, 'VISUAL', item.machineStatus,
      item.matchedPage, 'a cited matching crop on an expected page', item.label)),
    ...visualResults.map((item) => check(`visual-player-${item.id}`, 'PLAYER',
      item.playerAssessment.result === 'HELPFUL' ? 'PASS' : item.playerAssessment.result === 'NOT_RUN' ? 'NOT_EVALUATED' : 'FAIL',
      item.playerAssessment.result, 'HELPFUL', item.label)),
  ]
  if (benchmark === null) return checks
  const helpfulPercent = ratedCrops.length === 0 ? 0 : ratedCrops.filter((result) => result === 'HELPFUL').length * 100 / ratedCrops.length
  const enoughSamples = ratedCrops.length >= benchmark.minimumRatedCrops
  checks.push(check('visual-rated-sample', 'VISUAL', enoughSamples ? 'PASS' : 'NOT_EVALUATED',
    ratedCrops.length, benchmark.minimumRatedCrops, 'Only unique, manually rated visual crops count toward the benchmark.'))
  checks.push(check('visual-helpfulness-rate', 'VISUAL', !enoughSamples ? 'NOT_EVALUATED'
    : helpfulPercent >= benchmark.minimumHelpfulPercent ? 'PASS' : 'FAIL',
  Math.round(helpfulPercent * 100) / 100, benchmark.minimumHelpfulPercent,
  'The useful-crop rate is computed from unique manually rated crops.'))
  return checks
}

export function evaluateProduct(bundle) {
  const dataset = object(bundle.dataset, 'dataset')
  const lesson = object(bundle.lesson, 'lesson')
  const execution = object(bundle.execution, 'execution')
  if (execution.schemaVersion !== EXECUTION_SCHEMA) inputError(`execution.schemaVersion must be ${EXECUTION_SCHEMA}`)
  const digests = object(bundle.digests, 'digests')
  const thresholds = object(dataset.thresholds, 'dataset.thresholds')
  const sections = nonEmptyArray(lesson.sections, 'lesson.sections')
  const allSteps = sections.flatMap((section) => array(section.steps, `section ${section.topicKey}.steps`))
  const citedSteps = allSteps.filter(cited)
  const citationCoveragePercent = allSteps.length === 0 ? 0 : citedSteps.length * 100 / allSteps.length
  const lessonId = uuid(lesson.id, 'lesson.id')
  const planId = uuid(lesson.teachingPlanId, 'lesson.teachingPlanId')
  const runId = uuid(execution.runId, 'execution.runId')
  const identityMatches = lessonId === uuid(execution.lessonId, 'execution.lessonId')
    && planId === uuid(execution.teachingPlanId, 'execution.teachingPlanId')
  const versions = object(execution.versions, 'execution.versions')
  const promptVersions = object(versions.prompts, 'execution.versions.prompts')
  const normalizedPromptVersions = Object.fromEntries(Object.entries(promptVersions)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, version]) => [boundedString(name, 'prompt role', 40), boundedString(version, `prompt ${name} version`, 80)]))
  const roles = validateRoles(object(execution.models, 'execution.models'))
  const budget = object(execution.budget, 'execution.budget')
  const counts = object(execution.counts, 'execution.counts')
  const timings = object(execution.timings, 'execution.timings')
  const firstUsefulContentMs = elapsedMillis(timings.startedAt, timings.firstUsefulContentAt, 'first useful content')
  const completeBaseLessonMs = elapsedMillis(timings.startedAt, timings.baseLessonCompletedAt, 'base lesson')
  const modelCalls = number(counts.modelCalls, 'execution.counts.modelCalls')
  const toolCalls = number(counts.toolCalls, 'execution.counts.toolCalls')
  const estimatedTokens = number(counts.estimatedTokens, 'execution.counts.estimatedTokens')
  const limits = {
    first: number(thresholds.maximumFirstUsefulContentMs, 'thresholds.maximumFirstUsefulContentMs', 1),
    complete: number(thresholds.maximumCompleteBaseLessonMs, 'thresholds.maximumCompleteBaseLessonMs', 1),
    citation: number(thresholds.minimumCitationCoveragePercent, 'thresholds.minimumCitationCoveragePercent'),
    models: number(thresholds.maximumModelCalls, 'thresholds.maximumModelCalls', 1),
    tokens: number(thresholds.maximumEstimatedTokens, 'thresholds.maximumEstimatedTokens', 1),
  }
  if (limits.citation > 100) inputError('thresholds.minimumCitationCoveragePercent must be <= 100')
  const taskResults = nonEmptyArray(dataset.tasks, 'dataset.tasks').map((task) => evaluateTask(object(task, 'task'), lesson))
  if (new Set(taskResults.map((task) => task.id)).size !== taskResults.length) inputError('dataset task IDs must be unique')

  const visualEvaluation = object(dataset.visualEvaluation, 'dataset.visualEvaluation')
  const visualApplicability = string(visualEvaluation.applicability, 'visualEvaluation.applicability')
  if (!['REQUIRED', 'NOT_APPLICABLE'].includes(visualApplicability)) {
    inputError('visualEvaluation.applicability must be REQUIRED or NOT_APPLICABLE')
  }
  const configuredVisualChecks = array(visualEvaluation.checks, 'visualEvaluation.checks')
  if (visualApplicability === 'REQUIRED' && configuredVisualChecks.length === 0) {
    inputError('visualEvaluation.checks must not be empty when visual evaluation is required')
  }
  const visualReason = visualApplicability === 'NOT_APPLICABLE'
    ? string(visualEvaluation.reason, 'visualEvaluation.reason') : null
  const visualResults = configuredVisualChecks.map((item) => evaluateVisualCheck(object(item, 'visual check'), lesson))
  const benchmark = visualBenchmark(visualEvaluation, visualApplicability)
  const ratedCrops = ratedVisualCrops(visualResults)

  const checks = [
    check('source-hash', 'INPUT', digests.rulebook === sha256(dataset.sourceSha256, 'dataset.sourceSha256') ? 'PASS' : 'FAIL',
      digests.rulebook, dataset.sourceSha256, 'Rulebook input must match the evaluated dataset.'),
    check('artifact-identity', 'REPRODUCIBILITY', identityMatches ? 'PASS' : 'FAIL',
      `${lessonId}/${planId}`, `${execution.lessonId}/${execution.teachingPlanId}`, 'Lesson and execution identities must match.'),
    check('generator-version', 'REPRODUCIBILITY', lesson.generatorVersion === versions.generator ? 'PASS' : 'FAIL',
      lesson.generatorVersion, versions.generator, 'Lesson and execution generator versions must match.'),
    check('prompt-versions', 'REPRODUCIBILITY', Object.keys(normalizedPromptVersions).length > 0 ? 'PASS' : 'FAIL',
      Object.keys(normalizedPromptVersions), 'at least one prompt version', 'Prompt versions are recorded without prompt text.'),
    check('lesson-status', 'TEACHING', ['COMPLETE', 'DRAFT_READY'].includes(lesson.status) ? 'PASS' : 'FAIL',
      lesson.status, 'COMPLETE or DRAFT_READY', 'A partial lesson cannot satisfy the complete base-lesson gate.'),
    check('first-useful-content', 'GENERATION', firstUsefulContentMs <= limits.first ? 'PASS' : 'FAIL',
      firstUsefulContentMs, limits.first, 'Milliseconds from run start to first useful persisted content.'),
    check('complete-base-lesson', 'GENERATION', completeBaseLessonMs <= limits.complete ? 'PASS' : 'FAIL',
      completeBaseLessonMs, limits.complete, 'Milliseconds from run start to a complete cited base lesson.'),
    check('model-calls', 'GENERATION', modelCalls <= limits.models ? 'PASS' : 'FAIL',
      modelCalls, limits.models, 'Total model and critic calls recorded by the execution snapshot.'),
    check('estimated-tokens', 'GENERATION', estimatedTokens <= limits.tokens ? 'PASS' : 'FAIL',
      estimatedTokens, limits.tokens, 'Estimated input and output tokens recorded by the execution snapshot.'),
    check('citation-coverage', 'TEACHING', citationCoveragePercent >= limits.citation ? 'PASS' : 'FAIL',
      Math.round(citationCoveragePercent * 100) / 100, limits.citation,
      `${citedSteps.length}/${allSteps.length} steps contain both source pages and chunk IDs.`),
    ...taskChecks(taskResults),
    ...visualChecks(visualResults, benchmark, ratedCrops),
  ]
  appendOptionalChecks(checks, thresholds, versions, normalizedPromptVersions, roles)
  const failureStages = [...new Set(checks.filter((item) => item.status === 'FAIL').map((item) => item.stage))]
  const needsEvaluationStages = [...new Set(checks.filter((item) => item.status === 'NOT_EVALUATED').map((item) => item.stage))]
  const status = failureStages.length > 0 ? 'FAIL' : needsEvaluationStages.length > 0 ? 'NEEDS_REVIEW' : 'PASS'
  const openableArtifact = safeArtifact(execution.openableArtifact)

  return {
    schemaVersion: REPORT_SCHEMA,
    status,
    failureStages,
    needsEvaluationStages,
    dataset: { name: string(dataset.name, 'dataset.name'), sha256: digests.dataset },
    artifacts: { rulebookSha256: digests.rulebook, lessonSha256: digests.lesson, executionSha256: digests.execution,
      runId, teachingPlanId: planId, lessonId, openableArtifact },
    versions: { parser: string(versions.parser, 'execution.versions.parser'),
      generator: string(versions.generator, 'execution.versions.generator'), prompts: normalizedPromptVersions },
    models: roles,
    budget: { maxToolCalls: number(budget.maxToolCalls, 'execution.budget.maxToolCalls', 1),
      maxModelCalls: number(budget.maxModelCalls, 'execution.budget.maxModelCalls', 1),
      maxTokens: number(budget.maxTokens, 'execution.budget.maxTokens', 1),
      timeoutMs: number(budget.timeoutMs, 'execution.budget.timeoutMs', 1) },
    metrics: lessonMetrics(sections, allSteps, citedSteps, firstUsefulContentMs, completeBaseLessonMs,
      modelCalls, toolCalls, estimatedTokens, citationCoveragePercent),
    playerTasks: { machinePassed: taskResults.filter((task) => task.machineStatus === 'PASS').length,
      playerCanDo: taskResults.filter((task) => task.playerAssessment.result === 'CAN_DO').length,
      total: taskResults.length, results: taskResults },
    visuals: visualApplicability === 'NOT_APPLICABLE'
      ? { applicability: visualApplicability, reason: visualReason, passed: 0, total: 0, rated: 0, playerHelpful: 0,
          helpfulPercent: null, benchmark: null }
      : { applicability: visualApplicability, reason: null,
          passed: visualResults.filter((item) => item.machineStatus === 'PASS').length,
          total: visualResults.length,
          rated: ratedCrops.length,
          playerHelpful: ratedCrops.filter((result) => result === 'HELPFUL').length,
          helpfulPercent: ratedCrops.length === 0 ? null : Math.round(ratedCrops.filter((result) => result === 'HELPFUL').length * 10_000 / ratedCrops.length) / 100,
          benchmark },
    checks,
  }
}

function appendOptionalChecks(checks, thresholds, versions, promptVersions, roles) {
  if (thresholds.requireExactVersions === true) {
    const unrecorded = [versions.parser, versions.generator, ...Object.values(promptVersions)]
      .filter((value) => /(?:unknown|not[-_ ]recorded|legacy)/i.test(String(value)))
    checks.push(check('exact-version-metadata', 'REPRODUCIBILITY', unrecorded.length === 0 ? 'PASS' : 'FAIL',
      unrecorded, 'exact parser, generator, and prompt versions', 'Every behavior-affecting version must be recorded.'))
  }
  if (thresholds.forbidFakeRoles === true) {
    const fakeRoles = roles.filter((role) => role.mode === 'FAKE').map((role) => role.role)
    checks.push(check('model-role-readiness', 'REPRODUCIBILITY', fakeRoles.length === 0 ? 'PASS' : 'FAIL',
      fakeRoles, 'no FAKE roles', 'Fake roles invalidate a real product evaluation.'))
  }
}

function safeArtifact(value) {
  const artifact = object(value, 'execution.openableArtifact')
  const reference = boundedString(artifact.reference, 'execution.openableArtifact.reference', 500)
  if (/[?&](?:api[_-]?key|token|cookie|session)=/i.test(reference)) {
    inputError('execution.openableArtifact.reference must not contain credentials')
  }
  return { kind: boundedString(artifact.kind, 'execution.openableArtifact.kind', 40), reference }
}

function lessonMetrics(sections, steps, citedSteps, firstMs, completeMs, modelCalls, toolCalls, tokens, citedPercent) {
  return {
    firstUsefulContentMs: firstMs,
    completeBaseLessonMs: completeMs,
    modelCalls,
    toolCalls,
    estimatedTokens: tokens,
    sectionCount: sections.length,
    stepCount: steps.length,
    citedStepCount: citedSteps.length,
    citationCoveragePercent: Math.round(citedPercent * 100) / 100,
    visualStepCount: steps.filter((step) => step.kind === 'VISUAL').length,
    exampleStepCount: steps.filter((step) => step.kind === 'EXAMPLE').length,
    ledgerStepCount: steps.filter((step) => step.kind === 'LEDGER').length,
    checkStepCount: steps.filter((step) => step.kind === 'CHECK').length,
  }
}

export { loadEvaluationBundle } from './input.mjs'
