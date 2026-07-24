import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonReaderChapterHeader from './LessonReaderChapterHeader.vue'
import { setLocale } from '@/lib/locale'

function mountHeader(overrides: Record<string, unknown> = {}) {
  return mount(LessonReaderChapterHeader, {
    props: {
      section: { position: 2, title: '进行第一次行动', evidenceStatus: 'CITED_DRAFT' as const },
      sectionCount: 8,
      outcome: '完成第一次行动。',
      lessonStillGrowing: false,
      readingCurrentLastChapter: false,
      ...overrides,
    },
  })
}

describe('LessonReaderChapterHeader', () => {
  afterEach(() => setLocale('zh-CN'))

  it('forwards the question shortcut without owning reader state', async () => {
    const wrapper = mountHeader()
    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('askQuestion')).toEqual([[]])
  })

  it('localizes chapter chrome and evidence disclosure without translating chapter data', () => {
    setLocale('en')
    const wrapper = mountHeader({ lessonStillGrowing: true, readingCurrentLastChapter: true })

    expect(wrapper.text()).toContain('Chapter 2 of 8')
    expect(wrapper.text()).toContain('After this chapter, you should be able to: 完成第一次行动。')
    expect(wrapper.text()).toContain('Ask about this chapter')
    expect(wrapper.text()).toContain('Cited · details under review')
    expect(wrapper.text()).toContain('background detail review continues')
    expect(wrapper.text()).toContain('This is the last chapter available right now')
  })
})
