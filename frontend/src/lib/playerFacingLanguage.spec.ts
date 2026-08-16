import { describe, expect, it } from 'vitest'

import { playerFacingLanguageName } from './playerFacingLanguage'

describe('playerFacingLanguageName', () => {
  it.each([
    ['en', 'zh-CN', '英文'],
    ['en-US', 'en', 'English'],
    ['zh-CN', 'zh-CN', '简体中文'],
    ['zh-Hant', 'en', 'Traditional Chinese'],
    ['und', 'zh-CN', '语言未标注'],
    ['', 'en', 'Language not stated'],
    ['definitely not a language tag', 'zh-CN', '语言未标注'],
  ] as const)('presents %j in %s as %j', (language, locale, expected) => {
    expect(playerFacingLanguageName(language, locale)).toBe(expected)
  })

  it('uses a localized human name for another valid language instead of leaking its code', () => {
    expect(playerFacingLanguageName('de-DE', 'en')).toBe('German (Germany)')
    expect(playerFacingLanguageName('de-DE', 'zh-CN')).not.toContain('de-DE')
  })
})
