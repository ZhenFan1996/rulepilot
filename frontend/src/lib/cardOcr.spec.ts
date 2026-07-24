import { describe, expect, it } from 'vitest'

import { buildCardQuestion, normalizeCardText } from './cardOcr'

describe('card OCR text', () => {
  it('removes blank lines and normalizes spacing without flattening the card', () => {
    expect(normalizeCardText('  Lantern   Keeper\n\nMove  2 spaces\r\n')).toBe(
      'Lantern Keeper\nMove 2 spaces',
    )
  })

  it('turns confirmed OCR text into an explicit grounded question', () => {
    expect(buildCardQuestion('Gain 2 light.')).toBe(
      '请根据当前规则版本解释这张卡牌在本节中如何执行：\nGain 2 light.',
    )
  })

  it('keeps the grounding instruction in the player-selected language', () => {
    expect(buildCardQuestion('Gain 2 light.', 'Explain how this card is resolved in this chapter using the current rulebook version:\n')).toBe(
      'Explain how this card is resolved in this chapter using the current rulebook version:\nGain 2 light.',
    )
  })

  it('limits untrusted OCR text before it enters the question form', () => {
    expect(normalizeCardText('a'.repeat(900))).toHaveLength(620)
  })
})
