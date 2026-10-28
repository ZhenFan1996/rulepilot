import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LessonChapterList from './LessonChapterList.vue'

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

interface TestVisualFocus {
  pageNumber: number
  label: string
  visibleDescription?: string
  x: number
  y: number
  width: number
  height: number
}

interface TestSection {
  position: number
  title: string
  visualCaption: string
  steps: Array<{
    position: number
    heading: string
    kind: string
    text: string
    ruleFacts?: Array<{
      position: number
      role: 'PREREQUISITE' | 'CHOICE' | 'ACTION' | 'COST_OR_GAIN' | 'TIMING' | 'LIMIT' | 'RESULT' | 'EXCEPTION' | 'TABLE_STATE' | 'EXAMPLE_STATE'
      text: string
    }>
    sourcePages: number[]
    visualFocus: TestVisualFocus | null
    visualFoci?: TestVisualFocus[]
    readingBlocks?: import('@/composables/lessonSupportingContent').LessonReadingBlock[]
  }>
}

const sections: TestSection[] = [
  {
    position: 1,
    title: '摆好桌面',
    visualCaption: '先确认每位玩家的起始区域。',
    steps: [{ position: 1, heading: '放置主板', kind: 'DO', text: '把主板放在桌面中央。', sourcePages: [2], visualFocus: null }],
  },
  {
    position: 2,
    title: '走完第一回合',
    visualCaption: '按照行动顺序完成一次回合。',
    steps: [{ position: 1, heading: '选择行动', kind: 'FLOW', text: '选择一个可用行动。', sourcePages: [4], visualFocus: null }],
  },
]

function mountDirectory(lessonSections: TestSection[] = sections) {
  return mount(LessonChapterList, {
    props: {
      sections: lessonSections,
      idPrefix: 'test-chapter',
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (focus: { pageNumber: number }) => `/crop/${focus.pageNumber}`,
    },
  })
}

