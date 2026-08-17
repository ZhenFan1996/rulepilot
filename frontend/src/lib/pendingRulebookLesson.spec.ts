import { beforeEach, describe, expect, it } from 'vitest'

import {
  forgetPendingRulebookLesson,
  readPendingRulebookLessons,
  rememberPendingRulebookLesson,
} from './pendingRulebookLesson'

describe('pending rulebook lesson handoff', () => {
  beforeEach(() => localStorage.clear())

  it('keeps handoffs isolated by owner and replaces the same version', () => {
    rememberPendingRulebookLesson(localStorage, 'player', pending('version-1'))
    rememberPendingRulebookLesson(localStorage, 'player', { ...pending('version-1'), editionId: 'edition-2' })

    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{
      ...pending('version-1'), editionId: 'edition-2',
    }])
    expect(readPendingRulebookLessons(localStorage, 'other-player')).toEqual([])
  })

  it('rejects invalid handoffs without silently dropping older retained work', () => {
    for (let index = 0; index < 7; index += 1) {
      rememberPendingRulebookLesson(localStorage, 'player', pending(`version-${index}`))
    }
    localStorage.setItem('rulepilot:pending-rulebook-lessons:invalid', JSON.stringify([
      { ...pending('version-bad'), editionId: '' },
    ]))

    expect(readPendingRulebookLessons(localStorage, 'player')).toHaveLength(7)
    expect(readPendingRulebookLessons(localStorage, 'invalid')).toEqual([])
  })

  it('removes a handoff after success or terminal failure', () => {
    rememberPendingRulebookLesson(localStorage, 'player', pending('version-1'))

    forgetPendingRulebookLesson(localStorage, 'player', 'version-1')

    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([])
  })

  it('normalizes a natural learning goal without an arbitrary content cap', () => {
    rememberPendingRulebookLesson(localStorage, 'player', {
      ...pending('version-1'),
      learningGoal: '  先让我能带大家开局，再重点讲行动衔接。  ',
    })

    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{
      ...pending('version-1'),
      learningGoal: '先让我能带大家开局，再重点讲行动衔接。',
    }])

    const detailedGoal = '我希望按实际带玩顺序讲清楚每一个分支和例外。'.repeat(30)
    localStorage.setItem('rulepilot:pending-rulebook-lessons:detailed', JSON.stringify([{
      ...pending('version-2'), learningGoal: detailedGoal,
    }]))
    expect(readPendingRulebookLessons(localStorage, 'detailed')[0]?.learningGoal).toBe(detailedGoal)
  })

  it('sanitizes retired audience fields from an older stored handoff', () => {
    localStorage.setItem('rulepilot:pending-rulebook-lessons:player', JSON.stringify([{
      versionId: 'version-1',
      editionId: 'edition-1',
      playerCount: 4,
      beginnerCount: 4,
      durationMinutes: 25,
    }]))

    expect(readPendingRulebookLessons(localStorage, 'player')).toEqual([{
      versionId: 'version-1', editionId: 'edition-1',
    }])
  })
})

function pending(versionId: string) {
  return { versionId }
}
