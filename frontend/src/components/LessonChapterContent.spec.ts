import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import LessonChapterContent from './LessonChapterContent.vue'

const visualFocus = { pageNumber: 6, label: '行动网格', x: 100, y: 100, width: 500, height: 300 }
const visualStep = { position: 2, heading: '从网格中取牌', kind: 'VISUAL' as const, text: '选择一行或一列。', sourcePages: [6], visualFocus }

function mountContent(overrides: Record<string, unknown> = {}) {
  return mount(LessonChapterContent, {
    props: {
      section: { position: 2, title: '进行回合', visualKind: 'FLOW_DIAGRAM', visualCaption: '按顺序取牌和补牌。', visualSourcePages: [6] },
      leadStep: { position: 1, heading: '轮到你时', kind: 'UNDERSTAND', text: '选择整行或整列。', sourcePages: [6], visualFocus: null },
      pathSteps: [visualStep], supportSteps: [], checkSteps: [], visualStepCount: 1, pathTitle: '上桌时按这个顺序',
      currentVisualPageNumber: 6, visualFeedbackSaving: null, online: true,
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (focus: typeof visualFocus) => `/crop/${focus.pageNumber}`,
      stepSourceLabel: (step: { sourcePages: number[] }) => `原文 ${step.sourcePages.join('、')} 页`,
      moveMeta: () => ({ label: '看桌面', tone: 'bg-indigo/10 text-indigo' }),
      visualKindLabel: () => '流程示意',
      hasVisualAid: () => true,
      visualAidResult: () => 'NOT_RATED' as const,
      ...overrides,
    },
  })
}

describe('LessonChapterContent', () => {
  it('renders cited visual evidence and forwards only image-feedback intent', async () => {
    const wrapper = mountContent()

    expect(wrapper.text()).toContain('查看第 6 页上下文')
    expect(wrapper.get('img[alt*="行动网格"]').attributes('src')).toBe('/crop/6')
    expect(wrapper.text()).toContain('查看原文第 6 页')
    await wrapper.findAll('button').find((button) => button.text() === '有帮助')!.trigger('click')
    await wrapper.findAll('button').find((button) => button.text() === '没帮上忙')!.trigger('click')

    expect(wrapper.emitted('rateVisualAid')).toEqual([[2, 2, 'HELPFUL'], [2, 2, 'NOT_HELPFUL']])
  })

  it('keeps visual feedback disabled while it is being saved or offline', () => {
    const wrapper = mountContent({ visualFeedbackSaving: 'visual-s2-v2', online: false })
    expect(wrapper.findAll('button').filter((button) => ['有帮助', '没帮上忙'].includes(button.text())).every((button) => button.attributes('disabled') !== undefined)).toBe(true)
  })
})
