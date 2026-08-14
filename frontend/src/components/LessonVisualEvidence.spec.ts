import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'

import LessonVisualEvidence from './LessonVisualEvidence.vue'
import { setLocale } from '@/lib/locale'

const focus = {
  pageNumber: 6,
  label: '行动网格',
  visibleDescription: '六张牌排成两行，箭头从左侧指向右侧。',
  x: 100,
  y: 200,
  width: 500,
  height: 300,
}

function mountEvidence() {
  return mount(LessonVisualEvidence, {
    props: {
      focus,
      pageImageUrl: (page: number) => `/page/${page}`,
      pagePreviewImageUrl: (page: number) => `/preview/${page}`,
      focusedPageImageUrl: visual => `/crop/${visual.pageNumber}`,
    },
  })
}

describe('LessonVisualEvidence', () => {
  afterEach(() => setLocale('zh-CN'))

  it('pairs a lightweight whole-page locator with the verified close-up', () => {
    const wrapper = mountEvidence()

    expect(wrapper.text()).toContain('1 · 先定位')
    expect(wrapper.text()).toContain('2 · 再看细节')
    expect(wrapper.text()).toContain('六张牌排成两行，箭头从左侧指向右侧。')
    expect(wrapper.get('[data-testid="lesson-visual-context"] img').attributes('src')).toBe('/preview/6')
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toBe('/crop/6')
    expect(wrapper.get('[data-testid="lesson-visual-context"]').attributes('href')).toBe('/page/6')
    expect(wrapper.get('[data-testid="lesson-visual-detail"]').attributes('href')).toBe('/page/6')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('left: 10%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('top: 20%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('width: 50%')
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('height: 30%')
    expect(wrapper.text()).toContain('规则含义以上方有引用的步骤为准')
  })

  it('keeps the surviving scale and original-page escape hatch when either image fails', async () => {
    const wrapper = mountEvidence()

    await wrapper.get('[data-testid="lesson-visual-context"] img').trigger('error')
    expect(wrapper.find('[data-testid="lesson-visual-context"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toBe('/crop/6')
    expect(wrapper.text()).toContain('原页定位预览暂时没有加载')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)

    await wrapper.get('[data-testid="lesson-visual-detail"] img').trigger('error')
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('局部特写暂时没有加载')
    expect(wrapper.findAll('a[href="/page/6"]')).not.toHaveLength(0)
  })

  it('localizes navigation and evidence-boundary copy without changing media coordinates', () => {
    setLocale('en')
    const wrapper = mountEvidence()

    expect(wrapper.text()).toContain('1 · Locate it')
    expect(wrapper.text()).toContain('2 · Look closer')
    expect(wrapper.text()).toContain('The cited step above remains the rule authority.')
    expect(wrapper.get('img[alt*="highlighting 行动网格"]')).toBeTruthy()
    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]').attributes('style')).toContain('left: 10%')
  })

  it('downgrades a thin corner-clipped region to page context instead of showing a false close-up', () => {
    const focusedPageImageUrl = vi.fn(() => '/crop/6')
    const wrapper = mount(LessonVisualEvidence, {
      props: {
        focus: { ...focus, x: 130, y: 880, width: 870, height: 120 },
        pageImageUrl: (page: number) => `/page/${page}`,
        pagePreviewImageUrl: (page: number) => `/preview/${page}`,
        focusedPageImageUrl,
      },
    })

    expect(wrapper.get('[data-testid="lesson-visual-context"] img').attributes('src')).toBe('/preview/6')
    expect(wrapper.find('[data-testid="lesson-visual-context-focus"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="lesson-visual-detail"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="lesson-visual-detail-unreliable"]')).toBeTruthy()
    expect(wrapper.text()).toContain('这次不强行放大')
    expect(wrapper.text()).toContain('页眉、页脚或被截断的内容')
    const contextAlt = wrapper.get('[data-testid="lesson-visual-context"] img').attributes('alt')
    expect(contextAlt).toContain('用于核对')
    expect(contextAlt).toContain('行动网格')
    expect(contextAlt).toContain('页面上下文')
    expect(contextAlt).not.toContain('框选')
    expect(wrapper.text()).toContain('本页仅用于建立上下文')
    expect(wrapper.text()).not.toContain('定位框和特写只说明')
    expect(focusedPageImageUrl).not.toHaveBeenCalled()
  })

  it('keeps a thin central reference row when it is not clipped into the page trim', () => {
    const wrapper = mount(LessonVisualEvidence, {
      props: {
        focus: { ...focus, x: 100, y: 400, width: 800, height: 120 },
        pageImageUrl: (page: number) => `/page/${page}`,
        pagePreviewImageUrl: (page: number) => `/preview/${page}`,
        focusedPageImageUrl: visual => `/crop/${visual.pageNumber}`,
      },
    })

    expect(wrapper.get('[data-testid="lesson-visual-context-focus"]')).toBeTruthy()
    expect(wrapper.get('[data-testid="lesson-visual-detail"] img').attributes('src')).toBe('/crop/6')
    expect(wrapper.find('[data-testid="lesson-visual-detail-unreliable"]').exists()).toBe(false)
  })
})
