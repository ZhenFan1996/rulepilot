import { describe, expect, it, vi } from 'vitest'

import { installStaleAssetRecovery } from './staleAssetRecovery'

describe('installStaleAssetRecovery', () => {
  it('loads the intended route from the current release when its old asset was removed', () => {
    const target = new EventTarget()
    const assign = vi.fn()
    let handleNavigationError: ((error: unknown, to: { fullPath: string }) => void) | undefined
    const router = {
      onError: vi.fn((handler: typeof handleNavigationError) => {
        handleNavigationError = handler
        return vi.fn()
      }),
    }
    installStaleAssetRecovery(router, target, { assign })

    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    handleNavigationError?.(new Error('removed asset'), { fullPath: '/account' })

    expect(event.defaultPrevented).toBe(true)
    expect(assign).toHaveBeenCalledWith('/account')
  })

  it('does not reload for unrelated navigation errors', () => {
    const target = new EventTarget()
    const assign = vi.fn()
    let handleNavigationError: ((error: unknown, to: { fullPath: string }) => void) | undefined
    const router = {
      onError: vi.fn((handler: typeof handleNavigationError) => {
        handleNavigationError = handler
        return vi.fn()
      }),
    }
    installStaleAssetRecovery(router, target, { assign })

    handleNavigationError?.(new Error('application error'), { fullPath: '/account' })

    expect(assign).not.toHaveBeenCalled()
  })
})
