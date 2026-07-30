import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import LessonChapterContent from './LessonChapterContent.vue'
import { setLocale } from '@/lib/locale'

const visualFocus = {
  pageNumber: 6,
  label: '行动网格',
  visibleDescription: '六张牌排成两行，箭头从左侧指向右侧。',
  x: 100,
  y: 100,
  width: 500,
  height: 300,
}
const visualStep = { position: 2, heading: '从网格中取牌', kind: 'VISUAL' as const, text: '选择一行或一列。', sourcePages: [6], visualFocus }

function mountContent(overrides: Record<string, unknown> = {}) {
  return mount(LessonChapterContent, {
    props: {
      section: { position: 2, title: '进行回合', visualKind: 'FLOW_DIAGRAM', visualCaption: '按顺序取牌和补牌。', visualSourcePages: [6] },
      leadStep: { position: 1, heading: '轮到你时', kind: 'UNDERSTAND', text: '选择整行或整列。', sourcePages: [6], visualFocus: null },
      pathSteps: [visualStep], supportSteps: [], checkSteps: [], visualStepCount: 1, pathTitle: '上桌时按这个顺序',
      currentVisualPageNumber: 6, visualFeedbackSaving: null, online: true,
      pageImageUrl: (page: number) => `/page/${page}`,
      focusedPageImageUrl: (focus) => `/crop/${focus.pageNumber}`,
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
  afterEach(() => setLocale('zh-CN'))

  it('renders cited visual evidence and forwards only image-feedback intent', async () => {
    const wrapper = mountContent()

    expect(wrapper.text()).toContain('查看第 6 页上下文')
    expect(wrapper.text()).toContain('图中看什么')
    expect(wrapper.text()).toContain('六张牌排成两行，箭头从左侧指向右侧。')
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

  it('localizes reader chrome around cited source content without changing feedback actions', () => {
    setLocale('en')
    const wrapper = mountContent()

    expect(wrapper.text()).toContain('The one rule to remember')
    expect(wrapper.text()).toContain('From reading to playing')
    expect(wrapper.text()).toContain('1 step')
    expect(wrapper.text()).toContain('Look at the table')
    expect(wrapper.text()).toContain('View page 6 in context')
    expect(wrapper.text()).toContain('Did this visual help?')
    expect(wrapper.text()).toContain('It helped')
    expect(wrapper.text()).toContain('Not yet')
    expect(wrapper.get('aside[aria-label]').attributes('aria-label')).toBe('Rulebook pages and table visuals')
    expect(wrapper.get('img[alt*="from rulebook page 6"]').attributes('alt')).toContain('from rulebook page 6')
  })
})
