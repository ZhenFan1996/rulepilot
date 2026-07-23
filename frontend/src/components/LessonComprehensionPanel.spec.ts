import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonComprehensionPanel from './LessonComprehensionPanel.vue'

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

function mountPanel() {
  return mount(LessonComprehensionPanel, {
    props: {
      comprehension: report, errorMessage: '', saving: null, online: true,
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (visual: typeof focus) => `/crop/${visual.pageNumber}`,
      visualFocusStyle: () => ({ left: '10%' }),
    },
  })
}

describe('LessonComprehensionPanel', () => {
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
})
