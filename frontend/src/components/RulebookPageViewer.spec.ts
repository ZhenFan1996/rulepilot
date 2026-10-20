import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { setLocale } from '@/lib/locale'
import RulebookPageViewer from './RulebookPageViewer.vue'

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

const pages = [
  { pageNumber: 5, characterCount: 700 },
  { pageNumber: 6, characterCount: 810 },
  { pageNumber: 9, characterCount: 430 },
]

describe('RulebookPageViewer', () => {
  beforeEach(() => setLocale('zh-CN'))

  afterEach(() => {
    setLocale('zh-CN')
    document.body.innerHTML = ''
    localStorage.clear()
    sessionStorage.clear()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    if (originalScrollIntoView) {
      Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        value: originalScrollIntoView,
      })
    } else {
      Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView')
    }
  })

  it('retains the failed target and provides retry plus an exact original-page action', async () => {
    const wrapper = mount(RulebookPageViewer, {
      props: { versionId: 'opaque-version', pages },
    })

    const firstAttempt = wrapper.get('[data-testid="rulebook-page-loader"]')
    expect(firstAttempt.attributes('src')).toBe('/api/v1/document-versions/opaque-version/pages/5/image')
    await firstAttempt.trigger('error')

    expect(wrapper.get('[data-testid="rulebook-page-status"]').attributes('role')).toBe('alert')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('第 5 页暂时无法加载')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('只重试第 5 页')
    expect(wrapper.find('[data-testid="rulebook-page-image"]').exists()).toBe(false)
    expect(wrapper.get('a').attributes()).toMatchObject({
      href: '/api/v1/document-versions/opaque-version/pages/5/image',
      target: '_blank',
      rel: 'noopener noreferrer',
    })

    await wrapper.findAll('button').find(button => button.text() === '重试第 5 页')!.trigger('click')
    const retry = wrapper.get('[data-testid="rulebook-page-loader"]')
    expect(retry.attributes('data-request-token')).not.toBe(firstAttempt.attributes('data-request-token'))
    await retry.trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 5 页')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('5')
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('/pages/5/image')
    wrapper.unmount()
  })

  it('keeps the last displayed page while another page loads, fails, and retries locally', async () => {
    const wrapper = mount(RulebookPageViewer, {
      props: { versionId: 'opaque-version', pages },
    })
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')

    await wrapper.get('select').setValue('6')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('5')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text())
      .toContain('正在加载第 6 页；第 5 页继续显示')

    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('error')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('5')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('5')
    const failure = wrapper.get('[data-testid="rulebook-page-status"]')
    expect(failure.text()).toContain('第 6 页暂时无法加载')
    expect(failure.text()).toContain('仍显示第 5 页')
    expect(failure.text()).toContain('下一步：只重试第 6 页')

    await wrapper.findAll('button').find(button => button.text() === '重试第 6 页')!.trigger('click')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('5')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('6')
    wrapper.unmount()
  })

  it('moves through available pages by keyboard and can open an exact cited page', async () => {
    const wrapper = mount(RulebookPageViewer, {
      props: { versionId: 'public-guide', pages, initialPage: 6, pageImageUrl: page => `/public/source/${page}` },
    })
    expect(wrapper.get('[data-testid="rulebook-page-loader"]').attributes('src')).toBe('/public/source/6')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    await wrapper.get('[data-testid="rulebook-page-stage"]').trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('data-page-number')).toBe('9')
    expect(wrapper.get('button[aria-label="下一页"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('invalidates the old image callback when the document identity changes', async () => {
    const wrapper = mount(RulebookPageViewer, {
      props: { versionId: 'old-version', pages: [{ pageNumber: 5, characterCount: 700 }] },
    })
    const staleImage = wrapper.get('[data-testid="rulebook-page-loader"]')

    await wrapper.setProps({
      versionId: 'new-version',
      pages: [{ pageNumber: 7, characterCount: 920 }],
    })
    const currentImage = wrapper.get('[data-testid="rulebook-page-loader"]')
    await staleImage.trigger('load')

    expect(wrapper.find('[data-testid="rulebook-page-image"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 7 页')
    await currentImage.trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes()).toMatchObject({
      src: '/api/v1/document-versions/new-version/pages/7/image',
      alt: '规则书第 7 页',
    })
    wrapper.unmount()
  })

  it('announces loading and recovery in the selected English interface language', async () => {
    setLocale('en')
    const wrapper = mount(RulebookPageViewer, {
      props: { versionId: 'opaque-version', pages: [{ pageNumber: 5, characterCount: 700 }] },
    })

    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('Loading page 5')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('error')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('Page 5 could not be loaded')
    expect(wrapper.findAll('button').some(button => button.text() === 'Retry page 5')).toBe(true)
    expect(wrapper.get('a').text()).toBe('Open page 5 in a new tab')
    wrapper.unmount()
  })
})
