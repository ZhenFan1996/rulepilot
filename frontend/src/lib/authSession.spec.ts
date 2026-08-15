import { describe, expect, it, vi } from 'vitest'

import {
  LOGIN_REQUIRED_EVENT,
  SESSION_CLEARED_EVENT,
  notifyLoginRequired,
  notifySessionCleared,
  safeAuthReturnPath,
  safeLoginReturnPath,
} from './authSession'

describe('auth session UI policy', () => {
  it('announces an expired session without navigating', () => {
    const listener = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, listener)

    notifyLoginRequired()

    expect(listener).toHaveBeenCalledOnce()
    window.removeEventListener(LOGIN_REQUIRED_EVENT, listener)
  })

  it('lets a route-owned sign-in gate suppress the duplicate global reminder', () => {
    const listener = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, listener)

    notifyLoginRequired({ showReminder: false })

    expect(listener).toHaveBeenCalledOnce()
    expect((listener.mock.calls[0]?.[0] as CustomEvent).detail).toEqual({ showReminder: false })
    window.removeEventListener(LOGIN_REQUIRED_EVENT, listener)
  })

  it('accepts only canonical local paths outside the authentication views', () => {
    expect(safeAuthReturnPath('/lesson/plan-1?lang=en#sources')).toBe('/lesson/plan-1?lang=en#sources')
    expect(safeLoginReturnPath('/catalog/../lessons?filter=pending')).toBe('/lessons?filter=pending')
    expect(safeAuthReturnPath('https://example.com')).toBeNull()
    expect(safeAuthReturnPath('//example.com')).toBeNull()
    expect(safeAuthReturnPath('/\\example.com')).toBeNull()
    expect(safeAuthReturnPath('/%2Fexample.com')).toBeNull()
    expect(safeAuthReturnPath('/login')).toBeNull()
    expect(safeAuthReturnPath('/LOGIN/?redirect=/catalog')).toBeNull()
    expect(safeAuthReturnPath('/register#form')).toBeNull()
    expect(safeAuthReturnPath(['/account'])).toBeNull()
  })

  it('announces a completed logout so mounted private views can discard owner data', () => {
    const listener = vi.fn()
    window.addEventListener(SESSION_CLEARED_EVENT, listener)

    notifySessionCleared()

    expect(listener).toHaveBeenCalledOnce()
    window.removeEventListener(SESSION_CLEARED_EVENT, listener)
  })
})
