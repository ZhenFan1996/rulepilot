import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'

export const DATASET_SCHEMA = 'rulepilot.product-evaluation/v1'
export const EXECUTION_SCHEMA = 'rulepilot.product-execution/v1'
export const REPORT_SCHEMA = 'rulepilot.product-evaluation-report/v1'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SHA256_PATTERN = /^[0-9a-f]{64}$/

export class EvaluationInputError extends Error {}

export function inputError(message) {
  throw new EvaluationInputError(message)
}

export function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

export function object(value, label) {
  if (!isObject(value)) inputError(`${label} must be an object`)
  return value
}

export function array(value, label) {
  if (!Array.isArray(value)) inputError(`${label} must be an array`)
  return value
}

export function nonEmptyArray(value, label) {
  const result = array(value, label)
  if (result.length === 0) inputError(`${label} must not be empty`)
  return result
}

export function string(value, label) {
  if (typeof value !== 'string' || value.trim() === '') inputError(`${label} must be a non-empty string`)
  return value.trim()
}

export function boundedString(value, label, maximumLength) {
  const result = string(value, label)
  if (result.length > maximumLength) inputError(`${label} must contain at most ${maximumLength} characters`)
  return result
}

export function number(value, label, minimum = 0) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum) {
    inputError(`${label} must be a number >= ${minimum}`)
  }
  return value
}

export function uuid(value, label) {
  const result = string(value, label)
  if (!UUID_PATTERN.test(result)) inputError(`${label} must be a UUID`)
  return result
}

export function sha256(value, label) {
  const result = string(value, label).toLowerCase()
  if (!SHA256_PATTERN.test(result)) inputError(`${label} must be a lowercase SHA-256 digest`)
  return result
}

function digest(content) {
  return createHash('sha256').update(content).digest('hex')
}

async function limitedRead(path, label, maximumBytes) {
  const metadata = await stat(path).catch(() => inputError(`${label} does not exist: ${path}`))
  if (!metadata.isFile()) inputError(`${label} must be a file: ${path}`)
  if (metadata.size > maximumBytes) inputError(`${label} exceeds ${maximumBytes} bytes`)
  return readFile(path)
}

function parseJson(content, label) {
  try {
    return JSON.parse(content.toString('utf8'))
  } catch (error) {
    inputError(`${label} is not valid JSON: ${error.message}`)
  }
}

export function includesAny(text, terms) {
  const source = String(text ?? '').normalize('NFKC').toLocaleLowerCase('en-US').replace(/\s+/g, ' ').trim()
  return nonEmptyArray(terms, 'term group').some((term) => {
    const expected = string(term, 'term').normalize('NFKC').toLocaleLowerCase('en-US')
    return source.includes(expected)
  })
}

export function cited(step) {
  return Array.isArray(step.sourcePages) && step.sourcePages.length > 0
    && Array.isArray(step.sourceChunkIds) && step.sourceChunkIds.length > 0
}

export function elapsedMillis(start, end, label) {
  const startMillis = Date.parse(string(start, `${label}.startedAt`))
  const endMillis = Date.parse(string(end, `${label}.endedAt`))
  if (!Number.isFinite(startMillis) || !Number.isFinite(endMillis) || endMillis < startMillis) {
    inputError(`${label} timestamps are invalid or out of order`)
  }
  return endMillis - startMillis
}

export function check(id, stage, status, actual, expected, detail) {
  return { id, stage, status, actual, expected, detail }
}

export async function loadEvaluationBundle(datasetPath) {
  const absoluteDatasetPath = resolve(datasetPath)
  const datasetBytes = await limitedRead(absoluteDatasetPath, 'dataset', 1_000_000)
  const dataset = object(parseJson(datasetBytes, 'dataset'), 'dataset')
  if (dataset.schemaVersion !== DATASET_SCHEMA) {
    inputError(`dataset.schemaVersion must be ${DATASET_SCHEMA}`)
  }
  const artifacts = object(dataset.artifacts, 'dataset.artifacts')
  const base = dirname(absoluteDatasetPath)
  const sourcePath = resolve(base, string(artifacts.rulebook, 'dataset.artifacts.rulebook'))
  const lessonPath = resolve(base, string(artifacts.lesson, 'dataset.artifacts.lesson'))
  const executionPath = resolve(base, string(artifacts.execution, 'dataset.artifacts.execution'))
  const [sourceBytes, lessonBytes, executionBytes] = await Promise.all([
    limitedRead(sourcePath, 'rulebook artifact', 100_000_000),
    limitedRead(lessonPath, 'lesson artifact', 20_000_000),
    limitedRead(executionPath, 'execution artifact', 1_000_000),
  ])
  return {
    dataset,
    lesson: object(parseJson(lessonBytes, 'lesson artifact'), 'lesson artifact'),
    execution: object(parseJson(executionBytes, 'execution artifact'), 'execution artifact'),
    digests: {
      dataset: digest(datasetBytes),
      rulebook: digest(sourceBytes),
      lesson: digest(lessonBytes),
      execution: digest(executionBytes),
    },
  }
}
