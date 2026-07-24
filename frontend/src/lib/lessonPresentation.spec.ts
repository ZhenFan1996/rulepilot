import { describe, expect, it } from 'vitest'

import { deduplicatePublicLessons, groupPlansForReading, playerFacingTitle } from './lessonPresentation'

describe('playerFacingTitle', () => {
  it('removes rulebook and internal replay suffixes without rewriting stored data', () => {
    expect(playerFacingTitle('CATAN Base Game Rules Corpus Replay')).toBe('CATAN')
    expect(playerFacingTitle('Root: Learning to Play Rules')).toBe('Root')
    expect(playerFacingTitle('Atelier: The Painter\'s Studio Rules')).toBe("Atelier: The Painter's Studio")
    expect(playerFacingTitle('Rules')).toBe('Rules')
  })
})

describe('player-facing lesson groups', () => {
  it('keeps one stronger public lesson per visible game without deleting any source record', () => {
    const results = deduplicatePublicLessons([
      { id: 'older', rulebookTitle: 'Root Learning to Play Corpus Replay', gameCover: null, sectionCount: 10, stepCount: 55 },
      { id: 'stronger', rulebookTitle: 'Root: Learning to Play Rules', gameCover: { gameName: 'Root: Learning to Play Rules' }, sectionCount: 14, stepCount: 63 },
      { id: 'fort', rulebookTitle: 'Fort Rules', gameCover: null, sectionCount: 12, stepCount: 60 },
    ])

    expect(results).toEqual([
      expect.objectContaining({ title: 'Root', lesson: expect.objectContaining({ id: 'stronger' }) }),
      expect.objectContaining({ title: 'Fort', lesson: expect.objectContaining({ id: 'fort' }) }),
    ])
  })

  it('groups repeated titles in a short continuation list but leaves each plan intact', () => {
    const plans = groupPlansForReading([
      { id: 'one', gameTitle: 'CATAN Base Game Rules Corpus Replay' },
      { id: 'two', gameTitle: 'Catan' },
      { id: 'three', gameTitle: 'Root Rules' },
    ])

    expect(plans).toEqual([
      expect.objectContaining({ title: 'CATAN', count: 2, plan: expect.objectContaining({ id: 'one' }) }),
      expect.objectContaining({ title: 'Root', count: 1, plan: expect.objectContaining({ id: 'three' }) }),
    ])
  })

  it('keeps versions of the same uploaded rulebook together and prefers the strongest continuation', () => {
    const plans = groupPlansForReading([
      { id: 'newer-planned', documentVersionId: 'rulebook-1', gameTitle: 'Ahoy Rules', createdAt: '2026-07-24T09:00:00Z' },
      { id: 'older-readable', documentVersionId: 'rulebook-1', gameTitle: 'Ahoy Rules', createdAt: '2026-07-23T09:00:00Z' },
      { id: 'separate-upload', documentVersionId: 'rulebook-2', gameTitle: 'Ahoy Rules', createdAt: '2026-07-24T10:00:00Z' },
    ], (plan) => plan.id === 'older-readable' ? 10 : 0)

    expect(plans).toEqual([
      expect.objectContaining({ title: 'Ahoy', count: 2, plan: expect.objectContaining({ id: 'older-readable' }) }),
      expect.objectContaining({ title: 'Ahoy', count: 1, plan: expect.objectContaining({ id: 'separate-upload' }) }),
    ])
    expect(plans[0]?.plans).toHaveLength(2)
  })
})
