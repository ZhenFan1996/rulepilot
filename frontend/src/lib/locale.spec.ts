import { afterEach, describe, expect, it, vi } from 'vitest'

import { normalizeLocale, setLocale, useLocale } from './locale'

describe('player locale', () => {
  afterEach(() => {
    localStorage.clear()
    setLocale('zh-CN')
  })

  it('keeps an explicit language preference and only accepts the supported public values', () => {
    const { locale, t } = useLocale()

    setLocale('en')

    expect(locale.value).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(t('nav.library')).toBe('Public guides')
    expect(normalizeLocale('en-GB')).toBe('en')
    expect(normalizeLocale('fr')).toBe('zh-CN')
  })

  it('loads the English catalog when a saved preference is restored before startup', async () => {
    localStorage.setItem('rulepilot:locale', 'en')
    vi.resetModules()
    const freshLocale = await import('./locale')
    const { locale, t } = freshLocale.useLocale()

    expect(locale.value).toBe('en')
    await vi.waitFor(() => expect(t('nav.library')).toBe('Public guides'))
    expect(document.documentElement.lang).toBe('en')

    freshLocale.setLocale('zh-CN')
  })
})
