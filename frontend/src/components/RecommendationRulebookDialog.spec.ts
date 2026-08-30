import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import RecommendationRulebookDialog from './RecommendationRulebookDialog.vue'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(settle => { resolve = settle })
  return { promise, resolve }
}

const pages = [
  { pageNumber: 1, text: 'Setup', characterCount: 1200 },
  { pageNumber: 2, text: 'Turn order', characterCount: 900 },
]

const threePages = [
  ...pages,
  { pageNumber: 3, text: 'Ending', characterCount: 640 },
]

describe('RecommendationRulebookDialog', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('opens downloaded pages in place while the generated guide continues in the background', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(pages))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '展翅翱翔' },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('你可以先阅读原规则书')
    expect(wrapper.text()).toContain('讲解仍在后台生成')
    expect(wrapper.get('[data-testid="rulebook-page-loader"]').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/1/image')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/1/image')
    await wrapper.findAll('button').find(button => button.text().includes('第 2 页'))!.trigger('click')
    expect(wrapper.get('[data-testid="rulebook-page-loader"]').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/2/image')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('src')).toBe('/api/v1/document-versions/document-1/pages/2/image')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/v1/document-versions/document-1/pages/summaries')
    expect(fetchMock.mock.calls[0]![1]).toMatchObject({ credentials: 'include' })
    expect(fetchMock.mock.calls[0]![1].signal).toBeInstanceOf(AbortSignal)

    await wrapper.get('button[aria-label="关闭规则书"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('publishes image, page label, and accessible name atomically when an older page finishes late', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(threePages)))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '规则书' },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 1 页')
    const firstRequest = wrapper.get('[data-testid="rulebook-page-loader"]')
    expect(wrapper.find('[data-testid="rulebook-page-image"]').exists()).toBe(false)
    await firstRequest.trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 1 页')
    expect(wrapper.get('button[data-page-number="1"]').attributes('aria-current')).toBe('page')

    await wrapper.get('button[data-page-number="2"]').trigger('click')
    const lateSecondPage = wrapper.get('[data-testid="rulebook-page-loader"]')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 1 页')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 2 页；第 1 页继续显示')

    await wrapper.get('button[data-page-number="3"]').trigger('click')
    const currentThirdPage = wrapper.get('[data-testid="rulebook-page-loader"]')
    await lateSecondPage.trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 1 页')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 3 页；第 1 页继续显示')

    await currentThirdPage.trigger('load')
    const displayed = wrapper.get('[data-testid="rulebook-page-image"]')
    expect(displayed.attributes('src')).toBe('/api/v1/document-versions/document-1/pages/3/image')
    expect(displayed.attributes('alt')).toBe('规则书第 3 页')
    expect(wrapper.get('button[data-page-number="3"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('第 3 页已显示')
    wrapper.unmount()
  })

  it('invalidates a pending page image when the dialog closes and starts cleanly after reopen', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(Response.json(pages))))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '规则书' },
    })
    await flushPromises()
    const closedImage = wrapper.get('[data-testid="rulebook-page-loader"]')

    await wrapper.setProps({ open: false })
    await closedImage.trigger('load')
    expect(wrapper.find('[data-testid="rulebook-page-image"]').exists()).toBe(false)

    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.find('[data-testid="rulebook-page-image"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="rulebook-page-status"]').text()).toContain('正在加载第 1 页')
    await wrapper.get('[data-testid="rulebook-page-loader"]').trigger('load')
    expect(wrapper.get('[data-testid="rulebook-page-image"]').attributes('alt')).toBe('规则书第 1 页')
    wrapper.unmount()
  })

  it('aborts a closed generation and ignores its delayed result after a fresh reopen', async () => {
    const closed = deferred<Response>()
    const signals: AbortSignal[] = []
    let requests = 0
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      requests += 1
      signals.push(options!.signal!)
      if (requests === 1) return closed.promise
      return Promise.resolve(Response.json([
        { pageNumber: 1, text: 'Current page', characterCount: 700 },
      ]))
    }))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '规则书' },
    })
    await flushPromises()

    await wrapper.setProps({ open: false })
    expect(signals[0]!.aborted).toBe(true)
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(wrapper.text()).toContain('已识别 700 个字符')

    closed.resolve(Response.json([
      { pageNumber: 1, text: 'Closed page', characterCount: 99 },
    ]))
    await flushPromises()
    expect(wrapper.text()).toContain('已识别 700 个字符')
    expect(wrapper.text()).not.toContain('已识别 99 个字符')
    expect(signals[1]!.aborted).toBe(false)
    wrapper.unmount()
  })

  it('cancels the old version and never pairs its pages with the replacement title', async () => {
    const oldVersion = deferred<Response>()
    let oldSignal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request, options?: RequestInit) => {
      const path = String(input)
      if (path.includes('document-old')) {
        oldSignal = options?.signal ?? undefined
        return oldVersion.promise
      }
      return Promise.resolve(Response.json([
        { pageNumber: 1, text: 'New version', characterCount: 810 },
      ]))
    }))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-old', title: '旧版' },
    })
    await flushPromises()

    await wrapper.setProps({ versionId: 'document-new', title: '新版' })
    await flushPromises()
    expect(oldSignal?.aborted).toBe(true)
    expect(wrapper.text()).toContain('新版')
    expect(wrapper.text()).toContain('已识别 810 个字符')

    oldVersion.resolve(Response.json([
      { pageNumber: 1, text: 'Old version', characterCount: 120 },
    ]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('已识别 120 个字符')
    wrapper.unmount()
  })

  it('retries an ordinary current failure with a fresh transport generation', async () => {
    const signals: AbortSignal[] = []
    let requests = 0
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      requests += 1
      signals.push(options!.signal!)
      return Promise.resolve(requests === 1
        ? new Response(null, { status: 503 })
        : Response.json(pages))
    }))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '规则书' },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('页面暂时无法打开')
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
    await flushPromises()

    expect(signals[0]!.aborted).toBe(false)
    expect(signals[1]).not.toBe(signals[0])
    expect(signals[1]!.aborted).toBe(false)
    expect(wrapper.text()).toContain('共 2 页')
    wrapper.unmount()
  })

  it('aborts the page-index read when the reader component unmounts', async () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_input: string | URL | Request, options?: RequestInit) => {
      signal = options?.signal ?? undefined
      return new Promise<Response>(() => undefined)
    }))
    const wrapper = mount(RecommendationRulebookDialog, {
      props: { open: true, versionId: 'document-1', title: '规则书' },
    })
    await flushPromises()

    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
