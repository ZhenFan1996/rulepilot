import { describe, expect, it, vi } from 'vitest'

import { LOGIN_REQUIRED_EVENT, notifyLoginRequired, safeLoginReturnPath } from './authSession'

describe('auth session UI policy', () => {
  it('announces an expired session without navigating', () => {
    const listener = vi.fn()
    window.addEventListener(LOGIN_REQUIRED_EVENT, listener)

    notifyLoginRequired()

    expect(listener).toHaveBeenCalledOnce()
    window.removeEventListener(LOGIN_REQUIRED_EVENT, listener)
  })

  it('accepts only local non-login return paths', () => {
    expect(safeLoginReturnPath('/lesson/plan-1?lang=en')).toBe('/lesson/plan-1?lang=en')
    expect(safeLoginReturnPath('https://example.com')).toBeNull()
    expect(safeLoginReturnPath('//example.com')).toBeNull()
    expect(safeLoginReturnPath('/login')).toBeNull()
    expect(safeLoginReturnPath(['/account'])).toBeNull()
  })
})