describe('LessonChapterList', () => {
  beforeEach(() => {
    class TestFileReader {
      result: string | ArrayBuffer | null = null
      onload: ((event: ProgressEvent<FileReader>) => void) | null = null

      readAsDataURL() {
        this.result = 'data:image/jpeg;base64,anBlZw=='
        queueMicrotask(() => this.onload?.(new ProgressEvent('load') as ProgressEvent<FileReader>))
      }
    }
    vi.stubGlobal('FileReader', TestFileReader)
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(
      new Response(new Blob(['jpeg'], { type: 'image/jpeg' }), { status: 200 }),
    )))
  })

  afterEach(() => {
    window.history.replaceState(null, '', window.location.pathname)
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: originalScrollIntoView,
    })
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('keeps a compact mobile directory and a persistent desktop chapter rail', async () => {
    const wrapper = mountDirectory()

    expect(wrapper.get('[data-testid="mobile-chapter-directory"]').classes()).toContain('xl:hidden')
    expect(wrapper.get('[data-testid="desktop-chapter-directory"]').classes()).toEqual(expect.arrayContaining(['xl:block', 'xl:sticky', 'xl:top-24']))
    expect(wrapper.get('[data-testid="lesson-reading-column"]').classes()).toContain('min-w-0')
    expect(wrapper.findAll('[data-testid="desktop-chapter-link"]')).toHaveLength(2)
    expect(wrapper.get('[data-testid="desktop-chapter-link"][aria-current="location"]').text()).toContain('摆好桌面')

    await wrapper.findAll('[data-testid="desktop-chapter-link"]')[1]!.trigger('click')

    expect(wrapper.get('[data-testid="desktop-chapter-link"][aria-current="location"]').text()).toContain('走完第一回合')
    expect(wrapper.get('[data-testid="mobile-chapter-link"][aria-current="location"]').text()).toContain('走完第一回合')
  })

  it('tracks the visible chapter so both directories expose the reader position', async () => {
    let callback: IntersectionObserverCallback | undefined
    class TestIntersectionObserver {
      constructor(received: IntersectionObserverCallback) {
        callback = received
      }

      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() { return [] }
      readonly root = null
      readonly rootMargin = ''
      readonly thresholds = []
    }
    vi.stubGlobal('IntersectionObserver', TestIntersectionObserver)
    const wrapper = mountDirectory()
    const secondChapter = wrapper.get('#test-chapter-2').element

    callback?.([{
      target: secondChapter,
      isIntersecting: true,
      boundingClientRect: { top: 120 } as DOMRectReadOnly,
    } as IntersectionObserverEntry], {} as IntersectionObserver)
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-testid="desktop-chapter-link"][aria-current="location"]').attributes('href')).toBe('#test-chapter-2')
  })

  it('restores a directly linked chapter after the asynchronous reader mounts', async () => {
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView,
    })
    window.history.replaceState(null, '', '#test-chapter-2')

    const wrapper = mountDirectory()
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-testid="desktop-chapter-link"][aria-current="location"]').attributes('href')).toBe('#test-chapter-2')
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'start' })
  })

  it('uses one crop-first illustrated unit in the shared reader', async () => {
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [{
        ...sections[0]!.steps[0]!,
        kind: 'VISUAL',
        visualFocus: {
          pageNumber: 2,
          label: '起始区域',
          visibleDescription: '一张牌位于航线最左侧。',
          x: 100,
          y: 150,
          width: 300,
          height: 250,
        },
      }],
    }])
    await flushPromises()

    expect(wrapper.get('[data-testid="lesson-visual-evidence"]')).toBeTruthy()
    expect(wrapper.find('[data-testid="lesson-visual-context"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-image"] img').attributes('src'))
      .toMatch(/^data:image\/jpeg;base64,/)
    expect(fetch).toHaveBeenCalledWith('/crop/2', expect.objectContaining({ credentials: 'include' }))
    expect(wrapper.text()).toContain('把主板放在桌面中央。')
  })

  it('keeps each cited visual inside the step narrative it explains', async () => {
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [{
        ...sections[0]!.steps[0]!,
        kind: 'VISUAL',
        visualFocus: {
          pageNumber: 2,
          label: '起始区域',
          visibleDescription: '一张牌位于航线最左侧。',
          x: 100,
          y: 150,
          width: 300,
          height: 250,
        },
      }],
    }])
    await flushPromises()

    const pairedStep = wrapper.get('[data-testid="lesson-step-paired"]')
    const narrative = pairedStep.get('[data-testid="lesson-step-narrative"]')
    const visual = pairedStep.get('[data-testid="lesson-visual-evidence"]')

    expect(wrapper.get('[data-testid="lesson-step-paired"]').classes()).toContain('min-w-0')
    expect(narrative.text()).toContain('把主板放在桌面中央。')
    expect(visual.attributes('aria-describedby')).toBe(narrative.attributes('id'))
    expect(pairedStep.get('[data-testid="lesson-step-visuals"]').classes()).toContain('grid')
    expect(pairedStep.get('[data-testid="lesson-step-visuals"]').classes()).not.toContain('overflow-x-auto')
  })

  it('preserves authored paragraph and figure order without also rendering legacy projections', async () => {
    const focus: TestVisualFocus = { pageNumber: 2, label: 'Repeated step heading', x: 100, y: 150, width: 300, height: 250 }
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [{ ...sections[0]!.steps[0]!, text: 'Aggregate for other readers', visualFocus: focus,
        readingBlocks: [
          { kind: 'PARAGRAPH', text: 'Start with the board.', figures: [], flow: 'NONE' },
          { kind: 'FIGURES', text: '', figures: [{ focus, caption: 'The marked starting space.' }], flow: 'ASIDE' },
          { kind: 'PARAGRAPH', text: 'Now follow the arrow.', figures: [], flow: 'NONE' },
          { kind: 'FIGURES', text: '', figures: [{ focus, caption: '' }], flow: 'FULL_WIDTH' },
        ],
      }],
    }])
    await flushPromises()
    const flow = wrapper.get('[data-testid="lesson-authored-flow"]')
    expect(Array.from(flow.element.children).map(child => child.tagName)).toEqual(['P', 'UL', 'P', 'UL'])
    expect(flow.findAll('[data-testid="lesson-visual-evidence"]')).toHaveLength(2)
    expect(flow.text()).toContain('The marked starting space.')
    expect(flow.text()).not.toContain('Repeated step heading')
    expect(wrapper.text()).not.toContain('Aggregate for other readers')
    expect(wrapper.find('[data-testid="lesson-step-visuals"]').exists()).toBe(false)
    expect(flow.findAll('a[href="/page/2"]').length).toBeGreaterThan(0)
  })

  it('renders every visual focus attached to the same cited step', async () => {
    const first: TestVisualFocus = { pageNumber: 2, label: '行动图标', x: 100, y: 150, width: 300, height: 250 }
    const second: TestVisualFocus = { pageNumber: 3, label: '牌面示例', x: 200, y: 250, width: 320, height: 280 }
    const third: TestVisualFocus = { pageNumber: 4, label: '完整流程', x: 0, y: 0, width: 1000, height: 1000 }
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [{
        ...sections[0]!.steps[0]!,
        kind: 'VISUAL',
        visualFocus: first,
        visualFoci: [first, second, third],
      }],
    }])
    await flushPromises()
    await vi.waitFor(() => {
      expect(wrapper.findAll('[data-testid="lesson-visual-image"] img')).toHaveLength(3)
    })

    const gallery = wrapper.get('[data-testid="lesson-step-visuals"]')
    expect(gallery.findAll('[data-testid="lesson-visual-evidence"]')).toHaveLength(3)
    expect(gallery.classes()).toContain('overflow-x-auto')
    expect(gallery.classes()).toContain('snap-mandatory')
    expect(gallery.attributes('aria-label')).toBe('本步骤的 3 张互补图片')
    expect(gallery.attributes('tabindex')).toBe('0')
    expect(gallery.findAll('li')).toHaveLength(3)
    expect(gallery.find('li').classes()).toContain('snap-start')
    expect(wrapper.text()).toContain('结合图片 · 1/3')
    expect(wrapper.text()).toContain('结合图片 · 3/3')
    expect(vi.mocked(fetch).mock.calls.map(([input]) => String(input)))
      .toEqual(['/crop/2', '/crop/3', '/crop/4'])
  })

  it('shows a shared illustration once while retaining every step and its source links', async () => {
    const focus: TestVisualFocus = { pageNumber: 2, label: 'Setup', x: 0, y: 0, width: 1000, height: 1000 }
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [
        { ...sections[0]!.steps[0]!, visualFocus: focus },
        { ...sections[0]!.steps[0]!, position: 2, text: 'Then deal the cards.', visualFocus: focus },
      ],
    }])
    await flushPromises()
    expect(wrapper.findAll('[data-testid="lesson-visual-evidence"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="lesson-step-narrative"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('Then deal the cards.')
    expect(wrapper.findAll('[data-testid="lesson-step-narrative"] > div a[href="/page/2"]').length).toBeGreaterThanOrEqual(2)
    wrapper.unmount()
  })

  it('opens the exact cited page in place and returns keyboard focus to the source link', async () => {
    const wrapper = mount(LessonChapterList, {
      attachTo: document.body,
      props: { sections, idPrefix: 'sources', pageImageUrl: (page: number) => `/page/${page}`, focusedPageImageUrl: () => '/crop' },
    })
    const source = wrapper.get('a[href="/page/4"]')
    ;(source.element as HTMLElement).focus()
    await source.trigger('click')
    await flushPromises()
    const dialog = document.querySelector('[role="dialog"]')!
    expect(dialog.querySelector('img')?.getAttribute('src')).toBe('/page/4')
    expect(dialog.contains(document.activeElement)).toBe(true)
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(source.element)
    wrapper.unmount()
  })

  it('renders model-authored rule fact roles without parsing the natural step text', () => {
    const wrapper = mountDirectory([{
      ...sections[0]!,
      steps: [{
        ...sections[0]!.steps[0]!,
        text: '现在完成这个行动。',
        ruleFacts: [
          { position: 1, role: 'COST_OR_GAIN', text: '支付 2 枚标记。' },
          { position: 2, role: 'RESULT', text: '把棋子放到所选区域。' },
        ],
      }],
    }])

    expect(wrapper.get('[data-testid="lesson-rule-facts"]')).toBeTruthy()
    expect(wrapper.text()).toContain('支付 / 获得')
    expect(wrapper.text()).toContain('支付 2 枚标记。')
    expect(wrapper.text()).toContain('结果')
    expect(wrapper.text()).toContain('把棋子放到所选区域。')
  })
})
