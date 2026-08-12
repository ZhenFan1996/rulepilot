import { describe, expect, it } from 'vitest'

import {
  appScrollBehavior,
  appStickyOffset,
  focusMainContent,
  routeDocumentTitle,
  routeNeedsContentFocus,
} from './routeExperience'

describe('route experience', () => {
  it('uses the localized route title without exposing internal route names', () => {
    const translate = (key: string) => ({
      'route.title.guides': '我的讲解',
    })[key] ?? key

    expect(routeDocumentTitle(
      { meta: { titleKey: 'route.title.guides' } },
      translate,
    )).toBe('我的讲解 · RulePilot')
    expect(routeDocumentTitle({ meta: {} }, translate)).toBe('RulePilot')
  })

  it('restores history positions, follows hashes, and only resets a new path', () => {
    const saved = { left: 12, top: 480 }
    expect(appScrollBehavior(route('/lessons'), route('/'), saved)).toEqual(saved)
    expect(appScrollBehavior(route('/lessons', '#ready'), route('/'), null)).toEqual({
      el: '#ready',
      top: 80,
    })
    expect(appScrollBehavior(route('/lessons'), route('/lessons'), null)).toBe(false)
    expect(appScrollBehavior(route('/lessons'), route('/'), null)).toEqual({ left: 0, top: 0 })
  })

  it('keeps hash targets below the shared header and a visible connectivity status', () => {
    document.documentElement.style.setProperty('--app-top-inset', '64px')

    expect(appStickyOffset()).toBe(144)
    expect(appScrollBehavior(route('/lessons', '#ready'), route('/'), null)).toEqual({
      el: '#ready',
      top: 144,
    })

    document.documentElement.style.removeProperty('--app-top-inset')
  })

  it('focuses the shared main landmark only after the visible route changes', () => {
    document.body.innerHTML = '<main id="main-content" tabindex="-1">Guide</main>'

    expect(routeNeedsContentFocus(route('/lessons'), route('/'))).toBe(true)
    expect(routeNeedsContentFocus(route('/lessons'), route('/lessons'))).toBe(false)
    expect(focusMainContent()).toBe(true)
    expect(document.activeElement?.id).toBe('main-content')
  })
})

function route(path: string, hash = '') {
  return { path, hash } as never
}
