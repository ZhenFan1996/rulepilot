import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { evaluateProduct, loadEvaluationBundle } from './product-evaluation/evaluator.mjs'
import { EvaluationInputError, inputError } from './product-evaluation/input.mjs'

function seconds(milliseconds) {
  return `${(milliseconds / 1000).toFixed(milliseconds % 1000 === 0 ? 0 : 1)}s`
}

function printHuman(report, outputPath) {
  const prefix = report.status === 'PASS' ? 'PASS' : report.status === 'NEEDS_REVIEW' ? 'REVIEW' : 'FAIL'
  console.log(`${prefix} product evaluation: ${report.dataset.name}`)
  console.log(`  Input: ${report.artifacts.rulebookSha256}`)
  console.log(`  First useful content: ${seconds(report.metrics.firstUsefulContentMs)}`)
  console.log(`  Complete base lesson: ${seconds(report.metrics.completeBaseLessonMs)}`)
  console.log(`  Cost: ${report.metrics.modelCalls} model calls, ${report.metrics.toolCalls} tool calls, ${report.metrics.estimatedTokens} estimated tokens`)
  console.log(`  Lesson: ${report.metrics.sectionCount} sections, ${report.metrics.stepCount} steps, ${report.metrics.citationCoveragePercent}% cited`)
  console.log(`  Player tasks: ${report.playerTasks.machinePassed}/${report.playerTasks.total} machine-ready, ${report.playerTasks.playerCanDo}/${report.playerTasks.total} recorded CAN_DO`)
  console.log(report.visuals.applicability === 'NOT_APPLICABLE'
    ? `  Visuals: not applicable (${report.visuals.reason})`
    : `  Visuals: ${report.visuals.passed}/${report.visuals.total} machine-valid, ${report.visuals.playerHelpful}/${report.visuals.total} recorded helpful`)
  report.checks.filter((item) => item.status !== 'PASS').forEach((item) => {
    console.log(`  ${item.status} [${item.stage}] ${item.id}: ${item.detail}`)
  })
  if (outputPath) console.log(`  Report: ${outputPath}`)
}

function parseArguments(argv) {
  const options = { dataset: process.env.PRODUCT_EVAL_DATASET, output: process.env.PRODUCT_EVAL_OUTPUT, json: false }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--dataset') options.dataset = argv[++index]
    else if (argument === '--output') options.output = argv[++index]
    else if (argument === '--json') options.json = true
    else if (argument === '--help') options.help = true
    else inputError(`unknown argument ${argument}`)
  }
  return options
}

function usage() {
  console.log('Usage: node scripts/evaluate-product.mjs --dataset <external-dataset.json> [--output <report.json>] [--json]')
  console.log('The dataset references a local rulebook, lesson snapshot, and execution snapshot. Rulebook contents are never copied into the report.')
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) return usage()
  if (!options.dataset) inputError('--dataset is required')
  const report = evaluateProduct(await loadEvaluationBundle(options.dataset))
  let outputPath = null
  if (options.output) {
    outputPath = resolve(options.output)
    await mkdir(dirname(outputPath), { recursive: true })
    await writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  }
  if (options.json) console.log(JSON.stringify(report, null, 2))
  else printHuman(report, outputPath)
  if (report.status === 'FAIL') process.exitCode = 1
}

const executedDirectly = process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url
if (executedDirectly) {
  main().catch((error) => {
    console.error(`${error instanceof EvaluationInputError ? 'INPUT' : 'ERROR'} ${error.message}`)
    process.exitCode = 2
  })
}
