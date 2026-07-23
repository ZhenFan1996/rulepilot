import { describe, expect, it } from 'vitest'

import { publicCoverUrl } from './publicCover'

describe('publicCoverUrl', () => {
  it('keeps publisher sources behind the cached public-cover endpoint', () => {
    expect(publicCoverUrl('plan / one', 'https://publisher.example/original.png'))
      .toBe('/api/public/lessons/plan%20%2F%20one/cover')
  })

  it('keeps the tabletop fallback when no cover source is available', () => {
    expect(publicCoverUrl('plan-1', null)).toBeUndefined()
  })
})
