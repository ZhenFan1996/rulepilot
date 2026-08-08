import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LessonChapterList from './LessonChapterList.vue'

const originalScrollIntoView = HTMLElement.prototype.scrollIntoView

const sections = [
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

function mountDirectory() {
  return mount(LessonChapterList, {
    props: {
      sections,
      idPrefix: 'test-chapter',
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (focus: { pageNumber: number }) => `/crop/${focus.pageNumber}`,
    },
  })
}

describe('LessonChapterList', () => {
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
    expect(wrapper.get('[data-testid="mobile-chapter-directory"]').classes()).toContain('player-board')
    expect(wrapper.findAll('.hex-token').length).toBeGreaterThanOrEqual(6)
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
})
