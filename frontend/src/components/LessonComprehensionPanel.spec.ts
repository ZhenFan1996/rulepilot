import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonComprehensionPanel from './LessonComprehensionPanel.vue'
import { setLocale } from '@/lib/locale'

const focus = { pageNumber: 4, label: '起始区域', x: 100, y: 100, width: 300, height: 200 }
const report = {
  lessonId: 'lesson-1', readyTaskCount: 1, taskCount: 1, canDoCount: 0, needsHelpCount: 1,
  readyVisualTaskCount: 1, visualAidRatedCount: 0, visualAidHelpfulCount: 0, visualAidHelpfulPercent: null,
  tasks: [{
    type: 'PREPARE_TABLE' as const, label: '摆好起始区域', prompt: '确认起始区域。', readiness: 'READY' as const,
    result: 'NEEDS_HELP' as const, chapterPositions: [2], sourcePages: [4], visualFocus: focus, visualAidResult: 'NOT_RATED' as const,
  }],
  visualAids: [{ key: 's2-v1', label: '起始区域', chapterPosition: 2, sourcePages: [4], visualFocus: focus, result: 'NOT_RATED' as const }],
}

function mountPanel(overrides: Record<string, unknown> = {}) {
  return mount(LessonComprehensionPanel, {
    props: {
      comprehension: report, errorMessage: '', saving: null, online: true,
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (visual: typeof focus) => `/crop/${visual.pageNumber}`,
      visualFocusStyle: () => ({ left: '10%' }),
      ...overrides,
    },
  })
}

describe('LessonComprehensionPanel', () => {
  afterEach(() => setLocale('zh-CN'))

  it('forwards ratings and chapter revisit without writing lesson state itself', async () => {
    const wrapper = mountPanel()
    await wrapper.get('button').trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '没帮上忙')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text().includes('回到第 2 节'))!.trigger('click')

    expect(wrapper.emitted('rateTask')).toEqual([['PREPARE_TABLE', 'CAN_DO']])
    expect(wrapper.emitted('rateVisualAid')).toEqual([['s2-v1', 'NOT_HELPFUL']])
    expect(wrapper.emitted('revisitChapter')).toEqual([[1]])
  })

  it('keeps the original-page fallback visible when a comprehension image fails', async () => {
    const wrapper = mountPanel()
    await wrapper.get('img[alt*="框选"]').trigger('error')
    expect(wrapper.text()).toContain('图片暂时没有载入')
    expect(wrapper.get('a[href="/page/4"]').text()).toContain('打开原图')
  })

  it('localizes deterministic comprehension chrome without changing task evidence or rating intents', async () => {
    setLocale('en')
    const wrapper = mountPanel()

    expect(wrapper.text()).toContain('Try it before you play')
    expect(wrapper.text()).toContain('Can you do these key moves now?')
    expect(wrapper.text()).toContain('Got it 0 / 1')
    expect(wrapper.text()).toContain('Revisit this')
    expect(wrapper.text()).toContain('Check rulebook pages 4')
    expect(wrapper.text()).toContain('I can do this')
    expect(wrapper.text()).toContain('Walk me through it')
    expect(wrapper.text()).toContain('Revisit chapter 2')
    expect(wrapper.text()).toContain('Review these rulebook crops one by one')
    expect(wrapper.text()).toContain('Page 4 · Chapter 2')
    expect(wrapper.get('img[alt*="Rulebook page 4"]').attributes('alt')).toContain('起始区域')

    await wrapper.findAll('button').find((button) => button.text() === 'Not yet')!.trigger('click')
    expect(wrapper.emitted('rateVisualAid')).toEqual([['s2-v1', 'NOT_HELPFUL']])
  })
})
