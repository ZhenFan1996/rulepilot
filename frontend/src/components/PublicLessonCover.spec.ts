import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PublicLessonCover from './PublicLessonCover.vue'

describe('PublicLessonCover', () => {
  it('requests the durable server-cached cover as soon as the public card renders', () => {
    const wrapper = mount(PublicLessonCover, {
      props: {
        title: 'Wingspan',
        imageUrl: 'https://images.example/wingspan.png',
        alt: 'Wingspan 的游戏封面',
      },
    })

    const image = wrapper.get('img')
    expect(image.attributes('src')).toBe('https://images.example/wingspan.png')
    expect(image.attributes('loading')).toBe('eager')
    expect(image.attributes('decoding')).toBe('async')
    expect(image.attributes('fetchpriority')).toBe('high')
  })

  it('defers below-the-fold covers so catalog rendering does not flood the connection pool', () => {
    const wrapper = mount(PublicLessonCover, {
      props: {
        title: 'Wingspan',
        imageUrl: 'https://images.example/wingspan.png',
        alt: 'Wingspan 的游戏封面',
        index: 6,
      },
    })

    const image = wrapper.get('img')
    expect(image.attributes('loading')).toBe('lazy')
    expect(image.attributes('fetchpriority')).toBe('auto')
  })
})
