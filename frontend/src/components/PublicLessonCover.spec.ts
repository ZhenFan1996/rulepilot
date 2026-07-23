import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import PublicLessonCover from './PublicLessonCover.vue'

describe('PublicLessonCover', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('starts a remote cover request only when the card approaches the viewport', async () => {
    let notify: IntersectionObserverCallback | undefined

    class DeferredIntersectionObserver {
      constructor(callback: IntersectionObserverCallback) {
        notify = callback
      }

      disconnect() {}
      observe() {}
      takeRecords() { return [] }
      unobserve() {}
      readonly root = null
      readonly rootMargin = '80px 0px'
      readonly thresholds = [0.01]
    }

    vi.stubGlobal('IntersectionObserver', DeferredIntersectionObserver)

    const wrapper = mount(PublicLessonCover, {
      props: {
        title: 'Wingspan',
        imageUrl: 'https://images.example/wingspan.png',
        alt: 'Wingspan 的游戏封面',
      },
    })

    expect(wrapper.find('img').exists()).toBe(false)

    notify?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
    await nextTick()

    const image = wrapper.get('img')
    expect(image.attributes('src')).toBe('https://images.example/wingspan.png')
    expect(image.attributes('loading')).toBe('lazy')
    expect(image.attributes('decoding')).toBe('async')
    expect(image.attributes('fetchpriority')).toBe('high')
  })
})
