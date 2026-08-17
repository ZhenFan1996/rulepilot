import { describe, expect, it } from 'vitest'

import {
  backgroundWorkStorageKeys,
  clearBackgroundWorkStorage,
  parseBackgroundTeachingItems,
  reconcileBackgroundTeaching,
  type BackgroundTeachingItem,
} from './backgroundTeachingStatus'

describe('background teaching status', () => {
  const first: BackgroundTeachingItem = { runId: 'run-1', planId: 'plan-1', gameTitle: '星际探索' }
  const second: BackgroundTeachingItem = { runId: 'run-2', planId: 'plan-2', gameTitle: '森林之旅' }

  it('reports only plans that stopped being active', () => {
    const transition = reconcileBackgroundTeaching([first, second], [second])

    expect(transition.active).toEqual([second])
    expect(transition.finished).toEqual([first])
  })

  it('does not report a restarted run for the same plan as finished', () => {
    const replacement = { ...first, runId: 'run-3' }

    expect(reconcileBackgroundTeaching([first], [replacement]).finished).toEqual([])
  })

  it('ignores corrupt session values without hiding valid work behind arbitrary item or title caps', () => {
    expect(parseBackgroundTeachingItems('{bad')).toEqual([])
    expect(parseBackgroundTeachingItems('[{"runId":"run-1"}]')).toEqual([])
    expect(parseBackgroundTeachingItems(JSON.stringify([{ ...first, gameTitle: 'x'.repeat(161) }]))).toEqual([
      { ...first, gameTitle: 'x'.repeat(161) },
    ])
    expect(parseBackgroundTeachingItems(JSON.stringify([{ ...first, terminalState: 'RUNNING' }]))).toEqual([])
    expect(parseBackgroundTeachingItems(JSON.stringify([{ ...first, terminalState: 'FAILED' }])))
      .toEqual([{ ...first, terminalState: 'FAILED' }])
    expect(parseBackgroundTeachingItems(JSON.stringify(Array.from({ length: 25 }, (_, index) => ({
      ...first,
      runId: `run-${index}`,
      planId: `plan-${index}`,
    }))))).toHaveLength(25)
    expect(parseBackgroundTeachingItems(JSON.stringify([first]))).toEqual([first])
  })

  it('isolates persisted background work by normalized account and clears only that account', () => {
    const player = backgroundWorkStorageKeys(' player@example.com ')
    const other = backgroundWorkStorageKeys('other@example.com')
    expect(player.activeTeaching).toBe('rulepilot:active-teaching-runs:player%40example.com')
    sessionStorage.setItem(player.activeTeaching, 'player')
    sessionStorage.setItem(other.activeTeaching, 'other')
    sessionStorage.setItem('rulepilot:active-teaching-runs', 'legacy')

    clearBackgroundWorkStorage(sessionStorage, 'player@example.com')

    expect(sessionStorage.getItem(player.activeTeaching)).toBeNull()
    expect(sessionStorage.getItem(other.activeTeaching)).toBe('other')
    expect(sessionStorage.getItem('rulepilot:active-teaching-runs')).toBeNull()
  })
})
