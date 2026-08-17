import { describe, expect, it } from 'vitest'

import { playerTurnLocale } from './playerTurnLanguage'

describe('playerTurnLocale', () => {
  it.each([
    ['Can three players finish this in 60–90 minutes?', 'zh-CN', 'en'],
    ['When zorq?', 'zh-CN', 'en'],
    ['Is《卡卡颂》good for two players, and what is the tradeoff?', 'zh-CN', 'en'],
    ['请比较 Concordia 和 Lorenzo，哪个更适合 4 人？', 'en', 'zh-CN'],
    ['请问 cobalt spindle 在哪？', 'en', 'zh-CN'],
    ['这两款里哪一款互动更直接？', 'en', 'zh-CN'],
  ] as const)('uses the current player turn instead of the UI locale for %s', (text, fallback, expected) => {
    expect(playerTurnLocale(text, fallback)).toBe(expected)
  })

  it('uses the UI locale for a title or otherwise ambiguous fragment without a vocabulary list', () => {
    expect(playerTurnLocale('Concordia', 'zh-CN')).toBe('zh-CN')
    expect(playerTurnLocale('Concordia', 'en')).toBe('en')
    expect(playerTurnLocale('Mosaic Field', 'zh-CN')).toBe('zh-CN')
    expect(playerTurnLocale('Castles of Burgundy', 'zh-CN')).toBe('zh-CN')
  })
})
