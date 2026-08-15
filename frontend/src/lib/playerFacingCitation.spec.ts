import { describe, expect, it } from 'vitest'

import { playerFacingCitationExcerpt } from './playerFacingCitation'

describe('playerFacingCitationExcerpt', () => {
  it('removes the legacy visual-evidence instruction envelope while preserving the rule fact', () => {
    expect(playerFacingCitationExcerpt(
      'Visual-transcribed rule evidence. Only the statements under Visible rule facts are rule evidence. '
        + 'Do not derive a per-item value from a worked total.\nVisible rule facts: Each completed goal card scores 2 points.',
    )).toBe('Each completed goal card scores 2 points.')
  })

  it('keeps extracted page text but removes internal presentation instructions', () => {
    expect(playerFacingCitationExcerpt(
      'Visual page facts (literal observations only; verify rules against the cited page).\n'
        + 'Visible facts: Two cards are shown.\n\n'
        + 'Extracted page text (may omit inline visual symbols):\nScore every completed card.',
    )).toBe('Two cards are shown.\n\nScore every completed card.')
  })

  it('does not rewrite ordinary source excerpts', () => {
    expect(playerFacingCitationExcerpt('Score every completed card.')).toBe('Score every completed card.')
    expect(playerFacingCitationExcerpt('Section: Visible facts: score every completed card.'))
      .toBe('Section: Visible facts: score every completed card.')
  })
})
