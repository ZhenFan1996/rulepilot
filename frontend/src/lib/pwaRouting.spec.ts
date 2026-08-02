import { describe, expect, it } from 'vitest'

import { pwaNavigationFallbackDenylist } from '@/lib/pwaRouting'

describe('PWA navigation fallback routing', () => {
  it('keeps API documents and media out of the SPA fallback', () => {
    const [apiRoute] = pwaNavigationFallbackDenylist

    expect(apiRoute?.test('/api/public/lessons/plan-1/pages/4/image')).toBe(true)
    expect(apiRoute?.test('/api/v1/document-versions/version-1/pages/4/image')).toBe(true)
    expect(apiRoute?.test('/lesson/plan-1')).toBe(false)
  })
})
