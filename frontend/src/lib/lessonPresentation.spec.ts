import { describe, expect, it } from 'vitest'

import { deduplicatePublicLessons, groupPlansForReading, playerFacingTitle, publicLessonTitle } from './lessonPresentation'

describe('playerFacingTitle', () => {
  it('normalizes uploaded filenames without guessing which title words should be deleted', () => {
    expect(playerFacingTitle('CATAN Base Game Rules Corpus Replay')).toBe('CATAN Base Game Rules Corpus Replay')
    expect(playerFacingTitle('Root: Learning to Play Rules')).toBe('Root: Learning to Play Rules')
    expect(playerFacingTitle('Atelier: The Painter\'s Studio Rules')).toBe("Atelier: The Painter's Studio Rules")
    expect(playerFacingTitle('wingspan_quick_start.pdf')).toBe('wingspan quick start')
    expect(playerFacingTitle('Rules')).toBe('Rules')
  })
})

describe('publicLessonTitle', () => {
  it('prefers an exact linked BGG identity over cover and uploaded filename', () => {
    expect(publicLessonTitle({
      rulebookTitle: 'wingspan_rules.pdf',
      gameCover: { gameName: 'Wingspan Rules' },
      publicGame: { name: '翼展' },
    })).toBe('翼展')
  })
})

describe('player-facing lesson groups', () => {
  it('deduplicates exact normalized identities without collapsing titles by keyword or punctuation', () => {
    const results = deduplicatePublicLessons([
      { id: 'older', rulebookTitle: 'Root', gameCover: null, sectionCount: 10, stepCount: 55 },
      { id: 'stronger', rulebookTitle: 'root', gameCover: { gameName: 'root' }, sectionCount: 14, stepCount: 63 },
      { id: 'learning-guide', rulebookTitle: 'Root: Learning to Play Rules', gameCover: null, sectionCount: 9, stepCount: 30 },
      { id: 'fort', rulebookTitle: 'Fort Rules', gameCover: null, sectionCount: 12, stepCount: 60 },
    ])

    expect(results).toEqual([
      expect.objectContaining({ title: 'root', lesson: expect.objectContaining({ id: 'stronger' }) }),
      expect.objectContaining({ title: 'Root: Learning to Play Rules', lesson: expect.objectContaining({ id: 'learning-guide' }) }),
      expect.objectContaining({ title: 'Fort Rules', lesson: expect.objectContaining({ id: 'fort' }) }),
    ])
  })

  it('groups repeated titles in a short continuation list but leaves each plan intact', () => {
    const plans = groupPlansForReading([
      { id: 'one', gameTitle: 'CATAN' },
      { id: 'two', gameTitle: 'Catan' },
      { id: 'three', gameTitle: 'Root Rules' },
    ])

    expect(plans).toEqual([
      expect.objectContaining({ title: 'CATAN', count: 2, plan: expect.objectContaining({ id: 'one' }) }),
      expect.objectContaining({ title: 'Root Rules', count: 1, plan: expect.objectContaining({ id: 'three' }) }),
    ])
  })

  it('keeps versions of the same uploaded rulebook together and prefers the strongest continuation', () => {
    const plans = groupPlansForReading([
      { id: 'newer-planned', documentVersionId: 'rulebook-1', gameTitle: 'Ahoy Rules', createdAt: '2026-07-24T09:00:00Z' },
      { id: 'older-readable', documentVersionId: 'rulebook-1', gameTitle: 'Ahoy Rules', createdAt: '2026-07-23T09:00:00Z' },
      { id: 'separate-upload', documentVersionId: 'rulebook-2', gameTitle: 'Ahoy Rules', createdAt: '2026-07-24T10:00:00Z' },
    ], (plan) => plan.id === 'older-readable' ? 10 : 0)

    expect(plans).toEqual([
      expect.objectContaining({ title: 'Ahoy Rules', count: 2, plan: expect.objectContaining({ id: 'older-readable' }) }),
      expect.objectContaining({ title: 'Ahoy Rules', count: 1, plan: expect.objectContaining({ id: 'separate-upload' }) }),
    ])
    expect(plans[0]?.plans).toHaveLength(2)
  })
})
