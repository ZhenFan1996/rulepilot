import type { AppLocale } from '@/lib/locale'

const englishFunctionWords = new Set([
  'a', 'an', 'and', 'are', 'can', 'do', 'does', 'for', 'how', 'i', 'in', 'is', 'it', 'of', 'or',
  'should', 'the', 'this', 'to', 'we', 'what', 'when', 'where', 'which', 'why', 'with', 'would', 'you',
])

/** Chooses the reply language from the current player turn; UI locale is only the ambiguity fallback. */
export function playerTurnLocale(text: string, fallback: AppLocale): AppLocale {
  const normalized = text.normalize('NFKC')
  let hanCharacters = 0
  const latinWords: string[] = []
  let currentLatinWord = ''

  for (const character of normalized) {
    const codePoint = character.codePointAt(0) ?? 0
    if (isHanCodePoint(codePoint)) hanCharacters += 1
    if (isAsciiLatinLetter(codePoint)) {
      currentLatinWord += character.toLowerCase()
    } else if (currentLatinWord) {
      latinWords.push(currentLatinWord)
      currentLatinWord = ''
    }
  }
  if (currentLatinWord) latinWords.push(currentLatinWord)
  const englishSignals = latinWords.filter(word => englishFunctionWords.has(word)).length

  if (englishSignals >= 2) return 'en'
  if (englishSignals >= 1 && latinWords.length >= 2 && hanCharacters <= 1) return 'en'
  if (hanCharacters >= 4 && (latinWords.length <= 3 || hanCharacters >= latinWords.length)) return 'zh-CN'
  if (latinWords.length >= 4 && hanCharacters <= 4) return 'en'
  if (hanCharacters >= 2 && latinWords.length === 0) return 'zh-CN'
  return fallback
}

function isAsciiLatinLetter(codePoint: number) {
  return codePoint >= 0x41 && codePoint <= 0x5a || codePoint >= 0x61 && codePoint <= 0x7a
}

function isHanCodePoint(codePoint: number) {
  return codePoint >= 0x3400 && codePoint <= 0x4dbf
    || codePoint >= 0x4e00 && codePoint <= 0x9fff
    || codePoint >= 0x20000 && codePoint <= 0x2fa1f
}
