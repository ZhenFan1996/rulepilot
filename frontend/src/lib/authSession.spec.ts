import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import {
  LOGIN_REQUIRED_EVENT,
  SESSION_CLEARED_EVENT,
  notifyLoginRequired,
  notifySessionCleared,
  safeKnownAuthReturnPath,
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

  it('keeps only canonical local return paths that resolve to a current application route', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: { template: '<div />' } },
        { path: '/lessons', name: 'lessons', component: { template: '<div />' } },
        { path: '/lesson/:planId', name: 'lesson', component: { template: '<div />' } },
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/register', name: 'register', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', name: 'not-found', component: { template: '<div />' } },
      ],
    })

    expect(safeKnownAuthReturnPath(router, '/lesson/plan-1?lang=en#sources'))
      .toBe('/lesson/plan-1?lang=en#sources')
    expect(safeKnownAuthReturnPath(router, '/catalog/../lessons?filter=pending'))
      .toBe('/lessons?filter=pending')
    expect(safeKnownAuthReturnPath(router, '/lessons?filter=pending#ready'))
      .toBe('/lessons?filter=pending#ready')
    expect(safeKnownAuthReturnPath(router, '/retired-player-area')).toBeNull()
    expect(safeKnownAuthReturnPath(router, 'https://example.com')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '//example.com')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '/\\example.com')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '/%2Fexample.com')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '/login?redirect=/lessons')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '/LOGIN/?redirect=/catalog')).toBeNull()
    expect(safeKnownAuthReturnPath(router, '/register#form')).toBeNull()
    expect(safeKnownAuthReturnPath(router, ['/lessons'])).toBeNull()
  })

  it('announces a completed logout so mounted private views can discard owner data', () => {
    const listener = vi.fn()
    window.addEventListener(SESSION_CLEARED_EVENT, listener)

    notifySessionCleared()

    expect(listener).toHaveBeenCalledOnce()
    window.removeEventListener(SESSION_CLEARED_EVENT, listener)
  })
})
