const CARD_TEXT_LIMIT = 620
const QUESTION_PREFIX = '请根据当前规则版本解释这张卡牌在本节中如何执行：\n'

export function normalizeCardText(value: string, limit = CARD_TEXT_LIMIT) {
  const normalized = value
    .replaceAll('\u0000', '')
    .split(/\r?\n/)
    .map((line) => line.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .join('\n')
    .trim()

  if (normalized.length <= limit) return normalized
  return normalized.slice(0, limit).trimEnd()
}

export function buildCardQuestion(recognizedText: string) {
  const cardText = normalizeCardText(recognizedText)
  return cardText ? `${QUESTION_PREFIX}${cardText}` : ''
}
