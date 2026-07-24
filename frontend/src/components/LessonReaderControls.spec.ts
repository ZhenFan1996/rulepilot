import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonReaderControls from './LessonReaderControls.vue'
import { setLocale } from '@/lib/locale'

function mountControls(overrides: Record<string, unknown> = {}) {
  return mount(LessonReaderControls, {
    props: {
      currentIndex: 1,
      sectionCount: 3,
      lessonStillGrowing: false,
      readingCurrentLastChapter: false,
      waitingForNextChapter: false,
      ...overrides,
    },
  })
}

describe('LessonReaderControls', () => {
  afterEach(() => setLocale('zh-CN'))

  it('emits only the existing reader navigation intents', async () => {
    const wrapper = mountControls()
    for (const button of wrapper.findAll('button')) await button.trigger('click')

    expect(wrapper.emitted('previous')).toEqual([[]])
    expect(wrapper.emitted('skip')).toEqual([[]])
    expect(wrapper.emitted('complete')).toEqual([[]])
  })

  it('localizes controls and preserves the waiting state', () => {
    setLocale('en')
    const wrapper = mountControls({ currentIndex: 2, lessonStillGrowing: true, readingCurrentLastChapter: true, waitingForNextChapter: true })

    expect(wrapper.attributes('aria-label')).toBe('Guide controls')
    expect(wrapper.text()).toContain('Previous')
    expect(wrapper.text()).toContain('Read later')
    expect(wrapper.text()).toContain('Waiting for next chapter…')
    expect(wrapper.get('button.bg-copper').attributes('disabled')).toBeDefined()
  })
})
