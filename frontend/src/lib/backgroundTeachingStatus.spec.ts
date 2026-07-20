import { describe, expect, it } from 'vitest'

import {
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

  it('ignores corrupt or unbounded session values', () => {
    expect(parseBackgroundTeachingItems('{bad')).toEqual([])
    expect(parseBackgroundTeachingItems('[{"runId":"run-1"}]')).toEqual([])
    expect(parseBackgroundTeachingItems(JSON.stringify([{ ...first, gameTitle: 'x'.repeat(161) }]))).toEqual([])
    expect(parseBackgroundTeachingItems(JSON.stringify(Array.from({ length: 25 }, () => first)))).toHaveLength(20)
    expect(parseBackgroundTeachingItems(JSON.stringify([first]))).toEqual([first])
  })
})
