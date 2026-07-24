import { describe, expect, it } from 'vitest'

import { publicCoverUrl } from './publicCover'

describe('publicCoverUrl', () => {
  it('keeps publisher sources behind the cached public-cover endpoint', () => {
    expect(publicCoverUrl('plan / one'))
      .toBe('/api/public/lessons/plan%20%2F%20one/cover')
  })

  it('also requests a cached rulebook-front fallback when no remote cover was registered', () => {
    expect(publicCoverUrl('plan-1')).toBe('/api/public/lessons/plan-1/cover')
  })

  it('keeps the tabletop fallback only when the public lesson identifier is missing', () => {
    expect(publicCoverUrl('')).toBeUndefined()
  })
})
