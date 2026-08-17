const QUESTION_PREFIX = '请根据当前规则版本解释这张卡牌如何执行：\n'

export function normalizeCardText(value: string) {
  return value
    .replaceAll('\u0000', '')
    .split(/\r?\n/)
    .map((line) => line.replace(/\s+/g, ' ').trim())
    .filter(Boolean)
    .join('\n')
    .trim()
}

export function buildCardQuestion(recognizedText: string, prefix = QUESTION_PREFIX) {
  const cardText = normalizeCardText(recognizedText)
  return cardText ? `${prefix}${cardText}` : ''
}
