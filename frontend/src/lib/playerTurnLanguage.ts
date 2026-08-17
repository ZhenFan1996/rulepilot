import type { AppLocale } from '@/lib/locale'

/** Chooses the reply language from the current player turn; UI locale is only the ambiguity fallback. */
export function playerTurnLocale(text: string, fallback: AppLocale): AppLocale {
  const normalized = text.normalize('NFKC')
  let hanCharacters = 0
  const latinWords: string[] = []
  let currentLatinWord = ''
  let hasSentencePunctuation = false

  for (const character of normalized) {
    const codePoint = character.codePointAt(0) ?? 0
    if (isHanCodePoint(codePoint)) hanCharacters += 1
    if (isSentencePunctuation(codePoint)) hasSentencePunctuation = true
    if (isAsciiLatinLetter(codePoint)) {
      currentLatinWord += character.toLowerCase()
    } else if (currentLatinWord) {
      latinWords.push(currentLatinWord)
      currentLatinWord = ''
    }
  }
  if (currentLatinWord) latinWords.push(currentLatinWord)

  // A short, unpunctuated Latin fragment is commonly a title, person, card, or component name. It does not carry
  // enough language signal to switch an otherwise Chinese conversation; complete sentences still do.
  if (hanCharacters === 0 && latinWords.length >= 2 && hasSentencePunctuation) return 'en'
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

function isSentencePunctuation(codePoint: number) {
  return codePoint === 0x21
    || codePoint === 0x2e
    || codePoint === 0x3f
    || codePoint === 0x3002
    || codePoint === 0xff01
    || codePoint === 0xff1f
}
