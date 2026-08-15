interface AnswerCopy {
  shortVerdict: string
  explanation: string
}

interface WalkthroughStep {
  instruction: string
  explanation: string
  orderBasis: 'RULE_ORDER' | 'EXPLANATION_ORDER'
}

interface AnswerWithWalkthrough extends AnswerCopy {
  walkthroughSteps?: WalkthroughStep[]
}

const segmenter = new Intl.Segmenter('und', { granularity: 'word' })

const STOP_UNITS = new Set([
  'a', 'an', 'and', 'are', 'as', 'at', 'be', 'by', 'for', 'from', 'game', 'in', 'is', 'it', 'its',
  'of', 'on', 'rule', 'rulebook', 'rules', 's', 'that', 'the', 'this', 'to', 'turn', 'you', 'your',
  '也', '了', '你', '到', '如果', '就', '就把', '已', '当', '把', '按', '时', '时候', '明确', '游戏',
  '玩家', '的', '规则', '规则书', '规定', '说明', '轮到', '进行', '这', '这个', '该', '那么', '并',
])

const FACT_MARKERS: ReadonlyArray<readonly [string, RegExp]> = [
  ['condition', /(?:如果|当.+时|时(?:候)?|\bif\b|\bwhen\b)/iu],
  ['only', /(?:只有|仅限|只能|\bonly\b)/iu],
  ['unless', /(?:除非|\bunless\b|\bexcept\b)/iu],
  ['required', /(?:必须|务必|\bmust\b|\brequired\b)/iu],
  ['prohibited', /(?:不得|禁止|不能|\bcannot\b|\bmust not\b|\bmay not\b)/iu],
  ['optional', /(?:可以|可选择|\bmay\b|\boptional\b)/iu],
  ['maximum', /(?:最多|至多|不超过|\bat most\b|\bno more than\b)/iu],
  ['minimum', /(?:至少|不得少于|\bat least\b|\bno fewer than\b)/iu],
  ['before', /(?:之前|以前|\bbefore\b|\bfirst\b)/iu],
  ['after', /(?:之后|以后|然后|随后|\bafter\b|\bthen\b)/iu],
  ['each', /(?:每(?:张|个|枚|次|轮|回合|位|名|座|块|条|份)|\beach\b|\bper\b)/iu],
  ['all', /(?:全部|所有|\ball\b|\bevery\b)/iu],
]

function canonicalText(value: string) {
  return value.normalize('NFKC').toLocaleLowerCase().replace(/[’']/gu, "'")
}

function comparisonText(value: string) {
  return canonicalText(value)
    .replace(/(?:也就是说|换句话说|简单来说|规则书(?:明确)?(?:说明|规定|要求)|the rulebook (?:says|states|requires))/giu, ' ')
    .replace(/[^\p{Letter}\p{Number}]+/gu, '')
}

function factTokens(value: string) {
  const canonical = canonicalText(value)
  const facts = new Set<string>()
  for (const [name, pattern] of FACT_MARKERS) {
    if (pattern.test(canonical)) facts.add(name)
  }
  for (const match of canonical.matchAll(/\d+(?:[.,]\d+)?|[零〇一二两三四五六七八九十百千万]+(?:张|个|枚|次|分|轮|回合|位|名|座|块|条|份)|[×÷+*/%=<>≤≥-]/gu)) {
    facts.add(`value:${match[0]}`)
  }
  return facts
}

function contentUnits(value: string) {
  const units = new Set<string>()
  for (const part of segmenter.segment(canonicalText(value))) {
    if (!part.isWordLike) continue
    const unit = part.segment.replace(/'s$/u, '')
    if (!unit || STOP_UNITS.has(unit)) continue
    if ([...unit].length === 1 && !/\d/u.test(unit)) continue
    units.add(unit)
  }
  return units
}

function isSubset(candidate: Set<string>, reference: Set<string>) {
  return [...candidate].every(value => reference.has(value))
}

function addsMaterialDetail(candidate: string, anchors: string[]) {
  const trimmed = candidate.trim()
  if (!trimmed) return false

  const candidateText = comparisonText(trimmed)
  const anchorText = comparisonText(anchors.join(' '))
  if (!candidateText) return false
  if (candidateText === anchorText || anchorText.includes(candidateText)) return false

  const candidateFacts = factTokens(trimmed)
  const anchorFacts = factTokens(anchors.join(' '))
  if (!isSubset(candidateFacts, anchorFacts)) return true

  if (candidateText.includes(anchorText)) {
    const extraLength = candidateText.length - anchorText.length
    if (extraLength <= Math.max(8, Math.floor(candidateText.length * 0.18))) return false
  }

  const candidateUnits = contentUnits(trimmed)
  const anchorUnits = contentUnits(anchors.join(' '))
  if (candidateUnits.size === 0) return false
  const newUnits = [...candidateUnits].filter(unit => !anchorUnits.has(unit))
  return newUnits.length >= 2 || newUnits.length / candidateUnits.size > 0.25
}

/**
 * Returns only prose that adds a usable rule detail after the standalone verdict.
 * Conditions and quantities are deliberately treated as material so presentation
 * cleanup cannot make a ruling broader than the evidence-backed answer.
 */
export function playerFacingExplanation(answer: AnswerCopy) {
  return addsMaterialDetail(answer.explanation, [answer.shortVerdict])
    ? answer.explanation.trim()
    : ''
}

/**
 * A procedure is atomic for presentation: either the complete sequence adds detail
 * and remains visible, or the whole duplicate sequence is omitted. We never drop
 * individual steps and accidentally publish a truncated procedure.
 */
export function playerFacingWalkthroughSteps(answer: AnswerWithWalkthrough) {
  const steps = answer.walkthroughSteps ?? []
  if (steps.length === 0) return []
  const sequence = steps.map(step => `${step.instruction} ${step.explanation}`).join(' ')
  return addsMaterialDetail(sequence, [answer.shortVerdict, answer.explanation]) ? steps : []
}
