import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonVisualEvidence from './LessonVisualEvidence.vue'
import { setLocale } from '@/lib/locale'

const focus = {
  pageNumber: 6,
  label: '行动网格',
  visibleDescription: '六张牌排成两行，箭头从左侧指向右侧。',
  x: 100,
  y: 200,
  width: 500,
  height: 300,
}

function mountEvidence() {
  return mount(LessonVisualEvidence, {
    props: {
      focus,
      pageImageUrl: (page: number) => `/page/${page}`,
      pagePreviewImageUrl: (page: number) => `/preview/${page}`,
      focusedPageImageUrl: visual => `/crop/${visual.pageNumber}`,
    },
  })
}

function imageResponse(status = 200, retryAfter?: string, failureReason?: string) {
  const headers = new Headers()
  if (retryAfter !== undefined) headers.set('Retry-After', retryAfter)
  if (failureReason !== undefined) headers.set('X-RulePilot-Visual-Failure', failureReason)
  return new Response(new Blob(['jpeg'], { type: 'image/jpeg' }), {
    status,
    headers,
  })
}

describe('LessonVisualEvidence', () => {
  beforeEach(() => {
    class TestFileReader {
      result: string | ArrayBuffer | null = null
      onload: ((event: ProgressEvent<FileReader>) => void) | null = null
      onerror: ((event: ProgressEvent<FileReader>) => void) | null = null

      readAsDataURL() {
        this.result = 'data:image/jpeg;base64,anBlZw=='
        queueMicrotask(() => this.onload?.(new ProgressEvent('load') as ProgressEvent<FileReader>))
      }
    }
    vi.stubGlobal('FileReader', TestFileReader)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(imageResponse()))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    setLocale('zh-CN')
  })

  it('pairs a lightweight whole-page locator with the verified close-up', async () => {
    const wrapper = mountEvidence()
    await flushPromises()

    expect(wrapper.text()).toContain('1 · 先定位')
    expect(wrapper.text()).toContain('2 · 再看细节')
    expect(wrapper.text()).toContain('六张牌排成两行，箭头从左侧指向右侧。')
    expect(wrapper.get('[data-testid="lesson-visual-context"] img').attributes('src')).toBe('/preview/6')
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toMatch(/^data:image\/jpeg;base64,/)
    expect(fetch).toHaveBeenCalledWith('/crop/6', expect.objectContaining({ credentials: 'include' }))
    expect(wrapper.get('[data-testid="lesson-visual-context"]').attributes('href')).toBe('/page/6')
    expect(wrapper.get('[data-testid="lesson-visual-detail"]').attributes('href')).toBe('/page/6')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('left: 10%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('top: 20%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('width: 50%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('height: 30%')
    expect(wrapper.text()).toContain('规则含义以上方有引用的步骤为准')
  })

  it('keeps the surviving scale and original-page escape hatch when either image fails', async () => {
    const wrapper = mountEvidence()
    await flushPromises()

    await wrapper.get('[data-testid="lesson-visual-context"] img').trigger('error')
    expect(wrapper.find('[data-testid="lesson-visual-context"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toMatch(/^data:image\/jpeg;base64,/)
    expect(wrapper.text()).toContain('原页定位预览暂时没有加载')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)

    await wrapper.get('[data-testid="lesson-visual-detail"] img').trigger('error')
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('浏览器无法读取返回的局部图')
    expect(wrapper.text()).toContain('只省略此图，已发布的带引用正文保留')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)
  })

  it('localizes navigation and evidence-boundary copy without changing media coordinates', async () => {
    setLocale('en')
    const wrapper = mountEvidence()
    await flushPromises()

    expect(wrapper.text()).toContain('1 · Locate it')
    expect(wrapper.text()).toContain('2 · Look closer')
    expect(wrapper.text()).toContain('The cited step above remains the rule authority.')
    expect(wrapper.get('img[alt*="highlighting 行动网格"]')).toBeTruthy()
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('left: 10%')
  })

  it('downgrades a thin corner-clipped region to page context instead of showing a false close-up', () => {
    const focusedPageImageUrl = vi.fn(() => '/crop/6')
    const wrapper = mount(LessonVisualEvidence, {
      props: {
        focus: { ...focus, x: 130, y: 880, width: 870, height: 120 },
        pageImageUrl: (page: number) => `/page/${page}`,
        pagePreviewImageUrl: (page: number) => `/preview/${page}`,
        focusedPageImageUrl,
      },
    })

    expect(wrapper.get('[data-testid="lesson-visual-context"] img').attributes('src')).toBe('/preview/6')
    expect(wrapper.find('[data-testid="lesson-visual-context-focus"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail-unreliable"]')).toBeTruthy()
    expect(wrapper.text()).toContain('这次不强行放大')
    expect(wrapper.text()).toContain('页眉、页脚或被截断的内容')
    const contextAlt = wrapper.get('[data-testid="lesson-visual-context"] img').attributes('alt')
    expect(contextAlt).toContain('用于核对')
    expect(contextAlt).toContain('行动网格')
    expect(contextAlt).toContain('页面上下文')
    expect(contextAlt).not.toContain('框选')
    expect(wrapper.text()).toContain('本页仅用于建立上下文')
    expect(wrapper.text()).not.toContain('定位框和特写只说明')
    expect(focusedPageImageUrl).not.toHaveBeenCalled()
  })

  it('keeps a thin central reference row when it is not clipped into the page trim', async () => {
    const wrapper = mount(LessonVisualEvidence, {
      props: {
        focus: { ...focus, x: 100, y: 400, width: 800, height: 120 },
        pageImageUrl: (page: number) => `/page/${page}`,
        pagePreviewImageUrl: (page: number) => `/preview/${page}`,
        focusedPageImageUrl: visual => `/crop/${visual.pageNumber}`,
      },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]')).toBeTruthy()
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toMatch(/^data:image\/jpeg;base64,/)
    expect(wrapper.find('[data-testid="lesson-visual-detail-unreliable"]').exists()).toBe(false)
  })

  it('defers crop decoding until the visual approaches the viewport', async () => {
    let reveal: (() => void) | undefined
    class TestIntersectionObserver {
      constructor(callback: IntersectionObserverCallback) {
        reveal = () => callback(
          [{ isIntersecting: true } as IntersectionObserverEntry],
          this as unknown as IntersectionObserver,
        )
      }

      observe() {}
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal('IntersectionObserver', TestIntersectionObserver)
    const fetchMock = vi.mocked(fetch)

    mountEvidence()
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    reveal?.()
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retries one 503 crop response after a bounded Retry-After delay and recovers locally', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.mocked(fetch)
    fetchMock
      .mockReset()
      .mockResolvedValueOnce(imageResponse(503, '99', 'DECODE_CAPACITY_EXCEEDED'))
      .mockResolvedValueOnce(imageResponse())

    const wrapper = mountEvidence()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="lesson-visual-detail-retrying"]').text()).toContain('图像解码容量暂时繁忙')
    expect(wrapper.text()).toContain('自动重试这张局部图一次')
    expect(wrapper.text()).toContain('已发布的带引用正文不受影响')

    await vi.advanceTimersByTimeAsync(999)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toMatch(/^data:image\/jpeg;base64,/)
    expect(wrapper.find('[data-testid="lesson-visual-detail-loading"]').exists()).toBe(false)
  })

  it('does not retry a permanent crop failure and keeps a stable local fallback', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockReset().mockResolvedValue(imageResponse(502, undefined, 'PAGE_IMAGE_UNAVAILABLE'))

    const wrapper = mountEvidence()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/crop/6', expect.objectContaining({ credentials: 'include' }))
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail-failure"]').text()).toContain('原页图或裁剪已永久不可用')
    expect(wrapper.text()).toContain('原样重试无益')
    expect(wrapper.text()).toContain('只省略此图，已发布的带引用正文保留')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)
  })

  it('stops after one retry when the source page remains temporarily unavailable', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockReset().mockResolvedValue(imageResponse(503, '1', 'PAGE_IMAGE_TEMPORARILY_UNAVAILABLE'))

    const wrapper = mountEvidence()
    await flushPromises()
    expect(wrapper.get('[data-testid="lesson-visual-detail-retrying"]').text()).toContain('原页图暂时无法读取')
    expect(wrapper.text()).toContain('已发布的带引用正文不受影响')

    await vi.advanceTimersByTimeAsync(1_000)
    await flushPromises()
    await vi.runAllTimersAsync()

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail-failure"]').text()).toContain('原页图重试后仍暂时无法读取')
    expect(wrapper.text()).toContain('本次局部图已停止自动重试')
    expect(wrapper.text()).toContain('只省略此图，已发布的带引用正文保留')
    expect(fetchMock.mock.calls.every(([url]) => url === '/crop/6')).toBe(true)
  })

  it('classifies a network failure without retrying or hiding the published lesson text', async () => {
    const fetchMock = vi.mocked(fetch)
    fetchMock.mockReset().mockRejectedValue(new TypeError('network unavailable'))

    const wrapper = mountEvidence()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="lesson-visual-detail-failure"]').text()).toContain('局部图在传输或服务响应时失败')
    expect(wrapper.text()).toContain('只省略此图，已发布的带引用正文保留')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)
  })

  it('classifies a browser read failure after a successful response without retrying', async () => {
    class FailingFileReader {
      result: string | ArrayBuffer | null = null
      onload: ((event: ProgressEvent<FileReader>) => void) | null = null
      onerror: ((event: ProgressEvent<FileReader>) => void) | null = null

      readAsDataURL() {
        queueMicrotask(() => this.onerror?.(new ProgressEvent('error') as ProgressEvent<FileReader>))
      }
    }
    vi.stubGlobal('FileReader', FailingFileReader)
    const fetchMock = vi.mocked(fetch)

    const wrapper = mountEvidence()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="lesson-visual-detail-failure"]').text()).toContain('浏览器无法读取返回的局部图')
    expect(wrapper.text()).toContain('只省略此图，已发布的带引用正文保留')
  })
})
