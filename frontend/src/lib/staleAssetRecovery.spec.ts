import { describe, expect, it, vi } from 'vitest'

import { installStaleAssetRecovery } from './staleAssetRecovery'

describe('installStaleAssetRecovery', () => {
  it('loads the intended route from the current release when its old asset was removed', () => {
    const target = new EventTarget()
    const replace = vi.fn()
    const recoveryStorage = new Map<string, string>()
    let handleNavigationError: ((error: unknown, to: { fullPath: string }) => void) | undefined
    let handleNavigationComplete: ((_to: unknown, _from: unknown, failure?: unknown) => void) | undefined
    const router = {
      onError: vi.fn((handler: typeof handleNavigationError) => {
        handleNavigationError = handler
        return vi.fn()
      }),
      afterEach: vi.fn((handler: typeof handleNavigationComplete) => {
        handleNavigationComplete = handler
        return vi.fn()
      }),
    }
    installStaleAssetRecovery(router, target, { replace }, {
      getItem: key => recoveryStorage.get(key) ?? null,
      setItem: (key, value) => { recoveryStorage.set(key, value) },
      removeItem: key => { recoveryStorage.delete(key) },
    })

    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    handleNavigationError?.(new Error('removed asset'), { fullPath: '/account' })

    expect(event.defaultPrevented).toBe(true)
    expect(replace).toHaveBeenCalledWith('/account')

    const repeatedEvent = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(repeatedEvent)
    handleNavigationError?.(new Error('still unavailable'), { fullPath: '/account' })
    expect(repeatedEvent.defaultPrevented).toBe(false)
    expect(replace).toHaveBeenCalledTimes(1)

    handleNavigationComplete?.({}, {}, undefined)
    const laterEvent = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(laterEvent)
    expect(laterEvent.defaultPrevented).toBe(true)
  })

  it('does not reload for unrelated navigation errors', () => {
    const target = new EventTarget()
    const replace = vi.fn()
    let handleNavigationError: ((error: unknown, to: { fullPath: string }) => void) | undefined
    const router = {
      onError: vi.fn((handler: typeof handleNavigationError) => {
        handleNavigationError = handler
        return vi.fn()
      }),
      afterEach: vi.fn(() => vi.fn()),
    }
    installStaleAssetRecovery(router, target, { replace })

    handleNavigationError?.(new Error('application error'), { fullPath: '/account' })

    expect(replace).not.toHaveBeenCalled()
  })

  it('surfaces a stale asset error without reloading when recovery storage is unavailable', () => {
    const target = new EventTarget()
    const replace = vi.fn()
    const deniedStorage = {
      getItem: vi.fn(() => { throw new DOMException('denied', 'SecurityError') }),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    }
    let handleNavigationError: ((error: unknown, to: { fullPath: string }) => void) | undefined
    const router = {
      onError: vi.fn((handler: typeof handleNavigationError) => {
        handleNavigationError = handler
        return vi.fn()
      }),
      afterEach: vi.fn(() => vi.fn()),
    }
    installStaleAssetRecovery(router, target, { replace }, deniedStorage)

    const event = new Event('vite:preloadError', { cancelable: true })
    target.dispatchEvent(event)
    handleNavigationError?.(new Error('removed asset'), { fullPath: '/account' })

    expect(event.defaultPrevented).toBe(false)
    expect(replace).not.toHaveBeenCalled()
  })
})
