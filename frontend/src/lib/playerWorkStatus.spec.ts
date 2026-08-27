import { describe, expect, it } from 'vitest'

import {
  guideWorkStatus,
  playerWorkStatus,
} from './playerWorkStatus'

type PlayerWorkFacts = Parameters<typeof playerWorkStatus>[1]

describe('player-facing work status', () => {
  it('keeps capability, readiness, terminality, and outcome orthogonal', () => {
    const activeFacts: PlayerWorkFacts = {
      capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    }
    const stoppedFacts: PlayerWorkFacts = {
      capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    }

    const active = playerWorkStatus('GUIDE_READABLE', activeFacts, 'zh-CN')
    const stopped = playerWorkStatus('GUIDE_READABLE', stoppedFacts, 'zh-CN')

    expect(active.label).toBe('基础讲解可读')
    expect(stopped.label).toBe('基础讲解可读')
    expect(active).toMatchObject(activeFacts)
    expect(stopped).toMatchObject(stoppedFacts)
  })

  it('derives guide generation facts consistently for active, readable, and stopped work', () => {
    expect(guideWorkStatus('organizing', 0, 'zh-CN')).toMatchObject({
      stage: 'ORGANIZING_GUIDE', capability: 'rulebook', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('reviewing', 2, 'zh-CN')).toMatchObject({
      stage: 'REVIEWING_GUIDE', capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('readable', 1, 'zh-CN')).toMatchObject({
      stage: 'GUIDE_READABLE', capability: 'guide', readiness: 'usable', terminality: 'active', outcome: 'none',
    })
    expect(guideWorkStatus('complete', 2, 'zh-CN')).toMatchObject({
      stage: 'GUIDE_COMPLETE', capability: 'guide', readiness: 'complete', terminality: 'terminal', outcome: 'none',
    })
    expect(guideWorkStatus('needs-action', 2, 'zh-CN')).toMatchObject({
      stage: 'NEEDS_ACTION', capability: 'guide', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    })
    expect(guideWorkStatus('complete', 0, 'zh-CN')).toMatchObject({
      stage: 'NEEDS_ACTION', capability: 'rulebook', readiness: 'usable', terminality: 'terminal', outcome: 'needs-action',
    })
  })
})
